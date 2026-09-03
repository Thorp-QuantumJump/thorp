package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.vo.PerformanceSummary;
import java.time.LocalDate;

/** 핵심 지표 유스케이스 출력. 필드 의미와 null 규칙은 {@link PerformanceSummary}와 같다. */
public record BacktestSummaryResult(
        long initialEquity,
        long finalEquity,
        Double totalReturn,
        Double cagr,
        Double maxDrawdown,
        LocalDate maxDrawdownPeakDate,
        LocalDate maxDrawdownTroughDate,
        LocalDate maxDrawdownRecoveryDate,
        Double volatility,
        Double sharpeRatio,
        int tradeCount,
        Double winRate,
        Double profitFactor,
        String metricDefinitionVersion) {

    public static BacktestSummaryResult from(PerformanceSummary summary) {
        return new BacktestSummaryResult(
                summary.initialEquity(),
                summary.finalEquity(),
                summary.totalReturn(),
                summary.cagr(),
                summary.maxDrawdown(),
                summary.maxDrawdownPeakDate(),
                summary.maxDrawdownTroughDate(),
                summary.maxDrawdownRecoveryDate(),
                summary.volatility(),
                summary.sharpeRatio(),
                summary.tradeCount(),
                summary.winRate(),
                summary.profitFactor(),
                summary.metricDefinitionVersion());
    }
}
