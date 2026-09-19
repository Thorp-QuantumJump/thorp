"""엔진 결과 EngineOutput(schemaVersion 2). 기준: README §4.3, 픽스처 event-finished-*.json.

v2에서 추가된 블록(``weightsSeries``·``costs``·``liquidation``·``analytics``·``exceptions``·
``versions``)은 전부 생략 가능하다. 단일 종목 데모 엔진은 채울 수 있는 것만 채우고 나머지는
키를 생략한다.
"""

from datetime import date
from decimal import Decimal
from enum import StrEnum

from worker.contracts.base import ContractModel, UtcDatetime


class RunStatus(StrEnum):
    """최종 상태만 온다. 그 외 값은 계약 오류."""

    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"


class TradeSide(StrEnum):
    LONG = "LONG"
    SHORT = "SHORT"


class TradeStatus(StrEnum):
    OPEN = "OPEN"
    CLOSED = "CLOSED"


class TradeReason(StrEnum):
    SIGNAL = "SIGNAL"
    REBALANCE = "REBALANCE"
    HALT_DEFERRED = "HALT_DEFERRED"
    LIQUIDATION = "LIQUIDATION"


class WeightsSource(StrEnum):
    USER = "USER"
    COMPUTED = "COMPUTED"


class EngineError(ContractModel):
    # README §4.3 코드 집합: INVALID_REQUEST, DATA_INSUFFICIENT, CONSTRAINT_VIOLATION,
    # ESTIMATION_FAILED, ENGINE_ERROR, INTERNAL_ERROR
    code: str
    message: str


class RunResult(ContractModel):
    run_id: str  # envelope의 runId와 같다
    status: RunStatus
    started_at: UtcDatetime
    finished_at: UtcDatetime
    partial: bool | None = None  # True = 취소·한도 도달로 끊긴 부분 결과. None = False
    last_trading_date: date | None = None  # 처리한 마지막 거래일
    effective_start: date | None = None  # 워밍업 이후 실제 평가 시작일
    effective_end: date | None = None
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
    net_total_return: Decimal | None = None  # 비용 차감 후(FR-027). None = 미산출
    cagr: Decimal | None = None
    max_drawdown: Decimal
    sharpe_ratio: Decimal | None = None
    volatility: Decimal | None = None
    trade_count: int  # CLOSED 거래 수
    win_rate: Decimal | None = None
    total_trading_cost: Decimal | None = None  # None = 미산출


class PositionSnapshot(ContractModel):
    instrument_id: str
    quantity: Decimal
    market_value: Decimal
    weight: Decimal
    unrealized_pnl: Decimal


class CashFlow(ContractModel):
    type: str  # 예 DISTRIBUTION
    instrument_id: str | None = None
    amount: Decimal


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
    cash_flows: list[CashFlow] | None = None  # 분배금 등. None = 없음


class WeightsPoint(ContractModel):
    date: date
    target: dict[str, Decimal]
    actual: dict[str, Decimal]
    weight_error: Decimal | None = None
    risk_scale: Decimal | None = (
        None  # TARGET_RISK: 목표 위험 맞춤 조절 비율(PD-18). 시나리오 아니면 None
    )
    estimated_volatility: Decimal | None = None  # TARGET_RISK: 조절에 쓴 추정 변동성


class TradeResult(ContractModel):
    """OPEN 거래는 exit_*·gross_pnl·realized_pnl·return_rate가 None.

    commission·slippage_cost는 진입 측만 합산한다.
    """

    trade_id: str
    instrument_id: str
    side: TradeSide
    status: TradeStatus
    entry_time: UtcDatetime
    exit_time: UtcDatetime | None = None
    entry_price: Decimal  # 슬리피지 반영 실제 평균 체결가
    exit_price: Decimal | None = None
    quantity: Decimal
    gross_pnl: Decimal | None = None
    commission: Decimal
    slippage_cost: Decimal
    realized_pnl: Decimal | None = None
    return_rate: Decimal | None = None
    reason: TradeReason | None = None
    planned_date: date | None = None  # HALT_DEFERRED: 원래 예정일


class ExecutionResult(ContractModel):
    order_count: int
    fill_count: int
    rejected_order_count: int
    total_commission: Decimal
    total_slippage_cost: Decimal


class Costs(ContractModel):
    """비용 합계(FR-027)."""

    commission: Decimal
    spread: Decimal
    slippage: Decimal
    tax: Decimal
    total: Decimal


class MarkToMarket(ContractModel):
    final_equity: Decimal
    total_return: Decimal


class Liquidated(ContractModel):
    final_equity: Decimal
    total_return: Decimal
    liquidation_cost: Decimal


class Liquidation(ContractModel):
    """종료 시 시가평가 결과와 청산 비용 포함 결과의 구분(FR-017)."""

    mark_to_market: MarkToMarket
    liquidated: Liquidated


class Analytics(ContractModel):
    """파생 지표(FR-027). 계산 방법은 ``method`` 에 적는다."""

    recovery_days: int | None = None  # None = 미회복
    turnover: Decimal | None = None
    correlation_matrix: dict[str, dict[str, Decimal]] | None = None
    risk_contribution: dict[str, Decimal] | None = None
    loss_contribution: dict[str, Decimal] | None = None
    weight_error: Decimal | None = None
    method: str | None = None


class ExceptionRecord(ContractModel):
    """예외 처리 기록(FR-020·FR-030). ``kind`` 는 개방 집합."""

    date: date
    instrument_id: str | None = None  # None = 포트폴리오 전체
    kind: str
    rule: str | None = None
    action: str | None = None


class RuleVersion(ContractModel):
    version: str
    provisional: bool | None = None  # True = 팀 결정 전 임시값


class Dependencies(ContractModel):
    """계산에 쓴 수치 라이브러리의 실제 설치 버전(spec 004 T028). 워커가 자기 환경에서 읽는다."""

    numpy: str
    scipy: str


class Versions(ContractModel):
    """참조 버전(FR-028)."""

    mapping: str | None = None
    rules: RuleVersion | None = None
    scenario_rules: str | None = None
    cost_profile: RuleVersion | None = None
    data: str | None = None
    engine: str | None = None
    evaluation_split: str | None = None  # 요청의 evaluation.splitVersion 사본. None = 미지정
    worker: str | None = None
    dependencies: Dependencies | None = None  # None = 미보고(T028 이전 워커)


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
    weights_series: list[WeightsPoint] | None = None
    weights_source: WeightsSource | None = None
    trades: list[TradeResult]
    execution: ExecutionResult | None = None
    costs: Costs | None = None
    liquidation: Liquidation | None = None
    analytics: Analytics | None = None
    exceptions: list[ExceptionRecord] | None = None
    versions: Versions | None = None
    diagnostics: Diagnostics
