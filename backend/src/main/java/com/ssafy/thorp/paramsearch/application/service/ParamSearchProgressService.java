package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import java.util.Optional;
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

    public ParamSearchProgressService(
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            CandidateDispatcher dispatcher,
            ParamSearchCompletionService completionService) {
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.dispatcher = dispatcher;
        this.completionService = completionService;
    }

    /**
     * 후보 하나가 끝났다.
     *
     * @param backtestJobId 끝난 실행 행의 id
     * @param failed 실패로 끝났는가
     * @param errorCode 실패 사유 코드. 성공이면 null
     * @return 이 이벤트가 묶음에 속한 후보의 것이었는가
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean candidateFinished(long backtestJobId, boolean failed, String errorCode) {
        Optional<SearchCandidate> found = candidateRepository.findByBacktestJobId(backtestJobId);
        if (found.isEmpty()) {
            // 묶음에 속하지 않은 단일 실행이 대부분이다. 경고를 남기면 로그가 그것으로 덮인다(내부 계약 §3).
            return false;
        }
        SearchCandidate candidate = found.get();
        if (candidate.getStatus().isFinal()) {
            log.info(
                    "late candidate result ignored: candidateId={} status={}",
                    candidate.getId(),
                    candidate.getStatus());
            return true;
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
        // 집계 갱신과 종료 판정·선정은 completionService 한곳이 한다. 취소로 끝나는 길과
        // 같은 코드를 쓰지 않으면 한쪽만 고쳐져 취소된 묶음에만 선정이 안 붙는다.
        ParamSearch settled = completionService.settle(locked);
        if (!settled.getStatus().isFinal()) {
            // 아직 남았다 — 창의 빈자리를 채운다. 멈추기로 한 묶음이면 advance 가 스스로 막는다.
            dispatcher.advance(settled.getId(), snapshotOf(settled));
        }
        return true;
    }

    private static ParamSearchSnapshot snapshotOf(ParamSearch search) {
        return ParamSearchSnapshot.fromMap(
                search.getId(),
                search.getSearchId().value(),
                search.getOwnerUserId(),
                search.getExecutionPlan());
    }
}
