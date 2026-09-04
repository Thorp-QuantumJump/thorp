package com.ssafy.thorp.strategy.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.strategy.domain.StrategyFixtures;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import com.ssafy.thorp.strategy.domain.vo.ResolvedParameters;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 네 규칙이 확정 값만으로 같은 답을 내는지 확인한다(FR-010a·FR-010b). */
class HistoryRequirementCalculatorTest {

    @Test
    @DisplayName("FIXED는 파라미터와 무관하게 고정값을 낸다")
    void fixedRule() {
        StrategyDefinition definition = StrategyFixtures.withoutParameters();

        assertThat(
                        HistoryRequirementCalculator.requiredBars(
                                definition, ResolvedParameters.empty()))
                .isEqualTo(250);
    }

    @Test
    @DisplayName("PARAMETER는 지정한 파라미터 값에 offset을 더한다")
    void parameterRule() {
        StrategyDefinition definition = StrategyFixtures.rsiReversion();

        assertThat(
                        HistoryRequirementCalculator.requiredBars(
                                definition,
                                resolved(
                                        "rsiPeriod",
                                        14L,
                                        "oversoldThreshold",
                                        new java.math.BigDecimal("30"),
                                        "overboughtThreshold",
                                        new java.math.BigDecimal("70"),
                                        "maxHoldingBars",
                                        20L)))
                .isEqualTo(15);
    }

    @Test
    @DisplayName("MAX_PARAMETERS는 대상 중 최댓값을 쓴다")
    void maxRule() {
        StrategyDefinition definition = StrategyFixtures.smaCrossover();

        assertThat(
                        HistoryRequirementCalculator.requiredBars(
                                definition,
                                resolved(
                                        "shortPeriod",
                                        20L,
                                        "longPeriod",
                                        200L,
                                        "allowShort",
                                        Boolean.FALSE,
                                        "rebalancePeriod",
                                        "DAILY")))
                .isEqualTo(201);
    }

    @Test
    @DisplayName("SUM_PARAMETERS는 대상의 합을 쓴다")
    void sumRule() {
        StrategyDefinition definition = StrategyFixtures.macdSignal();

        assertThat(
                        HistoryRequirementCalculator.requiredBars(
                                definition,
                                resolved("fastPeriod", 12L, "slowPeriod", 26L, "signalPeriod", 9L)))
                .isEqualTo(36);
    }

    @Test
    @DisplayName("대상 파라미터가 확정 값에 없으면 정의가 잘못된 것이다")
    void missingTargetIsDefinitionError() {
        StrategyDefinition definition = StrategyFixtures.smaCrossover();

        assertThatThrownBy(
                        () ->
                                HistoryRequirementCalculator.requiredBars(
                                        definition, resolved("shortPeriod", 20L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("확정 값에 없다");
    }

    private static ResolvedParameters resolved(Object... namesAndValues) {
        Map<String, ParameterValue> values = new LinkedHashMap<>();
        for (int index = 0; index < namesAndValues.length; index += 2) {
            values.put((String) namesAndValues[index], toValue(namesAndValues[index + 1]));
        }
        return new ResolvedParameters(values);
    }

    private static ParameterValue toValue(Object raw) {
        if (raw instanceof Long number) {
            return ParameterValue.of(number.longValue());
        }
        if (raw instanceof java.math.BigDecimal decimal) {
            return ParameterValue.of(decimal);
        }
        if (raw instanceof Boolean flag) {
            return ParameterValue.of(flag.booleanValue());
        }
        return ParameterValue.ofEnum((String) raw);
    }
}
