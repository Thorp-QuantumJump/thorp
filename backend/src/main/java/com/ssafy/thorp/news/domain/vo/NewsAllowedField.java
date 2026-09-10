package com.ssafy.thorp.news.domain.vo;

/**
 * 매체 회신에서 표시·내보내기를 허용받은 필드(spec FR-015·FR-017).
 *
 * <p>기사 본문에 해당하는 값은 이 목록에 없다. 허가 여부와 무관하게 본문은 저장·반환하지 않는다(spec FR-002).
 */
public enum NewsAllowedField {
    TITLE,
    PUBLISHER,
    PUBLISHED_AT,
    ORIGINAL_URL
}
