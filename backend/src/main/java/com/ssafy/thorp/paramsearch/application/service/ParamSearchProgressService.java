package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import java.time.Clock;
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
    private final ParamSearchSelectionService selectionService;
    private final Clock clock;

    public ParamSearchProgressService(
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            CandidateDispatcher dispatcher,
            ParamSearchSelectionService selectionService,
            Clock clock) {
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.dispatcher = dispatcher;
        this.selectionService = selectionService;
        this.clock = clock;
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
        ParamSearch updated = paramSearchRepository.update(locked.withCounts(recount(locked)));
        finishOrAdvance(updated);
        return true;
    }

    /** 후보 상태를 세어 집계를 다시 만든다. 더하지 않고 세는 것이 동시 수신에서 유일하게 안전하다. */
    private SearchCounts recount(ParamSearch search) {
        Long id = search.getId();
        SearchCounts before = search.getCounts();
        return new SearchCounts(
                before.expected(),
                before.valid(),
                candidateRepository.countByStatus(id, CandidateStatus.COMPLETED),
                candidateRepository.countByStatus(id, CandidateStatus.FAILED),
                candidateRepository.countByStatus(id, CandidateStatus.EXCLUDED),
                candidateRepository.countByStatus(id, CandidateStatus.SKIPPED));
    }

    /**
     * 남은 후보가 있으면 창을 채우고, 없으면 묶음을 끝낸다.
     *
     * <p>미실행 후보가 남은 채 끝나면 {@code PARTIAL}이다 — 취소·한도 도달의 결과이며 완료된 후보로는 그대로 비교할 수 있다(FR-009).
     */
    private void finishOrAdvance(ParamSearch search) {
        if (!dispatcher.isDrained(search.getId())) {
            dispatcher.advance(search.getId(), snapshotOf(search));
            return;
        }
        // 여기가 묶음의 끝이다 — PENDING 0 && DISPATCHED 0(내부 계약 §4 "묶음 종료").
        if (search.getCounts().skipped() > 0) {
            // 멈춘 사유는 멈춘 쪽(취소·한도)이 남기고 PARTIAL 전이도 그쪽이 한다.
            // 선정은 최종 상태가 된 뒤에만 붙일 수 있으므로(ParamSearch.withSelection)
            // 그 경로가 전이 직후 selectOnce 를 부른다. 취소된 묶음도 완료된 후보끼리는
            // 그대로 비교할 수 있다(data-model §3).
            return;
        }
        // 완료로 확정한 뒤 선정을 딱 한 번 계산한다. 안정성 필터가 묶음 내 상대 분위라
        // 중간에 재면 나중에 끝난 후보가 분위를 흔들어 앞선 판정의 근거가 사라진다(불변식 3).
        paramSearchRepository.update(
                selectionService.selectOnce(search.markCompleted(clock.instant())));
    }

    private static ParamSearchSnapshot snapshotOf(ParamSearch search) {
        return ParamSearchSnapshot.fromMap(
                search.getId(),
                search.getSearchId().value(),
                search.getOwnerUserId(),
                search.getExecutionPlan());
    }
}
