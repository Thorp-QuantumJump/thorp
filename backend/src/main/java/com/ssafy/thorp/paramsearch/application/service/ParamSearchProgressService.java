package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.service.limit.FailureLimitGuard;
import com.ssafy.thorp.paramsearch.application.service.limit.TimeLimitGuard;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후보 결과를 묶음에 반영한다 — 후보 상태 갱신, 집계, 다음 발행, 종료 판정(FR-008).
 *
 * <p><b>집계를 세어서 다시 쓴다.</b> 이전 값에 1을 더하지 않는 이유는 동시 수신 때문이다 — 두 이벤트가 같은 값을 읽어 각자 더하면 하나가 사라진다. 묶음 행을
 * 잠근 뒤 후보 상태별로 다시 세면 그 경쟁이 없다(내부 계약 §4).
 *
 * <p><b>실행 하나에 후보가 여럿 걸릴 수 있다.</b> 004가 같은 조건의 요청을 살아 있는 실행에 합류시키므로(spec 004 R-02), 같은 조건을 두 번 접수하면
 * 두 묶음의 후보가 같은 실행 행을 가리킨다. 결과 이벤트 하나가 그 후보 전부를 끝내야 한다 — 하나만 끝내면 나머지 묶음이 영영 {@code RUNNING}으로 남는다.
 *
 * <p>{@code REQUIRES_NEW}로 여는 이유: 이 메서드는 {@code AFTER_COMMIT} 콜백에서 불린다. 그 시점의 원래 트랜잭션은 커밋됐지만 자원이 아직
 * 묶여 있어서, 기본 전파로 열면 끝난 트랜잭션에 참여해 <b>변경이 다시 커밋되지 않는다</b>(backend CLAUDE.md).
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class ParamSearchProgressService {

    private static final Logger log = LoggerFactory.getLogger(ParamSearchProgressService.class);

    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final CandidateDispatcher dispatcher;
    private final ParamSearchCompletionService completionService;
    private final TimeLimitGuard timeLimitGuard;
    private final FailureLimitGuard failureLimitGuard;

    public ParamSearchProgressService(
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            CandidateDispatcher dispatcher,
            ParamSearchCompletionService completionService,
            TimeLimitGuard timeLimitGuard,
            FailureLimitGuard failureLimitGuard) {
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.dispatcher = dispatcher;
        this.completionService = completionService;
        this.timeLimitGuard = timeLimitGuard;
        this.failureLimitGuard = failureLimitGuard;
    }

    /**
     * 실행 하나가 끝났다. 그 실행에 걸린 후보를 전부 끝낸다.
     *
     * @param backtestJobId 끝난 실행 행의 id
     * @param failed 실패로 끝났는가
     * @param errorCode 실패 사유 코드. 성공이면 null
     * @return 이 이벤트가 묶음에 속한 후보의 것이었는가
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean candidateFinished(long backtestJobId, boolean failed, String errorCode) {
        List<SearchCandidate> bound = candidateRepository.findAllByBacktestJobId(backtestJobId);
        if (bound.isEmpty()) {
            // 묶음에 속하지 않은 단일 실행이 대부분이다. 경고를 남기면 로그가 그것으로 덮인다(내부 계약 §3).
            return false;
        }
        // 묶음 id 순으로 처리한다. 한 실행에 여러 묶음의 후보가 걸려 있으면 묶음 행을 여럿 잠그게 되는데,
        // 잠그는 순서가 이벤트마다 다르면 서로의 락을 기다리다 엉킨다.
        bound.stream()
                .sorted(Comparator.comparing(SearchCandidate::getParamSearchId))
                .forEach(candidate -> applyResult(candidate, failed, errorCode));
        return true;
    }

    /**
     * 후보 하나에 결과를 반영하고 그 묶음을 전진시킨다.
     *
     * <p>이미 끝난 후보는 건너뛴다 — 늦게 도착한 결과가 취소·한도로 닫힌 후보를 되살리면 안 된다.
     */
    private void applyResult(SearchCandidate candidate, boolean failed, String errorCode) {
        if (candidate.getStatus().isFinal()) {
            log.info(
                    "late candidate result ignored: candidateId={} status={}",
                    candidate.getId(),
                    candidate.getStatus());
            return;
        }
        candidateRepository.update(
                failed
                        ? candidate.markFailed(
                                errorCode == null ? "ENGINE_ERROR" : errorCode, "후보 실행이 실패했습니다")
                        : candidate.markCompleted());

        ParamSearch locked =
                paramSearchRepository
                        .findByIdForUpdate(candidate.getParamSearchId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "candidate without a search: "
                                                        + candidate.getId()));
        // 한도는 다음 발행 직전에 본다. 여기서 멈추기로 하면 그 사유가 곧 PARTIAL 의 사유가 된다.
        // 이미 발행된 후보는 끊지 않는다 — 계산이 끝나가는 후보를 버리면 그만큼이 통째로 사라진다.
        ParamSearch guarded = applyLimits(locked);
        // 집계 갱신과 종료 판정·선정은 completionService 한곳이 한다. 취소로 끝나는 길과
        // 같은 코드를 쓰지 않으면 한쪽만 고쳐져 취소된 묶음에만 선정이 안 붙는다.
        ParamSearch settled = completionService.settle(guarded);
        if (!settled.getStatus().isFinal()) {
            // 아직 남았다 — 창의 빈자리를 채운다. 멈추기로 한 묶음이면 advance 가 스스로 막는다.
            dispatcher.advance(settled.getId(), snapshotOf(settled));
        }
    }

    /**
     * 실행시간·연속 실패 한도를 보고, 닿았으면 멈추기로 표시한다(FR-005, 내부 계약 §4 "한도 도달").
     *
     * <p><b>순서가 뜻을 갖는다.</b> 실행시간을 먼저 본다 — 오래 걸려 멈추는 것과 줄줄이 실패해 멈추는 것이 동시에 참일 때, 사용자가 먼저 알아야 할 것은
     * "시간이 다 됐다"이기 때문이다. 이미 사유가 있으면 {@code requestStop}이 덮어쓰지 않는다.
     *
     * <p>남은 대기 후보가 없으면 보지 않는다 — 멈출 것이 없는데 사유를 남기면 정상 완료가 한도 도달로 기록된다.
     */
    private ParamSearch applyLimits(ParamSearch search) {
        if (search.isStopRequested()
                || candidateRepository.countByStatus(search.getId(), CandidateStatus.PENDING)
                        == 0) {
            return search;
        }
        StopReason reason = null;
        if (timeLimitGuard.isReached(search)) {
            reason = StopReason.TIME_LIMIT;
        } else if (failureLimitGuard.isReached(search.getId())) {
            reason = StopReason.FAILURE_LIMIT;
        }
        if (reason == null) {
            return search;
        }
        log.info(
                "param search stopped by a limit: searchId={} reason={}",
                search.getSearchId().value(),
                reason);
        ParamSearch stopped = paramSearchRepository.update(search.requestStop(reason));
        skipPending(stopped, reason);
        return stopped;
    }

    /** 한도로 멈추면 남은 대기 후보는 '미실행'이다 — 취소와 같은 뜻이며 되돌아오지 않는다(FR-009). */
    private void skipPending(ParamSearch search, StopReason reason) {
        for (SearchCandidate candidate :
                candidateRepository.findByParamSearchIdAndStatus(
                        search.getId(), CandidateStatus.PENDING)) {
            candidateRepository.update(candidate.markSkipped(reason.name(), detailOf(reason)));
        }
    }

    private static String detailOf(StopReason reason) {
        return switch (reason) {
            case TIME_LIMIT -> "실행시간 상한에 닿아 실행되지 않았습니다";
            case FAILURE_LIMIT -> "연속 실패가 이어져 실행되지 않았습니다";
            default -> "실행되지 않았습니다";
        };
    }

    private static ParamSearchSnapshot snapshotOf(ParamSearch search) {
        return ParamSearchSnapshot.fromMap(
                search.getId(),
                search.getSearchId().value(),
                search.getOwnerUserId(),
                search.getExecutionPlan());
    }
}
