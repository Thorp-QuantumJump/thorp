package com.ssafy.thorp.learning.domain.model;

import java.util.Objects;

/**
 * 글의 출처 하나(FR-007). {@code url}은 없을 수 있다.
 *
 * <p>{@code http}·{@code https}만 허용하는 검사는 저장 요청 검증(B02)이 한다.
 */
public record ArticleSource(String label, String url) {

    public ArticleSource {
        Objects.requireNonNull(label, "source label must not be null");
    }
}
