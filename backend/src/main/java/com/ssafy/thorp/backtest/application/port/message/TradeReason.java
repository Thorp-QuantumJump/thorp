package com.ssafy.thorp.backtest.application.port.message;

/** 거래 사유(FR-027 거래 내역). SIGNAL은 단일 종목 신호 전략용이다. */
public enum TradeReason {
    SIGNAL,
    REBALANCE,
    HALT_DEFERRED,
    LIQUIDATION
}
