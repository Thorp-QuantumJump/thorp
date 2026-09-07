from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

import pandas as pd
import pytest

from worker.engine import run_engine
from worker.engine_contracts import EngineInput, PortfolioSnapshot, ProgressSnapshot
from worker.market_data import CsvDataProvider, MarketDataError, MarketDataset, validate_prices
from worker.nautilus_engine import CallbackError

FIXTURE = Path(__file__).parents[1] / "examples" / "momentum_fixture.csv"


def request(**overrides: Any) -> EngineInput:
    values = {
        "run": {"backtestId": "TEST", "start": "2025-01-06", "end": "2025-01-14"},
        "strategy": {"parameters": {"lookbackDays": 2}},
        "portfolio": {"initialCash": "1000"},
    }
    values.update(overrides)
    return EngineInput.model_validate(values)


def test_native_next_open_round_trips_and_callback() -> None:
    callbacks = []
    output = run_engine(
        request(), data_provider=CsvDataProvider(FIXTURE), on_snapshot=callbacks.append
    )
    assert output.run.status == "COMPLETED", output.error
    assert len(callbacks) == len(output.portfolio_series) == 7
    assert [s.completed_trading_days for s in callbacks] == list(range(1, 8))
    assert all(s.total_trading_days == 7 and len(s.portfolio_series) == 1 for s in callbacks)
    assert output.diagnostics.metadata["warmupTradingDays"] == 2
    orders = output.execution.orders
    assert len(orders) == 3
    assert [o.side for o in orders] == ["BUY", "SELL", "BUY"]
    assert orders[0].quantity == 9
    assert orders[0].signal_time.date() == date(2025, 1, 6)
    assert orders[0].fill_time.date() == date(2025, 1, 7)
    assert (orders[0].signal_time.hour, orders[0].signal_time.minute) == (6, 30)
    assert orders[0].fill_time.hour == 0  # 09:00 Asia/Seoul, not the closing bar.
    assert orders[0].submitted_time == orders[0].fill_time
    assert orders[0].fill_price == Decimal("105.0525")
    assert orders[1].fill_price == Decimal("94.9525")
    assert output.portfolio_series[1].cash == Decimal("54.5275")
    assert output.performance.trade_count == 1
    assert [t.status for t in output.trades] == ["CLOSED", "OPEN"]
    closed, opened = output.trades
    assert closed.gross_pnl == -90
    assert closed.slippage_cost == Decimal("0.9")
    assert closed.realized_pnl == Decimal("-90.9")
    assert closed.realized_pnl == closed.gross_pnl - closed.commission - closed.slippage_cost
    assert opened.realized_pnl is None and opened.exit_time is None
    assert output.performance.win_rate == 0
    assert output.performance.max_drawdown < 0
    assert output.performance.final_equity == Decimal("1068.7")
    for point in output.portfolio_series:
        assert point.equity == point.cash + point.position_value
        assert point.cash >= 0
        if point.positions is not None:
            assert sum(p.market_value for p in point.positions) == point.position_value
    payload = output.model_dump(mode="json", by_alias=True)
    assert isinstance(payload["performance"]["totalReturn"], str)
    assert payload["trades"][-1]["realizedPnl"] is None


class FrameProvider:
    def __init__(self, frame: pd.DataFrame) -> None:
        self.frame = frame

    def load(self, request: EngineInput) -> MarketDataset:
        return MarketDataset(self.frame, "000660.XKRX", "000660.KS", "FIXED_TEST_DATA")


def test_gap_rejects_whole_order_and_retries_without_future_sizing() -> None:
    frame = CsvDataProvider(FIXTURE).load(request()).prices
    frame.loc["2025-01-07", ["open", "high"]] = [200, 200]
    output = run_engine(request(), data_provider=FrameProvider(frame))
    assert output.run.status == "COMPLETED", output.error
    first, second = output.execution.orders[:2]
    assert first.quantity == 9  # Same close-sized quantity despite the unknown future gap.
    assert first.status == "REJECTED" and first.fill_price is None
    assert second.signal_time.date() == date(2025, 1, 7)
    assert second.quantity == 8
    assert second.status == "FILLED"
    assert output.portfolio_series[1].cash == 1000
    assert "INSUFFICIENT_CASH" in [w.code for w in output.diagnostics.warnings]
    assert output.execution.rejected_order_count == 1


