package com.ssafy.thorp.regime.domain.model;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 표시 후보 한 건 (data-model §4).
 *
 * <p>{@code rank}는 <b>표시 순서</b>다 — {@link RegimeSnapshot#rankedCandidates()}의 원본 순위는 ±6개월 건너뛰기를
 * 적용하지 않은 값이라 둘이 다를 수 있고, 화면이 세는 것은 보이는 순서다.
 *
 * <p>{@code eraPositions}가 비면 그 사례는 시대별 보조 설명을 하나도 만들지 못했다는 뜻이다. 만들지 못한 지표는 <b>키가 없다</b> — 0으로 채우지
 * 않는다(SC-011).
 */
public record RegimeCase(
        int rank,
        YearMonth endMonth,
        CaseDistance distance,
        Map<String, CaseIndicatorValue> indicatorValues,
        CaseExplanation explanation,
        Map<String, EraPosition> eraPositions) {

    /** 등록표 순서가 아니라 <b>거리 기여가 큰 순서</b>로 훑을 때 쓰는 목록. 상세 화면의 지표 나열에 쓴다. */
    public List<CaseIndicatorValue> indicators() {
        return List.copyOf(indicatorValues.values());
    }
}
