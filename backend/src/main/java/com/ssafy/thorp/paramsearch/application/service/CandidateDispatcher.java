package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 발행 창 관리(research R2, contracts/param-search-internal.md §4).
 *
 * <p><b>접수 시점에 후보 전량을 발행하지 않는다.</b> 이유는 둘이다 — 한 묶음이 {@code backtest.jobs}를 독점하면 다른 사용자의 단일 실행이 뒤로
 * 밀리고, 취소를 "다음 후보를 발행하지 않는다"는 동작으로 만들 수 없다(이미 큐에 든 메시지는 회수할 수 없다).
 *
 * <p>불변식: 한 묶음의 {@code DISPATCHED} 후보 수는 항상 발행 창 이하다. 동시에 도착한 결과 이벤트가 서로의 셈을 덮어쓰지 않도록 <b>묶음 행을 비관적
 * 락으로 잡고</b> 창을 채운다 — 락 없이 하면 두 이벤트가 각각 "창에 자리가 있다"고 판단해 창을 넘겨 발행한다.
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class CandidateDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CandidateDispatcher.class);

    private final CandidateJobLauncher launcher;
    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final SearchLimits limits;
    private final Clock clock;

    public CandidateDispatcher(
            CandidateJobLauncher launcher,
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            SearchLimits limits,
            Clock clock) {
        this.launcher = launcher;
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.limits = limits;
        this.clock = clock;
    }

    /**
     * 접수 직후 첫 발행. 기준 1건을 먼저 보내고 창만큼 후보를 발행한다.
     *
     * <p>기준을 먼저 보내는 이유: 하락 폭 필터의 기준값이 없으면 선정 계산이 성립하지 않는다(FR-013d). 기준 전략 지정이 없으면 건너뛰고 그 사실을 선정 규칙
     * 스냅샷이 이미 담고 있다.
     */
    public ParamSearch start(ParamSearch saved, ParamSearchSnapshot snapshot) {
        ParamSearch running = paramSearchRepository.update(saved.markRunning(clock.instant()));
        if (snapshot.baselineStrategyId() != null) {
            running =
                    paramSearchRepository.update(
                            running.withBaselineJobId(launcher.launchBaseline(snapshot)));
        }
        fillWindow(running.getId(), snapshot);
        return paramSearchRepository.findById(running.getId()).orElse(running);
    }

    /**
     * 후보 하나가 끝났을 때 창을 다시 채운다. 묶음 행을 잠그고 세므로 동시 수신에서도 창을 넘지 않는다.
     *
     * <p>이미 끝난 묶음이면 아무것도 하지 않는다 — 취소·한도 도달 후에는 새 후보가 발행되지 않는다(불변식 2).
     */
    public void advance(Long paramSearchId, ParamSearchSnapshot snapshot) {
        ParamSearch locked = paramSearchRepository.findByIdForUpdate(paramSearchId).orElse(null);
        if (locked == null || locked.getStatus().isFinal() || locked.isStopRequested()) {
            // 멈추기로 한 묶음에는 새 후보를 내보내지 않는다. 상태는 아직 RUNNING 이지만
            // 사유가 박혀 있으면 그것이 발행을 막는 근거다(불변식 2).
            return;
        }
        fillWindow(paramSearchId, snapshot);
    }

    /**
     * 창의 빈자리만큼 발행한다. <b>락은 호출자가 잡는다</b> — {@link #advance}가 묶음 행을 잠근 뒤 부른다.
     *
     * <p>패키지 안에서만 보이게 둔 이유는 이 불변식(창을 넘지 않는다)을 락 없이도 단위 테스트로 고정하기 위해서다.
     */
    void fillWindow(Long paramSearchId, ParamSearchSnapshot snapshot) {
        long inFlight =
                candidateRepository.countByStatus(paramSearchId, CandidateStatus.DISPATCHED);
        int room = limits.dispatchWindow() - (int) inFlight;
        // 거부된 후보는 창의 자리를 차지하지 않으므로 다음 후보로 자리를 다시 채운다. 창이 1일 때
        // 이 되채움이 없으면 후보 하나가 거부되는 순간 실행 중이 0이 되고, 창을 깨울 결과 이벤트가
        // 영영 오지 않아 묶음이 멈춘다.
        while (room > 0) {
            List<SearchCandidate> next =
                    candidateRepository.findNextByStatus(
                            paramSearchId, CandidateStatus.PENDING, room);
            if (next.isEmpty()) {
                return;
            }
            for (SearchCandidate candidate : next) {
                if (dispatch(snapshot, candidate)) {
                    room--;
                }
            }
        }
    }

    /**
     * 후보 하나를 발행한다.
     *
     * @return 창의 자리를 실제로 쓴 발행이었는가. 접수가 거부해 제외된 후보는 {@code false}다
     *     <p>후보를 {@code FAILED}로 바꾸지 않는다 — 계산 실패와 발행 실패는 다르다(FR-011). {@code FAILED}는 재시도 대상이라 "다시
     *     보내도 같은 답이 오는 조합"을 거기 두면 재시도가 끝나지 않는다. 접수가 거부한 조합은 {@code EXCLUDED}다.
     *     <p><b>접수 거부(BusinessException) 하나만 잡는다.</b> 한때 {@code catch (RuntimeException)}으로 "발행 실패면
     *     다음 후보로 넘어간다"를 의도했지만 그 복원력은 없었다 — {@code launchCandidate} → 004 접수가 <b>같은 트랜잭션에 참여</b>하므로,
     *     거기서 제약 위반이 나면 트랜잭션은 이미 rollback-only다. 잡아도 되살아나지 않고 커밋 시점에 {@code
     *     UnexpectedRollbackException}이 되어, 후보 하나의 실패가 묶음 전체를 지운 채 500으로 나갔다.
     *     <p>이 catch가 노리던 브로커 장애는 애초에 여기로 오지 않는다. 004는 작업 메시지를 <b>커밋 뒤에</b> 보내고(spec 004 R-18), 보내지
     *     못한 실행은 {@code published_at NULL}로 남아 재발행기가 집어 간다. 브로커가 죽어도 접수는 성공하고 후보는 발행된 채로 기다린다.
     */
    private boolean dispatch(ParamSearchSnapshot snapshot, SearchCandidate candidate) {
        long backtestJobId;
        try {
            backtestJobId = launcher.launchCandidate(snapshot, candidate);
        } catch (BusinessException rejected) {
            // 접수가 이 조합을 받지 않았다 — 계산 전 제외로 확정하고 다음 후보로 넘어간다.
            // 잡을 수 있는 이유: 004 접수는 BusinessException 에 noRollbackFor 를 걸어 두어
            // 이 예외로는 트랜잭션이 rollback-only 가 되지 않는다. 그 표시가 없는 예외(제약 위반 등)는
            // 잡아도 되살아나지 않으므로 여기서도 잡지 않는다.
            log.warn(
                    "candidate rejected on admission: paramSearchId={} candidateNo={} code={} message={}",
                    candidate.getParamSearchId(),
                    candidate.getCandidateNo(),
                    rejected.getErrorCode(),
                    rejected.getMessage());
            candidateRepository.update(
                    candidate.markExcluded(
                            rejected.getErrorCode().name(),
                            rejected.getMessage() == null
                                    ? "접수가 이 조합을 받지 않았습니다"
                                    : rejected.getMessage()));
            return false;
        }
        candidateRepository.update(candidate.markDispatched(backtestJobId));
        return true;
    }

    /** 묶음이 끝났는가 — 대기도 실행 중도 없으면 끝이다(내부 계약 §4 "묶음 종료"). */
    public boolean isDrained(Long paramSearchId) {
        return candidateRepository.countByStatus(paramSearchId, CandidateStatus.PENDING) == 0
                && candidateRepository.countByStatus(paramSearchId, CandidateStatus.DISPATCHED)
                        == 0;
    }

    /** 상태가 아직 진행 중인가. 종료 판정 전에 확인한다. */
    public static boolean isRunning(ParamSearchStatus status) {
        return status == ParamSearchStatus.RUNNING || status == ParamSearchStatus.QUEUED;
    }
}
