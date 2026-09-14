package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.application.result.SelectionResult;
import com.ssafy.thorp.paramsearch.application.service.selection.DrawdownFilter;
import com.ssafy.thorp.paramsearch.application.service.selection.StabilityFilter;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 표시 후보 선정(FR-013·FR-017, research R3).
 *
 * <p>순서는 손실 제약 → 안정성 → 정렬 → 상위 {@code displayCount}다. <b>규칙은 접수 시점 스냅샷이 정하고 여기서 고르지
 * 않는다</b>(FR-013c, 원칙 VII) — 결과를 본 뒤 기준을 바꾸면 그것은 새 묶음이다.
 *
 * <p><b>모자라면 모자란 대로 둔다</b>(원칙 VII, Edge Case). 문턱 통과 후보가 5개에 못 미쳐도 필터를 느슨하게 해 자리를 채우지 않는다. 채우면 "기준을
 * 통과한 후보"라는 표시가 거짓이 되고, 화면은 통과하지 못한 후보를 통과한 것으로 읽는다.
 *
 * <p><b>동률은 잘라내지 않고 전부 표시한다</b>(R3). 정렬 지표 값이 같은 두 후보 중 하나만 남기면 그 선택의 근거가 정렬 구현의 우연이 된다. 그래서 상한 경계에
 * 걸친 값과 같은 후보는 {@code displayCount}를 넘겨도 함께 나간다.
 *
 * <p><b>이 클래스에 전략 지식이 없다</b>(FR-025). 기준 전략 ID는 스냅샷에 있고 선정은 그 값을 읽지도 않는다 — 필요한 것은 기준 실행의 숫자뿐이다.
 */
@Service
public class CandidateSelector {

    private final DrawdownFilter drawdownFilter;
    private final StabilityFilter stabilityFilter;

    public CandidateSelector(DrawdownFilter drawdownFilter, StabilityFilter stabilityFilter) {
        this.drawdownFilter = drawdownFilter;
        this.stabilityFilter = stabilityFilter;
    }

    /**
     * 묶음 하나의 표시 후보를 고른다.
     *
     * @param rule 접수 시점 선정 규칙 스냅샷
     * @param candidates 묶음의 후보 전체. 완료되지 않은 후보도 사유를 남기려고 함께 받는다
     * @param performanceByCandidateId 완료 후보의 성과. 값이 없으면 정렬·필터에 쓸 수 없다
     * @param stabilityByCandidateId 완료 후보의 인접 안정성
     * @param baselineMaxDrawdown 기준 실행의 가장 크게 줄었던 폭. 기준이 없거나 실패했으면 null
     */
    public SelectionResult select(
            SelectionRule rule,
            List<SearchCandidate> candidates,
            Map<Long, CandidatePerformance> performanceByCandidateId,
            Map<Long, StabilityScore> stabilityByCandidateId,
            BigDecimal baselineMaxDrawdown) {
        boolean drawdownApplied = drawdownFilter.isApplicable(rule, baselineMaxDrawdown);
        BigDecimal stabilityThreshold =
                stabilityFilter.thresholdOf(rule, stabilityByCandidateId.values());

        Map<Long, Map<String, Object>> reasons = new LinkedHashMap<>();
        List<Ranked> passed = new ArrayList<>();

        for (SearchCandidate candidate : candidates) {
            CandidatePerformance performance = performanceByCandidateId.get(candidate.getId());
            if (candidate.getStatus() != CandidateStatus.COMPLETED
                    || performance == null
                    || !performance.isComparable()) {
                reasons.put(candidate.getId(), notEvaluated(candidate));
                continue;
            }
            List<String> passedFilters = new ArrayList<>();
            if (drawdownApplied) {
                if (!drawdownFilter.passes(performance.maxDrawdown(), baselineMaxDrawdown)) {
                    reasons.put(candidate.getId(), filteredOut("DRAWDOWN", passedFilters));
                    continue;
                }
                passedFilters.add("DRAWDOWN");
            }
            StabilityScore stability = stabilityByCandidateId.get(candidate.getId());
            if (!stabilityFilter.passes(stability, stabilityThreshold)) {
                reasons.put(candidate.getId(), filteredOut("STABILITY", passedFilters));
                continue;
            }
            passedFilters.add("STABILITY");
            passed.add(new Ranked(candidate, performance.netReturn(), passedFilters));
        }

        List<Ranked> displayed = takeTopWithTies(passed, rule.displayCount());
        Set<Long> displayedIds = new HashSet<>();
        displayed.forEach(ranked -> displayedIds.add(ranked.candidate().getId()));
        for (Ranked ranked : passed) {
            reasons.put(
                    ranked.candidate().getId(),
                    displayedIds.contains(ranked.candidate().getId())
                            ? selected(ranked, displayed)
                            : notInDisplay(ranked.passedFilters()));
        }

        return new SelectionResult(
                displayed.stream().map(ranked -> ranked.candidate().getCandidateNo()).toList(),
                displayed.size() < rule.displayCount(),
                noteOf(displayed.size(), rule.displayCount()),
                drawdownApplied,
                reasons);
    }

