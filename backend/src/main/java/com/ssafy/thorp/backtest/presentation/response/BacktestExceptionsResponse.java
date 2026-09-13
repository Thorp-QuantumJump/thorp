package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.backtest.application.result.BacktestExceptionsResult;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/exceptions} 응답. 계약: {@code docs/specs/backtest-api/README.md}
 * §2.7과 {@code examples/backtest-exceptions.json}.
 *
 * <p>{@code kind}는 개방 집합이다(ZERO_VOLATILITY, NO_SELECTION, ESTIMATION_FAILED, CONSTRAINT_VIOLATION,
 * HALT_DEFERRED, HALT_SUPERSEDED, WEIGHT_ROUNDING, SPLIT_FRACTION …). 포트폴리오 전체에 걸린 예외는 {@code
 * instrumentId} 키가 없다.
 */
public record BacktestExceptionsResponse(String backtestId, boolean partial, List<Item> items) {

    public record Item(
            LocalDate date,
            @JsonInclude(JsonInclude.Include.NON_NULL) String instrumentId,
            String kind,
            @JsonInclude(JsonInclude.Include.NON_NULL) String rule,
            @JsonInclude(JsonInclude.Include.NON_NULL) String action) {}

    public static BacktestExceptionsResponse from(BacktestExceptionsResult result) {
        return new BacktestExceptionsResponse(
                result.backtestId(),
                result.partial(),
                result.items().stream()
                        .map(
                                e ->
                                        new Item(
                                                e.date(),
                                                e.instrumentId(),
                                                e.kind(),
                                                e.rule(),
                                                e.action()))
                        .toList());
    }
}
