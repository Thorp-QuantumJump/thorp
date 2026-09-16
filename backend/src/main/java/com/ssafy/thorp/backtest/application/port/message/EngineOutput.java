package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 엔진 실행 결과(schemaVersion 2). FINISHED 이벤트 payload에 담겨 {@code backtest.events}로 들어온다.
 *
 * <p>계약 기준: README §4.3, 픽스처 {@code event-finished-*.json}. 실패({@code run.status=FAILED}) 시 {@code
 * run.error}가 채워지고 {@code performance}·{@code execution}은 null, {@code portfolioSeries}·{@code
 * trades}는 빈 리스트다. 취소({@code CANCELLED})는 {@code run.partial=true}이고 {@code lastTradingDate}까지의 부분
 * 결과를 담는다. 존재하지 않는 성과를 0으로 채우지 않는다.
 *
 * <p>v2 추가 항목({@code weightsSeries}·{@code costs}·{@code liquidation}·{@code analytics}·{@code
 * exceptions}·{@code versions} 등)은 모두 nullable이다. 단일 종목 데모 엔진은 채우지 않고, 다종목 리밸런싱 엔진(spec 004
 * T006·T008)이 채운다.
 *
 * @param performance null = 실패로 계산 불가
 * @param execution null = 실패로 계산 불가
 */
