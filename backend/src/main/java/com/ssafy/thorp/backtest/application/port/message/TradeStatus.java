package com.ssafy.thorp.backtest.application.port.message;

/** 포지션 상태. OPEN은 청산 필드가 null. */
public enum TradeStatus {
    OPEN,
    CLOSED
}
