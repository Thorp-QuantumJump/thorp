package com.ssafy.thorp.regimelink.domain.vo;

/**
 * 사례 하나에 비교 묶음을 붙일 수 있는가 (research R4, contracts §1).
 *
 * <p>상수 순서가 곧 <b>묶음 판정의 우선순위</b>다 — 여러 실행의 판정이 다르면 가장 나쁜 것이 묶음 값이 된다. 선언 순서로 비교하므로 상수를 끼워 넣을 때는 자리를
 * 지켜야 한다.
 */
public enum AvailabilityState {

    /** 원천 자료가 없어 계산 자체가 성립하지 않는다. 작업을 만들지 않는다(FR-018). */
    DATA_INSUFFICIENT,

    /** 사후 확보 거래일이 21 미만이다. 기본 목록에서 빠지고 요약 수치가 없다. */
    POST_INCOMPLETE,

    /**
     * 원천은 충분하지만 기존 실행의 분석 기간이 이 사례를 덮지 않는다.
     *
     * <p><b>필터 조건이 아니다</b> — 기본 목록에 남고 사례를 열 때 전체 기간 재계산 확인을 받는다(Clarifications 09-18 Q1). 링크 행에는
     * 저장하지 않는다(data-model §4).
     */
    OUT_OF_PERIOD,

    NORMAL;

    /** 공통 가용 범위 필터가 걸러 내는 상태인가. {@code OUT_OF_PERIOD}는 여기 들지 않는다. */
    public boolean excludedByFilter() {
        return this == DATA_INSUFFICIENT || this == POST_INCOMPLETE;
    }

    /** 둘 중 나쁜 쪽. 비교 묶음 전체의 판정을 실행별 판정에서 모을 때 쓴다. */
    public AvailabilityState worseOf(AvailabilityState other) {
        return other == null || compareTo(other) <= 0 ? this : other;
    }
}
