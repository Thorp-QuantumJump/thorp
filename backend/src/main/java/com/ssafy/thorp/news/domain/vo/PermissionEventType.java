package com.ssafy.thorp.news.domain.vo;

/**
 * 매체 허가 이력의 사건 종류(spec FR-015). 이력은 append-only이며 요청과 회신을 발생 순서대로 남긴다.
 *
 * <p>요청은 {@link PermissionStatus#PENDING}, 회신은 {@code ALLOWED} 또는 {@code DENIED}와만 짝지어진다.
 */
public enum PermissionEventType {
    REQUESTED,
    RESPONDED;

    /** 이 사건 종류에 이 상태를 기록할 수 있는지 확인한다. */
    public boolean allows(PermissionStatus status) {
        return switch (this) {
            case REQUESTED -> status == PermissionStatus.PENDING;
            case RESPONDED ->
                    status == PermissionStatus.ALLOWED || status == PermissionStatus.DENIED;
        };
    }
}
