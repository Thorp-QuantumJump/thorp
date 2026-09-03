package com.ssafy.thorp.backtest.domain.vo;

import java.time.LocalDate;
import java.util.Objects;

/** 백테스트 기간. 시작일이 종료일보다 늦을 수 없다. */
public record BacktestPeriod(LocalDate startDate, LocalDate endDate) {

    public BacktestPeriod {
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException(
                    "startDate must not be after endDate: " + startDate + " > " + endDate);
        }
    }
}
