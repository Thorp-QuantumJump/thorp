"""작업 메시지 본문 EngineInput. 기준: README §4.1, 픽스처 engine-input.json."""

from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Any

from worker.contracts.base import ContractModel


class Interval(StrEnum):
    DAY_1 = "1D"


class Adjustment(StrEnum):
    SPLIT_ADJUSTED = "SPLIT_ADJUSTED"


class PriceType(StrEnum):
    LAST = "LAST"


class PositionSizingType(StrEnum):
    STRATEGY_DEFINED = "STRATEGY_DEFINED"


class SignalTiming(StrEnum):
    BAR_CLOSE = "BAR_CLOSE"


class ExecutionTiming(StrEnum):
    NEXT_BAR_OPEN = "NEXT_BAR_OPEN"


class OrderType(StrEnum):
    MARKET = "MARKET"


class CommissionModel(StrEnum):
    BROKER = "BROKER"


class SlippageModel(StrEnum):
    FIXED_BPS = "FIXED_BPS"


class LiquidityModel(StrEnum):
    UNLIMITED = "UNLIMITED"


class TradingHaltPolicy(StrEnum):
    WAIT_UNTIL_RESUME = "WAIT_UNTIL_RESUME"


class DelistingPolicy(StrEnum):
    CAUSE_BASED = "CAUSE_BASED"


class RunSpec(ContractModel):
    backtest_id: str
    start: date
    end: date
    random_seed: int | None = None  # None = 미지정


class UniverseSpec(ContractModel):
    instrument_ids: list[str]  # "{6자리코드}.XKRX"


class MarketDataSpec(ContractModel):
    dataset_id: str
    interval: Interval
    adjustment: Adjustment
    price_type: PriceType


class StrategySpec(ContractModel):
    strategy_id: str
    version: str
    # 전략별 자유 형식. 정수는 int, 소수는 float로 그대로 통과 (문자열 변환 금지)
    parameters: dict[str, Any]


class PositionSizing(ContractModel):
    type: PositionSizingType
    max_position_pct: Decimal | None = None  # None = 제한 없음


class PortfolioSpec(ContractModel):
    initial_cash: Decimal
    currency: str
    position_sizing: PositionSizing
    short_selling: bool
    leverage: Decimal


class CommissionSpec(ContractModel):
    model: CommissionModel
    broker: str | None = None  # model=BROKER일 때 필수 (예 KIWOOM)
    version: str | None = None  # 요율표 버전 (예 2026-09)


class SlippageSpec(ContractModel):
    model: SlippageModel
    bps: int | None = None  # model=FIXED_BPS일 때 필수


class LiquiditySpec(ContractModel):
    model: LiquidityModel


class ExecutionSpec(ContractModel):
    signal_timing: SignalTiming
    execution_timing: ExecutionTiming
    order_types: list[OrderType]
    commission: CommissionSpec
    slippage: SlippageSpec
    partial_fill: bool
    liquidity: LiquiditySpec
    trading_halt: TradingHaltPolicy
    delisting: DelistingPolicy


class BenchmarkSpec(ContractModel):
    instrument_id: str


class EngineInput(ContractModel):
    """백엔드가 조립해 ``backtest.jobs`` 로 발행하는 작업 메시지. 엔진은 이것만 받는다."""

    run: RunSpec
    universe: UniverseSpec
    market_data: MarketDataSpec
    strategy: StrategySpec
    portfolio: PortfolioSpec
    execution: ExecutionSpec
    benchmark: BenchmarkSpec | None = None  # None = 벤치마크 미지정
