package com.ssafy.thorp.marketflow.domain.model;

import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 화면에 표시하는 지표의 실제 값과 근거. 표준화 점수나 좋고 나쁨 판정을 담지 않는다. */
public record IndicatorDisplay(
        String indicatorId,
        String name,
        String unit,
        BigDecimal value,
        List<PathPoint> path,
        YearMonth referenceMonth,
        LocalDate releaseDate,
        String source,
        String agency,
        AfterReferenceMonthRelease afterReferenceMonthRelease) {

    public IndicatorDisplay {
        requireText(indicatorId, "indicatorId");
        requireText(name, "name");
        requireText(unit, "unit");
        requireText(source, "source");
        requireText(agency, "agency");
        if (referenceMonth == null) {
            throw new IllegalArgumentException("referenceMonth는 필수입니다.");
        }
        path = List.copyOf(path);
        if (path.size() != 3) {
            throw new IllegalArgumentException("최근 경로는 t-2·t-1·t 세 점이어야 합니다.");
        }
        if (afterReferenceMonthRelease != null
                && !afterReferenceMonthRelease.referenceMonth().isAfter(referenceMonth)) {
            throw new IllegalArgumentException("기준월 이후 발표는 분야 기준월보다 뒤여야 합니다.");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "은 비어 있을 수 없습니다.");
        }
    }
}
