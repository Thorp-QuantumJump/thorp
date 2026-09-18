package com.ssafy.thorp.regime.domain.vo;

/**
 * 검색 상태의 사유 (data-model §2.1).
 *
 * <p>계산 불가 사유가 넷으로 나뉘어 있는 것이 요점이다. 한 가지로 뭉치면 화면이 <b>질의 입력이 없는 것을 "척도가 60개월이 안 된다"고 잘못 말한다</b> —
 * 사용자가 할 수 있는 일(다른 기준월을 고른다 / 기다린다)이 사유마다 다르다.
 */
public enum StatusReason {

    /** 척도 연속 구간이 60개월 미만 (spec FR-005·FR-008). */
    SCALE_WINDOW_TOO_SHORT,

    /** MAD도 IQR도 0이라 산포를 말할 수 없다 (spec FR-006). */
    SCALE_ZERO,

    /** 어느 지표의 비교 시점이 하나도 없다 (spec FR-001·FR-007). */
    QUERY_INPUT_MISSING,

    /** 유효 후보가 0건이다 (spec FR-012). */
    NO_CANDIDATE,

    /** 문턱 미교정 — {@link SearchStatus#NEAREST_ONLY}의 사유다 (spec FR-020). */
    THRESHOLD_NOT_CALIBRATED
}
