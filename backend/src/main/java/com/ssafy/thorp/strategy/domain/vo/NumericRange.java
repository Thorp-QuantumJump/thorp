package com.ssafy.thorp.strategy.domain.vo;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 수치형 파라미터의 유효 범위. {@link ParameterType#INTEGER}와 {@link ParameterType#DECIMAL}이 이 형태로 범위를
 * 표현한다(FR-008).
 *
 * <p>한쪽 경계만 지정한 열린 범위도 유효하다. {@code min}이 없으면 하한이 없고 {@code max}가 없으면 상한이 없다. 경계를 포함하는지 여부는 값과 분리해
 * 두므로 "50 초과 95 이하"처럼 비대칭인 범위도 그대로 담긴다.
 *
 * <p>모든 수치는 {@link BigDecimal}로 다룬다. {@code double}을 쓰면 {@code 0.1 + 0.2}가 {@code 0.3}이 아니게 되어 같은
 * 정의와 같은 값으로 소비 지점마다 다른 판정이 나온다(SC-002).
 *
 * <p>이 클래스는 자기 자신이 성립하는 데 필요한 불변식만 지킨다. {@code min <= max}인지(D13), {@code step}과 함께 {@code
 * minInclusive}가 참인지(D12) 같은 정의 형식 규칙은 카탈로그 등재 시점의 형식 검증이 다룬다.
 */
public record NumericRange(
        BigDecimal min,
        boolean minInclusive,
        BigDecimal max,
        boolean maxInclusive,
        BigDecimal step) {

    public NumericRange {
        if (step != null) {
            if (step.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("step은 0보다 커야 한다: " + step);
            }
            if (min == null) {
                // step 판정은 기준점이 있어야 정의된다. 기준점 없이 배수를 따지면 소비 지점마다
                // 0을 기준으로 하는 구현과 min을 기준으로 하는 구현이 갈린다.
                throw new IllegalArgumentException("step을 지정하려면 min이 있어야 한다");
            }
        }
    }

    /** 하한과 상한을 모두 포함하는 범위. */
    public static NumericRange closed(BigDecimal min, BigDecimal max) {
        return new NumericRange(min, true, max, true, null);
    }

    /** 하한과 상한을 모두 포함하고 허용 간격이 있는 범위. */
    public static NumericRange closed(BigDecimal min, BigDecimal max, BigDecimal step) {
        return new NumericRange(min, true, max, true, step);
    }

    /** 하한만 있는 열린 범위. */
    public static NumericRange atLeast(BigDecimal min) {
        return new NumericRange(min, true, null, true, null);
    }

    public boolean hasMin() {
        return min != null;
    }

    public boolean hasMax() {
        return max != null;
    }

    public boolean hasStep() {
        return step != null;
    }

    public Optional<BigDecimal> stepValue() {
        return Optional.ofNullable(step);
    }

    /** 값이 경계 안에 있는지. 경계가 없는 쪽은 항상 만족한다. */
    public boolean contains(BigDecimal value) {
        if (min != null) {
            int cmp = value.compareTo(min);
            if (minInclusive ? cmp < 0 : cmp <= 0) {
                return false;
            }
        }
        if (max != null) {
            int cmp = value.compareTo(max);
            if (maxInclusive ? cmp > 0 : cmp >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 값이 허용 간격의 배수인지. 판정식은 {@code (value - min) % step == 0}이며 십진 연산으로 수행한다.
     *
     * <p>{@code step}이 없으면 제약이 없으므로 항상 참이다.
     */
    public boolean matchesStep(BigDecimal value) {
        if (step == null) {
            return true;
        }
        BigDecimal remainder = value.subtract(min).divideAndRemainder(step)[1];
        return remainder.compareTo(BigDecimal.ZERO) == 0;
    }
}
