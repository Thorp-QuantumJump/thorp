"""Typed, decimal-string integration contract for the executable engine.

결과(출력) 모델은 공용 메시지 계약 ``worker.contracts.engine_output`` 으로 옮겼다. 진행 콜백이
넘기는 값도 같은 이유로 ``worker.contracts.events.ProgressPoint`` 를 그대로 쓴다. 같은 모양을
두 곳에 선언해 두면 한쪽만 고쳤을 때 조용히 어긋나기 때문이다.

입력 모델은 아직 여기 남아 있다. 계약 ``worker.contracts.engine_input`` 의 고정값과 이 엔진이
실제로 지원하는 값이 달라서, 지원 값을 팀에서 맞춘 뒤 함께 옮긴다. ``marketData.adjustment``
는 공급자를 yfinance ``auto_adjust=False`` 로 바꿔 계약 고정값 ``SPLIT_ADJUSTED`` 와 일치시켰다.
남은 차이는 수수료 모델(계약 ``commission.model=BROKER`` vs 엔진 ``ZERO``)이다. 계약에 ``ZERO``
를 추가하기로 결정했고(ADR-0004) 그 반영이 남아 있다. ``delisting=CAUSE_BASED`` 는 요청을 받고
미지원 경고를 남기기로 결정했으며(ADR-0003) 아직 구현하지 않아 여기서는 ``UNSUPPORTED`` 다.
"""

from datetime import date as Date
from decimal import Decimal
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel


class Contract(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class RunSpec(Contract):
    backtest_id: str = Field(min_length=1)
    start: Date
    end: Date
    random_seed: int = 42

    @model_validator(mode="after")
    def dates_ordered(self) -> "RunSpec":
        if self.end < self.start:
            raise ValueError("run.end must be on or after run.start")
        return self


class UniverseSpec(Contract):
    instrument_ids: list[str] = Field(min_length=1, max_length=1)

    @model_validator(mode="after")
    def krx_only(self) -> "UniverseSpec":
        symbol, _, venue = self.instrument_ids[0].partition(".")
        if len(symbol) != 6 or not symbol.isdigit() or venue != "XKRX":
            raise ValueError("Expected one six-digit KOSPI instrument, e.g. 000660.XKRX")
        return self


class MarketDataSpec(Contract):
    dataset_id: str = "YFINANCE_DAILY"
    interval: Literal["1D"] = "1D"
    adjustment: Literal["SPLIT_ADJUSTED"] = "SPLIT_ADJUSTED"
    price_type: Literal["LAST"] = "LAST"


class MomentumParameters(Contract):
    lookback_days: int = Field(default=120, gt=0, strict=True)


class StrategySpec(Contract):
    strategy_id: Literal["MOMENTUM"] = "MOMENTUM"
    version: Literal["1.0.0"] = "1.0.0"
    parameters: MomentumParameters = Field(default_factory=MomentumParameters)


class PositionSizing(Contract):
    type: Literal["STRATEGY_DEFINED"] = "STRATEGY_DEFINED"
    max_position_pct: Decimal = Field(default=Decimal("1"), gt=0, le=1)


class PortfolioSpec(Contract):
    initial_cash: Decimal = Field(default=Decimal("10000000"), gt=0)
    currency: Literal["KRW"] = "KRW"
    position_sizing: PositionSizing = Field(default_factory=PositionSizing)
    short_selling: Literal[False] = False
    leverage: Decimal = Field(default=Decimal("1"), ge=1, le=1)


class CommissionSpec(Contract):
    model: Literal["ZERO"] = "ZERO"
    version: Literal["DEMO_ZERO_V1"] = "DEMO_ZERO_V1"
    tax_rate: Decimal = Field(default=Decimal("0"), ge=0, le=0)


class SlippageSpec(Contract):
    model: Literal["FIXED_BPS"] = "FIXED_BPS"
    bps: Literal[5] = 5


class LiquiditySpec(Contract):
    model: Literal["UNLIMITED"] = "UNLIMITED"


class ExecutionSpec(Contract):
    signal_timing: Literal["BAR_CLOSE"] = "BAR_CLOSE"
    execution_timing: Literal["NEXT_BAR_OPEN"] = "NEXT_BAR_OPEN"
    order_types: list[Literal["MARKET"]] = Field(
        default_factory=lambda: ["MARKET"], min_length=1, max_length=1
    )
    commission: CommissionSpec = Field(default_factory=CommissionSpec)
    slippage: SlippageSpec = Field(default_factory=SlippageSpec)
    partial_fill: Literal[False] = False
    liquidity: LiquiditySpec = Field(default_factory=LiquiditySpec)
    trading_halt: Literal["WAIT_UNTIL_RESUME"] = "WAIT_UNTIL_RESUME"
    delisting: Literal["UNSUPPORTED"] = "UNSUPPORTED"


class EngineInput(Contract):
    run: RunSpec
    universe: UniverseSpec = Field(
        default_factory=lambda: UniverseSpec(instrument_ids=["000660.XKRX"])
    )
    market_data: MarketDataSpec = Field(default_factory=MarketDataSpec)
    strategy: StrategySpec = Field(default_factory=StrategySpec)
    portfolio: PortfolioSpec = Field(default_factory=PortfolioSpec)
    execution: ExecutionSpec = Field(default_factory=ExecutionSpec)
    benchmark: None = None
