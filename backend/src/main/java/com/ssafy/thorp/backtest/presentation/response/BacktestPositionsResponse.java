package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/positions?date=} 응답. 계약: {@code
 * docs/specs/backtest-api/README.md} §2.4와 {@code examples/backtest-positions.json}.
 *
 * <p>{@code snapshotDate}가 {@code requestedDate}보다 앞인 것이 정상이다 — 평가액은 {@code snapshotDate}의 Close
 * 기준이므로 화면에 그 날짜를 표시해야 한다.
 */
public record BacktestPositionsResponse(
        String backtestId,
        boolean partial,
        LocalDate requestedDate,
        LocalDate snapshotDate,
        List<Position> positions) {

    public record Position(
            String instrumentId,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal quantity,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal marketValue,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal weight,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal unrealizedPnl) {}

    public static BacktestPositionsResponse from(BacktestPositionsResult result) {
        return new BacktestPositionsResponse(
                result.backtestId(),
                result.partial(),
                result.requestedDate(),
                result.snapshotDate(),
                result.positions().stream()
                        .map(
                                p ->
                                        new Position(
                                                p.instrumentId(),
                                                p.quantity(),
                                                p.marketValue(),
                                                p.weight(),
                                                p.unrealizedPnl()))
                        .toList());
    }
}
