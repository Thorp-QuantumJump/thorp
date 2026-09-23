package com.ssafy.thorp.regimelink.application.port.message;

/** 체결 사유. 004 {@code fills[].reason}과 같은 값이며 011은 표시용으로만 쓴다. */
public enum TradeReason {
    SIGNAL,
    REBALANCE,
    HALT_DEFERRED,
    LIQUIDATION
}