def test_final_signal_unfilled_and_single_day_metrics_null() -> None:
    req = request(run={"backtestId": "LAST", "start": "2025-01-06", "end": "2025-01-06"})
    output = run_engine(req, data_provider=CsvDataProvider(FIXTURE))
    assert output.run.status == "COMPLETED", output.error
    assert output.execution.order_count == 1
    assert output.execution.fill_count == 0
    assert output.execution.orders[0].status == "PENDING"
    assert output.performance.final_equity == 1000
    assert output.performance.sharpe_ratio is None
    assert output.performance.volatility is None
    assert output.performance.win_rate is None
    assert output.performance.cagr is None


def test_insufficient_history_stays_cash_and_warns() -> None:
    req = request(strategy={"parameters": {"lookbackDays": 120}})
    output = run_engine(req, data_provider=CsvDataProvider(FIXTURE))
    assert output.run.status == "COMPLETED", output.error
    assert output.execution.order_count == 0
    assert output.performance.final_equity == 1000
    assert output.performance.sharpe_ratio is None
    assert any(w.code == "INSUFFICIENT_HISTORY" for w in output.diagnostics.warnings)


def test_callback_failure_propagates() -> None:
    def broken(snapshot: ProgressSnapshot) -> None:
        raise RuntimeError("consumer unavailable")

    with pytest.raises(CallbackError):
        run_engine(request(), data_provider=CsvDataProvider(FIXTURE), on_snapshot=broken)


@pytest.mark.parametrize("damage", ["duplicate", "unordered", "nan", "negative", "range"])
def test_invalid_ohlcv_rejected(damage: str) -> None:
    frame = CsvDataProvider(FIXTURE).load(request()).prices
    if damage == "duplicate":
        frame = pd.concat([frame, frame.iloc[-1:]])
    elif damage == "unordered":
        frame = frame.iloc[::-1]
    elif damage == "nan":
        frame.iloc[0, 0] = float("nan")
    elif damage == "negative":
        frame.iloc[0, 0] = -1
    else:
        frame.iloc[0, 1] = 1
    with pytest.raises(MarketDataError):
        validate_prices(frame)


def test_metrics_follow_sample_standard_deviation_and_calendar_days() -> None:
    from worker.performance import calculate_performance

    points = [
        PortfolioSnapshot(
            date=date(2025, 1, i + 1),
            equity=Decimal(equity),
            cash=Decimal(equity),
            position_value=0,
            daily_return=Decimal(ret),
            cumulative_return=0,
            drawdown=Decimal(dd),
            positions=[],
        )
        for i, (equity, ret, dd) in enumerate(
            [
                ("1000", "0", "0"),
                ("1100", "0.1", "0"),
                ("990", "-0.1", "-0.1"),
            ]
        )
    ]
    result = calculate_performance(Decimal(1000), points, [])
    assert float(result.volatility) == pytest.approx(0.1 * 252**0.5)
    assert result.sharpe_ratio == 0
    assert result.max_drawdown == Decimal("-0.1")
    assert result.total_return == Decimal("-0.01")
    assert float(result.cagr) == pytest.approx(0.99 ** (365 / 2) - 1)


def test_future_data_changes_do_not_change_previous_output() -> None:
    original = CsvDataProvider(FIXTURE).load(request()).prices
    changed = original.copy()
    changed.loc["2025-01-14", ["open", "high", "low", "close"]] = [900, 910, 890, 905]
    baseline = run_engine(request(), data_provider=FrameProvider(original))
    alternate = run_engine(request(), data_provider=FrameProvider(changed))
    assert baseline.run.status == alternate.run.status == "COMPLETED"
    assert baseline.portfolio_series[:-1] == alternate.portfolio_series[:-1]
    assert baseline.execution.orders[2].quantity == alternate.execution.orders[2].quantity
    assert alternate.execution.orders[2].status == "REJECTED"


