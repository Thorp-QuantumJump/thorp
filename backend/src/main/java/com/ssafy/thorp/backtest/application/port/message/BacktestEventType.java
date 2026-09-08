package com.ssafy.thorp.backtest.application.port.message;

/** 이벤트 종류. payload 타입을 결정한다. */
public enum BacktestEventType {
    STARTED,
    PROGRESS,
    FINISHED
}
