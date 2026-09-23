package com.ssafy.thorp.marketflow.domain.model;

import com.ssafy.thorp.marketflow.domain.vo.PathPoint;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/** 특정 기준월에서 고정한 지표의 최근 3개월 경로와 발표 정보. */
public record IndicatorPathSnapshot(
        String indicatorId,
        YearMonth referenceMonth,
        List<PathPoint> path,
        LocalDate releaseDate,
        AfterReferenceMonthRelease afterReferenceMonthRelease) {
    public IndicatorPathSnapshot {
        path = List.copyOf(path);
    }
}
