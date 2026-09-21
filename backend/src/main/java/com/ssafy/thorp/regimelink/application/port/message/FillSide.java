package com.ssafy.thorp.regimelink.application.port.message;

/** 체결 방향. 004 {@code fills[].side}를 그대로 옮긴 값이라 진입·청산(LONG·SHORT)이 아니라 매수·매도다. */
public enum FillSide {
    BUY,
    SELL
}
