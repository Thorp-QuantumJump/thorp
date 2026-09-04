package com.ssafy.thorp.strategy.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.domain.vo.HistoryRule;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 네 규칙이 하나의 선언 형식 안에서 서로 배타적으로 표현되는지 확인한다. */
class MinimumHistoryRequirementTest {

    @Test
    @DisplayName("네 규칙이 모두 같은 선언 형식으로 표현된다")
    void allFourRulesShareOneShape() {
        List<MinimumHistoryRequirement> requirements =
                List.of(
                        MinimumHistoryRequirement.fixed(250, 0),
                        MinimumHistoryRequirement.ofParameter("period", 1),
                        MinimumHistoryRequirement.maxOf(List.of("shortPeriod", "longPeriod"), 1),
                        MinimumHistoryRequirement.sumOf(List.of("slowPeriod", "signalPeriod"), 1));

        assertThat(requirements)
                .extracting(MinimumHistoryRequirement::rule)
                .containsExactly(
                        HistoryRule.FIXED,
                        HistoryRule.PARAMETER,
                        HistoryRule.MAX_PARAMETERS,
                        HistoryRule.SUM_PARAMETERS);
        assertThat(requirements)
                .allSatisfy(it -> assertThat(it.unit()).isEqualTo(MinimumHistoryRequirement.BARS));
    }

    @Test
    @DisplayName("FIXED는 고정값만, 나머지는 대상 파라미터만 갖는다")
    void fixedAndParameterFormsAreExclusive() {
        assertThat(MinimumHistoryRequirement.fixed(250, 0).fixedValue()).contains(250);
        assertThat(MinimumHistoryRequirement.fixed(250, 0).targetParameters()).isEmpty();
        assertThat(MinimumHistoryRequirement.ofParameter("period", 1).fixedValue()).isEmpty();
        assertThat(MinimumHistoryRequirement.ofParameter("period", 1).targetParameters())
                .containsExactly("period");
    }

    @Test
    @DisplayName("PARAMETER 규칙의 대상은 하나뿐이다")
    void parameterRuleTakesExactlyOneTarget() {
        assertThatThrownBy(
                        () ->
                                new MinimumHistoryRequirement(
                                        HistoryRule.PARAMETER,
                                        null,
                                        List.of("shortPeriod", "longPeriod"),
                                        0,
                                        MinimumHistoryRequirement.BARS))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1개까지");
    }

    @Test
    @DisplayName("대상 파라미터가 중복되면 거부한다")
    void rejectsDuplicateTargets() {
        assertThatThrownBy(
                        () ->
                                MinimumHistoryRequirement.sumOf(
                                        List.of("slowPeriod", "slowPeriod"), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }

    @Test
    @DisplayName("단위는 bars 외의 값을 가질 수 없다")
    void unitIsFixedToBars() {
        assertThatThrownBy(
                        () -> new MinimumHistoryRequirement(HistoryRule.FIXED, 10, null, 0, "days"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bars");
    }
}
