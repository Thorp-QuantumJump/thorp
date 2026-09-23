package com.ssafy.thorp.strategy.application.port;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 축의 입력 허용 범위. 상세 변경 화면과 범위 검증이 그대로 쓴다(007 FR-027).
 *
 * <p>목록형 축은 {@code choices}만, 입력형 축은 {@code min}·{@code max}·{@code step}을 쓴다. <b>007은 어느 쪽인지 판단하지
 * 않고 등록표가 준 형태를 그대로 옮긴다</b> — 축의 종류를 007이 해석하기 시작하면 그것이 전략 지식이다(007 FR-025).
 *
 * <p><b>두 형태를 동시에 채우지 않는다.</b> 동시에 채우면 화면이 목록을 그릴지 입력칸을 그릴지 정할 수 없다(data-model §3·§4). 생성자가 막으므로
 * 등록표가 그렇게 적히면 기동이 실패한다.
 *
 * <p><b>후보는 값과 표시 문구를 함께 담는다</b>({@link RegisteredChoice}). 값만 알고 문구를 모르면 화면이 {@code TOP_N}을 그대로
 * 보이거나 무슨 말로 보일지 코드에 적게 된다(FR-005). 값만 필요한 호출자는 {@link #values()}를 쓴다 — 007이 그렇다.
 *
 * <p>범위 상수를 007에 두지 않는다. 기본값과 범위의 출처는 등록표 하나다.
 */
public record AllowedRange(Object min, Object max, Object step, List<RegisteredChoice> choices) {

    public AllowedRange {
        choices = choices == null ? null : Collections.unmodifiableList(List.copyOf(choices));
        boolean hasChoices = choices != null && !choices.isEmpty();
        boolean hasBounds = min != null || max != null || step != null;
        if (hasChoices && hasBounds) {
            throw new IllegalArgumentException(
                    "allowedRange must not fill both values and min/max/step");
        }
    }

    /** 입력형 축의 범위. */
    public static AllowedRange between(Object min, Object max, Object step) {
        return new AllowedRange(min, max, step, null);
    }

    /** 문구 없는 후보들로 만든 목록형 범위. 값 자체로 읽히는 후보에만 쓴다. */
    public static AllowedRange ofValues(List<Object> values) {
        return new AllowedRange(
                null,
                null,
                null,
                values == null ? null : values.stream().map(RegisteredChoice::of).toList());
    }

    /** 값과 문구를 함께 담은 목록형 범위. */
    public static AllowedRange ofChoices(List<RegisteredChoice> choices) {
        return new AllowedRange(null, null, null, choices);
    }

    /**
     * 허용값만 순서대로. 표시 문구를 쓰지 않는 호출자(007의 미리보기 응답·부분집합 검사)가 쓴다.
     *
     * <p>이름이 {@code values}인 record 컴포넌트였다가 {@link RegisteredChoice}로 바뀐 자리다. 호출부가 값만 보는 한 이 메서드로
     * 충분해서 접근자 이름을 그대로 남겼다.
     */
    public List<Object> values() {
        return choices == null ? null : choices.stream().map(RegisteredChoice::value).toList();
    }

    /** 허용값 목록으로 판정하는 축인가. 판정 방식만 구분하며 축의 의미는 보지 않는다. */
    public boolean isEnumerated() {
        return choices != null && !choices.isEmpty();
    }

    /**
     * 이 값을 사람에게 보일 문구. 등록표가 적어 두지 않았으면 값을 그대로 읽은 문자열이다.
     *
     * <p><b>값만 내보내는 통로가 라벨을 잃게 만든다.</b> {@link #values()}는 값만 넘기므로 그것만 받은 쪽은 {@code {mode:
     * CALENDAR, unit: MONTH_END}}를 그릴 방법이 없어 화면에 {@code [object Object]}가 나온다. {@link
     * RegisteredChoice}가 값과 문구를 한 덩이로 묶어 둔 뜻이 거기서 끊기지 않도록, 값으로 문구를 되찾는 자리를 여기 둔다.
     *
     * <p>비교는 {@code equals}로만 한다 — 목록형 판정({@link #contains})과 같은 잣대여야 같은 값이 두 곳에서 다르게 읽히지 않는다.
     */
    public String labelOf(Object value) {
        if (choices != null) {
            for (RegisteredChoice choice : choices) {
                if (choice.value().equals(value) && choice.label() != null) {
                    return choice.label();
                }
            }
        }
        return String.valueOf(value);
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
            return choices.stream().anyMatch(choice -> choice.value().equals(value));
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
