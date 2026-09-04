package com.ssafy.thorp.strategy.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.strategy.application.result.StrategyDefinitionResult;
import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /strategies/{strategyId}} 응답 본문. FE 동적 폼이 읽는 계약이다.
 *
 * <p>필드 구성은 {@code docs/specs/strategy-definition/strategy-definition.schema.json}과 1:1이며, 그 스키마로
 * 검증했을 때 통과해야 한다.
 *
 * <p>{@link JsonInclude.Include#NON_NULL}은 취향이 아니라 계약이다. 스키마는 타입에 해당하지 않는 항목이 <b>있는 것 자체</b>를 금지한다
 * — BOOLEAN 파라미터에 {@code "numericRange": null}이 실리면 스키마 위반이고, 필수 파라미터에 {@code "defaultValue":
 * null}이 실리면 "기본값이 null" 로 읽힌다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StrategyDefinitionResponse(
        String schemaVersion,
        String strategyId,
        int definitionVersion,
        String displayName,
        String description,
        String entryCondition,
        String exitCondition,
        List<String> executionScopes,
        List<ParameterResponse> parameters,
        List<ConstraintResponse> parameterConstraints,
        HistoryRequirementResponse minimumHistoryRequirement,
        List<ChangeResponse> changeHistory,
        boolean deprecated) {

    public static StrategyDefinitionResponse from(StrategyDefinitionResult result) {
        return new StrategyDefinitionResponse(
                result.schemaVersion(),
                result.strategyId(),
                result.definitionVersion(),
                result.displayName(),
                result.description(),
                result.entryCondition(),
                result.exitCondition(),
                result.executionScopes(),
                result.parameters().stream().map(ParameterResponse::from).toList(),
                result.parameterConstraints().stream().map(ConstraintResponse::from).toList(),
                HistoryRequirementResponse.from(result.minimumHistoryRequirement()),
                result.changeHistory().stream().map(ChangeResponse::from).toList(),
                result.deprecated());
    }

    /**
     * 파라미터 하나의 정의. 폼은 {@code type} 하나만 보고 입력 컴포넌트를 고른다 — INTEGER·DECIMAL이면 {@code numericRange}가
     * 있고, ENUM이면 {@code enumValues}가 있으며, BOOLEAN이면 둘 다 없다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParameterResponse(
            String name,
            String displayName,
            String description,
            String type,
            boolean required,
            Object defaultValue,
            String unit,
            List<String> executionScopes,
            NumericRangeResponse numericRange,
            List<EnumOptionResponse> enumValues) {

        public static ParameterResponse from(StrategyDefinitionResult.ParameterResult result) {
            return new ParameterResponse(
                    result.name(),
                    result.displayName(),
                    result.description(),
                    result.type(),
                    result.required(),
                    result.defaultValue(),
                    result.unit(),
                    result.executionScopes(),
                    result.numericRange() == null
                            ? null
                            : NumericRangeResponse.from(result.numericRange()),
                    result.enumValues() == null
                            ? null
                            : result.enumValues().stream().map(EnumOptionResponse::from).toList());
        }
    }

    /** 없는 경계는 필드가 빠진다. 상한이 없다는 사실이 폼에도 드러나야 한다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NumericRangeResponse(
            BigDecimal min,
            boolean minInclusive,
            BigDecimal max,
            boolean maxInclusive,
            BigDecimal step) {

        public static NumericRangeResponse from(
                StrategyDefinitionResult.NumericRangeResult result) {
            return new NumericRangeResponse(
                    result.min(),
                    result.minInclusive(),
                    result.max(),
                    result.maxInclusive(),
                    result.step());
        }
    }

    public record EnumOptionResponse(String value, String displayName) {

        public static EnumOptionResponse from(StrategyDefinitionResult.EnumOptionResult result) {
            return new EnumOptionResponse(result.value(), result.displayName());
        }
    }

    public record ConstraintResponse(String left, String operator, String right, String message) {

        public static ConstraintResponse from(StrategyDefinitionResult.ConstraintResult result) {
            return new ConstraintResponse(
                    result.left(), result.operator(), result.right(), result.message());
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record HistoryRequirementResponse(
            String rule, Integer value, List<String> parameters, int offset, String unit) {

        public static HistoryRequirementResponse from(
                StrategyDefinitionResult.HistoryRequirementResult result) {
            return new HistoryRequirementResponse(
                    result.rule(),
                    result.value(),
                    result.parameters(),
                    result.offset(),
                    result.unit());
        }
    }

    public record ChangeResponse(
            int definitionVersion, String changeType, String changeSummary, String changedAt) {

        public static ChangeResponse from(StrategyDefinitionResult.ChangeResult result) {
            return new ChangeResponse(
                    result.definitionVersion(),
                    result.changeType(),
                    result.changeSummary(),
                    result.changedAt());
        }
    }
}
