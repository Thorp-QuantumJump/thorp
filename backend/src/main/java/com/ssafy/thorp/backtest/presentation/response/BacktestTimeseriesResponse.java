package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /backtests/{backtestId}/timeseries} 응답. 계약: {@code docs/specs/backtest-api/README.md}
 * §2.2와 {@code examples/backtest-timeseries.json}(발췌).
 */
public record BacktestTimeseriesResponse(String backtestId, List<Point> points) {

    /**
     * {@code benchmarkReturn}만 <b>키를 생략한다</b>({@code @JsonInclude}를 필드에 붙인다). 설계가 "벤치마크를 지정하지 않았으면
     * 필드 자체를 생략"이라고 정했다. 거래의 {@code exitDate}는 반대로 명시적 {@code null}이므로 전역 {@code NON_NULL}을 켤 수 없다.
     */
    public record Point(
            LocalDate date,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal equity,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal cumulativeReturn,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal drawdown,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonSerialize(using = PlainBigDecimalSerializer.class)
                    BigDecimal benchmarkReturn) {}

    public static BacktestTimeseriesResponse from(BacktestTimeseriesResult result) {
        return new BacktestTimeseriesResponse(
                result.backtestId(),
                result.points().stream()
                        .map(
                                p ->
                                        new Point(
                                                p.date(),
                                                p.equity(),
                                                p.cumulativeReturn(),
                                                p.drawdown(),
                                                p.benchmarkReturn()))
                        .toList());
    }
}
