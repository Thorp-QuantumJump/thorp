package com.ssafy.thorp.regime.domain.model;

import java.util.List;

/** 결과가 반영한 자료의 기준 시점 묶음 (spec FR-031). 계산 불가 응답에도 들어간다(SC-007). */
public record DataAsOf(List<IndicatorAsOf> indicators, List<NewerRelease> newerReleases) {

    public List<IndicatorAsOf> revisions() {
        return indicators.stream().filter(IndicatorAsOf::revised).toList();
    }
}
