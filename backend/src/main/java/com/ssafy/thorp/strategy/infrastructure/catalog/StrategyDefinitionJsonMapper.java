package com.ssafy.thorp.strategy.infrastructure.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.thorp.strategy.domain.exception.InvalidStrategyDefinitionException;
import com.ssafy.thorp.strategy.domain.model.ChangeHistoryEntry;
import com.ssafy.thorp.strategy.domain.model.MinimumHistoryRequirement;
import com.ssafy.thorp.strategy.domain.model.ParameterConstraint;
import com.ssafy.thorp.strategy.domain.model.ParameterDefinition;
import com.ssafy.thorp.strategy.domain.model.StrategyDefinition;
import com.ssafy.thorp.strategy.domain.vo.ChangeType;
import com.ssafy.thorp.strategy.domain.vo.ConstraintOperator;
import com.ssafy.thorp.strategy.domain.vo.EnumOption;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.HistoryRule;
import com.ssafy.thorp.strategy.domain.vo.NumericRange;
import com.ssafy.thorp.strategy.domain.vo.ParameterType;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * JSON 바인딩 DTO를 도메인 정의로 옮긴다.
 *
 * <p>여기서 하는 일은 <b>표현의 번역</b>뿐이다 — 열거 이름을 enum으로, 기본값 리터럴을 {@link ParameterValue}로, 생략된 값을 기본값으로
 * 채운다. 값이 타당한지는 도메인 record의 불변식이 판정하며, 위반은 {@link IllegalArgumentException}으로 올라와 호출자가 그 정의만
 * 제외한다(FR-017).
 */
public final class StrategyDefinitionJsonMapper {

    private StrategyDefinitionJsonMapper() {}

    /**
     * @param source 오류 메시지에 쓸 출처(파일 경로 등)
     * @throws InvalidStrategyDefinitionException 형식이 어긋나 도메인 정의로 옮길 수 없을 때
     */
    public static StrategyDefinition toDomain(StrategyDefinitionJsonDto dto, String source) {
        try {
            return new StrategyDefinition(
                    dto.schemaVersion(),
                    dto.strategyId(),
                    requireInt(dto.definitionVersion(), "definitionVersion"),
                    dto.displayName(),
                    dto.description(),
                    dto.entryCondition(),
                    dto.exitCondition(),
                    toScopes(dto.executionScopes()),
                    mapList(dto.parameters(), StrategyDefinitionJsonMapper::toParameter),
                    mapList(dto.parameterConstraints(), StrategyDefinitionJsonMapper::toConstraint),
                    toHistoryRequirement(dto.minimumHistoryRequirement()),
                    mapList(dto.changeHistory(), StrategyDefinitionJsonMapper::toChange),
                    Boolean.TRUE.equals(dto.deprecated()));
        } catch (RuntimeException e) {
            throw new InvalidStrategyDefinitionException(source, e.getMessage(), e);
        }
    }

    private static ParameterDefinition toParameter(StrategyDefinitionJsonDto.ParameterDto dto) {
        ParameterType type = toEnum(ParameterType.class, dto.type(), "파라미터 타입");
        return new ParameterDefinition(
                dto.name(),
                dto.displayName(),
                dto.description(),
                type,
                Boolean.TRUE.equals(dto.required()),
                toDefaultValue(dto.defaultValue(), type, dto.name()),
                dto.unit(),
                toScopes(dto.executionScopes()),
                dto.numericRange() == null ? null : toNumericRange(dto.numericRange()),
                dto.enumValues() == null
                        ? null
                        : mapList(dto.enumValues(), StrategyDefinitionJsonMapper::toEnumOption));
    }

