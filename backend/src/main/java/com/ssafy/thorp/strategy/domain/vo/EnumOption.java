package com.ssafy.thorp.strategy.domain.vo;

import java.util.Objects;

/**
 * 열거형 파라미터의 허용 값 하나. {@link ParameterType#ENUM}이 이 목록으로 범위를 표현한다(FR-008).
 *
 * <p>값과 표시 이름을 함께 담는 이유는 입력 화면이 정의만 보고 셀렉트를 구성할 수 있어야 하기 때문이다(SC-005). 값만 있으면 화면이 {@code "WEEKLY"}를
 * "매주"로 바꾸는 표를 따로 들고 있어야 하고, 그 표는 새 전략을 추가할 때마다 고쳐야 하는 소비 지점이 된다.
 */
public record EnumOption(String value, String displayName) {

    public EnumOption {
        Objects.requireNonNull(value, "열거 값은 null일 수 없다");
        Objects.requireNonNull(displayName, "열거 값의 표시 이름은 null일 수 없다");
        if (value.isBlank()) {
            throw new IllegalArgumentException("열거 값은 비어 있을 수 없다");
        }
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("열거 값의 표시 이름은 비어 있을 수 없다: value=" + value);
        }
    }
}
