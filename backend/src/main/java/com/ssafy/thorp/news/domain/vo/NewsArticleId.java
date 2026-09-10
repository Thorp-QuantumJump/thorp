package com.ssafy.thorp.news.domain.vo;

import java.util.Objects;

/**
 * 기사의 외부 노출 식별자(spec FR-025).
 *
 * <p>canonical URL에서 결정론적으로 유도하므로 같은 기사를 다시 수집해도 같은 값이 나온다. DB PK는 노출하지 않는다.
 */
public record NewsArticleId(String value) {

    /** 외부 계약(openapi.yaml)과 공유하는 형식 규칙. */
    public static final String REGEX = StableNewsId.REGEX;

    public NewsArticleId {
        Objects.requireNonNull(value, "articleId must not be null");
        if (!StableNewsId.PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid articleId format: " + value);
        }
    }

    /** canonical URL에서 만든다. 사업 키는 정규화된 URL 하나다. */
    public static NewsArticleId of(CanonicalNewsUrl canonicalUrl) {
        Objects.requireNonNull(canonicalUrl, "canonicalUrl must not be null");
        return new NewsArticleId(StableNewsId.derive(canonicalUrl.value()));
    }

    /** 저장된 문자열을 형식 검증과 함께 복원한다. */
    public static NewsArticleId from(String value) {
        return new NewsArticleId(value);
    }
}
