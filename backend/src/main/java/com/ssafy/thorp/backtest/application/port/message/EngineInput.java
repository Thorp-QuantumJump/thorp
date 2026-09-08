package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 작업 메시지 본문. 백엔드가 조립해 {@code backtest.jobs}로 발행하고 워커 엔진이 그대로 받는다.
 *
 * <p>계약 기준: {@code docs/specs/backtest-messaging/README.md} §4.1, 픽스처 {@code engine-input.json}.
 * JSON 키 = record 컴포넌트 이름(camelCase). 직렬화 규칙(BigDecimal 문자열, null 키 생략)은 infrastructure의 메시지
 * ObjectMapper가 담당하며 이 패키지는 프레임워크에 의존하지 않는다.
 *
 * @param benchmark null = 벤치마크 미지정
 */
public record EngineInput(
        RunSpec run,
        UniverseSpec universe,
        MarketDataSpec marketData,
        StrategySpec strategy,
        PortfolioSpec portfolio,
        ExecutionSpec execution,
        BenchmarkSpec benchmark) {

    /**
     * @param randomSeed null = 미지정
     */
    public record RunSpec(String backtestId, LocalDate start, LocalDate end, Integer randomSeed) {}

    public record UniverseSpec(List<String> instrumentIds) {}

    public record MarketDataSpec(
            String datasetId, Interval interval, Adjustment adjustment, PriceType priceType) {}

    /**
     * @param parameters 전략별 자유 형식. 정수는 정수, 소수는 숫자로 그대로 통과(문자열 변환 금지)
     */
    public record StrategySpec(String strategyId, String version, Map<String, Object> parameters) {}

    public record PortfolioSpec(
            BigDecimal initialCash,
            String currency,
            PositionSizing positionSizing,
            boolean shortSelling,
            BigDecimal leverage) {}

    /**
     * @param maxPositionPct 조건부. null = 제한 없음
     */
    public record PositionSizing(PositionSizingType type, BigDecimal maxPositionPct) {}

    public record ExecutionSpec(
            SignalTiming signalTiming,
            ExecutionTiming executionTiming,
            List<OrderType> orderTypes,
            CommissionSpec commission,
            SlippageSpec slippage,
            boolean partialFill,
            LiquiditySpec liquidity,
            TradingHaltPolicy tradingHalt,
            DelistingPolicy delisting) {}

    /**
     * @param broker 증권사 코드(예 KIWOOM). model=BROKER일 때 필수
     * @param version 요율표 버전(예 2026-09)
     */
    public record CommissionSpec(CommissionModel model, String broker, String version) {}

    /**
     * @param bps model=FIXED_BPS일 때 필수. null = 해당 없음
     */
    public record SlippageSpec(SlippageModel model, Integer bps) {}

    public record LiquiditySpec(LiquidityModel model) {}

    public record BenchmarkSpec(String instrumentId) {}
}
