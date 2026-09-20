package com.ssafy.thorp.regimelink.domain.vo;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 가용성 필터의 동일성 (research R4, data-model §2).
 *
 * <p>같은 비교 묶음·같은 자료면 같은 값이라 "같은 비교 묶음은 같은 후보"(FR-021)가 키 일치로 검증된다. <b>성과에 의존하는 값은 정의에 들어가지 않는다</b>
 * — 기존 실행의 기간도 넣지 않는다(FR-003).
 */
public record FilterKey(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    public FilterKey {
        Objects.requireNonNull(value, "filterKey must not be null");
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("filterKey 는 16진 64자여야 합니다: " + value);
        }
    }
}
