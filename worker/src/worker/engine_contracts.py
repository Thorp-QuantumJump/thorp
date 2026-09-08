"""Typed, decimal-string integration contract for the executable engine."""

from datetime import date as Date
from datetime import datetime
from decimal import Decimal
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_serializer, model_validator
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
    adjustment: Literal["TOTAL_RETURN_ADJUSTED"] = "TOTAL_RETURN_ADJUSTED"
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


class PositionSnapshot(Contract):
    instrument_id: str
    quantity: Decimal
    market_value: Decimal
    weight: Decimal
    unrealized_pnl: Decimal


class PortfolioSnapshot(Contract):
    date: Date
    equity: Decimal
    cash: Decimal
    position_value: Decimal
    daily_return: Decimal
    cumulative_return: Decimal
    drawdown: Decimal
    positions: list[PositionSnapshot] | None = None

    @model_serializer(mode="wrap")
    def serialize_snapshot(self, handler: Any) -> dict:
        result = handler(self)
        if self.positions is None:
            result.pop("positions", None)
        return result


class ProgressSnapshot(Contract):
    completed_trading_days: int
    total_trading_days: int
    portfolio_series: list[PortfolioSnapshot]


class TradeResult(Contract):
    trade_id: str
    instrument_id: str
    side: Literal["LONG"] = "LONG"
    status: Literal["OPEN", "CLOSED"] = "OPEN"
    entry_time: datetime
    exit_time: datetime | None = None
    entry_price: Decimal
    exit_price: Decimal | None = None
    quantity: Decimal
    gross_pnl: Decimal | None = None
    commission: Decimal = Decimal("0")
    slippage_cost: Decimal
    realized_pnl: Decimal | None = None
    return_rate: Decimal | None = None


class PerformanceResult(Contract):
    initial_equity: Decimal
    final_equity: Decimal
    currency: str = "KRW"
    total_return: Decimal
    cagr: Decimal | None
    max_drawdown: Decimal
    sharpe_ratio: Decimal | None
    volatility: Decimal | None
    trade_count: int
    win_rate: Decimal | None


class ExecutionResult(Contract):
    """Aggregate counts only; the per-order audit log is a local artifact, not a message."""

    order_count: int
    fill_count: int
    rejected_order_count: int
    total_commission: Decimal = Decimal("0")
    total_slippage_cost: Decimal


class WarningRecord(Contract):
    date: Date
    instrument_id: str
    code: str
    message: str


class Diagnostics(Contract):
    warnings: list[WarningRecord] = Field(default_factory=list)


class EngineError(Contract):
    code: str
    message: str


class RunResult(Contract):
    backtest_id: str
    status: Literal["COMPLETED", "FAILED"]
    started_at: datetime
    finished_at: datetime
    error: EngineError | None = None


class EngineOutput(Contract):
    run: RunResult
    performance: PerformanceResult | None = None
    portfolio_series: list[PortfolioSnapshot] = Field(default_factory=list)
    trades: list[TradeResult] = Field(default_factory=list)
    execution: ExecutionResult | None = None
    diagnostics: Diagnostics = Field(default_factory=Diagnostics)
