package com.ssafy.thorp.regimelink.domain.vo;

/** 사후 구간을 어떤 전제로 보는가 (data-model §4). */
public enum Scenario {

    /** 전체 기간 연속 운용의 그 구간을 잘라낸다. 시작 시점 보유가 있다. */
    CONTINUOUS,

    /** 그날 현금으로 시작한다. 004 {@code FRESH_CASH_FROM} 실행이 입력이다. */
    FRESH_CASH
}
