package com.ssafy.thorp.backtest.application.port.message;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 작업 메시지 본문(schemaVersion 2). 백엔드가 조립해 {@code backtest.jobs}로 발행하고 워커 엔진이 그대로 받는다.
 *
 * <p>계약 기준: {@code docs/specs/backtest-messaging/README.md} §4.1, 픽스처 {@code engine-input.json}.
 * JSON 키 = record 컴포넌트 이름(camelCase). 직렬화 규칙(BigDecimal 문자열, null 키 생략)은 infrastructure의 메시지
 * ObjectMapper가 담당하며 이 패키지는 프레임워크에 의존하지 않는다.
 *
 * <p>v2에서 바뀐 것: {@code run.backtestId}→{@code runId}(요청/실행 분리 전까지 값은 backtestId와 같다), {@code
 * universe.instrumentIds}→{@code items}(섹터 대응), {@code strategy}→{@code allocation}(규칙 버전·규칙 사본),
 * {@code commission}+{@code slippage}→{@code costProfile}(비용 조건 버전), {@code benchmark} 제거, {@code
 * evaluation}·{@code progress} 추가. 003(등록표)·001(자료 버전) 연동 전에는 {@code mappingVersion}·{@code
 * sectorId}·{@code rules}·{@code dataVersion}·{@code splitVersion}이 비어 있을 수 있다.
 */
public record EngineInput(
        RunSpec run,
        UniverseSpec universe,
        MarketDataSpec marketData,
        AllocationSpec allocation,
        PortfolioSpec portfolio,
        ExecutionSpec execution,
        EvaluationSpec evaluation,
        ProgressSpec progress) {

    /**
     * @param runId 실행 식별자. 이벤트 envelope의 {@code runId}와 같다
     * @param effectiveStart 워밍업 이후 실제 평가 시작일. null = 미산출(엔진이 자료로 정한다)
     * @param randomSeed null = 미지정
     * @param scenario 주 분석 또는 별도 시나리오(FR-016)
     * @param scenarioParams 시나리오 입력값. TARGET_RISK면 {@code targetVolatility}, FRESH_CASH_FROM이면
     *     {@code startDate}. 그 외 null
     * @param scenarioRules 시나리오 규칙 사본(등록표). TARGET_RISK에서만 채움
     */
    public record RunSpec(
            String runId,
            LocalDate start,
            LocalDate end,
            LocalDate effectiveStart,
            Integer randomSeed,
            Scenario scenario,
            Map<String, Object> scenarioParams,
            Map<String, Object> scenarioRules) {}

    /**
     * @param mappingVersion 섹터-ETF 대응표 버전. null = 대응표 없이 종목 직접 지정(003 연동 전)
     */
    public record UniverseSpec(String mappingVersion, List<UniverseItem> items) {}

    /**
     * @param sectorId null = 대응표 없이 종목 직접 지정
     * @param targetWeight FIXED_WEIGHT에서만 존재. 그 외 null
     */
    public record UniverseItem(String sectorId, String instrumentId, BigDecimal targetWeight) {}

    /**
     * @param dataVersion 001 자료 마감/버전. null = 미지정(공급자가 정한다)
     * @param fillPrice 체결에 쓰는 가격 계열. 계약 고정 RAW
     * @param signalPrice 신호·성과에 쓰는 가격 계열. 계약 고정 ADJUSTED
     * @param distributions 분배금 처리. 계약 고정 CASH_ON_PAY_DATE
     */
    public record MarketDataSpec(
            String datasetId,
            String dataVersion,
            Interval interval,
            Adjustment adjustment,
            PriceType priceType,
            PriceSource fillPrice,
            PriceSource signalPrice,
            DistributionPolicy distributions) {}

    /**
     * @param rulesVersion 전략 규칙 버전({@code strategy_rules.rules_version})
     * @param rules 규칙 명세 사본. null = 등록표 연동 전(엔진은 자체 기본값을 두지 않는다)
     * @param parameters 전략별 자유 형식. 정수는 정수, 소수는 숫자로 그대로 통과(문자열 변환 금지)
     */
    public record AllocationSpec(
            String strategyId,
            String rulesVersion,
            Map<String, Object> rules,
            Map<String, Object> parameters) {}

    public record PortfolioSpec(
            BigDecimal initialCash,
            String currency,
            PositionSizing positionSizing,
            boolean shortSelling,
            BigDecimal leverage,
            boolean integerLots) {}

    /**
     * @param maxPositionPct 조건부. null = 제한 없음
     */
    public record PositionSizing(PositionSizingType type, BigDecimal maxPositionPct) {}

    public record ExecutionSpec(
            SignalTiming signalTiming,
            ExecutionTiming executionTiming,
            List<OrderType> orderTypes,
            CostProfile costProfile,
            boolean partialFill,
            LiquiditySpec liquidity,
            TradingHaltPolicy tradingHalt,
            DelistingPolicy delisting) {}

    /**
     * 비용 조건 버전({@code cost_profile}). bps 값은 plain 문자열 decimal.
     *
     * @param provisional true = 팀 결정(PD-07) 전 임시값. 결과 versions에 그대로 남는다
     */
    public record CostProfile(
            String version,
            BigDecimal commissionBps,
            BigDecimal spreadBps,
            BigDecimal slippageBps,
            BigDecimal taxBps,
            boolean provisional) {}

    public record LiquiditySpec(LiquidityModel model) {}

    /**
     * @param splitVersion 평가 분할 버전. null = 미지정
     * @param warmupTradingDays 공통 워밍업 거래일 수. null = 엔진이 전략 관측 기간으로 정한다
     * @param liquidationAtEnd 종료 시 청산 결과도 산출할지
     */
    public record EvaluationSpec(
            String splitVersion, Integer warmupTradingDays, boolean liquidationAtEnd) {}

    /**
     * @param batchTradingDays PROGRESS 발행 묶음 크기(거래일)
     */
    public record ProgressSpec(int batchTradingDays) {}
}
