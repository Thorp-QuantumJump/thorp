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
        path = List.copyOf(path);
        // 달이 정해졌으면 세 점이고, 정해지지 않았으면 그릴 달이 없어 빈 배열이다. 그 사이는
        // 없다 — 부분 경로를 허용하면 "자료가 없다"와 "그 달의 값이 결측이다"가 같은 모양이
        // 되고, 화면은 둘을 구분해 보여야 한다(009 contracts, 008 FR-008).
        if (referenceMonth == null && !path.isEmpty()) {
            throw new IllegalArgumentException("기준월이 없으면 경로도 없어야 합니다.");
        }
        if (referenceMonth != null && path.size() != 3) {
            throw new IllegalArgumentException("최근 경로는 t-2·t-1·t 세 점이어야 합니다.");
        }
        if (afterReferenceMonthRelease != null) {
            if (referenceMonth == null) {
                // 기준월이 없으면 "그보다 뒤"라는 말이 성립하지 않는다.
                throw new IllegalArgumentException("기준월이 없으면 기준월 이후 발표도 없어야 합니다.");
            }
            if (!afterReferenceMonthRelease.referenceMonth().isAfter(referenceMonth)) {
                throw new IllegalArgumentException("기준월 이후 발표는 분야 기준월보다 뒤여야 합니다.");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "은 비어 있을 수 없습니다.");
        }
    }
}
