package com.ssafy.thorp.news.domain.vo;

/**
 * 매체의 표시 허가 상태(spec FR-015).
 *
 * <p>공개 조회·순위·내보내기는 {@link #ALLOWED}만 사용한다. 회신이 없거나 불허인 매체의 항목은 공개 환경에 어떤 형태로도 나오지 않는다(spec
 * FR-016).
 */
public enum PermissionStatus {
    /** 요청했으나 회신이 없다. 공개하지 않는다. */
    PENDING,
    /** 표시를 허가하는 회신을 받았다. */
    ALLOWED,
    /** 표시를 불허하는 회신을 받았다. */
    DENIED;

    /** 공개 환경에 노출할 수 있는지 여부. */
    public boolean isPubliclyVisible() {
        return this == ALLOWED;
    }
}
