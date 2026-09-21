"""사후 구간 잘라내기·요약 계산 검증(spec 011 T015).

기대값은 워커를 import 하지 않는 독립 계산
(``docs/specs/regime-link-messaging/examples/reconciliation/reconcile.py``)이 만든
``manual_reconciliation.json`` 이다. 기대값을 워커 출력으로 채우면 대조가 아니므로 이 테스트는
그 파일을 읽기만 한다. 입력 픽스처는 ``fixtures/make_fixtures.py`` 가 만든다 — 둘 중 하나를 다시
만들면 다른 하나도 다시 돌린다.

파일이 없으면 **건너뛰지 않고 실패한다** — 조용히 꺼지는 검사는 검사가 아니다.
"""

import json
from copy import deepcopy
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from worker.regime_link.contracts import RegimeLinkErrorCode, RegimeLinkJob, TurnoverBasis
from worker.regime_link.slicer import MappingClosePrices, SliceError, slice_window

FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures"
RECONCILIATION = (
    Path(__file__).resolve().parents[3]
    / "docs"
    / "specs"
    / "regime-link-messaging"
    / "examples"
    / "reconciliation"
    / "manual_reconciliation.json"
)

CASES = ["continuous_63", "incomplete_34", "fresh_cash", "partial_delisted", "zero_pnl"]

SEMI = "091160.XKRX"
BIO = "244580.XKRX"


def load_job(name: str) -> RegimeLinkJob:
    return RegimeLinkJob.model_validate_json(
        (FIXTURE_DIR / f"{name}.job.json").read_text(encoding="utf-8")
    )


def load_prices(name: str) -> MappingClosePrices:
    raw = json.loads((FIXTURE_DIR / f"{name}.closes.json").read_text(encoding="utf-8"))
    return MappingClosePrices(
        {
            iid: {date.fromisoformat(day): Decimal(price) for day, price in series.items()}
            for iid, series in raw.items()
        }
    )


def expected() -> dict[str, Any]:
    return json.loads(RECONCILIATION.read_text(encoding="utf-8"))


def run(name: str) -> Any:
    return slice_window(load_job(name), load_prices(name))


# --- 독립 계산과의 대조 ---------------------------------------------------------------


@pytest.mark.parametrize("name", CASES)
def test_windows_match_the_manual_reconciliation(name: str) -> None:
    outcome = run(name)
    want = expected()[name]
    for key in ("window63", "window21"):
        summary = getattr(outcome.result, key)
        case = want[key]
        assert summary.trading_days == case["tradingDays"], key
        assert summary.complete is case["complete"], key
        assert summary.window_return == Decimal(case["return"]), key
        assert summary.max_drawdown == Decimal(case["maxDrawdown"]), key
        assert summary.trade_count == case["tradeCount"], key
        assert summary.trades_per_month == Decimal(case["tradesPerMonth"]), key
        assert summary.turnover == Decimal(case["turnover"]), key
        assert summary.turnover_basis is TurnoverBasis.ANNUALIZED_252, key
        assert summary.costs.commission == Decimal(case["costs"]["commission"]), key
        assert summary.costs.spread == Decimal(case["costs"]["spread"]), key
        assert summary.costs.slippage == Decimal(case["costs"]["slippage"]), key
        assert summary.costs.tax == Decimal(case["costs"]["tax"]), key
        assert summary.costs.total == Decimal(case["costs"]["total"]), key
        if case["lossContribution"] is None:
            assert summary.loss_contribution is None, key
        else:
            assert summary.loss_contribution == {
                sector: Decimal(value) for sector, value in case["lossContribution"].items()
            }, key


@pytest.mark.parametrize("name", CASES)
def test_benchmark_partial_and_series_match(name: str) -> None:
    outcome = run(name)
    want = expected()[name]
    assert outcome.partial is want["partial"]
    assert outcome.delisted_on == (
        date.fromisoformat(want["delistedOn"]) if want["delistedOn"] else None
    )
    assert outcome.result.kospi.window63.window_return == Decimal(
        want["kospi"]["window63"]["return"]
    )
    assert outcome.result.kospi.window63.max_drawdown == Decimal(
        want["kospi"]["window63"]["maxDrawdown"]
    )
    assert outcome.result.kospi.window21.window_return == Decimal(
        want["kospi"]["window21"]["return"]
    )
    assert outcome.result.kospi.window21.max_drawdown == Decimal(
        want["kospi"]["window21"]["maxDrawdown"]
    )
    # 시계열·목록은 구간 전체를 담는다(계약).
    assert len(outcome.result.holdings) == want["holdingDays"]
    assert len(outcome.result.trades) == outcome.result.window63.trade_count
    for holding in (outcome.result.holdings[0], outcome.result.holdings[-1]):
        assert holding.actual == {
            iid: Decimal(value) for iid, value in want["holdings"][holding.date.isoformat()].items()
        }


