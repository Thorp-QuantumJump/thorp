package com.ssafy.thorp.paramsearch.application.port;

import java.util.Collections;
import java.util.List;

/**
 * 축의 입력 허용 범위. 상세 변경 화면과 범위 검증이 그대로 쓴다(FR-027).
 *
 * <p>열거형 축은 {@code values}만, 수치형 축은 {@code min}·{@code max}·{@code step}을 쓴다. <b>007은 어느 쪽인지 판단하지
 * 않고 등록표가 준 형태를 그대로 옮긴다</b> — 축의 종류를 007이 해석하기 시작하면 그것이 전략 지식이다(FR-025).
 *
 * <p>범위 상수를 007에 두지 않는다. 기본값과 범위의 출처는 등록표 하나다.
 */
public record AllowedRange(Object min, Object max, Object step, List<Object> values) {

    public AllowedRange {
        values = values == null ? null : Collections.unmodifiableList(List.copyOf(values));
    }

    /** 수치형 축의 범위. */
    public static AllowedRange between(Object min, Object max, Object step) {
        return new AllowedRange(min, max, step, null);
    }

    /** 열거형 축의 허용값 목록. */
    public static AllowedRange ofValues(List<Object> values) {
        return new AllowedRange(null, null, null, values);
    }

    /** 허용값 목록으로 판정하는 축인가. 판정 방식만 구분하며 축의 의미는 보지 않는다. */
    public boolean isEnumerated() {
        return values != null && !values.isEmpty();
    }
}
