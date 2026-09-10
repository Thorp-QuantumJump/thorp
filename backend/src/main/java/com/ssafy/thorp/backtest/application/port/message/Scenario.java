package com.ssafy.thorp.backtest.application.port.message;

/** 실행 시나리오(FR-016). 주 분석은 FULL_PERIOD, 나머지는 별도 실행으로 기록한다. */
public enum Scenario {
    FULL_PERIOD,
    FRESH_CASH_FROM,
    COST_ESCALATION,
    TARGET_RISK
}
