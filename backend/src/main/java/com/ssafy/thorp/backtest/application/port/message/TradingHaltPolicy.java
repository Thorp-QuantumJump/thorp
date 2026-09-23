package com.ssafy.thorp.backtest.application.port.message;

/** 거래정지·거래 불가 시 처리. 그날 체결하지 않고 다음 거래 가능일로 미루며 거래 내역에 남긴다(spec 004 Edge Case). */
public enum TradingHaltPolicy {
    DEFER_TO_NEXT_TRADABLE
}
