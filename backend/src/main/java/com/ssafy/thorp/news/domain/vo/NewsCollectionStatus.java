package com.ssafy.thorp.news.domain.vo;

/**
 * 뉴스원별 수집 회차의 실행 상태(spec FR-013·FR-014).
 *
 * <p>{@link #INCOMPLETE}는 GDELT 조회가 결과 상한에 도달해 요청 구간의 기사를 모두 받지 못했고, 최소 15분 구간까지 분할한 뒤에도 상한이 유지된
 * 경우다. 이때 받은 기사는 저장하되 수집 위치를 그 구간 너머로 전진시키지 않고 마지막 성공 시각도 갱신하지 않아 다음 회차가 같은 구간을 재시도한다. 부분 수집 구간을 완전
 * 수집으로 기록하지 않는다.
 */
public enum NewsCollectionStatus {
    IDLE,
    RUNNING,
    SUCCEEDED,
    INCOMPLETE,
    FAILED,
    RATE_LIMITED;

    /** 마지막 성공 시각을 갱신해도 되는 상태인지 여부. INCOMPLETE는 성공이 아니다. */
    public boolean advancesLastSuccess() {
        return this == SUCCEEDED;
    }

    /** 수집 위치(cursor)를 전진시켜도 되는 상태인지 여부. */
    public boolean advancesCursor() {
        return this == SUCCEEDED;
    }
}
