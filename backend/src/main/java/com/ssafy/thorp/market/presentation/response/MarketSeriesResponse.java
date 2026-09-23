package com.ssafy.thorp.market.presentation.response;

import com.ssafy.thorp.market.application.result.MarketSeriesResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 최근 흐름. {@code points}는 날짜 오름차순이다. */
public record MarketSeriesResponse(
        String code,
        String name,
        String unit,
        String frequency,
        List<Point> points,
        Instant retrievedAt,
        MarketResponse.Source source,
        String status,
        String statusMessage) {
    public record Point(String date, BigDecimal value) {}

    public static MarketSeriesResponse from(MarketSeriesResult result) {
        var code = result.code();
        var points = result.points();
        return new MarketSeriesResponse(
                code.name(),
                code.displayName(),
                points.get(points.size() - 1).unit(),
                code.frequency(),
                points.stream().map(p -> new Point(p.observationDate(), p.value())).toList(),
                result.retrievedAt(),
                new MarketResponse.Source(code.provider(), code.datasetName()),
                result.stale() ? "stale" : "ok",
                result.stale() ? "최근 흐름 갱신에 실패하여 이전 수집값을 표시합니다." : null);
    }
}
