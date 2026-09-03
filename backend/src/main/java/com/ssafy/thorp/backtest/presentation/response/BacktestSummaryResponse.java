package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestSummaryResult;
import java.time.LocalDate;

/**
 * 응답의 {@code summary}: 핵심 지표. 계약은 {@code
 * specs/003-backtest-result-analysis/contracts/backtests-api.md}.
 *
 * <p>지표 필드의 {@code null}은 "계산 불가"다. 낙폭 구간 날짜의 {@code null}은 "구간 없음"(낙폭 0) 또는 "미회복"(회복일)이다. 키는 항상
 * 존재해야 하므로 {@code @JsonInclude(NON_NULL)}을 붙이지 않는다.
 */
public record BacktestSummaryResponse(
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

    /** 결과가 없으면({@code null}) 그대로 null을 돌려준다. */
    public static BacktestSummaryResponse from(BacktestSummaryResult result) {
        if (result == null) {
            return null;
        }
        return new BacktestSummaryResponse(
                result.initialEquity(),
                result.finalEquity(),
                result.totalReturn(),
                result.cagr(),
                result.maxDrawdown(),
                result.maxDrawdownPeakDate(),
                result.maxDrawdownTroughDate(),
                result.maxDrawdownRecoveryDate(),
                result.volatility(),
                result.sharpeRatio(),
                result.tradeCount(),
                result.winRate(),
                result.profitFactor(),
                result.metricDefinitionVersion());
    }
}
