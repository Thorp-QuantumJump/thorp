package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * PROGRESS 이벤트 payload. {@code EngineInput.progress.batchTradingDays} 단위로 이번 구간에서 새로 계산한 포인트만
 * 담는다(마지막 잔여 구간도 발행). 백엔드는 {@code stage}·{@code lastTradingDate}·거래일 수를 실행 행에 저장한다(research R-05).
 *
 * @param stage 항상 COMPUTING
 * @param lastTradingDate 이번 구간까지 처리한 마지막 거래일 = 취소·한도 시 완료 범위
 */
public record ProgressPayload(
        ProcessingStage stage,
        int completedTradingDays,
        int totalTradingDays,
        LocalDate lastTradingDate,
        List<ProgressPoint> portfolioSeries) {

    public record ProgressPoint(
            LocalDate date, BigDecimal equity, BigDecimal cumulativeReturn, BigDecimal drawdown) {}
}
