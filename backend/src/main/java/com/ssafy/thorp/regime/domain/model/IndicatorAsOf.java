package com.ssafy.thorp.regime.domain.model;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 지표 하나의 자료 기준 시점 — 계산 당시의 <b>복사본</b> (spec FR-031, data-model §9).
 *
 * <p>나중에 008을 다시 읽으면 그 뒤 개정된 값이 나오므로, "이 결과가 언제 자료인가"는 결과와 함께 굳어 있어야 한다.
 *
 * <p>{@code revisedAt}이 있으면 그 값이 개정됐다는 뜻이다. <b>수집 시각은 여기 없다</b> — 배치는 값이 그대로여도 돌기 때문에 새 정보가 들어온 것처럼
 * 읽힌다(FR-031).
 */
public record IndicatorAsOf(
        String indicatorId, YearMonth targetMonth, LocalDate publishedAt, LocalDate revisedAt) {

    public boolean revised() {
        return revisedAt != null;
    }
}
