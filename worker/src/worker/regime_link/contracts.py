"""사례 연결 작업·이벤트 메시지 계약(pydantic).

기준은 ``docs/specs/regime-link-messaging/``(README + ``examples/*.json``)이고
백엔드 ``com.ssafy.thorp.regimelink.application.port.message``의 record와 같은
모양이다. 백테스트 계약인 ``worker.contracts``에 넣지 않는다 — 큐도 스키마도 다르다.

``ContractModel``을 따라 JSON camelCase ↔ 내부 snake_case는 alias로, null은 키
생략으로 다룬다. 다만 ``extra="forbid"``로 좁혀 **계약에 없는 키가 오면 멈춘다**.
백테스트 계약은 ``extra="ignore"``라 모르는 필드를 넘기지만, 011은 백엔드가 잘라
담은 입력으로만 계산하므로 모르는 키는 곧 양쪽 계약이 어긋났다는 신호다. 조용히
무시하면 틀린 입력으로 숫자를 낸다.

**없는 값을 기본값으로 채우지 않는다.** 계약이 언제나 싣는 필드에는 기본값을 두지
않아 누락이 곧 검증 실패가 되고, 생략 가능한 필드만 ``T | None = None``으로
선언한다(계약 "표기 규칙"의 null 규칙).
"""

from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Generic, TypeVar

from pydantic import ConfigDict, Field, model_validator

from worker.contracts.base import ContractModel, UtcDatetime

# --- 공통 설정 -----------------------------------------------------------------------


class RegimeLinkModel(ContractModel):
    """011 계약 모델의 공통 설정.

    ``ContractModel``의 alias·null 규칙에 ``extra="forbid"``만 더한다.
    """

    model_config = ConfigDict(extra="forbid")


# --- 열거값 (백엔드 enum과 같은 값) ---------------------------------------------------


class RegimeLinkScenario(StrEnum):
    """사후 구간을 무엇을 기준으로 재는가(research R3).

    ``CONTINUOUS``는 기준값이 ``inputs.portfolio_series``의 ``baseline_date``
    평가액이고, ``FRESH_CASH``는 ``run.initial_cash``다. 시작 보유도 앞의 것은 있고
    뒤의 것은 빈 목록이다.
    """

    CONTINUOUS = "CONTINUOUS"
    FRESH_CASH = "FRESH_CASH"


class RegimeLinkEventType(StrEnum):
    """백테스트와 달리 진행률(``PROGRESS``)이 없다 — 63거래일은 한 번에 끝난다."""

    STARTED = "STARTED"
    FINISHED = "FINISHED"


class RegimeLinkStatus(StrEnum):
    """계산이 실패해도 이벤트는 정상 발행되므로 둘 다 끝까지 처리한 결과다."""

    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class RegimeLinkErrorCode(StrEnum):
    """``FINISHED(FAILED)``의 실패 사유. 네 값이 전부다.

    워커는 ``DATA_INSUFFICIENT``를 내지 않는다 — 자료 부족은 발행 전 백엔드
    판정이라 그런 작업은 여기까지 오지 않는다(research R13). 계약 위반(JSON·스키마
    오류)도 여기 없다 — 이벤트가 아니라 reject → DLQ다.
    """

    INPUT_INCOMPLETE = "INPUT_INCOMPLETE"
    MARKET_DATA_MISSING = "MARKET_DATA_MISSING"
    INVALID_REQUEST = "INVALID_REQUEST"
    ENGINE_ERROR = "ENGINE_ERROR"


class FillSide(StrEnum):
    """004 ``fills[].side``를 그대로 옮긴 값이라 진입·청산이 아니라 매수·매도다."""

    BUY = "BUY"
    SELL = "SELL"


class TradeReason(StrEnum):
    """004 ``fills[].reason``과 같은 값. 011은 표시용으로만 쓴다."""

    SIGNAL = "SIGNAL"
    REBALANCE = "REBALANCE"
    HALT_DEFERRED = "HALT_DEFERRED"
    LIQUIDATION = "LIQUIDATION"


class TurnoverBasis(StrEnum):
    """회전율의 연환산 단위.

    수익률·최대 하락 폭은 연환산하지 않으므로 이 단위는 회전율에만 붙는다. 값과
    단위를 함께 실어 63거래일 값이 1년 값으로 읽히는 것을 막는다(research R3).
    """

    ANNUALIZED_252 = "ANNUALIZED_252"


