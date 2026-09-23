package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /backtests/{backtestId}/weights} 응답. 계약: {@code docs/specs/backtest-api/README.md}
 * §2.6과 {@code examples/backtest-weights.json}.
 *
 * <p>{@code riskScale}·{@code estimatedVolatility}는 {@code TARGET_RISK} 시나리오에서만 있고 그 밖에는 <b>키를
 * 생략한다</b>(PD-18). 비중 맵의 값도 다른 소수처럼 문자열이다.
 */
public record BacktestWeightsResponse(
        String backtestId, boolean partial, WeightsSource weightsSource, List<Point> points) {

    public record Point(
            LocalDate date,
            @JsonSerialize(contentUsing = PlainBigDecimalSerializer.class)
                    Map<String, BigDecimal> target,
            @JsonSerialize(contentUsing = PlainBigDecimalSerializer.class)
                    Map<String, BigDecimal> actual,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonSerialize(using = PlainBigDecimalSerializer.class)
                    BigDecimal weightError,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonSerialize(using = PlainBigDecimalSerializer.class)
                    BigDecimal riskScale,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonSerialize(using = PlainBigDecimalSerializer.class)
                    BigDecimal estimatedVolatility) {}

    public static BacktestWeightsResponse from(BacktestWeightsResult result) {
        return new BacktestWeightsResponse(
                result.backtestId(),
                result.partial(),
                result.weightsSource(),
                result.points().stream()
                        .map(
                                p ->
                                        new Point(
                                                p.date(),
                                                p.target(),
                                                p.actual(),
                                                p.weightError(),
                                                p.riskScale(),
                                                p.estimatedVolatility()))
                        .toList());
    }
}
