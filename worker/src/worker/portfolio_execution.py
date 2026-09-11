"""다종목 리밸런싱 실행(spec 004 T006, research R-01).

배분 전략 전부를 Nautilus ``Strategy`` **하나**로 돌린다. 목표 비중은 ``worker.allocation`` 의
순수 함수가 정하고, 여기는 그 비중을 주문·체결·현금으로 바꾸는 일만 한다. 체결 회계가 한
곳에 있어야 전략을 바꿔도 체결 코드는 그대로고, 수기 대조(SC-003)가 전략 수만큼 늘지 않는다.

하루의 순서(거래일 D):

1. 09:00 D 의 QuoteTick 이 종목별로 도착한다. 첫 틱에서 그날의 기업행위(분할 → 수량, 권리락일 →
   분배금 자격, 지급일 → 현금)를 먼저 반영한다.
2. 마지막 종목의 틱이 오면 전날 판단한 주문을 **매도 먼저, 매수 나중**에 제출한다. 그날 거래
   정지(정지 표시 또는 거래량 0)인 종목의 주문은 제출하지 않고 다음 거래 가능일로 미룬다
   (``HALT_DEFERRED``). 체결가는 틱의 호가 — 시가에 호가차·슬리피지를 얹은 값이다.
3. 15:30 D 의 Bar 가 도착한다. 마지막 봉에서 종가로 평가해 스냅샷을 남기고, D 가 판단일(평가
   시작일과 매월 마지막 거래일)이면 목표 비중을 구해 다음 거래일 주문을 만든다.

**체결 콜백은 핸들러가 끝난 뒤에 온다.** Nautilus 1.219 에서 ``submit_order`` 는 같은 핸들러 안에서
체결 이벤트를 돌려주지 않는다(``self.cash`` 는 아직 옛값이다). 그래서 매수 수량은 판단 시점에 정하고
제출 직전에 예상 현금(현금 + 매도 예상 대금)으로 한 번 더 줄이기만 한다 — 핸들러 안에서 최신
현금을 읽어 정하려 하면 조용히 틀린다.

**현금·보유의 원장은 이 장부이고 Nautilus 는 주문 경로와 체결 이벤트만 준다.** 계좌는 MARGIN 이다 —
액면분할은 이 장부의 수량만 바꾸고 Nautilus 의 포지션은 그대로라, 분할 뒤 늘어난 수량을 팔면 CASH
계좌는 공매도로 보고 거절한다(탐침으로 확인). MARGIN 계좌는 매수 대금을 잔고에서 빼지 않으므로
Nautilus 잔고와 장부 현금은 대조 대상이 아니다. 공매도 금지는 이 장부가 지킨다 — 보유가 음수가
되면 실행을 멈춘다. 체결가는 매 건 그 방향의 호가와 같아야 한다(FillModel 슬리피지 0).

**비중을 몰래 재정규화하지 않는다.** 정수 수량 때문에 목표를 못 맞추면 남는 돈은 현금으로 두고
``weightsSeries[].weightError`` 에 오차를 적는다. 한 주도 못 사는 종목은 ``WEIGHT_ROUNDING``
예외로 남긴다.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from typing import Any

import pandas as pd
from nautilus_trader.backtest.engine import BacktestEngine, BacktestEngineConfig
from nautilus_trader.backtest.models import FillModel
from nautilus_trader.config import LoggingConfig, StrategyConfig
from nautilus_trader.model.currencies import Currency
from nautilus_trader.model.data import Bar, BarType, QuoteTick
from nautilus_trader.model.enums import AccountType, CurrencyType, OmsType, OrderSide
from nautilus_trader.model.identifiers import InstrumentId, Symbol, Venue
from nautilus_trader.model.instruments import Equity
from nautilus_trader.model.objects import Money, Price, Quantity
from nautilus_trader.trading.strategy import Strategy as NautilusStrategy

from worker.allocation import AllocationError, compute_weights
from worker.artifacts import OrderRecord
from worker.contracts.engine import ProgressCallback
from worker.contracts.engine_input import EngineInput, RunSpec
from worker.contracts.engine_output import (
    CashFlow,
    ExceptionRecord,
    PortfolioSnapshot,
    PositionSnapshot,
    TradeReason,
    TradeResult,
    TradeSide,
    TradeStatus,
    WeightsPoint,
    WeightsSource,
)
from worker.contracts.engine_output import Warning as ContractWarning
from worker.contracts.events import ProgressPoint
from worker.corporate_actions import DistributionLedger, apply_split, splits_on
from worker.costs import CostModel, FillCost, Side
from worker.market_data import MarketData

ZERO = Decimal("0")
PRICE_QUANTUM = Decimal("0.000001")
#: ``run.randomSeed`` 미지정(None) 시 쓰는 값. 재현성을 위해 시드 없이 실행하지 않는다.
DEFAULT_RANDOM_SEED = 42
#: 계약이 허용하는 유일한 리밸런싱 주기(FR-011).
REBALANCE_MONTH_END = "MONTH_END"
#: 결과 계약 ``exceptions[].kind``.
HALT_DEFERRED = "HALT_DEFERRED"
WEIGHT_ROUNDING = "WEIGHT_ROUNDING"
#: Nautilus 가 무제한 유동성(``liquidity.model=UNLIMITED``)으로 다루도록 두는 호가 잔량.
UNLIMITED_SIZE = Quantity.from_str("18446744073")
VENUE = Venue("XKRX")


def effective_random_seed(run: RunSpec) -> int:
    """실제로 ``FillModel`` 에 넣는 시드. 계약은 ``None``(미지정)을 허용한다."""
    return DEFAULT_RANDOM_SEED if run.random_seed is None else run.random_seed


class CallbackError(RuntimeError):
    """진행 콜백 실패를 보존해 소비자가 발행을 명시적으로 재시도할 수 있게 한다."""


class EngineFailure(RuntimeError):
    """엔진 자체 불변식 위반. 같은 입력이면 다시 나므로 결과(``ENGINE_ERROR``)로 돌려준다.

    공급자 접속 실패 같은 일시 장애와 갈라야 한다 — 그쪽은 결과로 바꾸지 않고 소비자에게 전파해
    재전달 경로로 보낸다(계약 §7).
    """


def timestamp(ns: int) -> datetime:
    return pd.Timestamp(ns, tz="UTC").to_pydatetime(warn=False)


def as_price(value: Any) -> Price:
    return Price.from_str(format(Decimal(str(value)).quantize(PRICE_QUANTUM), ".6f"))


def _day_of(ns: int) -> date:
    return pd.Timestamp(ns, tz="UTC").tz_convert("Asia/Seoul").date()


def _open_ns(day: date) -> int:
    return int((pd.Timestamp(day, tz="Asia/Seoul") + pd.Timedelta(hours=9)).value)


def _close_ns(day: date) -> int:
    return int((pd.Timestamp(day, tz="Asia/Seoul") + pd.Timedelta(hours=15, minutes=30)).value)


@dataclass
class PendingOrder:
    """판단일에 정한 주문. 체결되거나 버려질 때까지 종목당 하나만 있다."""

    instrument_id: str
    side: Side
    quantity: int
    planned_date: date
    record: OrderRecord
    deferred: bool = False


@dataclass
class Lot:
    """FIFO 원장의 한 매수 묶음. 매도는 오래된 묶음부터 닫는다."""

    trade: TradeResult
    quantity: Decimal
    entry_reference: Decimal


@dataclass(frozen=True)
class DayPlan:
    """평가일·판단일. 전략과 러너가 함께 본다."""

    calendar: list[date]
    evaluation_days: list[date]
    decision_days: frozenset[date]

    def next_trading_day(self, day: date) -> date | None:
        for candidate in self.calendar:
            if candidate > day:
                return candidate
        return None


def plan_days(calendar: list[date], evaluation_start: date, end: date, rebalance: str) -> DayPlan:
    """평가일과 판단일을 정한다.

    첫 판단은 **평가 시작일 종가**다 — 첫 월말까지 현금으로 앉아 있으면 전략이 아니라 대기 기간을
    평가하게 된다. 그 뒤로는 매월 마지막 거래일. 마지막 평가일에는 판단하지 않는다 — 체결할 다음
    거래일이 없다.
    """
    if rebalance != REBALANCE_MONTH_END:
        raise ValueError(
            f"allocation.parameters.rebalance={rebalance!r} is not supported;"
            f" only {REBALANCE_MONTH_END} is an approved cadence (FR-011)"
        )
    evaluation_days = [day for day in calendar if evaluation_start <= day <= end]
    if not evaluation_days:
        return DayPlan(calendar, [], frozenset())
    decisions = {evaluation_days[0]}
    for index, day in enumerate(evaluation_days[:-1]):
        if evaluation_days[index + 1].month != day.month:
            decisions.add(day)
    decisions.discard(evaluation_days[-1])
    return DayPlan(calendar, evaluation_days, frozenset(decisions))


@dataclass
class MarketEvents:
    """Nautilus 에 넣을 상품·이벤트. 정렬은 날짜 → 종목 이름순으로 고정한다(R-14)."""

    currency: Currency
    instruments: dict[str, Equity]
    events: list[Any]
    reference_opens: dict[tuple[str, date], Decimal]
    expected_per_day: dict[date, int]


def build_market_events(request: EngineInput, market: MarketData) -> MarketEvents:
    # KRW 는 소수 자리가 없어 bps 비용이 깨진다. 1:1 시뮬레이션 통화를 따로 둔다(재등록은 무해하다).
    currency = Currency("KRW_SIM", 8, 0, "KRW simulation (1:1)", CurrencyType.FIAT)
    Currency.register(currency)
    instruments: dict[str, Equity] = {}
    for iid in sorted(market.prices):
        instrument_id = InstrumentId.from_str(iid)
        instruments[iid] = Equity(
            instrument_id=instrument_id,
            raw_symbol=Symbol(instrument_id.symbol.value),
            currency=currency,
            price_precision=6,
            price_increment=Price.from_str("0.000001"),
            lot_size=Quantity.from_int(1),
            ts_event=0,
            ts_init=0,
            maker_fee=ZERO,
            taker_fee=ZERO,
        )
    costs = CostModel(request.execution.cost_profile)
    events: list[Any] = []
    reference_opens: dict[tuple[str, date], Decimal] = {}
    expected_per_day: dict[date, int] = {}
    for day in market.trading_days:
        ns_open, ns_close = _open_ns(day), _close_ns(day)
        ticks: list[Any] = []
        bars: list[Any] = []
        for iid, instrument in instruments.items():
            frame = market.prices[iid]
            if pd.Timestamp(day) not in frame.index:
                continue
            row = frame.loc[pd.Timestamp(day)]
            reference = Decimal(str(row.open))
            reference_opens[(iid, day)] = reference
            ticks.append(
                QuoteTick(
                    instrument.id,
                    as_price(costs.effective_price(Side.SELL, reference)),
                    as_price(costs.effective_price(Side.BUY, reference)),
                    UNLIMITED_SIZE,
                    UNLIMITED_SIZE,
                    ns_open,
                    ns_open,
                )
            )
            bars.append(
                Bar(
                    BarType.from_str(f"{instrument.id}-1-DAY-LAST-EXTERNAL"),
                    as_price(row.open),
                    as_price(row.high),
                    as_price(row.low),
                    as_price(row.close),
                    Quantity.from_int(int(row.volume)),
                    ns_close,
                    ns_close,
                )
            )
        expected_per_day[day] = len(ticks)
        events.extend(ticks)
        events.extend(bars)
    return MarketEvents(currency, instruments, events, reference_opens, expected_per_day)


class PortfolioRebalanceExecution(NautilusStrategy):
    """주문·체결·회계만 한다. 비중 계산은 ``worker.allocation`` 에 있다."""

    def __init__(
        self,
        request: EngineInput,
        market: MarketData,
        prepared: MarketEvents,
        plan: DayPlan,
        on_progress: ProgressCallback | None,
    ) -> None:
        super().__init__(StrategyConfig(strategy_id="PORTFOLIO-001"))
        self.request = request
        self.market = market
        self.prepared = prepared
        self.instrument_ids = sorted(prepared.instruments)
        self.plan = plan
        self.on_progress_callback = on_progress
        self.costs = CostModel(request.execution.cost_profile)
        self.ledger = DistributionLedger(list(market.distributions))
        # 신호용 조정 종가(signalPrice=ADJUSTED). 판단일까지만 잘라 넘긴다 — 미래 참조 금지.
        self.signal_history = pd.DataFrame(
            {iid: frame["adjusted_close"] for iid, frame in market.prices.items()}
        ).sort_index()

        self.cash: Decimal = request.portfolio.initial_cash
        self.quantity: dict[str, Decimal] = {iid: ZERO for iid in self.instrument_ids}
        self.lots: dict[str, list[Lot]] = {iid: [] for iid in self.instrument_ids}
        self.last_close: dict[str, Decimal] = {}
        self.pending: dict[str, PendingOrder] = {}
        self.pending_target: tuple[date, dict[str, Decimal]] | None = None
        self.orders: list[OrderRecord] = []
        self.trades: list[TradeResult] = []
        self.fills: list[FillCost] = []
        self.series: list[PortfolioSnapshot] = []
        self.weights_series: list[WeightsPoint] = []
        self.weights_source: WeightsSource | None = None
        self.exceptions: list[ExceptionRecord] = []
        self.warnings: list[ContractWarning] = []
        self.cash_flows_today: list[CashFlow] = []
        self.peak: Decimal = request.portfolio.initial_cash
        self.changed = False
        self.callback_error: Exception | None = None
        # Nautilus 는 핸들러 예외를 로그로 삼키므로 첫 실패를 잡아 두고 실행 뒤에 다시 던진다.
        self.failure: Exception | None = None

        self._current_day: date | None = None
        self._seen_ticks: set[str] = set()
        self._seen_bars: set[str] = set()

    # --- 구독 -------------------------------------------------------------------

    def on_start(self) -> None:
        for instrument in self.prepared.instruments.values():
            self.subscribe_quote_ticks(instrument.id)
            self.subscribe_bars(BarType.from_str(f"{instrument.id}-1-DAY-LAST-EXTERNAL"))

    def _guarded(self, action: Callable[[], None]) -> None:
        if self.failure is not None:
            return
        try:
            action()
        except Exception as exc:  # noqa: BLE001 - 첫 실패를 보존해 러너가 계약 오류로 바꾼다
            self.failure = exc
            self.stop()

    # --- 09:00 틱: 기업행위 → 주문 제출 -----------------------------------------------

    def on_quote_tick(self, tick: QuoteTick) -> None:
        self._guarded(lambda: self._on_quote_tick(tick))

    def _on_quote_tick(self, tick: QuoteTick) -> None:
        day = _day_of(tick.ts_event)
        if day != self._current_day:
            self._begin_day(day)
        self._seen_ticks.add(str(tick.instrument_id))
        if len(self._seen_ticks) == self.prepared.expected_per_day[day]:
            self._submit_pending(day)

    def _begin_day(self, day: date) -> None:
        self._current_day = day
        self._seen_ticks = set()
        self._seen_bars = set()
        self.cash_flows_today = []
        for split in splits_on(list(self.market.splits), day):
            iid = split.instrument_id
            if iid not in self.quantity:
                continue
            order = self.pending.get(iid)
            if order is not None:
                # 전날 분할 전 가격으로 정한 수량. 오늘 시가는 분할 후 가격이라 수량도 같은 비율로
                # 늘린다 — 그대로 두면 목표 비중의 절반만 사고 나머지가 현금으로 남는다.
                scaled = apply_split(split, Decimal(order.quantity)).quantity
                order.quantity = int(scaled)
                order.record.quantity = scaled
            if self.quantity[iid] == ZERO:
                continue
            outcome = apply_split(split, self.quantity[iid])
            self.quantity[iid] = outcome.quantity
            if outcome.exception is not None:
                self.exceptions.append(outcome.exception)
            # 묶음도 같은 비율로 늘린다. 단주는 위에서 한 번만 기록한다.
            for lot in self.lots[iid]:
                lot.quantity = apply_split(split, lot.quantity).quantity
                lot.trade.quantity = lot.quantity
                lot.trade.entry_price = lot.trade.entry_price / split.ratio
                lot.entry_reference = lot.entry_reference / split.ratio
            self.changed = True
        # 권리락일 자격은 **그날 시작 시점 보유**로 — 이날 체결될 주문은 아직 반영하지 않는다.
        self.ledger.on_ex_date(day, dict(self.quantity))
        paid = self.ledger.on_pay_date(day)
        for flow in paid:
            self.cash += flow.amount
        self.cash_flows_today.extend(paid)

    def _tradable(self, iid: str, day: date) -> bool:
        frame = self.market.prices[iid]
        if pd.Timestamp(day) not in frame.index:
            return False
        row = frame.loc[pd.Timestamp(day)]
        return not bool(row.trading_suspended) and int(row.volume) > 0

    def _submit_pending(self, day: date) -> None:
        """매도 먼저, 매수 나중. 둘 다 종목 이름순이라 실행마다 같은 순서다."""
        ordered = sorted(
            self.pending.values(), key=lambda order: (order.side is Side.BUY, order.instrument_id)
        )
        expected_proceeds = ZERO
        for order in ordered:
            if order.side is Side.SELL and self._tradable(order.instrument_id, day):
                reference = self.prepared.reference_opens[(order.instrument_id, day)]
                quantity = Decimal(order.quantity)
                expected_proceeds += (
                    self.costs.effective_price(Side.SELL, reference) * quantity
                    - self.costs.for_fill(Side.SELL, reference, quantity).cash
                )
        spent = ZERO
        for order in ordered:
            iid = order.instrument_id
            if not self._tradable(iid, day):
                self._defer(order, day)
                continue
            reference = self.prepared.reference_opens[(iid, day)]
            quantity = order.quantity
            if order.side is Side.BUY:
                affordable = self.costs.affordable_quantity(
                    self.cash + expected_proceeds - spent, reference
                )
                if affordable < quantity:
                    # 판단일 종가로 정한 수량을 체결일 시가가 못 받쳐 주는 경우. 살 수 있는 만큼만
                    # 사고 그 사실을 남긴다 — 주문 전체를 버리면 목표 비중에서 더 멀어진다.
                    self.warnings.append(
                        ContractWarning(
                            date=day,
                            instrument_id=iid,
                            code="INSUFFICIENT_CASH",
                            message=f"planned {quantity} shares, cash covers {affordable}",
                        )
                    )
                    quantity = affordable
                if quantity <= 0:
                    order.record.status = "REJECTED"
                    order.record.reason = "INSUFFICIENT_CASH"
                    del self.pending[iid]
                    continue
                spent += self.costs.effective_price(Side.BUY, reference) * quantity
                spent += self.costs.for_fill(Side.BUY, reference, Decimal(quantity)).cash
            order.quantity = quantity
            order.record.quantity = Decimal(quantity)
            order.record.reference_price = reference
            order.record.submitted_time = timestamp(_open_ns(day))
            nautilus_order = self.order_factory.market(
                instrument_id=self.prepared.instruments[iid].id,
                order_side=OrderSide.BUY if order.side is Side.BUY else OrderSide.SELL,
                quantity=Quantity.from_int(quantity),
            )
            order.record.order_id = str(nautilus_order.client_order_id)
            self.submit_order(nautilus_order)

    def _defer(self, order: PendingOrder, day: date) -> None:
        """거래 정지일. 버리지도, 그 가격으로 체결하지도 않고 다음 거래 가능일로 미룬다."""
        order.deferred = True
        self.exceptions.append(
            ExceptionRecord(
                date=day,
                instrument_id=order.instrument_id,
                kind=HALT_DEFERRED,
                rule=str(self.request.execution.trading_halt),
                action=f"planned {order.planned_date.isoformat()} -> next tradable day",
            )
        )

    # --- 체결 ------------------------------------------------------------------------

    def on_order_denied(self, event: Any) -> None:
        code = "INSUFFICIENT_CASH" if "BALANCE" in event.reason else "ORDER_REJECTED"
        self._guarded(lambda: self._reject(str(event.instrument_id), code, event.reason))

    def on_order_rejected(self, event: Any) -> None:
        self._guarded(
            lambda: self._reject(str(event.instrument_id), "ORDER_REJECTED", event.reason)
        )

    def _reject(self, iid: str, code: str, reason: str) -> None:
        order = self.pending.pop(iid)
        order.record.status = "REJECTED"
        order.record.reason = f"{code}: {reason}"
        self.warnings.append(
            ContractWarning(
                date=self._current_day or self.request.run.start,
                instrument_id=iid,
                code=code,
                message=reason,
            )
        )

    def on_order_filled(self, event: Any) -> None:
        self._guarded(lambda: self._on_order_filled(event))

    def _on_order_filled(self, event: Any) -> None:
        iid = str(event.instrument_id)
        order = self.pending.pop(iid)
        quantity = event.last_qty.as_decimal()
        price = event.last_px.as_decimal()
        if quantity != Decimal(order.quantity):
            raise RuntimeError("Partial fills are not supported (execution.partialFill=false)")
        reference = order.record.reference_price
        assert reference is not None
        quoted = as_price(self.costs.effective_price(order.side, reference)).as_decimal()
        if price != quoted:
            raise RuntimeError(f"{iid} filled at {price}, quoted {quoted} for {order.side.value}")
        order.record.status = "FILLED"
        order.record.fill_time = timestamp(event.ts_event)
        order.record.fill_price = price
        fill = self.costs.for_fill(order.side, reference, quantity)
        self.fills.append(fill)
        reason = TradeReason.HALT_DEFERRED if order.deferred else TradeReason.REBALANCE
        planned = order.planned_date if order.deferred else None

        if order.side is Side.BUY:
            self.cash -= price * quantity + fill.cash
            if self.cash < ZERO:
                raise RuntimeError("Negative cash invariant violated")
            self.quantity[iid] += quantity
            trade = TradeResult(
                trade_id=f"TR-{len(self.trades) + 1:06d}",
                instrument_id=iid,
                side=TradeSide.LONG,
                status=TradeStatus.OPEN,
                entry_time=order.record.fill_time,
                entry_price=price,
                quantity=quantity,
                commission=fill.commission,
                slippage_cost=fill.spread + fill.slippage,
                reason=reason,
                planned_date=planned,
            )
            self.trades.append(trade)
            self.lots[iid].append(Lot(trade, quantity, reference))
        else:
            self.cash += price * quantity - fill.cash
            self.quantity[iid] -= quantity
            if self.quantity[iid] < ZERO:
                raise RuntimeError(f"Short position invariant violated for {iid}")
            self._close_lots(iid, quantity, price, reference, fill, order.record.fill_time, planned)
        self.changed = True

    def _close_lots(
        self,
        iid: str,
        quantity: Decimal,
        price: Decimal,
        reference: Decimal,
        fill: FillCost,
        when: datetime,
        planned: date | None,
    ) -> None:
        """오래된 묶음부터 닫는다. 묶음 일부만 팔리면 팔린 만큼을 새 거래로 떼어 닫는다.

        ``commission`` 은 양쪽 수수료의 합이고 세금은 ``realized_pnl`` 에서만 빠진다(거래 계약에
        세금 칸이 없다 — 합계는 ``costs.tax``). ``gross_pnl`` 은 가격 충격 전 기준가 차이다. 이월
        체결이면 닫힌 거래에 ``HALT_DEFERRED`` 와 원래 예정일을 남긴다.
        """
        per_share_fee = fill.cash / quantity
        per_share_impact = (fill.spread + fill.slippage) / quantity
        per_share_commission = fill.commission / quantity
        remaining = quantity
        lots = self.lots[iid]
        while remaining > ZERO:
            lot = lots[0]
            take = min(lot.quantity, remaining)
            share = take / lot.quantity
            entry_commission = lot.trade.commission * share
            entry_impact = lot.trade.slippage_cost * share
            if take == lot.quantity:
                lots.pop(0)
                trade = lot.trade
            else:
                lot.quantity -= take
                lot.trade.quantity = lot.quantity
                lot.trade.commission -= entry_commission
                lot.trade.slippage_cost -= entry_impact
                trade = TradeResult(
                    trade_id=f"TR-{len(self.trades) + 1:06d}",
                    instrument_id=iid,
                    side=TradeSide.LONG,
                    status=TradeStatus.OPEN,
                    entry_time=lot.trade.entry_time,
                    entry_price=lot.trade.entry_price,
                    quantity=take,
                    commission=entry_commission,
                    slippage_cost=entry_impact,
                    reason=lot.trade.reason,
                    planned_date=lot.trade.planned_date,
                )
                self.trades.append(trade)
            trade.status = TradeStatus.CLOSED
            trade.exit_time = when
            trade.exit_price = price
            trade.commission = entry_commission + per_share_commission * take
            trade.slippage_cost = entry_impact + per_share_impact * take
            trade.gross_pnl = (reference - lot.entry_reference) * take
            trade.realized_pnl = (
                (price - trade.entry_price) * take - entry_commission - per_share_fee * take
            )
            trade.return_rate = trade.realized_pnl / (trade.entry_price * take)
            if planned is not None:
                trade.reason = TradeReason.HALT_DEFERRED
                trade.planned_date = planned
            remaining -= take

    # --- 15:30 봉: 평가 → 판단 ------------------------------------------------------

    def on_bar(self, bar: Bar) -> None:
        self._guarded(lambda: self._on_bar(bar))

    def _on_bar(self, bar: Bar) -> None:
        iid = str(bar.bar_type.instrument_id)
        day = _day_of(bar.ts_event)
        if day != self._current_day:
            self._begin_day(day)
        self.last_close[iid] = bar.close.as_decimal()
        self._seen_bars.add(iid)
        if len(self._seen_bars) == self.prepared.expected_per_day[day]:
            self._end_day(day, bar.ts_event)

    def _market_value(self, iid: str) -> Decimal:
        return self.quantity[iid] * self.last_close[iid] if self.quantity[iid] else ZERO

    def _end_day(self, day: date, ns_close: int) -> None:
        if day not in self.plan.evaluation_days:
            return
        position_value = sum((self._market_value(iid) for iid in self.instrument_ids), ZERO)
        equity = self.cash + position_value
        self.peak = max(self.peak, equity)
        is_first = not self.series
        is_last = day == self.plan.evaluation_days[-1]
        positions = None
        if is_first or self.changed or is_last:
            positions = [
                PositionSnapshot(
                    instrument_id=iid,
                    quantity=self.quantity[iid],
                    market_value=self._market_value(iid),
                    weight=self._market_value(iid) / equity,
                    unrealized_pnl=sum(
                        (
                            (self.last_close[iid] - lot.trade.entry_price) * lot.quantity
                            for lot in self.lots[iid]
                        ),
                        ZERO,
                    ),
                )
                for iid in self.instrument_ids
                if self.quantity[iid]
            ]
        snapshot = PortfolioSnapshot(
            date=day,
            equity=equity,
            cash=self.cash,
            position_value=position_value,
            daily_return=equity / self.series[-1].equity - 1 if self.series else ZERO,
            cumulative_return=equity / self.request.portfolio.initial_cash - 1,
            drawdown=equity / self.peak - 1,
            positions=positions,
            cash_flows=list(self.cash_flows_today) or None,
        )
        self.series.append(snapshot)
        # 이월된 주문이 남아 있으면 아직 그 리밸런싱의 실제 비중이 아니다 — 다 체결된 날 기록한다.
        if self.pending_target is not None and self.pending_target[0] <= day and not self.pending:
            self._record_weights(day, equity, self.pending_target[1])
            self.pending_target = None
        self.changed = False
        self._report_progress(snapshot)
        if day in self.plan.decision_days:
            self._decide(day, ns_close, equity)

    def _record_weights(self, day: date, equity: Decimal, target: dict[str, Decimal]) -> None:
        """체결일 종가 기준 실제 비중. 목표와의 차이(Σ|target − actual|)가 ``weightError`` 다."""
        actual = {iid: self._market_value(iid) / equity for iid in self.instrument_ids}
        full_target = {iid: target.get(iid, ZERO) for iid in self.instrument_ids}
        error = sum((abs(full_target[iid] - actual[iid]) for iid in self.instrument_ids), ZERO)
        self.weights_series.append(
            WeightsPoint(date=day, target=full_target, actual=actual, weight_error=error)
        )

    def _report_progress(self, snapshot: PortfolioSnapshot) -> None:
        if self.on_progress_callback is None:
            return
        try:
            self.on_progress_callback(
                ProgressPoint(
                    date=snapshot.date,
                    equity=snapshot.equity,
                    cumulative_return=snapshot.cumulative_return,
                    drawdown=snapshot.drawdown,
                ),
                len(self.series),
                len(self.plan.evaluation_days),
            )
        except Exception as exc:  # noqa: BLE001 - 소비자가 재시도 판단을 한다
            self.callback_error = exc
            self.stop()

    def _decide(self, day: date, ns_close: int, equity: Decimal) -> None:
        """판단일 종가. 목표 비중 → 정수 수량 → 다음 거래일 주문."""
        history = self.signal_history.loc[: pd.Timestamp(day)]
        result = compute_weights(self.request.allocation, self.request.universe.items, history, day)
        self.weights_source = result.source
        self.exceptions.extend(result.exceptions)
        fill_day = self.plan.next_trading_day(day)
        if fill_day is None:
            return

        targets: dict[str, int] = {}
        for iid in self.instrument_ids:
            weight = result.weights.get(iid, ZERO)
            close = self.last_close.get(iid)
            if weight <= ZERO or close is None:
                targets[iid] = 0
                continue
            # 종가 기준 예산. 체결은 다음 시가라 어긋날 수 있고, 그 차이는 제출 시점에 줄인다.
            targets[iid] = self.costs.affordable_quantity(equity * weight, close)
            if targets[iid] == 0:
                self.exceptions.append(
                    ExceptionRecord(
                        date=day,
                        instrument_id=iid,
                        kind=WEIGHT_ROUNDING,
                        rule="portfolio.integerLots=true",
                        action=f"target weight {weight} buys no whole share at {close}; kept cash",
                    )
                )

        for iid in self.instrument_ids:
            delta = targets[iid] - int(self.quantity[iid])
            if delta == 0 or iid in self.pending:
                continue
            side = Side.BUY if delta > 0 else Side.SELL
            record = OrderRecord(
                order_id=f"PLANNED-{len(self.orders) + 1:06d}",
                signal_time=timestamp(ns_close),
                quantity=Decimal(abs(delta)),
                side=side.value,
            )
            self.orders.append(record)
            self.pending[iid] = PendingOrder(iid, side, abs(delta), fill_day, record)
        self.pending_target = (fill_day, dict(result.weights))


def execute_portfolio(
    request: EngineInput,
    market: MarketData,
    plan: DayPlan,
    on_progress: ProgressCallback | None,
) -> PortfolioRebalanceExecution:
    """모든 판단과 체결은 실제 BacktestEngine 이벤트 루프가 이끈다."""
    prepared = build_market_events(request, market)
    engine = BacktestEngine(
        BacktestEngineConfig(logging=LoggingConfig(bypass_logging=True), run_analysis=False)
    )
    engine.add_venue(
        VENUE,
        oms_type=OmsType.NETTING,
        account_type=AccountType.MARGIN,  # 모듈 주석 참고: 분할 뒤 매도가 CASH 계좌에서 거절된다
        starting_balances=[Money(request.portfolio.initial_cash, prepared.currency)],
        bar_execution=False,
        trade_execution=False,
        fill_model=FillModel(prob_slippage=0.0, random_seed=effective_random_seed(request.run)),
    )
    for instrument in prepared.instruments.values():
        engine.add_instrument(instrument)
    engine.add_data(prepared.events)
    execution = PortfolioRebalanceExecution(request, market, prepared, plan, on_progress)
    engine.add_strategy(execution)
    try:
        engine.run()
        if execution.callback_error is not None:
            raise CallbackError("Progress callback failed") from execution.callback_error
        if execution.failure is not None:
            if isinstance(execution.failure, AllocationError):
                raise execution.failure  # 배분·추정 오류는 자기 코드로 결과가 된다
            raise EngineFailure(
                f"{type(execution.failure).__name__}: {execution.failure}"
            ) from execution.failure
        if len(execution.series) != len(plan.evaluation_days):
            raise EngineFailure(
                f"{len(execution.series)} snapshots for {len(plan.evaluation_days)} evaluation days"
            )
        for order in execution.pending.values():
            # 마지막 판단일 뒤 거래 가능일이 없어 끝내 체결하지 못한 주문. 버리지 않고 남긴다.
            execution.warnings.append(
                ContractWarning(
                    date=plan.evaluation_days[-1],
                    instrument_id=order.instrument_id,
                    code="ORDER_NOT_FILLED",
                    message=(
                        f"{order.side.value} {order.quantity} planned for "
                        f"{order.planned_date.isoformat()} found no tradable day before the end"
                    ),
                )
            )
    finally:
        engine.dispose()
    return execution