public record EngineOutput(
        RunResult run,
        PerformanceResult performance,
        List<PortfolioSnapshot> portfolioSeries,
        List<WeightsPoint> weightsSeries,
        WeightsSource weightsSource,
        List<TradeResult> trades,
        ExecutionResult execution,
        Costs costs,
        Liquidation liquidation,
        Analytics analytics,
        List<ExceptionRecord> exceptions,
        Versions versions,
        Diagnostics diagnostics) {

    /**
     * @param runId envelope의 {@code runId}와 같다
     * @param partial true = 취소·한도 도달로 끊긴 부분 결과. null = false
     * @param lastTradingDate 처리한 마지막 거래일. 부분 결과의 완료 범위
     * @param effectiveStart 워밍업 이후 실제 평가 시작일
     * @param error null = 성공
     */
    public record RunResult(
            String runId,
            RunStatus status,
            Instant startedAt,
            Instant finishedAt,
            Boolean partial,
            LocalDate lastTradingDate,
            LocalDate effectiveStart,
            LocalDate effectiveEnd,
            EngineError error) {}

    /**
     * @param code README §4.3 코드 집합(INVALID_REQUEST, DATA_INSUFFICIENT, CONSTRAINT_VIOLATION,
     *     ESTIMATION_FAILED, ENGINE_ERROR, INTERNAL_ERROR)
     */
    public record EngineError(String code, String message) {}

    /**
     * 계산 불가 규칙(설계 OUTPUT Spec): 변동성 0 → sharpeRatio만 null. 관측치 2개 미만 → volatility·sharpeRatio null.
     * CLOSED 거래 0건 → winRate null. initialEquity ≤ 0 → totalReturn·cagr null.
     *
     * @param netTotalReturn 비용 차감 후 전체 수익률(핵심 숫자 1). null = 미산출
     * @param totalTradingCost 거래비용 합계(핵심 숫자 3). null = 미산출
     * @param tradeCount CLOSED 거래 수
     */
    public record PerformanceResult(
            BigDecimal initialEquity,
            BigDecimal finalEquity,
            String currency,
            BigDecimal totalReturn,
            BigDecimal netTotalReturn,
            BigDecimal cagr,
            BigDecimal maxDrawdown,
            BigDecimal sharpeRatio,
            BigDecimal volatility,
            int tradeCount,
            BigDecimal winRate,
            BigDecimal totalTradingCost) {}

    /**
     * @param positions 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 존재. null = 이 날은 구성 변경 없음
     * @param cashFlows 분배금 등 현금 흐름. null = 없음
     */
    public record PortfolioSnapshot(
            LocalDate date,
            BigDecimal equity,
            BigDecimal cash,
            BigDecimal positionValue,
            BigDecimal dailyReturn,
            BigDecimal cumulativeReturn,
            BigDecimal drawdown,
            List<PositionSnapshot> positions,
            List<CashFlow> cashFlows) {}

    public record PositionSnapshot(
            String instrumentId,
            BigDecimal quantity,
            BigDecimal marketValue,
            BigDecimal weight,
            BigDecimal unrealizedPnl) {}

    /**
     * @param type 현재 DISTRIBUTION만. 개방 집합
     */
    public record CashFlow(String type, String instrumentId, BigDecimal amount) {}

    /**
     * 리밸런싱일별 목표·실제 비중(FR-027 보유 비중 시계열).
     *
     * @param target 종목별 목표 비중
     * @param actual 정수 수량 체결 후 실제 비중
     * @param weightError 목표와 실제의 차이 합계
     * @param riskScale 목표 위험 맞춤 조절 비율(PD-18). 시나리오가 아니면 null
     * @param estimatedVolatility 목표 위험 맞춤에 쓴 추정 변동성. 시나리오가 아니면 null
     */
    public record WeightsPoint(
            LocalDate date,
            Map<String, BigDecimal> target,
            Map<String, BigDecimal> actual,
            BigDecimal weightError,
            BigDecimal riskScale,
            BigDecimal estimatedVolatility) {}

    /**
     * OPEN 거래는 exitTime·exitPrice·grossPnl·realizedPnl·returnRate가 null이고 commission·slippageCost는
     * 진입 측만 합산.
     *
     * @param entryPrice 계약상 실제 평균 체결가(슬리피지 반영). 현재 픽스처는 기준가(README §4.3 체결가 의미 참조)
     * @param reason 거래 사유. null = 미기록(단일 종목 데모)
     * @param plannedDate 거래 불가로 미룬 경우 원래 체결 예정일. null = 미룸 없음
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
            BigDecimal returnRate,
            TradeReason reason,
            LocalDate plannedDate) {}

    public record ExecutionResult(
            int orderCount,
            int fillCount,
            int rejectedOrderCount,
            BigDecimal totalCommission,
            BigDecimal totalSlippageCost) {}

    /** 비용 합계(FR-027). */
    public record Costs(
            BigDecimal commission,
            BigDecimal spread,
            BigDecimal slippage,
            BigDecimal tax,
            BigDecimal total) {}

    /** 종료 시 시가평가 결과와 청산 비용 포함 결과의 구분(FR-017). */
    public record Liquidation(MarkToMarket markToMarket, Liquidated liquidated) {}

    public record MarkToMarket(BigDecimal finalEquity, BigDecimal totalReturn) {}

    public record Liquidated(
            BigDecimal finalEquity, BigDecimal totalReturn, BigDecimal liquidationCost) {}

    /**
     * 파생 지표(FR-027). 계산 방법은 {@code method}에 적는다.
     *
     * @param recoveryDays 최대 낙폭 저점에서 이전 고점 회복까지 거래일. null = 미회복
     */
    public record Analytics(
            Integer recoveryDays,
            BigDecimal turnover,
            Map<String, Map<String, BigDecimal>> correlationMatrix,
            Map<String, BigDecimal> riskContribution,
            Map<String, BigDecimal> lossContribution,
            BigDecimal weightError,
            String method) {}

    /**
     * 예외 처리 기록(FR-020·FR-030).
     *
     * @param kind ZERO_VOLATILITY, NO_SELECTION, ESTIMATION_FAILED, CONSTRAINT_VIOLATION,
     *     HALT_DEFERRED, WEIGHT_ROUNDING 등 개방 집합
     * @param instrumentId null = 포트폴리오 전체에 걸린 예외
     */
    public record ExceptionRecord(
            LocalDate date, String instrumentId, String kind, String rule, String action) {}

    /**
     * 참조 버전(FR-028, 원칙 VIII).
     *
     * @param mapping 섹터-ETF 대응표 버전. null = 없음
     * @param scenarioRules 시나리오 규칙 버전. null = 주 분석
     * @param evaluationSplit 요청의 {@code evaluation.splitVersion} 사본. null = 미지정. 결과 행이 실행 행 없이도 재현
     *     기준을 다 갖도록 워커가 되돌려 준다(data-model {@code backtest_result.versions} = {@code run.versions}
     *     사본, T018)
     */
    public record Versions(
            String mapping,
            RuleVersion rules,
            String scenarioRules,
            RuleVersion costProfile,
            String data,
            String engine,
            String evaluationSplit,
            String worker) {}

    /**
     * @param provisional true = 팀 결정 전 임시값
     */
    public record RuleVersion(String version, Boolean provisional) {}

    public record Diagnostics(List<Warning> warnings) {}

    /**
     * @param code 개방 집합(예 TRADING_HALTED, INSUFFICIENT_CASH). 오류가 아닌 경고
     */
    public record Warning(LocalDate date, String instrumentId, String code, String message) {}
}
