package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * PROGRESS 이벤트 payload. 20거래일 단위로 이번 구간에서 새로 계산한 포인트만 담는다(마지막 잔여 구간도 발행). DB에 저장하지 않고 SSE로 전달한다.
 */
public record ProgressPayload(
        int completedTradingDays, int totalTradingDays, List<ProgressPoint> portfolioSeries) {

    public record ProgressPoint(
            LocalDate date, BigDecimal equity, BigDecimal cumulativeReturn, BigDecimal drawdown) {}
}
