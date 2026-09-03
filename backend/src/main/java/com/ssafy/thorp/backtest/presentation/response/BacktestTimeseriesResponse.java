package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.EquityCurveResult;
import java.util.List;

/**
 * {@code GET /backtests/{id}/timeseries} 응답. 계약은 {@code
 * specs/003-backtest-result-analysis/contracts/backtest-timeseries.schema.json}.
 */
public record BacktestTimeseriesResponse(Long backtestId, List<EquityPointResponse> points) {

    public static BacktestTimeseriesResponse from(EquityCurveResult result) {
        return new BacktestTimeseriesResponse(
                result.backtestId(),
                result.points().stream().map(EquityPointResponse::from).toList());
    }
}
