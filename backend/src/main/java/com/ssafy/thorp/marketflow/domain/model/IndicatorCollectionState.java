package com.ssafy.thorp.marketflow.domain.model;

import java.time.Instant;

/** 지표 단위 수집 결과. 실패·예시 여부를 분야 전체로 뭉개지 않는다. */
public record IndicatorCollectionState(
        String indicatorId, Instant lastUpdatedAt, boolean successful, boolean exampleData) {}
