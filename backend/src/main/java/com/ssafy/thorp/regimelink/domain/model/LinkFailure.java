package com.ssafy.thorp.regimelink.domain.model;

import java.util.Objects;

/**
 * 계산이 실패한 사실 (data-model §4 {@code failure}).
 *
 * <p><b>자료 부족은 여기 오지 않는다.</b> {@code DATA_INSUFFICIENT}는 판정이지 실패가 아니고, 그 링크는 결과 없이 {@code
 * COMPLETED}로 끝난다(FR-018, research R13).
 */
public record LinkFailure(String code, String message) {

    public LinkFailure {
        Objects.requireNonNull(code, "failure code must not be null");
    }
}
