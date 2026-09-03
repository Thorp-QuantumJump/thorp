package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.EquityPointResult;
import java.time.LocalDate;

/** 시계열 응답의 {@code points[]} 원소. {@code equity}는 KRW 정수, {@code drawdown}은 0 또는 음수. */
public record EquityPointResponse(
        LocalDate date, long equity, double cumulativeReturn, double drawdown) {

    public static EquityPointResponse from(EquityPointResult result) {
        return new EquityPointResponse(
                result.date(), result.equity(), result.cumulativeReturn(), result.drawdown());
    }
}
