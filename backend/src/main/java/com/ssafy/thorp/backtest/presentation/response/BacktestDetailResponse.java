package com.ssafy.thorp.backtest.presentation.response;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@code GET /backtests/{backtestId}} 응답(spec 004 contracts/rest-api.md).
 *
 * <p>백테스트가 실패해도 200이다 — 조회 자체는 성공했고 실패는 {@code status}·{@code failure}로 전달한다. 프론트 폴링은 이 리소스로 한다.
 *
 * <p>소수는 필드마다 문자열로 직렬화한다. <b>웹 ObjectMapper에 전역 등록하지 않는다</b> — 다른 도메인의 숫자 계약이 함께 바뀐다.
 */
public record BacktestDetailResponse(
        String backtestId,
        RunStatus status,
        ResultSource resultSource,
        boolean partial,
        boolean cancelRequested,
        Progress progress,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Long queueWaitSeconds,
        Map<String, Object> config,
        Map<String, Object> versions,
        Summary summary,
        String summaryUnavailable,
        Failure failure) {

    public record Progress(
            ProgressStage stage,
            LocalDate lastTradingDate,
            Integer completedTradingDays,
            Integer totalTradingDays) {}

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
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal netTotalReturn,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal totalTradingCost,
            Costs costs,
            Liquidation liquidation,
            Analytics analytics,
            int exceptionCount) {}

    /** 비용 네 갈래와 합(FR-027). */
    public record Costs(
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal commission,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal spread,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal slippage,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal tax,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal total) {}

    /** 종료 시 시가평가와 청산 가정치(FR-017). */
    public record Liquidation(MarkToMarket markToMarket, Liquidated liquidated) {}

    public record MarkToMarket(
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal finalEquity,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal totalReturn) {}

    public record Liquidated(
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal finalEquity,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal totalReturn,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal liquidationCost) {}

    /** 요약에 실리는 파생 지표 둘. {@code recoveryDays} null = 미회복. */
    public record Analytics(
            Integer recoveryDays,
            @JsonSerialize(using = PlainBigDecimalSerializer.class) BigDecimal turnover) {}

    public record Failure(String code, String message) {}

    public static BacktestDetailResponse from(BacktestDetailResult result) {
        return new BacktestDetailResponse(
                result.backtestId(),
                result.status(),
                result.resultSource(),
                result.partial(),
                result.cancelRequested(),
                result.progress() == null
                        ? null
                        : new Progress(
                                result.progress().stage(),
                                result.progress().lastTradingDate(),
                                result.progress().completedTradingDays(),
                                result.progress().totalTradingDays()),
                result.queuedAt(),
                result.startedAt(),
                result.finishedAt(),
                result.queueWaitSeconds(),
                result.config(),
                result.versions(),
                result.summary() == null ? null : summary(result.summary()),
                result.summaryUnavailable(),
                result.failure() == null
                        ? null
                        : new Failure(result.failure().code(), result.failure().message()));
    }

    /**
     * 요약 변환. 공개 열람 응답(spec 005 {@code result})이 같은 모양의 요약을 내보내려고 함께 쓴다 — 두 벌로 두면 필드가 늘 때 한쪽만 바뀐다.
     */
    public static Summary summary(BacktestDetailResult.Summary source) {
        return new Summary(
                source.initialEquity(),
                source.finalEquity(),
                source.currency(),
                source.totalReturn(),
                source.cagr(),
                source.maxDrawdown(),
                source.sharpeRatio(),
                source.volatility(),
                source.tradeCount(),
                source.winRate(),
                source.totalCommission(),
                source.totalSlippageCost(),
                source.netTotalReturn(),
                source.totalTradingCost(),
                source.costs() == null
                        ? null
                        : new Costs(
                                source.costs().commission(),
                                source.costs().spread(),
                                source.costs().slippage(),
                                source.costs().tax(),
                                source.costs().total()),
                source.liquidation() == null
                        ? null
                        : new Liquidation(
                                new MarkToMarket(
                                        source.liquidation().markToMarket().finalEquity(),
                                        source.liquidation().markToMarket().totalReturn()),
                                new Liquidated(
                                        source.liquidation().liquidated().finalEquity(),
                                        source.liquidation().liquidated().totalReturn(),
                                        source.liquidation().liquidated().liquidationCost())),
                source.analytics() == null
                        ? null
                        : new Analytics(
                                source.analytics().recoveryDays(), source.analytics().turnover()),
                source.exceptionCount());
    }
}
