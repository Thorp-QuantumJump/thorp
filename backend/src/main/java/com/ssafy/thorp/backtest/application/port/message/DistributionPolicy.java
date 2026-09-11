package com.ssafy.thorp.backtest.application.port.message;

/** 분배금 처리. 지급일에 보유 수량 × 주당 금액을 현금으로 한 번만 반영한다(FR-013). */
public enum DistributionPolicy {
    CASH_ON_PAY_DATE
}
