"""엔진 결과 EngineOutput과 벤치마크 BenchmarkResult.

기준: README §4.3, 픽스처 event-finished-*.json.
"""

from datetime import date, datetime
from decimal import Decimal
from enum import StrEnum

from worker.contracts.base import ContractModel


class RunStatus(StrEnum):
    """최종 상태만 온다. 그 외 값은 계약 오류."""

    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class TradeSide(StrEnum):
    LONG = "LONG"
    SHORT = "SHORT"


class TradeStatus(StrEnum):
    OPEN = "OPEN"
    CLOSED = "CLOSED"


class EngineError(ContractModel):
    # README §4.3 코드 집합: INVALID_REQUEST, NO_MARKET_DATA, STRATEGY_ERROR,
    # ENGINE_ERROR, INTERNAL_ERROR
    code: str
    message: str


class RunResult(ContractModel):
    backtest_id: str
    status: RunStatus
    started_at: datetime
    finished_at: datetime
    error: EngineError | None = None  # None = 성공


class PerformanceResult(ContractModel):
    """계산 불가 규칙(설계 OUTPUT Spec).

    변동성 0 → sharpe_ratio만 None. 관측치 2개 미만 → volatility·sharpe_ratio None.
    CLOSED 거래 0건 → win_rate None. initial_equity ≤ 0 → total_return·cagr None.
    """

    initial_equity: Decimal
    final_equity: Decimal
    currency: str
    total_return: Decimal | None = None
    cagr: Decimal | None = None
    max_drawdown: Decimal
    sharpe_ratio: Decimal | None = None
    volatility: Decimal | None = None
    trade_count: int  # CLOSED 거래 수
    win_rate: Decimal | None = None


class PositionSnapshot(ContractModel):
    instrument_id: str
    quantity: Decimal
    market_value: Decimal
    weight: Decimal
    unrealized_pnl: Decimal


class PortfolioSnapshot(ContractModel):
    date: date
    equity: Decimal
    cash: Decimal
    position_value: Decimal
    daily_return: Decimal
    cumulative_return: Decimal
    drawdown: Decimal
    # 보유 구성이 바뀐 거래일 + 시작일 + 종료일에만 존재. None = 이 날은 구성 변경 없음
    positions: list[PositionSnapshot] | None = None


class TradeResult(ContractModel):
    """OPEN 거래는 exit_*·gross_pnl·realized_pnl·return_rate가 None.

    commission·slippage_cost는 진입 측만 합산한다.
    """

    trade_id: str
    instrument_id: str
    side: TradeSide
    status: TradeStatus
    entry_time: datetime
    exit_time: datetime | None = None
    entry_price: Decimal  # 계약상 실제 평균 체결가. 현재 픽스처는 기준가 (README §4.3 체결가 의미)
    exit_price: Decimal | None = None
    quantity: Decimal
    gross_pnl: Decimal | None = None
    commission: Decimal
    slippage_cost: Decimal
    realized_pnl: Decimal | None = None
    return_rate: Decimal | None = None


class ExecutionResult(ContractModel):
    order_count: int
    fill_count: int
    rejected_order_count: int
    total_commission: Decimal
    total_slippage_cost: Decimal


class Warning(ContractModel):
    date: date
    instrument_id: str
    code: str  # 개방 집합 (예 TRADING_HALTED, INSUFFICIENT_CASH). 오류가 아닌 경고
    message: str


class Diagnostics(ContractModel):
    warnings: list[Warning]


class EngineOutput(ContractModel):
    """엔진 실행 결과.

    FAILED 시 run.error가 채워지고 performance·execution은 None, 리스트는 빈 리스트.
    존재하지 않는 성과를 0으로 채우지 않는다.
    """

    run: RunResult
    performance: PerformanceResult | None = None
    portfolio_series: list[PortfolioSnapshot]
    trades: list[TradeResult]
    execution: ExecutionResult | None = None
    diagnostics: Diagnostics


class BenchmarkPoint(ContractModel):
    date: date
    equity: Decimal
    cumulative_return: Decimal


class BenchmarkResult(ContractModel):
    """벤치마크 Buy & Hold.

    실행 Consumer가 계산해 FINISHED payload에 합친다 (엔진 결과의 일부가 아님).
    """

    instrument_id: str
    total_return: Decimal
    timeseries: list[BenchmarkPoint]
