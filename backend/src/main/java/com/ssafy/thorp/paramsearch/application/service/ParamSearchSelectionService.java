package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.application.port.CandidatePerformancePort;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.SelectionResult;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.CandidateId;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 묶음이 끝났을 때 안정성 → 선정을 계산하고 그 결과를 붙박는다(T077·T078, FR-013).
 *
 * <p><b>묶음당 정확히 1회다</b>(내부 계약 §4 불변식 3). 안정성 필터가 "묶음 내 상위 50%"라는 상대 분위라서, 중간 상태에서 계산하면 나중에 끝난 후보가
 * 분위를 흔들어 이미 선정된 후보의 근거가 사라진다. 호출자가 묶음 행을 비관적 락으로 잡은 뒤 부르고, 여기서도 <b>이미 선정이 있으면 다시 계산하지 않는다</b> — 락과
 * 검사 둘 다 두는 이유는 재시도 완료(FR-024)처럼 정당하게 다시 부르는 경로가 생길 때 그 판단을 호출자가 명시하게 하려는 것이다.
 *
 * <p>{@code COMPLETED}와 {@code PARTIAL} 둘 다 선정한다(data-model §3). 취소된 묶음도 완료된 후보끼리는 그대로 비교할 수 있다.
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class ParamSearchSelectionService {

    private final SearchCandidateRepository candidateRepository;
    private final CandidatePerformancePort performancePort;
    private final NeighborStabilityCalculator stabilityCalculator;
    private final CandidateSelector selector;

    public ParamSearchSelectionService(
            SearchCandidateRepository candidateRepository,
            CandidatePerformancePort performancePort,
            NeighborStabilityCalculator stabilityCalculator,
            CandidateSelector selector) {
        this.candidateRepository = candidateRepository;
        this.performancePort = performancePort;
        this.stabilityCalculator = stabilityCalculator;
        this.selector = selector;
    }

    /**
     * 선정을 계산해 후보와 묶음에 반영한다.
     *
     * @param search 잠긴 묶음 행
     * @return 선정 스냅샷을 담은 묶음. 이미 선정이 있으면 인자를 그대로 돌려준다
     */
    public ParamSearch selectOnce(ParamSearch search) {
        if (search.getSelection() != null) {
            return search;
        }
        List<SearchCandidate> candidates = candidateRepository.findByParamSearchId(search.getId());

        Map<Long, CandidatePerformance> performanceByCandidateId =
                performanceByCandidateId(candidates);
        Map<Long, StabilityScore> stabilityByCandidateId =
                stabilityCalculator.calculate(candidates, netReturns(performanceByCandidateId));

        SelectionResult result =
                selector.select(
                        search.getSelectionRule(),
                        candidates,
                        performanceByCandidateId,
                        stabilityByCandidateId,
                        baselineMaxDrawdown(search));

        persist(candidates, stabilityByCandidateId, result);
        return search.withSelection(result.toMap(displayedIds(search, result)));
    }

    /** 후보 id → 성과. 발행된 후보만 실행 행을 가지므로 그것만 묻는다. */
    private Map<Long, CandidatePerformance> performanceByCandidateId(
            List<SearchCandidate> candidates) {
        Map<Long, Long> runIdByCandidateId = new LinkedHashMap<>();
        for (SearchCandidate candidate : candidates) {
            if (candidate.getBacktestJobId() != null) {
                runIdByCandidateId.put(candidate.getId(), candidate.getBacktestJobId());
            }
        }
        Map<Long, CandidatePerformance> byRunId =
                performancePort.findByRunIds(runIdByCandidateId.values());
        Map<Long, CandidatePerformance> byCandidateId = new LinkedHashMap<>();
        runIdByCandidateId.forEach(
                (candidateId, runId) -> {
                    CandidatePerformance performance = byRunId.get(runId);
                    if (performance != null) {
                        byCandidateId.put(candidateId, performance);
                    }
                });
        return byCandidateId;
    }

    private static Map<Long, BigDecimal> netReturns(
            Map<Long, CandidatePerformance> performanceByCandidateId) {
        Map<Long, BigDecimal> netReturns = new LinkedHashMap<>();
        performanceByCandidateId.forEach(
                (candidateId, performance) -> {
                    if (performance.netReturn() != null) {
                        netReturns.put(candidateId, performance.netReturn());
                    }
                });
        return netReturns;
    }

    /**
     * 기준 실행의 가장 크게 줄었던 폭. 기준을 만들지 않았거나 실행이 실패했으면 null이고, 그때 손실 제약은 걸리지 않는다(FR-013d).
     *
     * <p>기준 전략이 무엇인지 묻지 않는다 — 필요한 것은 숫자뿐이다(FR-025).
     */
    private BigDecimal baselineMaxDrawdown(ParamSearch search) {
        Long baselineJobId = search.getBaselineJobId();
        if (baselineJobId == null) {
            return null;
        }
        CandidatePerformance baseline =
                performancePort.findByRunIds(List.of(baselineJobId)).get(baselineJobId);
        return baseline == null ? null : baseline.maxDrawdown();
    }

    /** 후보마다 안정성과 선정 사유를 붙인다. 탈락도 어느 단계에서 빠졌는지 남는다(FR-020). */
    private void persist(
            List<SearchCandidate> candidates,
            Map<Long, StabilityScore> stabilityByCandidateId,
            SelectionResult result) {
        List<Integer> displayedNos = result.displayedCandidateNos();
        for (SearchCandidate candidate : candidates) {
            SearchCandidate updated = candidate;
            StabilityScore stability = stabilityByCandidateId.get(candidate.getId());
            if (stability != null) {
                updated = updated.withStability(stability.toMap());
            }
            Map<String, Object> reason =
                    result.selectionReasonByCandidateId().get(candidate.getId());
            updated =
                    updated.withSelection(
                            displayedNos.contains(candidate.getCandidateNo()), reason);
            candidateRepository.update(updated);
        }
    }

    private static List<String> displayedIds(ParamSearch search, SelectionResult result) {
        List<String> ids = new ArrayList<>();
        result.displayedCandidateNos()
                .forEach(
                        candidateNo ->
                                ids.add(CandidateId.of(search.getSearchId(), candidateNo).value()));
        return ids;
    }
}