def test_full_input_example_and_output_json_roundtrip() -> None:
    from worker.engine_contracts import EngineOutput

    example = FIXTURE.with_name("engine-input.json").read_text(encoding="utf-8")
    parsed = EngineInput.model_validate_json(example)
    assert parsed == EngineInput.model_validate_json(parsed.model_dump_json(by_alias=True))
    output = run_engine(request(), data_provider=CsvDataProvider(FIXTURE))
    assert output == EngineOutput.model_validate_json(output.model_dump_json(by_alias=True))
    assert "positions" not in output.portfolio_series[2].model_dump(mode="json", by_alias=True)


def test_callbacks_are_isolated_from_result_ledger() -> None:
    def mutate(snapshot: ProgressSnapshot) -> None:
        snapshot.portfolio_series[0].equity = Decimal("999999999")

    output = run_engine(request(), data_provider=CsvDataProvider(FIXTURE), on_snapshot=mutate)
    assert output.run.status == "COMPLETED"
    assert output.performance.final_equity == Decimal("1068.7")


def test_yfinance_identity_and_exclusive_end(monkeypatch: pytest.MonkeyPatch) -> None:
    import yfinance

    from worker.market_data import YFinanceDataProvider

    calls = []

    class FakeTicker:
        def __init__(self, ticker: str) -> None:
            calls.append(ticker)

        def history(self, **kwargs: Any) -> pd.DataFrame:
            calls.append(kwargs)
            return CsvDataProvider(FIXTURE).load(request()).prices

        def get_history_metadata(self) -> dict:
            return {
                "symbol": "000660.KS",
                "currency": "KRW",
                "instrumentType": "EQUITY",
                "exchangeName": "KSC",
                "longName": "SK hynix Inc.",
            }

    monkeypatch.setattr(yfinance, "Ticker", FakeTicker)
    dataset = YFinanceDataProvider().load(request())
    assert calls[0] == "000660.KS"
    assert calls[1]["end"] == "2025-01-15"
    assert calls[1]["auto_adjust"] is True
    assert calls[1]["keepna"] is True
    assert dataset.metadata["instrumentName"] == "SK hynix Inc."
    monkeypatch.setattr(FakeTicker, "get_history_metadata", lambda _: {"symbol": "WRONG"})
    with pytest.raises(MarketDataError, match="Unexpected identity"):
        YFinanceDataProvider().load(request())


def test_empty_evaluation_is_failed_without_fake_metrics() -> None:
    req = request(run={"backtestId": "EMPTY", "start": "2026-01-01", "end": "2026-01-02"})
    output = run_engine(req, data_provider=CsvDataProvider(FIXTURE))
    assert output.run.status == "FAILED"
    assert output.error.code == "NO_MARKET_DATA"
    assert output.performance is None and output.execution is None


def test_120_previous_closes_are_required() -> None:
    dates = pd.bdate_range("2024-01-01", periods=123)
    values = [100] * 120 + [110, 115, 120]
    frame = pd.DataFrame({name: values for name in ["open", "high", "low", "close"]}, index=dates)
    frame["volume"] = 0  # Volume does not limit fills in this demo.
    frame.loc[dates[121], ["open", "low"]] = 110
    req = request(
        run={"backtestId": "120", "start": dates[119].date(), "end": dates[-1].date()},
        strategy={"parameters": {"lookbackDays": 120}},
    )
    output = run_engine(req, data_provider=FrameProvider(frame))
    assert output.run.status == "COMPLETED", output.error
    assert output.execution.orders[0].signal_time.date() == dates[120].date()
    assert output.execution.orders[0].fill_time.date() == dates[121].date()
    assert output.execution.order_count == 1
