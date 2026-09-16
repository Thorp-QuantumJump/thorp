package com.ssafy.thorp.strategy.application.port;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 축의 입력 허용 범위. 상세 변경 화면과 범위 검증이 그대로 쓴다(007 FR-027).
 *
 * <p>목록형 축은 {@code values}만, 입력형 축은 {@code min}·{@code max}·{@code step}을 쓴다. <b>007은 어느 쪽인지 판단하지
 * 않고 등록표가 준 형태를 그대로 옮긴다</b> — 축의 종류를 007이 해석하기 시작하면 그것이 전략 지식이다(007 FR-025).
 *
 * <p><b>두 형태를 동시에 채우지 않는다.</b> 동시에 채우면 화면이 목록을 그릴지 입력칸을 그릴지 정할 수 없다(data-model §3·§4). 생성자가 막으므로
 * 등록표가 그렇게 적히면 기동이 실패한다.
 *
 * <p>범위 상수를 007에 두지 않는다. 기본값과 범위의 출처는 등록표 하나다.
 */
public record AllowedRange(Object min, Object max, Object step, List<Object> values) {

    public AllowedRange {
        values = values == null ? null : Collections.unmodifiableList(List.copyOf(values));
        boolean hasValues = values != null && !values.isEmpty();
        boolean hasBounds = min != null || max != null || step != null;
        if (hasValues && hasBounds) {
            throw new IllegalArgumentException(
                    "allowedRange must not fill both values and min/max/step");
        }
    }

    /** 입력형 축의 범위. */
    public static AllowedRange between(Object min, Object max, Object step) {
        return new AllowedRange(min, max, step, null);
    }

    /** 목록형 축의 허용값 목록. */
    public static AllowedRange ofValues(List<Object> values) {
        return new AllowedRange(null, null, null, values);
    }

    /** 허용값 목록으로 판정하는 축인가. 판정 방식만 구분하며 축의 의미는 보지 않는다. */
    public boolean isEnumerated() {
        return values != null && !values.isEmpty();
    }

    /**
     * 이 값이 허용 범위 안에 있는가.
     *
     * <p>목록형은 {@code equals}로만 판정한다 — 같은 뜻의 값을 두 형태로 담지 않기로 했으므로({@code 63}과 {@code 63.0}을 섞지 않는다,
     * 내부 계약 §2.4) 형 변환으로 맞춰 주면 등록표에 없는 값을 통과시키는 셈이다.
     *
     * <p>입력형은 {@code min ≤ value ≤ max}이고 {@code (value − min)}이 {@code step}의 배수인지 본다. <b>{@code
     * double}을 거치지 않는다</b> — 등록표 값이 그대로 기록에 남는데 이진 부동소수로 한 번 바꾸면 같은 값이 비교마다 다르게 보일 수 있다({@code
     * RegistryRule.toNumber}와 같은 이유).
     */
    public boolean contains(Object value) {
        if (value == null) {
            return false;
        }
        if (isEnumerated()) {
            return values.contains(value);
        }
        if (min == null || max == null) {
            // 범위를 적지 않은 축은 판정할 근거가 없다. 없는 근거로 통과시키지 않는다.
            return false;
        }
        BigDecimal target;
        try {
            target = decimal(value);
        } catch (NumberFormatException e) {
            return false;
        }
        if (target.compareTo(decimal(min)) < 0 || target.compareTo(decimal(max)) > 0) {
            return false;
        }
        if (step == null) {
            return true;
        }
        BigDecimal interval = decimal(step);
        if (interval.signum() == 0) {
            return true;
        }
        return target.subtract(decimal(min)).remainder(interval).signum() == 0;
    }

    private static BigDecimal decimal(Object value) {
        return value instanceof BigDecimal decimal
                ? decimal
                : new BigDecimal(String.valueOf(value));
    }
}
