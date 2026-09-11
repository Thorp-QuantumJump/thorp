import json
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

import pandas as pd
import pytest
from conftest import engine_input_body

from worker.contracts.engine import BacktestEngine
from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import PortfolioSnapshot, RunStatus, TradeStatus
from worker.contracts.events import ProgressPoint
from worker.engine_runner import NautilusMomentumEngine
from worker.market_data import CsvDataProvider, MarketDataError, MarketDataset, validate_prices
from worker.nautilus_engine import CallbackError

FIXTURE = Path(__file__).parents[1] / "examples" / "momentum_fixture.csv"


def request(**overrides: Any) -> EngineInput:
    """회귀 기준 실행 조건. 전체 본문은 ``conftest.ENGINE_INPUT_BODY``에 있다."""
    return EngineInput.model_validate(engine_input_body(**overrides))


class ProgressRecorder:
    """계약 ``ProgressCallback`` 모양(인자 3개)으로만 호출을 받는 기록기."""

    def __init__(self) -> None:
        self.calls: list[tuple[ProgressPoint, int, int]] = []

    def __call__(
        self, point: ProgressPoint, completed_trading_days: int, total_trading_days: int
    ) -> None:
        self.calls.append((point, completed_trading_days, total_trading_days))


def test_native_next_open_round_trips_and_callback() -> None:
    recorder = ProgressRecorder()
    output, artifacts = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run_with_artifacts(
        request(), recorder
    )
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    assert len(recorder.calls) == len(output.portfolio_series) == 7
    assert [done for _, done, _ in recorder.calls] == list(range(1, 8))
    assert all(total == 7 for _, _, total in recorder.calls)
    assert all(isinstance(point, ProgressPoint) for point, _, _ in recorder.calls)
    assert artifacts.metadata["warmupTradingDays"] == 2
    # 계약이 randomSeed 미지정을 허용하므로 실제로 쓴 시드를 산출물에 남긴다.
    assert artifacts.metadata["randomSeed"] == 42
    orders = artifacts.orders
    assert len(orders) == 3
    assert output.execution.order_count == 3
    assert [o.side for o in orders] == ["BUY", "SELL", "BUY"]
    assert orders[0].quantity == 9
    assert orders[0].signal_time.date() == date(2025, 1, 6)
    assert orders[0].fill_time.date() == date(2025, 1, 7)
    assert (orders[0].signal_time.hour, orders[0].signal_time.minute) == (6, 30)
    assert orders[0].fill_time.hour == 0  # 09:00 Asia/Seoul, not the closing bar.
    assert orders[0].submitted_time == orders[0].fill_time
    assert orders[0].fill_price == Decimal("105.0525")
    assert orders[1].fill_price == Decimal("94.9525")
    # Pre-slippage opens live only in the artifacts, and still drive grossPnl.
    assert orders[0].reference_price == Decimal("105")
    assert orders[1].reference_price == Decimal("95")
    assert output.portfolio_series[1].cash == Decimal("54.5275")
    assert output.performance.trade_count == 1
    assert [t.status for t in output.trades] == [TradeStatus.CLOSED, TradeStatus.OPEN]
    closed, opened = output.trades
    assert closed.gross_pnl == -90
    assert (
        closed.gross_pnl
        == (orders[1].reference_price - orders[0].reference_price) * closed.quantity
    )
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
    payload = json.loads(output.to_json())
    assert isinstance(payload["performance"]["totalReturn"], str)
    assert payload["performance"]["currency"] == "KRW"
    # 계약 §5: 계산 불가·미지정 값은 명시 null이 아니라 키 자체를 쓰지 않는다.
    assert "error" not in payload["run"]
    assert payload["trades"][-1]["status"] == "OPEN"
    assert not {"exitTime", "exitPrice", "grossPnl", "realizedPnl", "returnRate"} & set(
        payload["trades"][-1]
    )
    assert {"exitTime", "exitPrice", "grossPnl", "realizedPnl", "returnRate"} <= set(
        payload["trades"][0]
    )
    # Diagnostic-only keys must not travel in the message.
    assert "orders" not in payload["execution"]
    assert "metadata" not in payload["diagnostics"]
    assert not [key for key in payload["trades"][0] if "eferencePrice" in key]


