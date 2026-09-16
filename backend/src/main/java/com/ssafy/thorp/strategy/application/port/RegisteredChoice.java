package com.ssafy.thorp.strategy.application.port;

import java.util.Objects;

/**
 * 목록형 허용 범위의 후보 하나. 값과 <b>그 값을 사람에게 보일 문구</b>를 한 덩이로 담는다(data-model §3).
 *
 * <p><b>왜 값과 문구를 함께 두나</b>: 둘을 따로 두면 후보를 늘리면서 문구를 빠뜨린 채 화면이 열린다. 기본값을 허용 범위와 한 덩이로 둔 것과 같은 이유다(§7).
 *
 * <p><b>문구가 없어도 되는 후보가 있다.</b> 숫자는 값과 축의 단위를 붙이면 그대로 읽히므로({@code 21} + {@code 거래일}) 등록표에 되풀이해 적지
 * 않는다. 그러나 {@code TOP_N}이나 {@code {mode: CALENDAR, unit: MONTH_END}}가 무슨 뜻인지는 등록표만 안다 — 그것을 적지 않으면
 * 화면이 {@code TOP_N}을 그대로 보이거나, 무슨 말로 보일지 코드에 적게 된다. 후자가 FR-005가 금지한 "화면 코드에 따로 적어 두는 안내"다. 그래서 숫자가
 * 아닌 후보에는 로더가 문구를 요구한다.
 *
 * @param value 후보값. 비교는 {@code equals}로만 한다(내부 계약 §2.4)
 * @param label 화면에 보일 문구. 없으면 null이고, 그때는 값과 단위로 만든다
 */
public record RegisteredChoice(Object value, String label) {

    public RegisteredChoice {
        Objects.requireNonNull(value, "choice value must not be null");
    }

    /** 문구 없는 후보. 숫자처럼 값 자체로 읽히는 것에만 쓴다. */
    public static RegisteredChoice of(Object value) {
        return new RegisteredChoice(value, null);
    }
}
