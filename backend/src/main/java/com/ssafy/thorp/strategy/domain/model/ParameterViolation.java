package com.ssafy.thorp.strategy.domain.model;

import com.ssafy.thorp.strategy.domain.vo.EnumOption;
import com.ssafy.thorp.strategy.domain.vo.ExecutionScope;
import com.ssafy.thorp.strategy.domain.vo.NumericRange;
import com.ssafy.thorp.strategy.domain.vo.ParameterValue;
import com.ssafy.thorp.strategy.domain.vo.ViolationCode;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 파라미터 값 판정에서 발견한 위반 하나.
 *
 * <p>{@code code}는 기계가 분기할 닫힌 어휘, {@code message}는 사람이 읽을 설명, {@code detail}은 판정 근거다. 셋을 나눠 두는 이유는
 * 소비 지점마다 필요한 것이 다르기 때문이다 — FE 폼은 {@code parameterName}으로 입력 칸을 찾고, 사용자는 {@code message}를 읽고, 회귀
 * 테스트는 {@code code}만 비교한다.
 *
 * @param code 위반의 종류
 * @param parameterName 위반한 파라미터. {@link ViolationCode#CONSTRAINT_VIOLATED}는 제약의 좌변을 담고, 전략 단위 위반은
 *     {@code null}
 * @param message 사용자에게 보일 한국어 설명
 * @param detail 판정 근거. 없으면 {@code null}
 */
public record ParameterViolation(
        ViolationCode code, String parameterName, String message, Map<String, Object> detail) {

    public ParameterViolation {
        Objects.requireNonNull(code, "위반 코드는 null일 수 없다");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("위반 설명은 비어 있을 수 없다: code=" + code);
        }
        detail = detail == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public static ParameterViolation unknownStrategy(String strategyId) {
        return new ParameterViolation(
                ViolationCode.UNKNOWN_STRATEGY,
                null,
                "존재하지 않는 전략입니다: " + strategyId,
                detail("strategyId", strategyId));
    }

    public static ParameterViolation executionScopeNotSupported(
            StrategyDefinition definition, ExecutionScope requested) {
        return new ParameterViolation(
                ViolationCode.EXECUTION_SCOPE_NOT_SUPPORTED,
                null,
                "%s 전략은 %s 실행을 지원하지 않습니다.".formatted(definition.displayName(), requested),
                detail(
                        "requested",
                        requested.name(),
                        "supported",
                        definition.executionScopes().stream().map(Enum::name).sorted().toList()));
    }

    public static ParameterViolation unknownParameter(String parameterName, ExecutionScope scope) {
        return new ParameterViolation(
                ViolationCode.UNKNOWN_PARAMETER,
                parameterName,
                "이 전략의 %s 실행에 없는 파라미터입니다: %s".formatted(scope, parameterName),
                detail("executionScope", scope.name()));
    }

    public static ParameterViolation requiredMissing(ParameterDefinition parameter) {
        return new ParameterViolation(
                ViolationCode.REQUIRED_PARAMETER_MISSING,
                parameter.name(),
                "%s은(는) 반드시 지정해야 합니다.".formatted(parameter.displayName()),
                null);
    }

    public static ParameterViolation typeMismatch(ParameterDefinition parameter, Object actual) {
        return new ParameterViolation(
                ViolationCode.TYPE_MISMATCH,
                parameter.name(),
                "%s의 값이 %s 타입이 아닙니다.".formatted(parameter.displayName(), parameter.type()),
                detail("expectedType", parameter.type().name(), "actual", describe(actual)));
    }

    public static ParameterViolation outOfRange(ParameterDefinition parameter, BigDecimal actual) {
        NumericRange range = parameter.range().orElseThrow();
        Map<String, Object> detail = new LinkedHashMap<>();
        if (range.hasMin()) {
            detail.put("min", range.min());
            detail.put("minInclusive", range.minInclusive());
        }
        if (range.hasMax()) {
            detail.put("max", range.max());
            detail.put("maxInclusive", range.maxInclusive());
        }
        detail.put("actual", actual);
        return new ParameterViolation(
                ViolationCode.OUT_OF_RANGE,
                parameter.name(),
                "%s은(는) %s 범위여야 합니다.".formatted(parameter.displayName(), describeRange(range)),
                detail);
    }

    public static ParameterViolation stepMismatch(
            ParameterDefinition parameter, BigDecimal actual) {
        NumericRange range = parameter.range().orElseThrow();
        return new ParameterViolation(
                ViolationCode.STEP_MISMATCH,
                parameter.name(),
                "%s은(는) %s부터 %s 간격의 값이어야 합니다."
                        .formatted(
                                parameter.displayName(),
                                range.min().toPlainString(),
                                range.step().toPlainString()),
                detail("min", range.min(), "step", range.step(), "actual", actual));
    }

    public static ParameterViolation notInEnum(ParameterDefinition parameter, String actual) {
        List<String> allowed = parameter.options().stream().map(EnumOption::value).toList();
        return new ParameterViolation(
                ViolationCode.NOT_IN_ENUM,
                parameter.name(),
                "%s은(는) %s 중 하나여야 합니다."
                        .formatted(parameter.displayName(), String.join(", ", allowed)),
                detail("allowedValues", allowed, "actual", actual));
    }

    public static ParameterViolation constraintViolated(
            ParameterConstraint constraint, ParameterValue left, ParameterValue right) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("left", constraint.left());
        detail.put("operator", constraint.operator().name());
        detail.put("right", constraint.right());
        detail.put("leftValue", left.raw());
        detail.put("rightValue", right.raw());
        // 메시지는 정의 작성자가 쓴 문구를 그대로 쓴다. 관계가 왜 필요한지는 전략을 아는 사람이 가장 잘 안다.
        return new ParameterViolation(
                ViolationCode.CONSTRAINT_VIOLATED, constraint.left(), constraint.message(), detail);
    }

    private static Map<String, Object> detail(String key, Object value) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(key, value);
        return detail;
    }

    private static Map<String, Object> detail(
            String firstKey, Object firstValue, String secondKey, Object secondValue) {
        Map<String, Object> detail = detail(firstKey, firstValue);
        detail.put(secondKey, secondValue);
        return detail;
    }

    private static Map<String, Object> detail(
            String firstKey,
            Object firstValue,
            String secondKey,
            Object secondValue,
            String thirdKey,
            Object thirdValue) {
        Map<String, Object> detail = detail(firstKey, firstValue, secondKey, secondValue);
        detail.put(thirdKey, thirdValue);
        return detail;
    }

    /** 값 자체가 아니라 "무엇이 왔는지"만 남긴다. 임의의 입력을 그대로 되돌려 보내지 않는다. */
    private static String describe(Object actual) {
        if (actual == null) {
            return "null";
        }
        if (actual instanceof Number || actual instanceof Boolean) {
            return actual.toString();
        }
        if (actual instanceof String text) {
            return "\"" + text + "\"";
        }
        return actual.getClass().getSimpleName();
    }

    private static String describeRange(NumericRange range) {
        String lower =
                range.hasMin()
                        ? range.min().toPlainString() + (range.minInclusive() ? " 이상" : " 초과")
                        : "하한 없음";
        String upper =
                range.hasMax()
                        ? range.max().toPlainString() + (range.maxInclusive() ? " 이하" : " 미만")
                        : "상한 없음";
        return lower + " " + upper;
    }
}
