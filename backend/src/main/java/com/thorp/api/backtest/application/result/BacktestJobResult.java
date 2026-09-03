package com.thorp.api.backtest.application.result;

import com.thorp.api.backtest.domain.model.BacktestJob;
import com.thorp.api.backtest.domain.model.BacktestStatus;
import java.time.LocalDate;

/** 백테스트 유스케이스 출력. HTTP 응답 DTO와 분리한다. */
public record BacktestJobResult(
        Long id, String strategy, LocalDate startDate, LocalDate endDate, BacktestStatus status) {

    public static BacktestJobResult from(BacktestJob job) {
        return new BacktestJobResult(
                job.getId(),
                job.getStrategy(),
                job.getPeriod().startDate(),
                job.getPeriod().endDate(),
                job.getStatus());
    }
}
