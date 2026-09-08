"""Nautilus execution gateway: close-time intent, next-open native market execution."""

from collections import deque
from collections.abc import Callable
from copy import deepcopy
from datetime import date, datetime
from decimal import Decimal
from typing import Any
from zoneinfo import ZoneInfo

import pandas as pd
from nautilus_trader.backtest.engine import BacktestEngine
from nautilus_trader.backtest.models import FillModel
from nautilus_trader.config import BacktestEngineConfig, LoggingConfig, StrategyConfig
from nautilus_trader.model.data import Bar, BarType, QuoteTick
from nautilus_trader.model.enums import AccountType, CurrencyType, OmsType, OrderSide
from nautilus_trader.model.identifiers import InstrumentId, Symbol, Venue
from nautilus_trader.model.instruments import Equity
from nautilus_trader.model.objects import Currency, Money, Price, Quantity
from nautilus_trader.trading.strategy import Strategy as NautilusStrategy

from worker.artifacts import OrderRecord
from worker.engine_contracts import (
    EngineInput,
    PortfolioSnapshot,
    PositionSnapshot,
    ProgressSnapshot,
    TradeResult,
    WarningRecord,
)
from worker.strategies.momentum import MomentumStrategy

SnapshotCallback = Callable[[ProgressSnapshot], None]
ZERO = Decimal("0")
SLIPPAGE = Decimal("0.0005")
PRICE_QUANTUM = Decimal("0.000001")


class CallbackError(RuntimeError):
    """Preserve callback failure so the consumer can retry publication explicitly."""


def timestamp(ns: int) -> datetime:
    return pd.Timestamp(ns, tz="UTC").to_pydatetime(warn=False)


def as_price(value: Any) -> Price:
    return Price.from_str(format(Decimal(str(value)).quantize(PRICE_QUANTUM), ".6f"))


