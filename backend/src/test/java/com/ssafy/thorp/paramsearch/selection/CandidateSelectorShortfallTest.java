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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모자란 표시 후보(T069, 원칙 VII, Edge Case).
 *
 * <p>여기서 지키는 것 하나 — <b>문턱을 낮춰 자리를 채우지 않는다.</b> 채우면 "기준을 통과한 후보"라는 표시가 거짓이 된다.
 */
class CandidateSelectorShortfallTest {

    private final CandidateSelector selector =
            new CandidateSelector(new DrawdownFilter(), new StabilityFilter());

    @Test
    @DisplayName("통과 후보가 5개에 못 미치면 그 수만 표시하고 사유를 남긴다")
    void doesNotLowerTheBarToFillFiveSlots() {
        // 손실 폭이 기준보다 큰 후보 넷은 떨어지고 둘만 남는다.
        Map<Long, String> drawdowns =
                Map.of(
                        1L, "-0.1000", 2L, "-0.1500", 3L, "-0.9000", 4L, "-0.9000", 5L, "-0.9000",
                        6L, "-0.9000");

        SelectionResult result = select(drawdowns);

        assertThat(result.displayedCandidateNos()).hasSize(2);
        assertThat(result.shortOfDisplayCount()).isTrue();
        assertThat(result.note()).isEqualTo("기준을 충족한 후보가 2개입니다");
        assertThat(result.selectionReasonByCandidateId().get(3L))
                .containsEntry("outcome", "FILTERED_OUT")
                .containsEntry("failedFilter", "DRAWDOWN");
    }

    @Test
    @DisplayName("통과 후보가 0개면 '기준을 충족한 후보가 없습니다'다")
    void noneMeetingTheBarIsStatedPlainly() {
        SelectionResult result = select(Map.of(1L, "-0.9000", 2L, "-0.9000"));

        assertThat(result.displayedCandidateNos()).isEmpty();
        assertThat(result.shortOfDisplayCount()).isTrue();
        assertThat(result.note()).isEqualTo("기준을 충족한 후보가 없습니다");
    }

    @Test
    @DisplayName("계산되지 않은 후보는 사유만 남고 성과 숫자를 만들지 않는다")
    void unevaluatedCandidatesKeepTheirReason() {
        SelectionResult result =
                selector.select(
                        SelectionFixtures.ruleWithBaseline(),
                        List.of(
                                SelectionFixtures.excluded(1L, "TOP_N_EXCEEDS_UNIVERSE", "섹터 수 초과"),
                                SelectionFixtures.failed(2L, "ENGINE_ERROR", "후보 실행이 실패했습니다")),
                        Map.of(),
                        Map.of(),
                        new BigDecimal("-0.2000"));

        assertThat(result.displayedCandidateNos()).isEmpty();
        assertThat(result.selectionReasonByCandidateId().get(1L))
                .containsEntry("outcome", "EXCLUDED_BEFORE_RUN")
                .containsEntry("code", "TOP_N_EXCEEDS_UNIVERSE");
        assertThat(result.selectionReasonByCandidateId().get(2L))
                .containsEntry("outcome", "NOT_EVALUATED")
                .containsEntry("code", "ENGINE_ERROR");
    }

    private SelectionResult select(Map<Long, String> drawdownById) {
        Map<Long, CandidatePerformance> performance = new LinkedHashMap<>();
        Map<Long, StabilityScore> stability = new LinkedHashMap<>();
        List<SearchCandidate> candidates =
                drawdownById.keySet().stream()
                        .sorted()
                        .peek(
                                id -> {
                                    performance.put(
                                            id,
                                            SelectionFixtures.performance(
                                                    "0.1000", drawdownById.get(id)));
                                    stability.put(
                                            id, StabilityScore.of(new BigDecimal("0.0100"), 2));
                                })
                        .map(SelectionFixtures::completed)
                        .toList();
        return selector.select(
                SelectionFixtures.ruleWithBaseline(),
                candidates,
                performance,
                stability,
                new BigDecimal("-0.2000"));
    }
}
