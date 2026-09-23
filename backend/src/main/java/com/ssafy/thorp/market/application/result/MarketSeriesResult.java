package com.ssafy.thorp.market.application.result;

import com.ssafy.thorp.market.domain.model.MarketObservation;
import com.ssafy.thorp.market.domain.vo.MarketCode;
import java.time.Instant;
import java.util.List;

/** 날짜 오름차순의 최근 관측값. {@code points}는 비어 있지 않다. */
public record MarketSeriesResult(
        MarketCode code, List<MarketObservation> points, Instant retrievedAt, boolean stale) {
    public MarketSeriesResult {
        points = List.copyOf(points);
        if (points.isEmpty()) throw new IllegalArgumentException("관측값이 필요합니다.");
    }
}
