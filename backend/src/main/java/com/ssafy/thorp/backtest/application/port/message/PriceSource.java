package com.ssafy.thorp.backtest.application.port.message;

/** 가격 계열. 체결은 RAW(원시 가격), 신호·성과는 ADJUSTED(기업행위 일관 반영)를 쓴다(FR-013). */
public enum PriceSource {
    RAW,
    ADJUSTED
}