class BasisKind(StrEnum):
    """결과가 어떤 자료 위에서 나왔는가.

    010과 같이 **항상** 역사 비교다 — 최신 개정자료로 과거를 다시 본 것이지 당시
    발표값 재현이 아니다(spec FR-015).
    """

    HISTORICAL_REVISION = "HISTORICAL_REVISION"


# --- 작업 메시지 ---------------------------------------------------------------------


class JobSpec(RegimeLinkModel):
    """``attempt``는 이 연결의 발행 시도 번호이며 ``message_id``에 실려 온다."""

    link_id: str
    attempt: int
    scenario: RegimeLinkScenario


class WindowSpec(RegimeLinkModel):
    """사후 구간의 날짜 경계. 전부 거래일이다.

    ``last_available``은 구간 안 상장폐지면 **폐지 전날까지로 백엔드가 이미 줄인
    값**이라 워커가 다시 줄이지 않는다. ``completed_trading_days``는
    d0~last_available의 거래일 수이고 ``inputs.portfolio_series``의 행 수는 그 값
    + 1이다(baseline_date 행이 하나 더 있다).
    """

    d0: date
    d21: date
    d63: date
    last_available: date
    completed_trading_days: int
    baseline_date: date


class RunSpec(RegimeLinkModel):
    """잘라낼 원천 실행(004). 실행 자체는 읽기만 한다."""

    run_id: str
    effective_start: date
    initial_cash: Decimal
    analytics_method: str


class UniverseItem(RegimeLinkModel):
    """``delisted_on``이 구간 안이면 그 전날까지만 계산하고 ``partial=True``다."""

    sector_id: str
    instrument_id: str
    delisted_on: date | None = None


class UniverseSpec(RegimeLinkModel):
    mapping_version: str
    items: list[UniverseItem]


class MarketDataSpec(RegimeLinkModel):
    dataset_id: str
    data_version: str
    benchmark_instrument_id: str


class PortfolioPoint(RegimeLinkModel):
    date: date
    equity: Decimal
    cash: Decimal


class Position(RegimeLinkModel):
    instrument_id: str
    quantity: int


class DailyPositions(RegimeLinkModel):
    """하루치 보유 스냅샷.

    ``positions``는 **그날 보유 전체**이고 수량 0인 종목은 빠져 있다. 그래서 이월은
    실린 종목만 고치는 부분 갱신이 아니라 덮어쓰기다 — 체결로 더하되 스냅샷이 있는
    날은 그 수량으로 맞춘다. 분할 같은 기업행위는 체결 밖에서 수량을 바꾸므로
    체결만 누적하면 그날부터 수량이 틀린다.
    """

    date: date
    positions: list[Position]


class FillCosts(RegimeLinkModel):
    """체결 한 건의 비용 네 갈래.

    합계는 두지 않는다 — 결과 쪽 ``WindowCosts``에만 있다.
    """

    commission: Decimal
    spread: Decimal
    slippage: Decimal
    tax: Decimal


class InputTrade(RegimeLinkModel):
    """개별 체결 한 건. 004 ``fills[]``를 그대로 옮긴 것이다.

    진입·청산을 묶은 거래 단위가 아니다. ``price``는 실제 체결가(호가차·슬리피지
    반영), ``reference_price``는 충격 전 기준가이고 비용의 기준 금액은
    ``quantity × reference_price``다. ``cash_cost``는 체결 대금과 별도로 현금에서
    빠진 비용(수수료 + 세금)이라 체결가에 이미 반영된 호가차·슬리피지는 들어 있지
    않다.
    """

    fill_id: str
    time: UtcDatetime
    instrument_id: str
    side: FillSide
    quantity: int
    price: Decimal
    reference_price: Decimal
    costs: FillCosts
    cash_cost: Decimal
    reason: TradeReason | None = None


class CashFlow(RegimeLinkModel):
    """구간 안 현금 흐름.

    체결과 이것만으로 구간 현금이 되살아난다(DEP-004-011 대조에서 확인).
    ``type``은 004와 같은 자유 문자열이며 현재 쓰이는 값은 ``DISTRIBUTION``
    하나다. StrEnum으로 좁히면 004가 갈래를 늘린 날 정상 메시지가 DLQ로 간다.
    """

    date: date
    instrument_id: str
    type: str
    amount: Decimal


