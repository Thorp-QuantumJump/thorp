package com.ssafy.thorp.marketflow.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/** 분야 기준월 뒤에 별도로 발표된 값. 기준월 경로와 변화 요약 입력에는 섞지 않는다. */
public record AfterReferenceMonthRelease(YearMonth referenceMonth, BigDecimal value, String unit) {
    public AfterReferenceMonthRelease {
        Objects.requireNonNull(referenceMonth, "referenceMonth");
        Objects.requireNonNull(value, "value");
        if (unit == null || unit.isBlank()) {
            throw new IllegalArgumentException("unit은 비어 있을 수 없습니다.");
        }
    }
}
