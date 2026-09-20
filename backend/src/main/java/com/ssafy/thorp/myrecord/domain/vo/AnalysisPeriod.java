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

    /**
     * 둘 다 있을 때만 기간이다. 한쪽만 온 접수는 기간 없이 기록한다.
     *
     * <p>여기서 거절하지 않는 이유: 이 값은 목록의 필터·표시에 쓰는 복제본이고, 006의 기록 생성은 접수 트랜잭션 안에서 돈다. 반쪽짜리 기간에 예외를 던지면
     * <b>006이 004·007의 접수를 되돌린다</b> — 기록하려다 분석을 못 하게 만드는 것이 가장 나쁜 실패다. 스키마의 {@code
     * ck_my_record_period}도 둘을 함께 두거나 함께 비우도록 강제하므로 이 변환이 곧 그 제약이다.
     */
    public static AnalysisPeriod ofNullable(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            return null;
        }
        return new AnalysisPeriod(start, end);
    }
}