class InputsSpec(RegimeLinkModel):
    """004 저장 결과에서 구간만큼 잘라 담은 입력.

    근거는 계약 "구간 입력의 출처와 제약"(DEP-004-011)이다.
    ``portfolio_series``는 baseline_date~last_available의 **모든** 거래일,
    ``daily_positions``는 그중 004가 스냅샷을 남긴 날(수량이 바뀐 날)만이다.
    백엔드가 잘라 담으므로 워커는 이 범위 밖을 요청하지 않고, 모자라면 채우지 않고
    ``INPUT_INCOMPLETE``로 실패한다.
    """

    portfolio_series: list[PortfolioPoint]
    opening_positions: list[Position]
    daily_positions: list[DailyPositions]
    trades: list[InputTrade]
    cash_flows: list[CashFlow]


class RegimeLinkJob(RegimeLinkModel):
    """``regime-link.jobs``로 오는 작업 메시지 본문.

    ``schema_version``이 본문에 없다 — 작업은 AMQP 헤더로만 버전을 싣고 이벤트만
    본문 첫 필드에 싣는다(계약 "표기 규칙"). ``input_digest``는 백엔드가 만든
    값이며 워커는 검증하지 않고 결과에 그대로 돌려준다.
    """

    job: JobSpec
    window: WindowSpec
    run: RunSpec
    universe: UniverseSpec
    market_data: MarketDataSpec
    inputs: InputsSpec
    input_digest: str


# --- 이벤트 -------------------------------------------------------------------------


class ProcessInfo(RegimeLinkModel):
    """어느 프로세스가 집었는지. 진단용이라 못 채우면 키를 생략한다."""

    pid: int | None = None
    hostname: str | None = None


class RegimeLinkStartedPayload(RegimeLinkModel):
    started_at: UtcDatetime
    resource: ProcessInfo | None = None


class WindowCosts(RegimeLinkModel):
    """구간 비용 네 갈래와 합계.

    체결 단위 ``FillCosts``와 달리 ``total``이 있다.
    """

    commission: Decimal
    spread: Decimal
    slippage: Decimal
    tax: Decimal
    total: Decimal


class WindowSummary(RegimeLinkModel):
    """구간 요약.

    ``window21``은 d0~d21, ``window63``은 d0~min(d63, last_available)이다.
    ``window_return``의 JSON 키는 계약대로 ``return``이다 — Python 예약어라 필드
    이름으로 쓸 수 없어 alias로 대응한다. **수익률과 최대 하락 폭은 연환산하지
    않고** 회전율만 ``turnover_basis``가 밝히는 단위로 연환산한다.

    ``loss_contribution``은 섹터별 구간 손익 ÷ 전체 구간 손익이라 이익 종목도 빼지
    않고 들어가 몫의 합이 1이며, 전체 구간 손익이 0일 때만 키가 없다. 이름이
    '손실'이지만 이익 종목을 빼지 않는다.
    """

    trading_days: int
    window_return: Decimal = Field(alias="return")
    max_drawdown: Decimal
    trade_count: int
    trades_per_month: Decimal
    turnover: Decimal
    turnover_basis: TurnoverBasis
    costs: WindowCosts
    loss_contribution: dict[str, Decimal] | None = None
    complete: bool


class Holding(RegimeLinkModel):
    """``actual``은 종목별 그날 비중(평가액 ÷ 자산)이다. 목표 비중이 아니다."""

    date: date
    actual: dict[str, Decimal]


class ResultTrade(RegimeLinkModel):
    """표시용 거래 한 건.

    작업의 ``InputTrade``와 이름만 같고 모양이 다르다 — 시각 대신 날짜를, 비용 네
    갈래 대신 한 값을 쓴다. 두 모델을 하나로 합치지 않는다.
    """

    date: date
    instrument_id: str
    side: FillSide
    quantity: int
    price: Decimal
    cost: Decimal
    reason: TradeReason | None = None


