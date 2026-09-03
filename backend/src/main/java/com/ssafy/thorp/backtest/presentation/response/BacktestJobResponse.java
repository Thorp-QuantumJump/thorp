package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;

/** 백테스트 Job 응답. 비동기 계약: {@code { backtestId, status }}. */
public record BacktestJobResponse(Long backtestId, BacktestStatus status) {

    public static BacktestJobResponse from(BacktestJobResult result) {
        return new BacktestJobResponse(result.id(), result.status());
    }
}
