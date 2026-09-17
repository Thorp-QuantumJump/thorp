package com.ssafy.thorp.regime.domain.model;

import java.time.YearMonth;
import java.util.List;

/**
 * 지표 하나의 비교 시점과 빠진 달 (spec FR-001, data-model §2.2).
 *
 * <p><b>지표마다 따로 정한다.</b> 같은 분야 안에서도 결측 월이 다를 수 있고, 멀쩡한 지표의 자료를 다른 지표의 구멍 때문에 버리지 않는다.
 *
 * <p>{@code omitted}가 비어 있지 않으면 화면이 <b>어느 지표의 어느 달인지</b>를 표기한다(spec FR-031, SC-007) — 남은 시점으로 계산했다는
 * 사실이 보이지 않으면 세 달로 비교한 결과와 구분되지 않는다.
 */
public record ComparisonMonths(
        String indicatorId, List<YearMonth> months, List<YearMonth> omitted) {

    public boolean partial() {
        return !omitted.isEmpty();
    }
}
