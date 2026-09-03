package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import java.time.LocalDate;

/** 백테스트가 실제 사용한 조건 집합(FR-006). HTTP 응답 DTO와 분리한다. */
public record BacktestConfigResult(
        String strategy, LocalDate startDate, LocalDate endDate, long initialCash) {

    public static BacktestConfigResult from(BacktestJob job) {
        return new BacktestConfigResult(
                job.getStrategy(),
                job.getPeriod().startDate(),
                job.getPeriod().endDate(),
                job.getInitialCash());
    }
}