class MomentumExecution(NautilusStrategy):
    """Orders/fills/accounting here; pure signal calculation stays in strategies/."""

    def __init__(
        self,
        request: EngineInput,
        instrument: Equity,
        bar_type: BarType,
        evaluation_dates: list[date],
        reference_opens: dict[int, Decimal],
        on_snapshot: SnapshotCallback | None,
    ) -> None:
        super().__init__(StrategyConfig(strategy_id="MOMENTUM-001"))
        self.request = request
        self.instrument = instrument
        self.bar_type = bar_type
        self.evaluation_dates = evaluation_dates
        self.reference_opens = reference_opens
        self.on_snapshot_callback = on_snapshot
        self.signal_strategy = MomentumStrategy(request.strategy.parameters.lookback_days)
        self.closes: deque[Decimal] = deque(maxlen=self.signal_strategy.lookback_days + 1)
        self.cash = request.portfolio.initial_cash
        self.quantity = 0
        self.pending = None
        self.pending_record: OrderRecord | None = None
        self.orders: list[OrderRecord] = []
        self.trades: list[TradeResult] = []
        # Pre-slippage entry opens stay internal: the message carries the actual fill price
        # and slippageCost, from which the reference price is derivable.
        self.entry_reference_prices: dict[str, Decimal] = {}
        self.series: list[PortfolioSnapshot] = []
        self.warnings: list[WarningRecord] = []
        self.peak = self.cash
        self.changed = False
        self.callback_error: Exception | None = None

    def on_start(self) -> None:
        self.subscribe_bars(self.bar_type)
        self.subscribe_quote_ticks(self.instrument.id)

    def on_quote_tick(self, tick: QuoteTick) -> None:
        if self.pending is None:
            return
        record = self.pending_record
        if record.submitted_time is not None:
            return
        # Only the currently delivered quote's reference price is consulted.
        record.reference_price = self.reference_opens[tick.ts_event]
        record.submitted_time = timestamp(tick.ts_event)
        price = tick.ask_price.as_decimal() if record.side == "BUY" else tick.bid_price.as_decimal()
        if record.side == "BUY" and record.quantity * price > self.cash:
            self._reject("INSUFFICIENT_CASH", "Next open fill cost exceeds available cash")
            return
        self.submit_order(self.pending)

    def _reject(self, code: str, reason: str) -> None:
        record = self.pending_record
        record.status = "REJECTED"
        record.reason = reason
        self.warnings.append(
            WarningRecord(
                date=record.submitted_time.astimezone(ZoneInfo("Asia/Seoul")).date(),
                instrument_id=str(self.instrument.id),
                code=code,
                message=reason,
            )
        )
        self.pending = None
        self.pending_record = None

    def on_order_denied(self, event: Any) -> None:
        code = "INSUFFICIENT_CASH" if "BALANCE" in event.reason else "ORDER_REJECTED"
        self._reject(code, event.reason)

    def on_order_rejected(self, event: Any) -> None:
        self._reject("ORDER_REJECTED", event.reason)

    def on_order_filled(self, event: Any) -> None:
        record = self.pending_record
        quantity = event.last_qty.as_decimal()
        price = event.last_px.as_decimal()
        if quantity != record.quantity:
            raise RuntimeError("Partial fills are not supported by this demo")
        record.status = "FILLED"
        record.fill_time = timestamp(event.ts_event)
        record.fill_price = price
        slip = abs(price - record.reference_price) * quantity
        if record.side == "BUY":
            self.cash -= price * quantity
            if self.cash < 0:
                raise RuntimeError("Negative cash invariant violated")
            self.quantity = int(quantity)
            trade = TradeResult(
                trade_id=f"TR-{len(self.trades) + 1:06d}",
                instrument_id=str(self.instrument.id),
                entry_time=record.fill_time,
                entry_price=price,
                quantity=quantity,
                slippage_cost=slip,
            )
            self.entry_reference_prices[trade.trade_id] = record.reference_price
            self.trades.append(trade)
        else:
            self.cash += price * quantity
            self.quantity = 0
            trade = self.trades[-1]
            entry_reference = self.entry_reference_prices[trade.trade_id]
            trade.status = "CLOSED"
            trade.exit_time = record.fill_time
            trade.exit_price = price
            trade.slippage_cost += slip
            trade.gross_pnl = (record.reference_price - entry_reference) * quantity
            trade.realized_pnl = (price - trade.entry_price) * quantity
            trade.return_rate = trade.realized_pnl / (trade.entry_price * quantity)
        self.pending = None
        self.pending_record = None
        self.changed = True

    def on_bar(self, bar: Bar) -> None:
        close = bar.close.as_decimal()
        self.closes.append(close)
        day = pd.Timestamp(bar.ts_event, tz="UTC").tz_convert("Asia/Seoul").date()
        if not self.request.run.start <= day <= self.request.run.end:
            return
        if len(self.closes) <= self.signal_strategy.lookback_days:
            if not any(w.code == "INSUFFICIENT_HISTORY" for w in self.warnings):
                self.warnings.append(
                    WarningRecord(
                        date=day,
                        instrument_id=str(self.instrument.id),
                        code="INSUFFICIENT_HISTORY",
                        message="Not enough previous trading closes; staying in cash until ready",
                    )
                )
        elif self.pending is None:
            prices = pd.DataFrame({"close": list(self.closes)})
            target = int(self.signal_strategy.generate_signals(prices).iloc[-1])
            if target and self.quantity == 0:
                budget = self.cash * self.request.portfolio.position_sizing.max_position_pct
                quantity = int(budget // (close * (1 + SLIPPAGE)))
                if quantity:
                    self._create_order(OrderSide.BUY, quantity, bar.ts_event)
            elif not target and self.quantity:
                self._create_order(OrderSide.SELL, self.quantity, bar.ts_event)
        value = self.quantity * close
        equity = self.cash + value
        self.peak = max(self.peak, equity)
        positions = None
        if not self.series or self.changed or day == self.evaluation_dates[-1]:
            positions = []
            if self.quantity:
                positions.append(
                    PositionSnapshot(
                        instrument_id=str(self.instrument.id),
                        quantity=Decimal(self.quantity),
                        market_value=value,
                        weight=value / equity,
                        unrealized_pnl=(close - self.trades[-1].entry_price) * self.quantity,
                    )
                )
        snapshot = PortfolioSnapshot(
            date=day,
            equity=equity,
            cash=self.cash,
            position_value=value,
            daily_return=equity / self.series[-1].equity - 1 if self.series else ZERO,
            cumulative_return=equity / self.request.portfolio.initial_cash - 1,
            drawdown=equity / self.peak - 1,
            positions=positions,
        )
        self.series.append(snapshot)
        self.changed = False
        if self.on_snapshot_callback is not None:
            try:
                self.on_snapshot_callback(
                    ProgressSnapshot(
                        completed_trading_days=len(self.series),
                        total_trading_days=len(self.evaluation_dates),
                        portfolio_series=[deepcopy(snapshot)],
                    )
                )
            except Exception as exc:
                self.callback_error = exc
                raise CallbackError("Snapshot callback failed") from exc

    def _create_order(self, side: OrderSide, quantity: int, ns: int) -> None:
        self.pending = self.order_factory.market(
            instrument_id=self.instrument.id,
            order_side=side,
            quantity=Quantity.from_int(quantity),
        )
        self.pending_record = OrderRecord(
            order_id=str(self.pending.client_order_id),
            signal_time=timestamp(ns),
            quantity=Decimal(quantity),
            side="BUY" if side == OrderSide.BUY else "SELL",
        )
        self.orders.append(self.pending_record)


def execute_nautilus(
    request: EngineInput,
    prices: pd.DataFrame,
    on_snapshot: SnapshotCallback | None,
) -> MomentumExecution:
    """Every signal and fill is driven by a real BacktestEngine event loop."""
    instrument_id = InstrumentId.from_str(request.universe.instrument_ids[0])
    # Native KRW has zero decimals; a distinct simulation code preserves fractional
    # adjusted-price costs without overwriting the process-wide real KRW definition.
    currency = Currency("KRW_SIM", 8, 0, "KRW simulation (1:1)", CurrencyType.FIAT)
    Currency.register(currency)
    instrument = Equity(
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
    bar_type = BarType.from_str(f"{instrument_id}-1-DAY-LAST-EXTERNAL")
    events = []
    reference_opens = {}
    dates = []
    for day, row in prices.iterrows():
        if day.date() > request.run.end:
            continue
        ns_open = (day.tz_localize("Asia/Seoul") + pd.Timedelta(hours=9)).value
        ns_close = (day.tz_localize("Asia/Seoul") + pd.Timedelta(hours=15, minutes=30)).value
        reference = as_price(row.open).as_decimal()
        reference_opens[ns_open] = reference
        events.append(
            QuoteTick(
                instrument_id,
                as_price(reference * (1 - SLIPPAGE)),
                as_price(reference * (1 + SLIPPAGE)),
                Quantity.from_str("18446744073"),
                Quantity.from_str("18446744073"),
                ns_open,
                ns_open,
            )
        )
        events.append(
            Bar(
                bar_type,
                as_price(row.open),
                as_price(row.high),
                as_price(row.low),
                as_price(row.close),
                Quantity.from_int(int(row.volume)),
                ns_close,
                ns_close,
            )
        )
        if request.run.start <= day.date() <= request.run.end:
            dates.append(day.date())
    execution = MomentumExecution(
        request, instrument, bar_type, dates, reference_opens, on_snapshot
    )
    engine = BacktestEngine(
        config=BacktestEngineConfig(
            logging=LoggingConfig(bypass_logging=True),
            run_analysis=False,
        )
    )
    try:
        engine.add_venue(
            venue=Venue("XKRX"),
            oms_type=OmsType.NETTING,
            account_type=AccountType.CASH,
            starting_balances=[Money(request.portfolio.initial_cash, currency)],
            bar_execution=False,
            trade_execution=False,
            fill_model=FillModel(prob_slippage=0, random_seed=request.run.random_seed),
        )
        engine.add_instrument(instrument)
        engine.add_data(events)
        engine.add_strategy(execution)
        engine.run()
        if execution.callback_error is not None:
            raise CallbackError("Snapshot callback failed") from execution.callback_error
        if len(execution.series) != len(dates):
            raise RuntimeError("Engine did not complete every evaluation day")
        native_fills = sum(order.filled_qty.as_decimal() > 0 for order in engine.cache.orders())
        if native_fills != sum(record.status == "FILLED" for record in execution.orders):
            raise RuntimeError("Native engine fills do not match output ledger")
        native_cash = engine.portfolio.account(Venue("XKRX")).balance_total(currency).as_decimal()
        if native_cash != execution.cash:
            raise RuntimeError(
                f"Native cash {native_cash} differs from output cash {execution.cash}"
            )
        if execution.pending is not None:
            execution.warnings.append(
                WarningRecord(
                    date=dates[-1],
                    instrument_id=str(instrument_id),
                    code="NO_NEXT_TRADING_DAY",
                    message="Final close order remains unsubmitted; no next evaluation open exists",
                )
            )
        return execution
    finally:
        engine.dispose()
