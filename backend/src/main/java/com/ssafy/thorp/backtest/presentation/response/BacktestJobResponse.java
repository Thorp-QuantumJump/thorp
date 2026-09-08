package com.ssafy.thorp.backtest.presentation.response;

import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;

/** 백테스트 Job 응답. 비동기 계약: { backtestId, status }. 결과 지표는 결과 조회 작업에서 더한다. */
public record BacktestJobResponse(String backtestId, BacktestStatus status) {

    public static BacktestJobResponse from(BacktestJobResult result) {
        return new BacktestJobResponse(result.backtestId(), result.status());
    }
}
