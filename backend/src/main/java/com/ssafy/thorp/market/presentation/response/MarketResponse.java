package com.ssafy.thorp.market.presentation.response;

import com.ssafy.thorp.market.application.result.MarketResult;
import java.math.BigDecimal;
import java.time.Instant;

public record MarketResponse(
        String code,
        String name,
        String group,
        BigDecimal value,
        String unit,
        String frequency,
        String observationDate,
        Instant retrievedAt,
        Source source,
        String status,
        String statusMessage) {
    public record Source(String provider, String datasetName) {}

    public static MarketResponse from(MarketResult result) {
        var code = result.code();
        var value = result.observation();
        return new MarketResponse(
                code.name(),
                code.displayName(),
                code.group(),
                value.value(),
                value.unit(),
                code.frequency(),
                value.observationDate(),
                result.retrievedAt(),
                new Source(code.provider(), code.datasetName()),
                result.stale() ? "stale" : "ok",
                result.stale() ? "최신값 갱신에 실패하여 이전 수집값을 표시합니다." : null);
    }
}
