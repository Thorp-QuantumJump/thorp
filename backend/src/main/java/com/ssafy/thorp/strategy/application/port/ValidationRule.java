package com.ssafy.thorp.strategy.application.port;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 등록표가 소유한 유효성 규칙 한 건(data-model §4-1).
 *
 * <p><b>조건이 참이면 위반이다.</b> 규칙의 뜻은 등록표가 정하고 이 record는 조건을 담기만 한다 — 축 이름·사유 코드·임계값이 코드에 상수로 나타나지 않으므로
 * 전략이 늘어나도 이 타입은 바뀌지 않는다(007 FR-025).
 *
 * <p><b>왜 규칙을 밖으로 내보내나</b>: 기존 {@link ParameterRegistryPort#validate}는 조합 하나의 판정 결과만 돌려준다. 007에는
 * 그것으로 충분하지만, 003 화면은 사용자가 입력형 축에 숫자를 치는 동안 그 자리에서 판정해야 해서 조건 자체가 필요하다(FR-008, 내부 계약 §3). 화면은 축
 * 이름이나 코드로 분기하지 않고 아래 네 필드만 기계적으로 평가한다.
 *
 * @param code 위반 사유 코드. 후보에 그대로 저장되고 화면에 표시된다
 * @param detail 사용자에게 보여줄 한 문장
 * @param left 왼쪽 피연산자
 * @param operator 비교 연산자
 * @param right 오른쪽 피연산자
 */
public record ValidationRule(
        String code, String detail, Operand left, Operator operator, Operand right) {

    public ValidationRule {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("rule code must not be blank");
        }
        Objects.requireNonNull(left, "rule left operand must not be null");
        Objects.requireNonNull(right, "rule right operand must not be null");
        Objects.requireNonNull(operator, "rule operator must not be null");
    }

    /**
     * 비교 대상 하나. {@code axis}·{@code context}·{@code value} 중 정확히 하나를 채운다.
     *
     * @param axis 축 이름. 그 설정의 축 값을 읽는다
     * @param context 맥락 키. 현재 {@code UNIVERSE_SIZE} 하나다
     * @param value 상수
     * @param multiplyByContext 축 값에 곱할 맥락 키. {@code axis}와 함께만 쓴다
     * @param multiplyByAxis 축 값에 곱할 <b>다른 축</b>의 이름. {@code axis}와 함께만 쓰고 {@code
     *     multiplyByContext}와 같이 쓰지 않는다. 섹터 모멘텀처럼 <b>후보 전체가 아니라 그중 몇 개에만</b> 비중이 가는 전략을 판정하려면 필요하다
     *     — {@code maxWeight × topN}이 그 예다(PD-03, T023). 곱할 축이 그 설정에 없으면(다른 선정 방식을 골랐다) 이 규칙은 위반이
     *     아니다
     * @param multiplyByCeil 축 값에 곱할 올림 비율. {@code context × axis ÷ divisor}를 올림한 뒤 곱한다. {@code
     *     maxWeight × ceil(UNIVERSE_SIZE × topPercent ÷ 100)}이 그 예다. 참조 축이 그 설정에 없으면 이 규칙은 판정하지 않는다
     */
    public record Operand(
            String axis,
            String context,
            Object value,
            String multiplyByContext,
            String multiplyByAxis,
            CeilingFactor multiplyByCeil) {

        public Operand {
            long filled = Stream.of(axis, context, value).filter(Objects::nonNull).count();
            if (filled != 1) {
                throw new IllegalArgumentException(
                        "operand must set exactly one of axis, context, value");
            }
            if ((multiplyByContext != null || multiplyByAxis != null || multiplyByCeil != null)
                    && axis == null) {
                throw new IllegalArgumentException(
                        "operand multipliers are only valid with an axis");
            }
            long multipliers =
                    Stream.of(multiplyByContext, multiplyByAxis, multiplyByCeil)
                            .filter(Objects::nonNull)
                            .count();
            if (multipliers > 1) {
                throw new IllegalArgumentException("operand cannot set more than one multiplier");
            }
        }

        public static Operand ofAxis(String axis, String multiplyByContext) {
            return new Operand(axis, null, null, multiplyByContext, null, null);
        }

        public static Operand ofAxisTimesAxis(String axis, String multiplyByAxis) {
            return new Operand(axis, null, null, null, multiplyByAxis, null);
        }

        public static Operand ofAxisTimesCeilingFactor(
                String axis, String context, String factorAxis, Object divisor) {
            return new Operand(
                    axis, null, null, null, null, new CeilingFactor(context, factorAxis, divisor));
        }

        public static Operand ofContext(String context) {
            return new Operand(null, context, null, null, null, null);
        }

        public static Operand ofValue(Object value) {
            return new Operand(null, null, value, null, null, null);
        }
    }

    /** {@code ceil(context × axis ÷ divisor)}로 계산하는 곱셈 인자. */
    public record CeilingFactor(String context, String axis, Object divisor) {

        public CeilingFactor {
            if (context == null || context.isBlank()) {
                throw new IllegalArgumentException("ceiling factor context must not be blank");
            }
            if (axis == null || axis.isBlank()) {
                throw new IllegalArgumentException("ceiling factor axis must not be blank");
            }
            Objects.requireNonNull(divisor, "ceiling factor divisor must not be null");
            try {
                if (new BigDecimal(String.valueOf(divisor)).signum() <= 0) {
                    throw new IllegalArgumentException("ceiling factor divisor must be positive");
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("ceiling factor divisor must be a number", e);
            }
        }
    }

    /** 규칙이 쓸 수 있는 비교 연산자. 여섯이 전부다. */
    public enum Operator {
        GREATER_THAN,
        GREATER_OR_EQUAL,
        LESS_THAN,
        LESS_OR_EQUAL,
        EQUAL,
        NOT_EQUAL;

        public static Operator from(String name) {
            for (Operator operator : values()) {
                if (operator.name().equals(name)) {
                    return operator;
                }
            }
            throw new IllegalArgumentException("unknown registry rule operator: " + name);
        }
    }
}
