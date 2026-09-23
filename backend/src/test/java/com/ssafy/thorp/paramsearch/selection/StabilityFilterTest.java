package com.ssafy.thorp.paramsearch.selection;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.application.service.selection.StabilityFilter;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.vo.StabilityScore;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 안정성 필터(T067, FR-013·FR-013b). */
class StabilityFilterTest {

    private final StabilityFilter filter = new StabilityFilter();
    private final SelectionRule rule = SelectionFixtures.ruleWithBaseline();

    @Test
    @DisplayName("계산 불가 후보는 통과한다 — 이웃이 없는 것은 후보의 흠이 아니다")
    void uncalculableAlwaysPasses() {
        BigDecimal threshold = filter.thresholdOf(rule, List.of(score("0.01"), score("0.02")));

        assertThat(filter.passes(StabilityScore.uncalculable(), threshold))
                .as("떨어뜨리면 축 끝 값이 구조적으로 선정에서 빠진다(FR-013b)")
                .isTrue();
    }

    @Test
    @DisplayName("상위 50%만 통과한다 — 값이 작을수록 안정적이다")
    void keepsTopHalf() {
        List<StabilityScore> scores =
                List.of(score("0.01"), score("0.02"), score("0.30"), score("0.40"));

        BigDecimal threshold = filter.thresholdOf(rule, scores);

        assertThat(threshold).isEqualByComparingTo("0.02");
        assertThat(filter.passes(score("0.01"), threshold)).isTrue();
        assertThat(filter.passes(score("0.02"), threshold)).isTrue();
        assertThat(filter.passes(score("0.30"), threshold)).isFalse();
    }

    @Test
    @DisplayName("경계값과 같은 후보는 전부 통과한다 — 동점을 정렬 순서로 가르지 않는다")
    void tiesAtTheThresholdAllPass() {
        List<StabilityScore> scores =
                List.of(score("0.01"), score("0.02"), score("0.02"), score("0.02"));

        BigDecimal threshold = filter.thresholdOf(rule, scores);

        assertThat(threshold).isEqualByComparingTo("0.02");
        assertThat(filter.passes(score("0.02"), threshold)).isTrue();
    }

    @Test
    @DisplayName("계산 가능한 후보가 하나뿐이어도 전멸시키지 않는다")
    void singleCalculableSurvives() {
        BigDecimal threshold = filter.thresholdOf(rule, List.of(score("0.05")));

        assertThat(filter.passes(score("0.05"), threshold)).isTrue();
    }

    @Test
    @DisplayName("잴 대상이 하나도 없으면 모두 통과한다")
    void noCalculableScoresMeansNoThreshold() {
        BigDecimal threshold = filter.thresholdOf(rule, List.of(StabilityScore.uncalculable()));

        assertThat(threshold).isNull();
        assertThat(filter.passes(score("9.99"), null)).isTrue();
    }

    private static StabilityScore score(String value) {
        return StabilityScore.of(new BigDecimal(value), 2);
    }
}