def test_injected_provider_is_reusable_across_runs() -> None:
    """공급자를 생성자로 한 번 주입한 인스턴스로 여러 번 실행해도 상태가 새지 않는다."""
    engine = NautilusMomentumEngine(CsvDataProvider(FIXTURE))
    first_recorder = ProgressRecorder()
    second_recorder = ProgressRecorder()
    first, first_artifacts = engine.run_with_artifacts(request(), first_recorder)
    second, second_artifacts = engine.run_with_artifacts(request(), second_recorder)
    assert first.run.status == second.run.status == "COMPLETED", first.run.error or second.run.error
    assert first.performance == second.performance
    assert first.portfolio_series == second.portfolio_series
    assert first.execution == second.execution
    assert first.trades == second.trades
    assert first.diagnostics == second.diagnostics
    assert first_artifacts == second_artifacts
    assert len(first_recorder.calls) == len(second_recorder.calls) == 7
    assert first_recorder.calls == second_recorder.calls
    # 다른 입력을 이어서 실행해도 앞선 실행 결과와 섞이지 않는다.
    single_day = {"runId": "REUSE", "start": "2025-01-06", "end": "2025-01-06"}
    other, other_artifacts = engine.run_with_artifacts(request(run=single_day))
    assert other.run.run_id == "REUSE"
    assert other.execution.order_count == 1
    assert first.execution.order_count == 3
    # 산출물은 반환값으로만 나가므로 앞선 실행의 주문 원장이 뒤 실행에 남지 않는다.
    assert len(other_artifacts.orders) == 1
    assert len(first_artifacts.orders) == 3


class FrameProvider:
    def __init__(self, frame: pd.DataFrame) -> None:
        self.frame = frame

    def load(self, request: EngineInput) -> MarketDataset:
        return MarketDataset(self.frame, "000660.XKRX", "000660.KS", "FIXED_TEST_DATA")


def test_gap_rejects_whole_order_and_retries_without_future_sizing() -> None:
    frame = CsvDataProvider(FIXTURE).load(request()).prices
    frame.loc["2025-01-07", ["open", "high"]] = [200, 200]
    output, artifacts = NautilusMomentumEngine(FrameProvider(frame)).run_with_artifacts(request())
    assert output.run.status == "COMPLETED", output.run.error
    first, second = artifacts.orders[:2]
    assert first.quantity == 9  # Same close-sized quantity despite the unknown future gap.
    assert first.status == "REJECTED" and first.fill_price is None
    assert second.signal_time.date() == date(2025, 1, 7)
    assert second.quantity == 8
    assert second.status == "FILLED"
    assert output.portfolio_series[1].cash == 1000
    assert "INSUFFICIENT_CASH" in [w.code for w in output.diagnostics.warnings]
    assert output.execution.rejected_order_count == 1


def test_final_signal_unfilled_and_single_day_metrics_null() -> None:
    req = request(run={"runId": "LAST", "start": "2025-01-06", "end": "2025-01-06"})
    output, artifacts = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run_with_artifacts(req)
    assert output.run.status == "COMPLETED", output.run.error
    assert output.execution.order_count == 1
    assert output.execution.fill_count == 0
    assert artifacts.orders[0].status == "PENDING"
    assert output.performance.final_equity == 1000
    assert output.performance.sharpe_ratio is None
    assert output.performance.volatility is None
    assert output.performance.win_rate is None
    assert output.performance.cagr is None


def test_insufficient_history_stays_cash_and_warns() -> None:
    req = request(allocation={"parameters": {"lookbackDays": 120}})
    output = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run(req)
    assert output.run.status == "COMPLETED", output.run.error
    assert output.execution.order_count == 0
    assert output.performance.final_equity == 1000
    assert output.performance.sharpe_ratio is None
    assert any(w.code == "INSUFFICIENT_HISTORY" for w in output.diagnostics.warnings)


def test_callback_failure_propagates() -> None:
    def broken(point: ProgressPoint, done: int, total: int) -> None:
        raise RuntimeError("consumer unavailable")

    with pytest.raises(CallbackError):
        NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run(request(), broken)


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
    result = calculate_performance(Decimal(1000), points, [], "KRW")
    assert result.currency == "KRW"
    assert float(result.volatility) == pytest.approx(0.1 * 252**0.5)
    assert result.sharpe_ratio == 0
    assert result.max_drawdown == Decimal("-0.1")
    assert result.total_return == Decimal("-0.01")
    assert float(result.cagr) == pytest.approx(0.99 ** (365 / 2) - 1)


def test_future_data_changes_do_not_change_previous_output() -> None:
    original = CsvDataProvider(FIXTURE).load(request()).prices
    changed = original.copy()
    changed.loc["2025-01-14", ["open", "high", "low", "close"]] = [900, 910, 890, 905]
    baseline, base_artifacts = NautilusMomentumEngine(FrameProvider(original)).run_with_artifacts(
        request()
    )
    alternate, alt_artifacts = NautilusMomentumEngine(FrameProvider(changed)).run_with_artifacts(
        request()
    )
    assert baseline.run.status == alternate.run.status == "COMPLETED"
    assert baseline.portfolio_series[:-1] == alternate.portfolio_series[:-1]
    assert base_artifacts.orders[2].quantity == alt_artifacts.orders[2].quantity
    assert alt_artifacts.orders[2].status == "REJECTED"


