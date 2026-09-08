package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 엔진 실행 결과. FINISHED 이벤트 payload에 담겨 {@code backtest.events}로 들어온다.
 *
 * <p>계약 기준: README §4.3, 픽스처 {@code event-finished-*.json}. 실패({@code run.status=FAILED}) 시 {@code
 * run.error}가 채워지고 {@code performance}·{@code execution}은 null, {@code portfolioSeries}·{@code
 * trades}는 빈 리스트다. 존재하지 않는 성과를 0으로 채우지 않는다.
 *
 * @param performance null = 실패로 계산 불가
 * @param execution null = 실패로 계산 불가
 */
public record EngineOutput(
        RunResult run,
        PerformanceResult performance,
        List<PortfolioSnapshot> portfolioSeries,
        List<TradeResult> trades,
        ExecutionResult execution,
        Diagnostics diagnostics) {

    /**
     * @param error null = 성공
     */
    public record RunResult(
            String backtestId,
            RunStatus status,
            Instant startedAt,
            Instant finishedAt,
            EngineError error) {}

    /**
     * @param code README §4.3 코드 집합(INVALID_REQUEST, NO_MARKET_DATA, STRATEGY_ERROR, ENGINE_ERROR,
     *     INTERNAL_ERROR)
     */
    public record EngineError(String code, String message) {}

    /**
     * 계산 불가 규칙(설계 OUTPUT Spec): 변동성 0 → sharpeRatio만 null. 관측치 2개 미만 → volatility·sharpeRatio null.
     * CLOSED 거래 0건 → winRate null. initialEquity ≤ 0 → totalReturn·cagr null.
     *
     * @param tradeCount CLOSED 거래 수
     */
    public record PerformanceResult(
            BigDecimal initialEquity,
            BigDecimal finalEquity,
            String currency,
            BigDecimal totalReturn,
            BigDecimal cagr,
            BigDecimal maxDrawdown,
            BigDecimal sharpeRatio,
            BigDecimal volatility,
            int tradeCount,
            BigDecimal winRate) {}

    /**
     * @param positions 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 존재. null = 이 날은 구성 변경 없음
     */
    public record PortfolioSnapshot(
            LocalDate date,
            BigDecimal equity,
            BigDecimal cash,
            BigDecimal positionValue,
            BigDecimal dailyReturn,
            BigDecimal cumulativeReturn,
            BigDecimal drawdown,
            List<PositionSnapshot> positions) {}

    public record PositionSnapshot(
            String instrumentId,
            BigDecimal quantity,
            BigDecimal marketValue,
            BigDecimal weight,
            BigDecimal unrealizedPnl) {}

    /**
     * OPEN 거래는 exitTime·exitPrice·grossPnl·realizedPnl·returnRate가 null이고 commission·slippageCost는
     * 진입 측만 합산.
     *
     * @param entryPrice 계약상 실제 평균 체결가(슬리피지 반영). 현재 픽스처는 기준가(README §4.3 체결가 의미 참조)
     */
    public record TradeResult(
            String tradeId,
            String instrumentId,
            TradeSide side,
            TradeStatus status,
            Instant entryTime,
            Instant exitTime,
            BigDecimal entryPrice,
            BigDecimal exitPrice,
            BigDecimal quantity,
            BigDecimal grossPnl,
            BigDecimal commission,
            BigDecimal slippageCost,
            BigDecimal realizedPnl,
            BigDecimal returnRate) {}

    public record ExecutionResult(
            int orderCount,
            int fillCount,
            int rejectedOrderCount,
            BigDecimal totalCommission,
            BigDecimal totalSlippageCost) {}

    public record Diagnostics(List<Warning> warnings) {}

    /**
     * @param code 개방 집합(예 TRADING_HALTED, INSUFFICIENT_CASH). 오류가 아닌 경고
     */
    public record Warning(LocalDate date, String instrumentId, String code, String message) {}
}
