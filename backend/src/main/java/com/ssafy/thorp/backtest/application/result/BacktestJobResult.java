package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;

/** 백테스트 유스케이스 출력. HTTP 응답 DTO와 분리한다. 결과 지표는 결과 조회 작업에서 더한다. */
public record BacktestJobResult(String backtestId, BacktestStatus status) {

    public static BacktestJobResult from(BacktestJob job) {
        return new BacktestJobResult(job.getBacktestId().value(), job.getStatus());
    }
}
