package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.BacktestResult;
import java.util.List;

/** 자산 가치 시계열 유스케이스 출력(US2). 포인트는 날짜 오름차순이며 비어 있지 않다. */
public record EquityCurveResult(Long backtestId, List<EquityPointResult> points) {

    public static EquityCurveResult from(BacktestResult result) {
        return new EquityCurveResult(
                result.getBacktestJobId(),
                result.getEquityCurve().points().stream().map(EquityPointResult::from).toList());
    }
}
