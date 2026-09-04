package com.ssafy.thorp.strategy.application.result;

import com.ssafy.thorp.strategy.domain.model.ChangeHistoryEntry;
import com.ssafy.thorp.strategy.domain.model.MinimumHistoryRequirement;
import com.ssafy.thorp.strategy.domain.model.ParameterConstraint;
import com.ssafy.thorp.strategy.domain.model.ParameterDefinition;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.vo.EnumOption;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.NumericRange;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * 전략 정의 조회 유스케이스의 출력.
 *
 * <p>도메인 모델을 그대로 내보내지 않고 직렬화 가능한 형태로 한 번 옮긴다. 옮기면서 하는 일은 세 가지다 — {@link ParameterValue}를 JSON 스칼라로,
 * {@code Set<ExecutionScope>}를 순서가 정해진 목록으로, {@link java.time.LocalDate}를 {@code yyyy-MM-dd} 문자열로
 * 바꾼다. 순서를 고정하는 이유는 응답이 요청마다 달라지면 캐시와 테스트가 흔들리기 때문이다.
 *
 * <p>필드 구성은 {@code docs/specs/strategy-definition/strategy-definition.schema.json}과 1:1이다. 타입에 해당하지
 * 않는 항목({@code numericRange}/{@code enumValues}/{@code defaultValue})은 {@code null}이며, 응답 직렬화에서 아예
 * 빠진다 — 스키마가 그 자리에 필드가 있는 것 자체를 금지한다.
 */
public record StrategyDefinitionResult(
        String schemaVersion,
        String strategyId,
        int definitionVersion,
        String displayName,
        String description,
        String entryCondition,
        String exitCondition,
        List<String> executionScopes,
        List<ParameterResult> parameters,
        List<ConstraintResult> parameterConstraints,
        HistoryRequirementResult minimumHistoryRequirement,
        List<ChangeResult> changeHistory,
        boolean deprecated) {

    public static StrategyDefinitionResult from(StrategyDefinition definition) {
        return new StrategyDefinitionResult(
                definition.schemaVersion(),
                definition.strategyId(),
                definition.definitionVersion(),
                definition.displayName(),
                definition.description(),
                definition.entryCondition(),
                definition.exitCondition(),
                scopeNames(definition.executionScopes()),
                definition.parameters().stream().map(ParameterResult::from).toList(),
                definition.parameterConstraints().stream().map(ConstraintResult::from).toList(),
                HistoryRequirementResult.from(definition.minimumHistoryRequirement()),
                definition.changeHistory().stream().map(ChangeResult::from).toList(),
                definition.deprecated());
    }

    static List<String> scopeNames(java.util.Set<ExecutionScope> scopes) {
        return scopes.stream().sorted(Comparator.naturalOrder()).map(Enum::name).toList();
    }

    /** 파라미터 하나의 정의. 타입에 맞지 않는 범위 표현은 {@code null}이다. */
    public record ParameterResult(
            String name,
            String displayName,
            String description,
            String type,
            boolean required,
            Object defaultValue,
            String unit,
            List<String> executionScopes,
            NumericRangeResult numericRange,
            List<EnumOptionResult> enumValues) {

        public static ParameterResult from(ParameterDefinition parameter) {
            return new ParameterResult(
                    parameter.name(),
                    parameter.displayName(),
                    parameter.description(),
                    parameter.type().name(),
                    parameter.required(),
                    parameter.defaultValueIfPresent().map(ParameterValue::raw).orElse(null),
                    parameter.unit(),
                    scopeNames(parameter.executionScopes()),
                    parameter.range().map(NumericRangeResult::from).orElse(null),
                    parameter.type().usesEnumValues()
                            ? parameter.options().stream().map(EnumOptionResult::from).toList()
                            : null);
        }
    }

    /** 한쪽 경계만 지정한 열린 범위도 표현한다. 없는 경계는 {@code null}이다. */
    public record NumericRangeResult(
            BigDecimal min,
            boolean minInclusive,
            BigDecimal max,
            boolean maxInclusive,
            BigDecimal step) {

        public static NumericRangeResult from(NumericRange range) {
            return new NumericRangeResult(
                    range.min(),
                    range.minInclusive(),
                    range.max(),
                    range.maxInclusive(),
                    range.step());
        }
    }

    public record EnumOptionResult(String value, String displayName) {

        public static EnumOptionResult from(EnumOption option) {
            return new EnumOptionResult(option.value(), option.displayName());
        }
    }

    public record ConstraintResult(String left, String operator, String right, String message) {

        public static ConstraintResult from(ParameterConstraint constraint) {
            return new ConstraintResult(
                    constraint.left(),
                    constraint.operator().name(),
                    constraint.right(),
                    constraint.message());
        }
    }

    public record HistoryRequirementResult(
            String rule, Integer value, List<String> parameters, int offset, String unit) {

        public static HistoryRequirementResult from(MinimumHistoryRequirement requirement) {
            return new HistoryRequirementResult(
                    requirement.rule().name(),
                    requirement.value(),
                    requirement.parameters(),
                    requirement.offset(),
                    requirement.unit());
        }
    }

    public record ChangeResult(
            int definitionVersion, String changeType, String changeSummary, String changedAt) {

        public static ChangeResult from(ChangeHistoryEntry entry) {
            return new ChangeResult(
                    entry.definitionVersion(),
                    entry.changeType().name(),
                    entry.changeSummary(),
                    entry.changedAt().toString());
        }
    }
}
