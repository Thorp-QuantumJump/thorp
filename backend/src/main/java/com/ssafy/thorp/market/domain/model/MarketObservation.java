package com.ssafy.thorp.market.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** 원천에서 확인한 관측값. 월 자료를 임의의 일자로 변환하지 않는다. */
public record MarketObservation(BigDecimal value, String unit, String observationDate) {
    public MarketObservation {
        Objects.requireNonNull(value);
        if (unit == null || unit.isBlank()) throw new IllegalArgumentException("단위가 필요합니다.");
        if (observationDate != null && observationDate.length() == 7)
            YearMonth.parse(observationDate);
        else LocalDate.parse(Objects.requireNonNull(observationDate));
    }
}
