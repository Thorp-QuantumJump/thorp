package com.ssafy.thorp.strategy.infrastructure.registry;

import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.ValidationRule;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 자원 파일에 적힌 유효성 규칙 한 건의 <b>평가</b>를 맡는다.
 *
 * <p>규칙의 내용은 {@link ValidationRule}이 담고 이 클래스는 비교만 수행한다. 두 역할을 나눈 이유는 003 조회 API가 규칙을 응답에 실어야 하기
 * 때문이다 — 규칙이 이 인프라 클래스 안에 갇혀 있으면 밖으로 나오는 문이 없다(내부 계약 §3).
 *
 * <p><b>규칙의 뜻은 등록표가 정한다.</b> 축 이름·사유 코드·임계값이 어디에도 상수로 나타나지 않으므로 전략이 늘어나도 이 코드는 바뀌지 않는다(007 FR-025).
 *
 * @param rule 등록표가 적은 조건. 조건이 참이면 위반이다
 */
record RegistryRule(ValidationRule rule) {

    RegistryRule {
        Objects.requireNonNull(rule, "rule must not be null");
    }

    String code() {
        return rule.code();
    }

    String detail() {
        return rule.detail();
    }

    /**
     * 이 조합이 규칙을 위반하는가.
     *
     * <p>피연산자가 가리키는 축이 조합에 없으면 판정하지 않는다(false) — 판정할 수 없는 것을 위반으로 처리하면 사유 없는 제외가 생긴다.
     */
    boolean isViolatedBy(Map<String, Object> parameters, SearchContext context) {
        Object leftValue = resolve(rule.left(), parameters, context);
        Object rightValue = resolve(rule.right(), parameters, context);
        if (leftValue == null || rightValue == null) {
            return false;
        }
        return test(rule.operator(), leftValue, rightValue);
    }

    private static Object resolve(
            ValidationRule.Operand operand, Map<String, Object> parameters, SearchContext context) {
        if (operand.context() != null) {
            return contextValue(operand.context(), context);
        }
        if (operand.value() != null) {
            return operand.value();
        }
        Object value = parameters.get(operand.axis());
        if (value == null || operand.multiplyByContext() == null) {
            return value;
        }
        return toNumber(value, operand.axis())
                .multiply(
                        toNumber(
                                contextValue(operand.multiplyByContext(), context),
                                operand.axis()));
    }

    private static Object contextValue(String key, SearchContext context) {
        if (ContextKey.UNIVERSE_SIZE.name().equals(key)) {
            return context.universeSize();
        }
        throw new IllegalArgumentException("unknown registry context key: " + key);
    }

    private static boolean test(ValidationRule.Operator operator, Object left, Object right) {
        if (operator == ValidationRule.Operator.EQUAL) {
            return Objects.equals(left, right);
        }
        if (operator == ValidationRule.Operator.NOT_EQUAL) {
            return !Objects.equals(left, right);
        }
        int comparison = toNumber(left, "left").compareTo(toNumber(right, "right"));
        return switch (operator) {
            case GREATER_THAN -> comparison > 0;
            case GREATER_OR_EQUAL -> comparison >= 0;
            case LESS_THAN -> comparison < 0;
            case LESS_OR_EQUAL -> comparison <= 0;
            default -> throw new IllegalStateException("unreachable operator: " + operator);
        };
    }

    /** 규칙이 참조할 수 있는 묶음 맥락. 늘어나면 {@link SearchContext}와 함께 넓힌다. */
    enum ContextKey {
        UNIVERSE_SIZE
    }

    /**
     * 크기 비교를 위한 수치 변환. 문자열로 적힌 소수도 받는다 — 비율은 메시지 계약에서 문자열이다.
     *
     * <p>{@code double}을 거치지 않는 이유: 등록표 값이 그대로 재현 기록에 남는데, 이진 부동소수로 한 번 바꾸면 같은 값이 비교마다 다르게 보일 수 있다.
     */
    private static BigDecimal toNumber(Object value, String where) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "registry rule needs a number but got: " + value + " (" + where + ")", e);
        }
    }
}
