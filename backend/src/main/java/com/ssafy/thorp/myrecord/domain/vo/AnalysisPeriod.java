package com.ssafy.thorp.myrecord.domain.vo;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 목록에 보이는 분석 기간(spec 006 FR-001·FR-004).
 *
 * <p>사용자가 요청한 구간이며 워커가 실제로 잰 실효 구간이 아니다. 실효 구간은 결과가 가진다 — 여기 복제하면 두 값이 갈린다(research R2).
 *
 * <p>경제환경 탐색 항목에는 이 값이 없다(기준월이 그 자리를 대신한다). 스키마의 {@code ck_my_record_period}가 두 날짜를 함께 두거나 함께 비우도록
 * 강제한다.
 */
public record AnalysisPeriod(LocalDate start, LocalDate end) {

    public AnalysisPeriod {
        Objects.requireNonNull(start, "period start must not be null");
        Objects.requireNonNull(end, "period end must not be null");
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("period start must not be after end: " + start);
        }
    }

    /** 둘 다 있으면 기간, 둘 다 없으면 {@code null}. 한쪽만 오면 스키마 제약과 어긋나므로 거절한다. */
    public static AnalysisPeriod ofNullable(LocalDate start, LocalDate end) {
        if (start == null && end == null) {
            return null;
        }
        return new AnalysisPeriod(start, end);
    }
}