    /** 기본값 리터럴을 파라미터 타입에 맞는 값으로 옮긴다. 타입에 맞지 않는 리터럴은 여기서 걸린다. */
    private static ParameterValue toDefaultValue(JsonNode node, ParameterType type, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        return switch (type) {
            case INTEGER -> {
                if (!node.isIntegralNumber()) {
                    throw new IllegalArgumentException("INTEGER 기본값은 정수여야 한다: name=" + name);
                }
                yield ParameterValue.of(node.longValue());
            }
            case DECIMAL -> {
                if (!node.isNumber()) {
                    throw new IllegalArgumentException("DECIMAL 기본값은 숫자여야 한다: name=" + name);
                }
                yield ParameterValue.of(node.decimalValue());
            }
            case BOOLEAN -> {
                if (!node.isBoolean()) {
                    throw new IllegalArgumentException(
                            "BOOLEAN 기본값은 true/false여야 한다: name=" + name);
                }
                yield ParameterValue.of(node.booleanValue());
            }
            case ENUM -> {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException("ENUM 기본값은 문자열이어야 한다: name=" + name);
                }
                yield ParameterValue.ofEnum(node.textValue());
            }
        };
    }

    private static NumericRange toNumericRange(StrategyDefinitionJsonDto.NumericRangeDto dto) {
        // 경계 포함 여부의 기본값은 true 다. 생략된 경계를 배타로 해석하면 정의 작성자의 의도와 어긋난다.
        return new NumericRange(
                dto.min(),
                !Boolean.FALSE.equals(dto.minInclusive()),
                dto.max(),
                !Boolean.FALSE.equals(dto.maxInclusive()),
                dto.step());
    }

    private static EnumOption toEnumOption(StrategyDefinitionJsonDto.EnumOptionDto dto) {
        return new EnumOption(dto.value(), dto.displayName());
    }

    private static ParameterConstraint toConstraint(StrategyDefinitionJsonDto.ConstraintDto dto) {
        return new ParameterConstraint(
                dto.left(),
                toEnum(ConstraintOperator.class, dto.operator(), "제약 연산자"),
                dto.right(),
                dto.message());
    }

    private static MinimumHistoryRequirement toHistoryRequirement(
            StrategyDefinitionJsonDto.HistoryRequirementDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("최소 이력 규칙은 생략할 수 없다");
        }
        return new MinimumHistoryRequirement(
                toEnum(HistoryRule.class, dto.rule(), "최소 이력 규칙"),
                dto.value(),
                dto.parameters(),
                dto.offset() == null ? 0 : dto.offset(),
                dto.unit());
    }

    private static ChangeHistoryEntry toChange(StrategyDefinitionJsonDto.ChangeDto dto) {
        return new ChangeHistoryEntry(
                requireInt(dto.definitionVersion(), "changeHistory[].definitionVersion"),
                toEnum(ChangeType.class, dto.changeType(), "호환성 등급"),
                dto.changeSummary(),
                toDate(dto.changedAt()));
    }

    private static LocalDate toDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("변경 날짜는 yyyy-MM-dd 형식이어야 한다: " + value);
        }
    }

    private static Set<ExecutionScope> toScopes(List<String> names) {
        if (names == null || names.isEmpty()) {
            throw new IllegalArgumentException("실행 목적은 하나 이상이어야 한다");
        }
        EnumSet<ExecutionScope> scopes = EnumSet.noneOf(ExecutionScope.class);
        names.forEach(name -> scopes.add(toEnum(ExecutionScope.class, name, "실행 목적")));
        if (scopes.size() != names.size()) {
            throw new IllegalArgumentException("실행 목적이 중복됐다: " + names);
        }
        return scopes;
    }

    private static <E extends Enum<E>> E toEnum(Class<E> type, String value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("%s은(는) 생략할 수 없다".formatted(label));
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "%s이(가) 정의된 값이 아니다: %s (허용: %s)"
                            .formatted(label, value, List.of(type.getEnumConstants())));
        }
    }

    private static int requireInt(Integer value, String label) {
        if (value == null) {
            throw new IllegalArgumentException("%s은(는) 생략할 수 없다".formatted(label));
        }
        return value;
    }

    private static <S, T> List<T> mapList(
            List<S> source, java.util.function.Function<S, T> mapper) {
        return source == null ? List.of() : source.stream().map(mapper).toList();
    }
}
