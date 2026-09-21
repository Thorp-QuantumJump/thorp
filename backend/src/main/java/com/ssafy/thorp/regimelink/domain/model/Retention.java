package com.ssafy.thorp.regimelink.domain.model;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

/**
 * 비교 묶음이 바뀌었을 때 후보가 얼마나 남았나 (data-model §3 {@code retention}).
 *
 * <p><b>기록 전용이며 화면에 내보내지 않는다</b>(FR-021). 응답 DTO에 이 값을 담는 자리를 만들지 않는다 — 내보내는 순간 사용자가 유지율을 성과처럼 읽게
 * 된다.
 */
public record Retention(
        BigDecimal ratio, List<YearMonth> kept, List<YearMonth> added, List<YearMonth> removed) {

    public Retention {
        kept = kept == null ? List.of() : List.copyOf(kept);
        added = added == null ? List.of() : List.copyOf(added);
        removed = removed == null ? List.of() : List.copyOf(removed);
    }
}
