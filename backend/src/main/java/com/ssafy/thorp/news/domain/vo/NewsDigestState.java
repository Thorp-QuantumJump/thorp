package com.ssafy.thorp.news.domain.vo;

/**
 * 뉴스 영역의 소비자 노출 상태(spec FR-014). 네 상태 모두 정상 응답이며 오류가 아니다.
 *
 * <p>판정 순서는 허가된 매체 존재 → 표시 자료 존재 → 신선도다. 앞 조건이 걸리면 뒤를 보지 않는다.
 */
public enum NewsDigestState {
    /** 허가된 매체가 없다. 공개 화면에 '준비 중'으로 보인다(spec FR-016). */
    PREPARING,
    /** 허가된 매체는 있으나 표시할 자료가 없다. 다른 자료로 채우지 않는다. */
    EMPTY,
    /** 표시할 자료가 있으나 마지막 갱신이 오래됐다. '실시간'·'속보' 표현을 붙이지 않는다. */
    STALE,
    /** 표시할 자료가 있고 신선하다. */
    READY;

    /** 표시할 묶음이 있는 상태인지 여부. */
    public boolean hasDigests() {
        return this == READY || this == STALE;
    }
}
