package com.ssafy.thorp.backtest.application.port.message;

/**
 * 체결 방향. {@link TradeSide}는 포지션 방향(LONG/SHORT)이라 다른 개념이다 — 공매도가 없는 이 제품에서 포지션은 늘 LONG이고, 체결은 그 포지션을
 * 늘리거나(BUY) 줄인다(SELL).
 */
public enum FillSide {
    BUY,
    SELL
}
