package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.vo.EquityPoint;
import java.time.LocalDate;

/** 자산 가치 시점 유스케이스 출력. 필드 의미는 {@link EquityPoint}와 같다. */
public record EquityPointResult(
        LocalDate date, long equity, double cumulativeReturn, double drawdown) {

    public static EquityPointResult from(EquityPoint point) {
        return new EquityPointResult(
                point.date(), point.equity(), point.cumulativeReturn(), point.drawdown());
    }
}
