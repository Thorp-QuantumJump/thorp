package com.ssafy.thorp.paramsearch.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.application.result.SelectionResult;
import com.ssafy.thorp.paramsearch.application.service.CandidateSelector;
import com.ssafy.thorp.paramsearch.application.service.selection.DrawdownFilter;
import com.ssafy.thorp.paramsearch.application.service.selection.StabilityFilter;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 정렬과 동률 처리(T068, research R3, Edge Case). */
class CandidateSelectorSortTest {

    private final CandidateSelector selector =
            new CandidateSelector(new DrawdownFilter(), new StabilityFilter());

    @Test
    @DisplayName("비용 차감 후 수익 내림차순으로 상위 5개를 고른다")
    void sortsByNetReturnDescending() {
        SelectionResult result =
                select(
                        Map.of(
                                1L, "0.10", 2L, "0.50", 3L, "0.20", 4L, "0.40", 5L, "0.30", 6L,
                                "0.05"));

        assertThat(result.displayedCandidateNos()).containsExactly(2, 4, 5, 3, 1);
        assertThat(result.shortOfDisplayCount()).isFalse();
        assertThat(result.note()).isNull();
    }

    @Test
    @DisplayName("경계에 걸친 동률은 5개를 넘겨서라도 전부 표시한다")
    void tiesAtTheBoundaryAllShow() {
        // 5위 자리에 0.30이 셋이다. 하나만 남기면 그 선택의 근거가 정렬 구현의 우연이 된다(R3).
        SelectionResult result =
                select(
                        Map.of(
                                1L, "0.50", 2L, "0.40", 3L, "0.35", 4L, "0.32", 5L, "0.30", 6L,
                                "0.30", 7L, "0.30", 8L, "0.10"));

        assertThat(result.displayedCandidateNos()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(result.displayedCandidateNos()).hasSizeGreaterThan(5);
        assertThat(result.shortOfDisplayCount()).isFalse();
    }

    @Test
    @DisplayName("동률 후보에는 tied 표시가 붙는다")
    void tiedCandidatesAreMarked() {
        SelectionResult result = select(Map.of(1L, "0.30", 2L, "0.30", 3L, "0.10"));

        assertThat(result.selectionReasonByCandidateId().get(1L))
                .containsEntry("outcome", "SELECTED")
                .containsEntry("tied", true);
        assertThat(result.selectionReasonByCandidateId().get(3L)).containsEntry("tied", false);
    }

    @Test
    @DisplayName("표시 범위 밖 후보는 탈락이 아니라 '표시하지 않음'이다")
    void beyondDisplayCountIsNotAFilterFailure() {
        SelectionResult result =
                select(
                        Map.of(
                                1L, "0.60", 2L, "0.50", 3L, "0.40", 4L, "0.30", 5L, "0.20", 6L,
                                "0.10"));

        assertThat(result.selectionReasonByCandidateId().get(6L))
                .containsEntry("outcome", "NOT_IN_DISPLAY");
        assertThat(result.selectionReasonByCandidateId().get(6L).get("passedFilters"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("DRAWDOWN", "STABILITY");
    }

    /** 모든 후보가 두 필터를 통과하도록 손실 폭·안정성을 같게 둔다 — 여기서 보는 것은 정렬뿐이다. */
    private SelectionResult select(Map<Long, String> netReturnById) {
        List<SearchCandidate> candidates = new ArrayList<>();
        Map<Long, CandidatePerformance> performance = new LinkedHashMap<>();
        Map<Long, StabilityScore> stability = new LinkedHashMap<>();
        netReturnById.keySet().stream()
                .sorted()
                .forEach(
                        id -> {
                            candidates.add(SelectionFixtures.completed(id));
                            performance.put(
                                    id,
                                    SelectionFixtures.performance(
                                            netReturnById.get(id), "-0.1000"));
                            stability.put(id, StabilityScore.of(new BigDecimal("0.0100"), 2));
                        });
        return selector.select(
                SelectionFixtures.ruleWithBaseline(),
                candidates,
                performance,
                stability,
                new BigDecimal("-0.2000"));
    }
}