class BenchmarkWindow(RegimeLinkModel):
    """``window_return``의 JSON 키는 ``return``이다."""

    window_return: Decimal = Field(alias="return")
    max_drawdown: Decimal


class Benchmark(RegimeLinkModel):
    """참고 지수의 두 구간 값.

    전략 결과와 나란히 두기만 하고 초과 수익을 계산하지 않는다.
    """

    window63: BenchmarkWindow
    window21: BenchmarkWindow


class Basis(RegimeLinkModel):
    """이 숫자가 어떤 자료 위에서 나왔는가.

    화면이 역사 비교임을 밝히는 근거다(spec FR-015).
    """

    kind: BasisKind
    data_version: str
    dataset_id: str
    analytics_method: str


class RegimeLinkResultPayload(RegimeLinkModel):
    """``FINISHED(COMPLETED)``의 계산 결과.

    011 화면의 숫자는 전부 여기서 나온다. ``holdings``는 구간의 **모든** 거래일을,
    ``trades``는 구간의 모든 거래를 담는다(개수가 ``window63.trade_count``와
    같다). ``kospi``는 ``market_data.benchmark_instrument_id``의 같은 날짜 종가로
    낸 참고 값이고 전략과의 차이는 계산하지 않는다.
    """

    window63: WindowSummary
    window21: WindowSummary
    holdings: list[Holding]
    trades: list[ResultTrade]
    kospi: Benchmark
    basis: Basis
    input_digest: str


class ErrorDetail(RegimeLinkModel):
    """``message``는 사람이 읽는 진단 문구다. 화면에 그대로 내보내지 않는다."""

    code: RegimeLinkErrorCode
    message: str


class ResourceUsage(RegimeLinkModel):
    """측정 기록용.

    백테스트와 달리 메모리·CPU는 재지 않는다 — 63거래일 계산이라 벽시계만으로
    충분하다.
    """

    wall_seconds: Decimal


class RegimeLinkFinishedPayload(RegimeLinkModel):
    """계산이 실패해도 **정상 결과**로 발행한다.

    이 이벤트는 워커가 끝까지 처리했다는 뜻이다. ``COMPLETED``면 ``result``가 있고
    ``error``가 없으며 ``FAILED``면 반대다. ``partial``은 구간 안 상장폐지로
    63거래일을 채우지 못했다는 뜻이며 ``window21``은 언제나 완성이라 이 값과
    무관하다.

    **이 짝은 모델이 지킨다.** 이벤트를 만드는 쪽이 워커라, 분기 실수로 결과 없는
    ``COMPLETED``를 발행하면 브로커를 건너 백엔드에서야 드러나고 그때는 이미
    ACK한 뒤라 되돌릴 길이 없다. 발행 전에 멈추는 편이 싸다.
    """

    status: RegimeLinkStatus
    partial: bool
    delisted_on: date | None = None
    result: RegimeLinkResultPayload | None = None
    error: ErrorDetail | None = None
    resource: ResourceUsage | None = None

    @model_validator(mode="after")
    def _outcome_matches_status(self) -> "RegimeLinkFinishedPayload":
        if self.status is RegimeLinkStatus.COMPLETED:
            if self.result is None:
                raise ValueError("COMPLETED 는 result 가 있어야 한다")
            if self.error is not None:
                raise ValueError("COMPLETED 에는 error 가 없어야 한다")
        if self.status is RegimeLinkStatus.FAILED:
            if self.error is None:
                raise ValueError("FAILED 는 error 가 있어야 한다")
            if self.result is not None:
                raise ValueError("FAILED 에는 result 가 없어야 한다")
        return self


P = TypeVar("P", RegimeLinkStartedPayload, RegimeLinkFinishedPayload)


class RegimeLinkEvent(RegimeLinkModel, Generic[P]):
    """``regime-link.events``로 발행하는 envelope.

    ``RegimeLinkEvent[RegimeLinkFinishedPayload]``처럼 payload 타입을 지정한다.
    수신 측은 본문 ``type``을 먼저 읽고 그에 맞는 payload 타입으로 검증한다.
    ``event_id``는 재발행해도 유지하는 멱등 키다.
    """

    schema_version: int
    event_id: str
    link_id: str
    attempt: int
    type: RegimeLinkEventType
    occurred_at: UtcDatetime
    payload: P
