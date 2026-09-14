package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 묶음의 끝을 확정한다 — 집계 갱신, 최종 상태 전이, 선정 1회(FR-008·FR-009·FR-013).
 *
 * <p><b>끝나는 길이 둘이라 한곳에 모았다.</b> 마지막 후보가 결과를 보내 끝나는 길({@link ParamSearchProgressService})과, 취소·한도가
 * 남은 후보를 '미실행'으로 돌려 끝나는 길({@link ParamSearchCancelService})이다. 두 곳에 같은 판정을 두면 한쪽만 고쳐져 <b>취소된 묶음에만
 * 선정이 안 붙는</b> 식으로 갈라진다.
 *
 * <p>끝났는가의 기준은 하나다 — {@code PENDING} 0 && {@code DISPATCHED} 0(내부 계약 §4 "묶음 종료"). 멈추기로 한 묶음이라도 이미
 * 발행된 후보가 남아 있으면 아직 끝이 아니다. 그 후보들은 회수할 수 없어 완주한다(research R2).
 *
 * <p>{@code stopReason}이 있으면 {@code PARTIAL}, 없으면 {@code COMPLETED}다. 둘 다 선정을 계산한다 — 취소된 묶음도 완료된
 * 후보끼리는 같은 조건으로 계산됐으므로 그대로 비교할 수 있다(data-model §3).
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class ParamSearchCompletionService {

    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final CandidateDispatcher dispatcher;
    private final ParamSearchSelectionService selectionService;
    private final Clock clock;

    public ParamSearchCompletionService(
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
     * 집계를 다시 세고, 남은 후보가 없으면 최종 상태로 확정하며 선정을 1회 계산한다.
     *
     * <p><b>호출자가 묶음 행을 잠근 뒤 부른다.</b> 선정이 묶음당 1회여야 해서(불변식 3) 종료 판정과 전이가 한 임계 구역 안에 있어야 한다.
     *
     * @param search 잠긴 묶음 행
     * @return 갱신된 묶음. 아직 끝나지 않았으면 집계만 갱신된 상태다
     */
    public ParamSearch settle(ParamSearch search) {
        ParamSearch counted = paramSearchRepository.update(search.withCounts(recount(search)));
        if (counted.getStatus().isFinal() || !dispatcher.isDrained(counted.getId())) {
            return counted;
        }
        ParamSearch finished =
                counted.isStopRequested()
                        ? counted.markPartial(counted.getStopReason(), clock.instant())
                        : counted.markCompleted(clock.instant());
        return paramSearchRepository.update(selectionService.selectOnce(finished));
    }

    /**
     * 후보 상태를 세어 집계를 다시 만든다.
     *
     * <p><b>이전 값에 더하지 않고 센다.</b> 두 이벤트가 같은 값을 읽어 각자 더하면 하나가 사라진다 — 묶음 행을 잠근 뒤 세는 것이 동시 수신에서 유일하게
     * 안전하다(내부 계약 §4).
     *
     * <p>{@code expected}·{@code valid}는 접수 때 정해진 값이라 다시 세지 않는다. 그 둘은 "무엇을 하려 했는가"이고 나머지는 "어떻게
     * 됐는가"다.
     */
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
}
