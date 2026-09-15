package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.result.ExecutionMetricSummary;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /internal/execution-metrics} 응답. 계약은 {@code docs/specs/backtest-api/README.md} §1.10.
 *
 * <p>비율·분위수는 다른 응답과 같이 문자열 decimal 이다. 표본이 없는 분위수는 {@code null} 그대로 — 0 으로 채우지 않는다(원칙 VI).
 *
 * @param from 조회 구간 시작(포함). 주지 않았으면 null = 전체
 * @param to 조회 구간 끝(미포함). 주지 않았으면 null = 전체
 */
public record ExecutionMetricsResponse(Instant from, Instant to, List<Row> rows) {

    public static ExecutionMetricsResponse of(
            Instant from, Instant to, List<ExecutionMetricSummary> summaries) {
        return new ExecutionMetricsResponse(from, to, summaries.stream().map(Row::of).toList());
    }

    public record Row(
            ExecutionType executionType,
            long count,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal failRate,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal cancelRate,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal autoRetryRate,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal waitP50Ms,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal waitP95Ms,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal waitP99Ms,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal runP50Ms,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal runP95Ms,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal peakRssP95Mb,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal flexServedRate,
            long reuseCount,
            long limitHitCount) {

        static Row of(ExecutionMetricSummary s) {
            return new Row(
                    s.executionType(),
                    s.count(),
                    s.failRate(),
                    s.cancelRate(),
                    s.autoRetryRate(),
                    s.waitP50Ms(),
                    s.waitP95Ms(),
                    s.waitP99Ms(),
                    s.runP50Ms(),
                    s.runP95Ms(),
                    s.peakRssP95Mb(),
                    s.flexServedRate(),
                    s.reuseCount(),
                    s.limitHitCount());
        }
    }
}
