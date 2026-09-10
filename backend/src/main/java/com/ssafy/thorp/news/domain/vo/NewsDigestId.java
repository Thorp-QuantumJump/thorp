package com.ssafy.thorp.news.domain.vo;

import java.util.Objects;

/**
 * 뉴스 묶음의 외부 노출 식별자(spec FR-025).
 *
 * <p>대표 기사 식별자와 묶음 규칙 버전에서 유도한다. 규칙 버전을 사업 키에 넣어야 규칙이 바뀔 때 같은 대표 기사라도 다른 묶음으로 식별되고, 기존 결과를 덮어쓰지
 * 않는다(원칙 VIII).
 */
public record NewsDigestId(String value) {

    /** 외부 계약(openapi.yaml)과 공유하는 형식 규칙. */
    public static final String REGEX = StableNewsId.REGEX;

    public NewsDigestId {
        Objects.requireNonNull(value, "digestId must not be null");
        if (!StableNewsId.PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid digestId format: " + value);
        }
    }

    /** 대표 기사와 묶음 규칙 버전에서 만든다. */
    public static NewsDigestId of(
            NewsArticleId representativeArticleId, String groupingRuleVersion) {
        Objects.requireNonNull(representativeArticleId, "representativeArticleId must not be null");
        if (groupingRuleVersion == null || groupingRuleVersion.isBlank()) {
            throw new IllegalArgumentException("groupingRuleVersion은 비어 있을 수 없다");
        }
        return new NewsDigestId(
                StableNewsId.derive(representativeArticleId.value() + "|" + groupingRuleVersion));
    }

    /** 저장된 문자열을 형식 검증과 함께 복원한다. */
    public static NewsDigestId from(String value) {
        return new NewsDigestId(value);
    }
}