    /**
     * 정렬 지표 내림차순으로 상위 {@code displayCount}를 고르되, 경계값과 같은 후보는 전부 담는다(R3).
     *
     * <p>같은 값끼리는 순번 오름차순으로 둔다 — 표시 순서를 정해야 하지만 그 근거가 지표에 없을 때, 접수 순서가 유일하게 재현 가능한 기준이다(원칙 VIII).
     * 이것은 등수가 아니라 목록의 안정적인 나열이다.
     */
    private static List<Ranked> takeTopWithTies(List<Ranked> passed, int displayCount) {
        List<Ranked> sorted = new ArrayList<>(passed);
        sorted.sort(
                Comparator.comparing(Ranked::netReturn)
                        .reversed()
                        .thenComparingInt(ranked -> ranked.candidate().getCandidateNo()));
        if (sorted.size() <= displayCount) {
            return sorted;
        }
        BigDecimal cutoff = sorted.get(displayCount - 1).netReturn();
        List<Ranked> displayed = new ArrayList<>();
        for (Ranked ranked : sorted) {
            if (displayed.size() >= displayCount && ranked.netReturn().compareTo(cutoff) != 0) {
                break;
            }
            displayed.add(ranked);
        }
        return displayed;
    }

    /** 표시 후보가 모자란 이유를 한 문장으로. 채우지 않았다는 사실 자체를 알린다(Edge Case). */
    private static String noteOf(int displayedCount, int displayCount) {
        if (displayedCount == 0) {
            return "기준을 충족한 후보가 없습니다";
        }
        if (displayedCount < displayCount) {
            return "기준을 충족한 후보가 %d개입니다".formatted(displayedCount);
        }
        return null;
    }

    private static Map<String, Object> selected(Ranked ranked, List<Ranked> displayed) {
        boolean tied =
                displayed.stream()
                        .anyMatch(
                                other ->
                                        other != ranked
                                                && other.netReturn().compareTo(ranked.netReturn())
                                                        == 0);
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("outcome", "SELECTED");
        reason.put("tied", tied);
        reason.put("passedFilters", List.copyOf(ranked.passedFilters()));
        return reason;
    }

    /** 두 필터를 통과했지만 정렬에서 표시 범위 밖이다. 탈락이 아니라 "표시하지 않음"이다. */
    private static Map<String, Object> notInDisplay(List<String> passedFilters) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("outcome", "NOT_IN_DISPLAY");
        reason.put("passedFilters", List.copyOf(passedFilters));
        return reason;
    }

    private static Map<String, Object> filteredOut(
            String failedFilter, List<String> passedFilters) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("outcome", "FILTERED_OUT");
        reason.put("failedFilter", failedFilter);
        reason.put("passedFilters", List.copyOf(passedFilters));
        return reason;
    }

    /**
     * 계산되지 않아 비교 대상이 아닌 후보. 제외는 계산 전 사유를, 실패·미실행은 그 사유를 그대로 옮긴다(FR-020).
     *
     * <p>성과 값을 0으로 채우지 않으므로 여기서도 숫자를 만들지 않는다(FR-011).
     */
    private static Map<String, Object> notEvaluated(SearchCandidate candidate) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put(
                "outcome",
                candidate.getStatus() == CandidateStatus.EXCLUDED
                        ? "EXCLUDED_BEFORE_RUN"
                        : "NOT_EVALUATED");
        reason.put("code", candidate.getReasonCode());
        reason.put("message", candidate.getReasonDetail());
        return reason;
    }

    /** 필터를 통과해 정렬 대상이 된 후보. */
    private record Ranked(
            SearchCandidate candidate, BigDecimal netReturn, List<String> passedFilters) {}
}
