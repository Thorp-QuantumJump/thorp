package com.ssafy.thorp.paramsearch.infrastructure.registry;

import com.ssafy.thorp.strategy.application.port.SearchContext;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * 자원 파일에 적힌 유효성 규칙 한 건. 임시 등록표(003 대역)의 일부다.
 *
 * <p><b>규칙의 뜻은 등록표가 정하고 이 클래스는 비교만 수행한다.</b> 축 이름·사유 코드·임계값이 어디에도 상수로 나타나지 않으므로 전략이 늘어나도 이 코드는 바뀌지
 * 않는다(FR-025).
 *
 * @param code 위반 사유 코드. 후보에 그대로 저장된다(FR-006)
 * @param detail 사용자에게 보여줄 사유 문구
 * @param left 왼쪽 피연산자
 * @param operator 비교 연산자. 조건이 참이면 위반이다
 * @param right 오른쪽 피연산자
 */
record RegistryRule(String code, String detail, Operand left, Operator operator, Operand right) {

    RegistryRule {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("rule code must not be blank");
        }
        Objects.requireNonNull(left, "rule left operand must not be null");
        Objects.requireNonNull(right, "rule right operand must not be null");
        Objects.requireNonNull(operator, "rule operator must not be null");
    }

    /**
     * 이 조합이 규칙을 위반하는가.
     *
     * <p>피연산자가 가리키는 축이 조합에 없으면 판정하지 않는다(false) — 판정할 수 없는 것을 위반으로 처리하면 사유 없는 제외가 생긴다.
     */
    boolean isViolatedBy(Map<String, Object> parameters, SearchContext context) {
        Object leftValue = left.resolve(parameters, context);
        Object rightValue = right.resolve(parameters, context);
        if (leftValue == null || rightValue == null) {
            return false;
        }
        return operator.test(leftValue, rightValue);
    }

    /** 비교 대상 하나. 셋 중 정확히 하나가 채워진다. */
    record Operand(String axis, String contextKey, Object literal, String multiplyByContextKey) {

        Operand {
            long filled =
                    java.util.stream.Stream.of(axis, contextKey, literal)
                            .filter(Objects::nonNull)
                            .count();
            if (filled != 1) {
                throw new IllegalArgumentException(
                        "operand must set exactly one of axis, context, value");
            }
            if (multiplyByContextKey != null && axis == null) {
                throw new IllegalArgumentException("multiplyByContext is only valid with an axis");
            }
        }

        static Operand ofAxis(String axis, String multiplyByContextKey) {
            return new Operand(axis, null, null, multiplyByContextKey);
        }

        static Operand ofContext(String contextKey) {
            return new Operand(null, contextKey, null, null);
        }

        static Operand ofLiteral(Object literal) {
            return new Operand(null, null, literal, null);
        }

        Object resolve(Map<String, Object> parameters, SearchContext context) {
            if (contextKey != null) {
                return contextValue(contextKey, context);
            }
            if (literal != null) {
                return literal;
            }
            Object value = parameters.get(axis);
            if (value == null || multiplyByContextKey == null) {
                return value;
            }
            return toNumber(value, axis)
                    .multiply(toNumber(contextValue(multiplyByContextKey, context), axis));
        }

        private static Object contextValue(String key, SearchContext context) {
            if (ContextKey.UNIVERSE_SIZE.name().equals(key)) {
                return context.universeSize();
            }
            throw new IllegalArgumentException("unknown registry context key: " + key);
        }
    }

    /** 규칙이 참조할 수 있는 묶음 맥락. 늘어나면 {@link SearchContext}와 함께 넓힌다. */
    enum ContextKey {
        UNIVERSE_SIZE
    }

    enum Operator {
        GREATER_THAN,
        GREATER_OR_EQUAL,
        LESS_THAN,
        LESS_OR_EQUAL,
        EQUAL,
        NOT_EQUAL;

        static Operator from(String name) {
            for (Operator operator : values()) {
                if (operator.name().equals(name)) {
                    return operator;
                }
            }
            throw new IllegalArgumentException("unknown registry rule operator: " + name);
        }

        boolean test(Object left, Object right) {
            if (this == EQUAL) {
                return Objects.equals(left, right);
            }
            if (this == NOT_EQUAL) {
                return !Objects.equals(left, right);
            }
            int comparison = toNumber(left, "left").compareTo(toNumber(right, "right"));
            return switch (this) {
                case GREATER_THAN -> comparison > 0;
                case GREATER_OR_EQUAL -> comparison >= 0;
                case LESS_THAN -> comparison < 0;
                case LESS_OR_EQUAL -> comparison <= 0;
                default -> throw new IllegalStateException("unreachable operator: " + this);
            };
        }
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