def test_pnl_sum_equals_the_equity_change() -> None:
    """종목 구간 손익 합과 구간 자산 증감이 같다(R3 대조 항등식)."""
    for name, case in expected().items():
        for key in ("window63", "window21"):
            assert case[key]["pnlSum"] == case[key]["equityChange"], f"{name}.{key}"


# --- 구간 경계와 시나리오 -------------------------------------------------------------


def test_incomplete_window_is_marked_not_completed() -> None:
    outcome = run("incomplete_34")
    assert outcome.result.window63.trading_days == 34
    assert outcome.result.window63.complete is False
    # 21거래일 보조 평가는 결과가 있으면 언제나 완성이다(R4).
    assert outcome.result.window21.trading_days == 21
    assert outcome.result.window21.complete is True


def test_fresh_cash_uses_initial_cash_without_a_baseline_row() -> None:
    job = load_job("fresh_cash")
    dates = [point.date for point in job.inputs.portfolio_series]
    assert job.window.baseline_date not in dates  # 현금 시작은 기준 행이 없다
    outcome = slice_window(job, load_prices("fresh_cash"))
    last = job.inputs.portfolio_series[-1].equity
    assert outcome.result.window63.window_return == (last / job.run.initial_cash - 1).quantize(
        Decimal("0.0001")
    )


def test_continuous_without_a_baseline_row_fails_as_input_incomplete() -> None:
    body = json.loads((FIXTURE_DIR / "continuous_63.job.json").read_text(encoding="utf-8"))
    body["inputs"]["portfolioSeries"] = body["inputs"]["portfolioSeries"][1:]
    job = RegimeLinkJob.model_validate(body)
    with pytest.raises(SliceError) as error:
        slice_window(job, load_prices("continuous_63"))
    assert error.value.code is RegimeLinkErrorCode.INPUT_INCOMPLETE


def test_fresh_cash_with_opening_positions_fails() -> None:
    body = json.loads((FIXTURE_DIR / "fresh_cash.job.json").read_text(encoding="utf-8"))
    body["inputs"]["openingPositions"] = [{"instrumentId": SEMI, "quantity": 10}]
    with pytest.raises(SliceError) as error:
        slice_window(RegimeLinkJob.model_validate(body), load_prices("fresh_cash"))
    assert error.value.code is RegimeLinkErrorCode.INPUT_INCOMPLETE


def test_trades_and_costs_outside_the_window_are_excluded() -> None:
    full = run("continuous_63").result
    cut = run("incomplete_34").result
    assert cut.window63.trade_count < full.window63.trade_count
    assert cut.window63.costs.total < full.window63.costs.total
    assert full.window21.trade_count < full.window63.trade_count
    assert all(trade.date <= full.holdings[-1].date for trade in full.trades)


# --- 수량 이월(분할)과 스냅샷 ---------------------------------------------------------


def split_day_of(job: RegimeLinkJob) -> date:
    """체결이 없는데 수량이 바뀐 날 — 분할이 난 스냅샷."""
    traded = {fill.time.date() for fill in job.inputs.trades}
    days = [s.date for s in job.inputs.daily_positions if s.date not in traded]
    assert days, "분할 스냅샷이 있는 픽스처여야 한다"
    return days[0]


def test_split_day_weight_follows_the_snapshot_not_the_fills() -> None:
    job = load_job("continuous_63")
    prices = load_prices("continuous_63")
    split_day = split_day_of(job)
    snapshot = next(s for s in job.inputs.daily_positions if s.date == split_day)
    equity = next(p.equity for p in job.inputs.portfolio_series if p.date == split_day)
    holding = next(h for h in run("continuous_63").result.holdings if h.date == split_day)

    for position in snapshot.positions:
        close = prices.close(position.instrument_id, split_day)
        assert close is not None
        want = (Decimal(position.quantity) * close / equity).quantize(Decimal("0.0001"))
        assert holding.actual[position.instrument_id] == want

    # 체결만 누적했다면 분할 종목의 수량이 절반이라 비중이 달라진다 — 이 차이가 잡혀야 한다.
    carried = {p.instrument_id: p.quantity for p in job.inputs.opening_positions}
    for fill in job.inputs.trades:
        if fill.time.date() <= split_day:
            sign = 1 if fill.side.value == "BUY" else -1
            carried[fill.instrument_id] += sign * fill.quantity
    assert carried[BIO] != next(p.quantity for p in snapshot.positions if p.instrument_id == BIO)


