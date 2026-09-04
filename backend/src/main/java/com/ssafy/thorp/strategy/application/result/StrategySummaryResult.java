package com.ssafy.thorp.strategy.application.result;

import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import java.util.List;

/**
 * 전략 목록 항목.
 *
 * <p>목록에는 파라미터 정의 전체를 싣지 않는다. 선택 화면이 필요한 것은 "무슨 전략이고 조정할 값이 몇 개인가"까지이고, 전체 정의는 하나를 고른 뒤 단건 조회로
 * 가져간다.
 */
public record StrategySummaryResult(
        String strategyId,
        int definitionVersion,
        String displayName,
        String description,
        List<String> executionScopes,
        int parameterCount,
        boolean deprecated) {

    public static StrategySummaryResult from(StrategyDefinition definition) {
        return new StrategySummaryResult(
                definition.strategyId(),
                definition.definitionVersion(),
                definition.displayName(),
                definition.description(),
                StrategyDefinitionResult.scopeNames(definition.executionScopes()),
                definition.parameters().size(),
                definition.deprecated());
    }
}
