package com.ssafy.thorp.strategy.domain.policy;

import com.ssafy.thorp.strategy.domain.model.MinimumHistoryRequirement;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import com.ssafy.thorp.strategy.domain.vo.ResolvedParameters;
import java.util.List;

/**
 * 전략 정의와 확정 파라미터 값으로부터 최소 이력 요구량(bars)을 산출한다(FR-010a).
 *
 * <p>전략 구현을 실행하지 않고 정의만으로 계산한다. 그래야 BE·FE·Worker가 같은 값을 낸다(FR-010b). 자유 수식을 허용하지 않고 네 규칙으로 닫아 둔 이유도
 * 같다 — 수식을 허용하면 파트마다 파서가 필요하고, 파서가 다르면 값이 갈린다.
 */
public final class HistoryRequirementCalculator {

    private HistoryRequirementCalculator() {}

    /**
     * @param definition 전략 정의
     * @param resolved 기본값이 모두 채워진 확정 값 집합
     * @return 0 이상의 bars. 규칙이 참조하는 파라미터가 확정 값에 없거나 INTEGER가 아니면 정의가 잘못된 것이므로 {@link
     *     IllegalStateException}
     */
    public static int requiredBars(StrategyDefinition definition, ResolvedParameters resolved) {
        MinimumHistoryRequirement requirement = definition.minimumHistoryRequirement();
        List<String> targets = requirement.targetParameters();

        long base =
                switch (requirement.rule()) {
                    case FIXED -> requirement.fixedValue().orElseThrow();
                    case PARAMETER -> barsOf(definition, resolved, targets.get(0));
                    case MAX_PARAMETERS ->
                            targets.stream()
                                    .mapToLong(name -> barsOf(definition, resolved, name))
                                    .max()
                                    .orElseThrow();
                    case SUM_PARAMETERS ->
                            targets.stream()
                                    .mapToLong(name -> barsOf(definition, resolved, name))
                                    .sum();
                };

        return Math.toIntExact(base + requirement.offset());
    }

    private static long barsOf(
            StrategyDefinition definition, ResolvedParameters resolved, String parameterName) {
        ParameterValue value =
                resolved.get(parameterName)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "최소 이력 규칙의 대상이 확정 값에 없다: strategyId=%s, name=%s"
                                                        .formatted(
                                                                definition.strategyId(),
                                                                parameterName)));
        if (value instanceof ParameterValue.IntegerValue integer) {
            return integer.value();
        }
        throw new IllegalStateException(
                "최소 이력 규칙의 대상은 INTEGER여야 한다: strategyId=%s, name=%s, type=%s"
                        .formatted(definition.strategyId(), parameterName, value.type()));
    }
}
