package com.ssafy.thorp.news.domain.vo;

/**
 * 기사 발행 시각의 확인 여부(spec FR-012).
 *
 * <p>timezone이 포함되고 정상 파싱되며 수집 시각보다 5분 넘게 미래가 아닌 시각만 {@link #VERIFIED}다. 그 밖에는 {@link #UNKNOWN}이고
 * 발행 시각을 비워 둔다. 수집 시각이나 GDELT 기록 시각으로 대체하지 않는다.
 *
 * <p>{@code UNKNOWN} 기사는 현재 뉴스에서 검증 시각 기사보다 뒤에 두고 과거 사례에는 연결하지 않는다(spec FR-006).
 */
public enum PublicationTimeStatus {
    VERIFIED,
    UNKNOWN;

    /** 과거 사례에 연결할 수 있는지 여부. */
    public boolean isUsableForHistory() {
        return this == VERIFIED;
    }
}
