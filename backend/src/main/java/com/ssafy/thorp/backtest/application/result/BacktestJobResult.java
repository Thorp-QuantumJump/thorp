package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunStatus;

/** 백테스트 유스케이스 출력. HTTP 응답 DTO와 분리한다. 결과 지표는 결과 조회 작업에서 더한다. */
public record BacktestJobResult(String backtestId, RunStatus status) {

    public static BacktestJobResult from(BacktestRun job) {
        return new BacktestJobResult(job.getBacktestId().value(), job.getStatus());
    }
}
