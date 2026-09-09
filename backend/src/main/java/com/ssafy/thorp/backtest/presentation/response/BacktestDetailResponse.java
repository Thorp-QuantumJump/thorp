package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /backtests/{backtestId}} 응답. 계약: {@code docs/specs/backtest-api/README.md} §2.1과
 * {@code examples/backtest-detail.json}.
 *
 * <p><b>decimal은 필드마다 {@link PlainBigDecimalSerializer}로 문자열이 된다.</b> 웹 ObjectMapper에 전역 등록하지 않는
 * 이유: 다른 도메인의 응답({@code MarketResponse.value})이 JSON 숫자에서 문자열로 바뀌어 그 API 계약이 깨진다.
 *
 * <p><b>전역 {@code NON_NULL}도 켜지 않는다.</b> 계약이 필드마다 다르다 — 거래의 {@code exitDate}는 명시적 {@code null}이고
 * 시계열의 {@code benchmarkReturn}만 키를 생략한다.
 *
 * @param summary {@code COMPLETED}가 아니면 null
 * @param error {@code FAILED}일 때만
 */
public record BacktestDetailResponse(
        String backtestId,
        BacktestStatus status,
        Config config,
        Summary summary,
        Error error,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * @param initialCash 저장 컬럼이 {@code NUMERIC(19,2)}라 요청보다 자릿수가 늘 수 있다. 값은 같다
     */
    public record Config(
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<String> instrumentIds,
            LocalDate start,
            LocalDate end,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal initialCash,
            String currency,
            String benchmarkId) {}

    /**
     * @param benchmark null = 결과에 벤치마크가 없음. 계산기 티켓 전까지는 항상 null이다
     */
    public record Summary(
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal initialEquity,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal finalEquity,
            String currency,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal totalReturn,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal cagr,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal maxDrawdown,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal sharpeRatio,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal volatility,
            int tradeCount,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal winRate,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal totalCommission,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal totalSlippageCost,
            Benchmark benchmark) {}

    public record Benchmark(
            String instrumentId,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal totalReturn) {}

    /** 엔진이 준 실패 코드다. HTTP {@code error.code}와 다른 집합이다. */
    public record Error(String code, String message) {}

    public static BacktestDetailResponse from(BacktestDetailResult result) {
        return new BacktestDetailResponse(
                result.backtestId(),
                result.status(),
                config(result.config()),
                summary(result.summary()),
                result.error() == null
                        ? null
                        : new Error(result.error().code(), result.error().message()),
                result.startedAt(),
                result.finishedAt());
    }

    private static Config config(BacktestDetailResult.Config c) {
        return new Config(
                c.strategyId(),
                c.strategyVersion(),
                c.parameters(),
                c.instrumentIds(),
                c.start(),
                c.end(),
                c.initialCash(),
                c.currency(),
                c.benchmarkId());
    }

    private static Summary summary(BacktestDetailResult.Summary s) {
        if (s == null) {
            return null;
        }
        return new Summary(
                s.initialEquity(),
                s.finalEquity(),
                s.currency(),
                s.totalReturn(),
                s.cagr(),
                s.maxDrawdown(),
                s.sharpeRatio(),
                s.volatility(),
                s.tradeCount(),
                s.winRate(),
                s.totalCommission(),
                s.totalSlippageCost(),
                s.benchmark() == null
                        ? null
                        : new Benchmark(s.benchmark().instrumentId(), s.benchmark().totalReturn()));
    }
}
