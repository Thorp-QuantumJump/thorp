package com.ssafy.thorp.regime.domain.vo;

/**
 * 검색 상태 (data-model §2.1, §8).
 *
 * <p><b>저장 값이고 조회 시 다시 판정하지 않는다</b>(research R6). 문턱이 교정된 날 조회가 다시 판정하면 그 이전 검색의 표시가 한꺼번에 바뀌고, 사용자가
 * 예전에 본 화면은 어디에도 남지 않는다.
 */
public enum SearchStatus {

    /** 계산할 수 없다. <b>오류가 아니라 정상 화면 흐름이라 200으로 내보낸다</b>(계약 §1, 원칙 VI). */
    NOT_COMPUTABLE,

    /** 가까운 순서만 제공하고 유사성은 판정하지 않는다. 문턱(PD-09) 교정 전의 유일한 정상 상태다. */
    NEAREST_ONLY,

    /** 문턱이 교정된 뒤에 생긴다. <b>배치가 아직 이 값을 만들지 않는다</b>(research R6). */
    THRESHOLD_APPLIED;

    public boolean computable() {
        return this != NOT_COMPUTABLE;
    }
}
