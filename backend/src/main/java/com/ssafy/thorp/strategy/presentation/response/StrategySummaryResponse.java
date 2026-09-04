package com.ssafy.thorp.strategy.presentation.response;

import com.ssafy.thorp.strategy.application.result.StrategySummaryResult;
import java.util.List;

/** {@code GET /strategies} 목록 항목. */
public record StrategySummaryResponse(
        String strategyId,
        int definitionVersion,
        String displayName,
        String description,
        List<String> executionScopes,
        int parameterCount,
        boolean deprecated) {

    public static StrategySummaryResponse from(StrategySummaryResult result) {
        return new StrategySummaryResponse(
                result.strategyId(),
                result.definitionVersion(),
                result.displayName(),
                result.description(),
                result.executionScopes(),
                result.parameterCount(),
                result.deprecated());
    }
}