def test_full_input_example_and_output_json_roundtrip() -> None:
    from worker.contracts.engine_output import EngineOutput

    example = FIXTURE.with_name("momentum-demo-input.json").read_text(encoding="utf-8")
    parsed = EngineInput.model_validate_json(example)
    assert parsed == EngineInput.model_validate_json(parsed.model_dump_json(by_alias=True))
    output = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run(request())
    assert output == EngineOutput.model_validate_json(output.to_json())
    # 계약 §4.3: positions는 보유 구성이 바뀐 날 + 시작일 + 종료일에만 존재한다.
    assert output.portfolio_series[2].positions is None
    assert "positions" not in json.loads(output.portfolio_series[2].to_json())
    assert "positions" not in json.loads(output.to_json())["portfolioSeries"][2]
    assert "positions" in json.loads(output.to_json())["portfolioSeries"][0]


def test_callbacks_are_isolated_from_result_ledger() -> None:
    def mutate(point: ProgressPoint, done: int, total: int) -> None:
        point.equity = Decimal("999999999")

    output = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run(request(), mutate)
    assert output.run.status == "COMPLETED"
    assert output.performance.final_equity == Decimal("1068.7")
    assert output.portfolio_series[-1].equity == Decimal("1068.7")


def test_engine_satisfies_backtest_engine_protocol_when_called_by_contract() -> None:
    """계약 모양대로 호출해도 실제로 동작하는지 본다.

    ``runtime_checkable`` Protocol의 ``isinstance``는 메서드 이름 존재만 보고 시그니처를
    검사하지 않으므로, 계약 ``ProgressCallback``(인자 3개)로 호출해 실행까지 확인한다.
    """
    engine = NautilusMomentumEngine(CsvDataProvider(FIXTURE))
    assert isinstance(engine, BacktestEngine)
    recorder = ProgressRecorder()
    contract_engine: BacktestEngine = engine
    output = contract_engine.run(request(), recorder)
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    # 진행 콜백은 평가 거래일 1개 단위. 묶음 발행은 실행 Consumer 책임이므로 엔진은 묶지 않는다.
    assert len(recorder.calls) == len(output.portfolio_series)
    assert [done for _, done, _ in recorder.calls] == list(
        range(1, len(output.portfolio_series) + 1)
    )
    assert {total for _, _, total in recorder.calls} == {len(output.portfolio_series)}
    # 진행 중에 보낸 값이 최종 결과와 어긋나면 화면 그래프가 최종 결과와 달라진다.
    final = {point.date: point for point in output.portfolio_series}
    for point, _, _ in recorder.calls:
        assert isinstance(point, ProgressPoint)
        assert set(json.loads(point.to_json())) == {
            "date",
            "equity",
            "cumulativeReturn",
            "drawdown",
        }
        snapshot = final[point.date]
        assert point.equity == snapshot.equity
        assert point.cumulative_return == snapshot.cumulative_return
        assert point.drawdown == snapshot.drawdown


def test_empty_evaluation_is_failed_without_fake_metrics() -> None:
    req = request(run={"runId": "EMPTY", "start": "2026-01-01", "end": "2026-01-02"})
    output, artifacts = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run_with_artifacts(req)
    assert output.run.status is RunStatus.FAILED
    # 실패 사유는 계약 위치(run.error)에 실린다. 여기가 아니면 백엔드가 조용히 버린다.
    assert output.run.error.code == "DATA_INSUFFICIENT"
    assert "No evaluation trading days" in output.run.error.message
    assert output.performance is None and output.execution is None
    payload = json.loads(output.to_json())
    assert payload["run"]["error"]["code"] == "DATA_INSUFFICIENT"
    # 존재하지 않는 성과를 0으로 채우지도, 명시 null로 내보내지도 않는다.
    assert "performance" not in payload and "execution" not in payload
    assert payload["portfolioSeries"] == [] and payload["trades"] == []
    # 실패해도 재현성 메타데이터는 남는다(주문은 만들어지기 전이므로 비어 있다).
    assert artifacts.metadata["metricsVersion"] == "DAILY_252_DDOF1_RF0_V1"
    assert artifacts.orders == []


def test_120_previous_closes_are_required() -> None:
    dates = pd.bdate_range("2024-01-01", periods=123)
    values = [100] * 120 + [110, 115, 120]
    frame = pd.DataFrame({name: values for name in ["open", "high", "low", "close"]}, index=dates)
    frame["volume"] = 0  # Volume does not limit fills in this demo.
    frame.loc[dates[121], ["open", "low"]] = 110
    req = request(
        run={"runId": "120", "start": dates[119].date(), "end": dates[-1].date()},
        allocation={"parameters": {"lookbackDays": 120}},
    )
    output, artifacts = NautilusMomentumEngine(FrameProvider(frame)).run_with_artifacts(req)
    assert output.run.status == "COMPLETED", output.run.error
    assert artifacts.orders[0].signal_time.date() == dates[120].date()
    assert artifacts.orders[0].fill_time.date() == dates[121].date()
    assert output.execution.order_count == 1
