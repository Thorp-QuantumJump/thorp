package com.ssafy.thorp.news.domain.vo;

/**
 * 뉴스 노출 모드(spec FR-016). 서버 설정이 정하며 클라이언트가 고를 수 없다.
 *
 * <p>{@link #PUBLIC}에서는 허가된 매체의 항목만 조회·순위·내보내기에 들어간다. {@link #INTERNAL}은 매체 회신 전 내부 검증용이다.
 */
public enum NewsExposureMode {
    INTERNAL,
    PUBLIC;

    /** 매체 허가 필터를 적용해야 하는지 여부. */
    public boolean requiresPermissionFilter() {
        return this == PUBLIC;
    }
}
