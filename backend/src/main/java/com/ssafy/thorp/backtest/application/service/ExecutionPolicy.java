package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.message.Adjustment;
import com.ssafy.thorp.backtest.application.port.message.DelistingPolicy;
import com.ssafy.thorp.backtest.application.port.message.DistributionPolicy;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.ExecutionTiming;
import com.ssafy.thorp.backtest.application.port.message.Interval;
import com.ssafy.thorp.backtest.application.port.message.LiquidityModel;
import com.ssafy.thorp.backtest.application.port.message.OrderType;
import com.ssafy.thorp.backtest.application.port.message.PriceSource;
import com.ssafy.thorp.backtest.application.port.message.PriceType;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.port.message.SignalTiming;
import com.ssafy.thorp.backtest.application.port.message.TradingHaltPolicy;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

/**
 * 사용자가 고르지 않고 서버가 고정하는 실행 정책.
 *
 * <p>계약 기준: {@code docs/specs/backtest-messaging/README.md} §4.1과 픽스처 {@code
 * docs/specs/backtest-messaging/examples/engine-input.json}. 엔진 계약이 바뀌어 비용·수정주가·상장폐지 정책이 달라지면 이
 * 클래스와 README, 픽스처만 고치면 된다. {@code EngineInputFixtureTest}가 어긋남을 즉시 잡는다.
 *
 * <p>비용 조건(PD-07)·전략 규칙 등록표(spec 004 T003)가 생기기 전까지의 임시값이다. {@link #COST_PROFILE}은 ADR-0004의 데모
 * 값(수수료 0, 슬리피지 5bps)을 재사용하며 {@code provisional=true}로 표시한다 — 새로 정한 값이 아니다.
 */
public final class ExecutionPolicy {

    /** 전략 규칙 등록표가 생기기 전까지 쓰는 고정 규칙 버전. 결과 재현과 설정 조회에 필요하다. */
    public static final String STRATEGY_VERSION = "1.0.0";

    /** 결과 재현을 위한 기본 시드. */
    public static final int DEFAULT_RANDOM_SEED = 42;

    /** 주 분석. 별도 시나리오(FR-016)는 요청 API v2(T005)가 지정한다. */
    public static final Scenario SCENARIO = Scenario.FULL_PERIOD;

    public static final EngineInput.MarketDataSpec MARKET_DATA =
            new EngineInput.MarketDataSpec(
                    "KRX_DAILY_V1",
                    null, // dataVersion: 001 자료 계층 연동 전
                    Interval.DAY_1,
                    Adjustment.SPLIT_ADJUSTED,
                    PriceType.LAST,
                    PriceSource.RAW,
                    PriceSource.ADJUSTED,
                    DistributionPolicy.CASH_ON_PAY_DATE);

    /** 체결 시각을 거래일로 바꾸는 거래소 현지 시간대. 모든 종목이 XKRX 다(ADR-0002 D4). */
    public static final ZoneId EXCHANGE_ZONE = ZoneId.of("Asia/Seoul");

    public static final String CURRENCY = "KRW";
    public static final boolean SHORT_SELLING = false;
    public static final BigDecimal LEVERAGE = new BigDecimal("1.0");
    public static final boolean INTEGER_LOTS = true;

    /** 비용 조건 임시 버전(PD-07 확정 전). 값은 ADR-0004 데모 값 그대로. */
    public static final EngineInput.CostProfile COST_PROFILE =
            new EngineInput.CostProfile(
                    "PROVISIONAL-DEMO-1",
                    new BigDecimal("0"),
                    new BigDecimal("0"),
                    new BigDecimal("5"),
                    new BigDecimal("0"),
                    true);

    public static final EngineInput.ExecutionSpec EXECUTION =
            new EngineInput.ExecutionSpec(
                    SignalTiming.BAR_CLOSE,
                    ExecutionTiming.NEXT_BAR_OPEN,
                    List.of(OrderType.MARKET),
                    COST_PROFILE,
                    false,
                    new EngineInput.LiquiditySpec(LiquidityModel.UNLIMITED),
                    TradingHaltPolicy.DEFER_TO_NEXT_TRADABLE,
                    DelistingPolicy.CAUSE_BASED);

    /** 평가 분할·공통 워밍업은 003·001 연동 뒤 요청마다 계산한다. 청산 결과 산출은 다종목 엔진(T006)부터. */
    public static final EngineInput.EvaluationSpec EVALUATION =
            new EngineInput.EvaluationSpec(null, null, false);

    public static final EngineInput.ProgressSpec PROGRESS = new EngineInput.ProgressSpec(20);

    private ExecutionPolicy() {}
}
