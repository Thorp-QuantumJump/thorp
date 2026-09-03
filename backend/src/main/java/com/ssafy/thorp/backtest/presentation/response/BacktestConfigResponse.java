package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestConfigResult;
import java.time.LocalDate;

/** 응답의 {@code config}: 백테스트가 실제 사용한 조건 집합. */
public record BacktestConfigResponse(
        String strategy, LocalDate startDate, LocalDate endDate, long initialCash) {

    public static BacktestConfigResponse from(BacktestConfigResult result) {
        return new BacktestConfigResponse(
                result.strategy(), result.startDate(), result.endDate(), result.initialCash());
    }
}
