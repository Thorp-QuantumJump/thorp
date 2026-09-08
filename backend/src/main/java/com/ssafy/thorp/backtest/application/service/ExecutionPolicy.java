package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.message.Adjustment;
import com.ssafy.thorp.backtest.application.port.message.CommissionModel;
import com.ssafy.thorp.backtest.application.port.message.DelistingPolicy;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.ExecutionTiming;
import com.ssafy.thorp.backtest.application.port.message.Interval;
import com.ssafy.thorp.backtest.application.port.message.LiquidityModel;
import com.ssafy.thorp.backtest.application.port.message.OrderType;
import com.ssafy.thorp.backtest.application.port.message.PriceType;
import com.ssafy.thorp.backtest.application.port.message.SignalTiming;
import com.ssafy.thorp.backtest.application.port.message.SlippageModel;
import com.ssafy.thorp.backtest.application.port.message.TradingHaltPolicy;
import java.math.BigDecimal;
import java.util.List;

/**
 * 사용자가 고르지 않고 서버가 고정하는 실행 정책.
 *
 * <p>계약 기준: {@code docs/specs/backtest-messaging/README.md} §4.1과 픽스처 {@code
 * docs/specs/backtest-messaging/examples/engine-input.json}. 엔진 계약이 바뀌어 수수료·수정주가·상장폐지 정책이 달라지면 이
 * 클래스와 README, 픽스처만 고치면 된다. {@code EngineInputFixtureTest}가 어긋남을 즉시 잡는다.
 */
public final class ExecutionPolicy {

    /** 전략 카탈로그가 생기기 전까지 쓰는 고정 버전. 결과 재현과 설정 조회에 필요하다. */
    public static final String STRATEGY_VERSION = "1.0.0";

    /** 결과 재현을 위한 기본 시드. */
    public static final int DEFAULT_RANDOM_SEED = 42;

    public static final EngineInput.MarketDataSpec MARKET_DATA =
            new EngineInput.MarketDataSpec(
                    "KRX_DAILY_V1", Interval.DAY_1, Adjustment.SPLIT_ADJUSTED, PriceType.LAST);

    public static final String CURRENCY = "KRW";
    public static final boolean SHORT_SELLING = false;
    public static final BigDecimal LEVERAGE = new BigDecimal("1.0");

    public static final EngineInput.ExecutionSpec EXECUTION =
            new EngineInput.ExecutionSpec(
                    SignalTiming.BAR_CLOSE,
                    ExecutionTiming.NEXT_BAR_OPEN,
                    List.of(OrderType.MARKET),
                    new EngineInput.CommissionSpec(CommissionModel.BROKER, "KIWOOM", "2026-09"),
                    new EngineInput.SlippageSpec(SlippageModel.FIXED_BPS, 5),
                    false,
                    new EngineInput.LiquiditySpec(LiquidityModel.UNLIMITED),
                    TradingHaltPolicy.WAIT_UNTIL_RESUME,
                    DelistingPolicy.CAUSE_BASED);

    private ExecutionPolicy() {}
}