def test_a_snapshot_overwrites_instead_of_patching() -> None:
    """스냅샷에 없는 종목은 그날 보유 0이다(부분 갱신이 아니다)."""
    body = json.loads((FIXTURE_DIR / "continuous_63.job.json").read_text(encoding="utf-8"))
    snapshot = deepcopy(body["inputs"]["dailyPositions"][0])
    body["inputs"]["dailyPositions"][0]["positions"] = [
        p for p in snapshot["positions"] if p["instrumentId"] != BIO
    ]
    job = RegimeLinkJob.model_validate(body)
    # 보유가 줄면 자산 항등식이 깨지므로 계산은 실패해야 한다 — 조용히 0으로 넘어가지 않는다.
    with pytest.raises(SliceError) as error:
        slice_window(job, load_prices("continuous_63"))
    assert error.value.code is RegimeLinkErrorCode.MARKET_DATA_MISSING


# --- 실패 경로 -----------------------------------------------------------------------


def test_missing_close_price_fails_as_market_data_missing() -> None:
    prices = load_prices("continuous_63")
    trimmed = {
        iid: {day: close for day, close in series.items()} for iid, series in prices.closes.items()
    }
    last = max(trimmed[SEMI])
    del trimmed[SEMI][last]
    with pytest.raises(SliceError) as error:
        slice_window(load_job("continuous_63"), MappingClosePrices(trimmed))
    assert error.value.code is RegimeLinkErrorCode.MARKET_DATA_MISSING


def test_equity_that_contradicts_the_holdings_fails() -> None:
    """자산만 손대면 종목 손익 합과 어긋난다 — 항등식 대조가 그것을 잡는다."""
    body = json.loads((FIXTURE_DIR / "continuous_63.job.json").read_text(encoding="utf-8"))
    row = body["inputs"]["portfolioSeries"][-1]
    row["equity"] = str(Decimal(row["equity"]) + Decimal(5000))
    with pytest.raises(SliceError) as error:
        slice_window(RegimeLinkJob.model_validate(body), load_prices("continuous_63"))
    assert error.value.code is RegimeLinkErrorCode.MARKET_DATA_MISSING


def test_trading_day_count_that_disagrees_with_the_window_fails() -> None:
    body = json.loads((FIXTURE_DIR / "continuous_63.job.json").read_text(encoding="utf-8"))
    body["window"]["completedTradingDays"] = 62
    with pytest.raises(SliceError) as error:
        slice_window(RegimeLinkJob.model_validate(body), load_prices("continuous_63"))
    assert error.value.code is RegimeLinkErrorCode.INPUT_INCOMPLETE


# --- 표시 규칙 -----------------------------------------------------------------------


def test_returns_are_not_annualised() -> None:
    """63거래일 수익률을 1년으로 늘리지 않는다 — 원값 그대로다(R3)."""
    job = load_job("continuous_63")
    outcome = slice_window(job, load_prices("continuous_63"))
    base = next(p.equity for p in job.inputs.portfolio_series if p.date == job.window.baseline_date)
    last = job.inputs.portfolio_series[-1].equity
    assert outcome.result.window63.window_return == (last / base - 1).quantize(Decimal("0.0001"))


def test_loss_contribution_includes_profitable_sectors_and_sums_to_one() -> None:
    summary = run("continuous_63").result.window63
    assert summary.loss_contribution is not None
    assert set(summary.loss_contribution) == {"SEMICONDUCTOR", "BATTERY", "BIO"}
    assert any(value > 0 for value in summary.loss_contribution.values())
    assert any(value < 0 for value in summary.loss_contribution.values())
    total = sum(summary.loss_contribution.values())
    assert abs(total - 1) < Decimal("0.00000005")


def test_loss_contribution_is_omitted_when_the_window_pnl_is_zero() -> None:
    summary = run("zero_pnl").result.window63
    assert summary.window_return == Decimal("0.0000")
    assert summary.loss_contribution is None


def test_basis_and_digest_pass_through() -> None:
    job = load_job("partial_delisted")
    result = slice_window(job, load_prices("partial_delisted")).result
    assert result.basis.data_version == job.market_data.data_version
    assert result.basis.dataset_id == job.market_data.dataset_id
    assert result.basis.analytics_method == job.run.analytics_method
    assert result.input_digest == job.input_digest
