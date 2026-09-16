package com.ssafy.thorp.marketflow.domain.vo;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/** 한 대상월의 실제 지표값. value가 null이면 결측이며 0이나 이전 값으로 채우지 않는다. */
public record PathPoint(YearMonth month, BigDecimal value) {
    public PathPoint {
        Objects.requireNonNull(month, "month");
    }
}
