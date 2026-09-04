"""백테스트 엔진 테스트. 계산 정의는 docs/specs/backtest-spec.md 3~6절."""

from datetime import date

import pandas as pd
import pytest

from worker.engine import (
    compute_metrics,
    count_trades,
    run_backtest,
    run_portfolio_backtest,
    simulate,
    to_positions,
)
from worker.models import BacktestJob
from worker.strategies.base import Strategy


class FixedSignalStrategy(Strategy):
    """테스트용: 미리 정한 신호를 그대로 돌려준다."""

    name = "FIXED"

    def __init__(self, signals: list[float]) -> None:
        self.signals = signals

    def generate_signals(self, prices: pd.DataFrame) -> pd.Series:
        return pd.Series(self.signals, index=prices.index, dtype="float64")


def make_prices(closes: list[float]) -> pd.DataFrame:
    index = pd.bdate_range("2024-01-01", periods=len(closes))
    return pd.DataFrame(
        {
            "open": closes,
            "high": closes,
            "low": closes,
            "close": closes,
            "volume": [1000.0] * len(closes),
        },
        index=index,
    )


def make_job() -> BacktestJob:
    return BacktestJob(
        job_id=1,
        strategy="FIXED",
        start_date=date(2024, 1, 1),
        end_date=date(2024, 12, 31),
    )


def test_positions_are_shifted_by_one_day() -> None:
    """당일 신호는 다음 거래일에 체결된다 (look-ahead 차단)."""
    signals = pd.Series([1.0, 0.0, 1.0], index=pd.bdate_range("2024-01-01", periods=3))

    positions = to_positions(signals)

    assert positions.tolist() == [0.0, 1.0, 0.0]


def test_simulate_applies_cost_on_turnover_only() -> None:
    prices = make_prices([100, 110, 110])
    positions = pd.Series([0.0, 1.0, 1.0], index=prices.index)

    # 편도 100bp = 1%. 진입한 날(index 1)에만 비용이 붙는다.
    net = simulate(prices, positions, cost_bps=100.0)

    assert net.iloc[0] == pytest.approx(0.0)
    assert net.iloc[1] == pytest.approx(0.1 - 0.01)
    assert net.iloc[2] == pytest.approx(0.0)


def test_metrics_on_known_series() -> None:
    returns = pd.Series([0.0, 0.0, 0.1, 0.1, 0.0])

    metrics = compute_metrics(returns, trade_count=1)

    assert metrics["total_return"] == pytest.approx(0.21)
    assert metrics["mdd"] == pytest.approx(0.0)
    assert metrics["sharpe"] > 0.0
    assert metrics["trade_count"] == 1


def test_mdd_is_negative_on_drawdown() -> None:
    # 1.0 -> 1.2 -> 0.9 : 고점 1.2 대비 25% 하락
    returns = pd.Series([0.2, -0.25])

    metrics = compute_metrics(returns, trade_count=0)

    assert metrics["mdd"] == pytest.approx(-0.25)


def test_metrics_on_empty_series() -> None:
    metrics = compute_metrics(pd.Series([], dtype="float64"), trade_count=0)

    assert metrics == {"total_return": 0.0, "mdd": 0.0, "sharpe": 0.0, "trade_count": 0}


def test_sharpe_is_zero_when_no_volatility() -> None:
    metrics = compute_metrics(pd.Series([0.01, 0.01, 0.01]), trade_count=0)

    assert metrics["sharpe"] == 0.0


def test_count_trades_counts_entry_and_exit() -> None:
    positions = pd.Series([0.0, 1.0, 1.0, 0.0, 1.0])

    assert count_trades(positions) == 3


def test_run_backtest_returns_result_for_job() -> None:
    prices = make_prices([100, 100, 110, 121, 121])
    strategy = FixedSignalStrategy([0.0, 1.0, 1.0, 1.0, 0.0])

    result = run_backtest(make_job(), strategy, prices, cost_bps=0.0)

    assert result.job_id == 1
    assert result.total_return == pytest.approx(0.21)
    assert result.trade_count == 1


def test_portfolio_is_equal_weighted() -> None:
    """두 종목 수익률의 평균이 포트폴리오 수익률이 된다."""
    flat = make_prices([100, 100, 100, 100])
    rising = make_prices([100, 100, 110, 110])
    strategy = FixedSignalStrategy([1.0, 1.0, 1.0, 1.0])

    result = run_portfolio_backtest(make_job(), strategy, {"A": flat, "B": rising}, cost_bps=0.0)

    # B만 10% 상승 -> 동일가중 포트폴리오는 5%
    assert result.total_return == pytest.approx(0.05)


def test_empty_universe_is_rejected() -> None:
    with pytest.raises(ValueError):
        run_portfolio_backtest(make_job(), FixedSignalStrategy([]), {})


def test_backtest_is_deterministic() -> None:
    """같은 입력은 항상 같은 결과. baseline과 분산 결과 비교의 전제다."""
    prices = {"A": make_prices([100, 102, 101, 105, 107])}
    strategy = FixedSignalStrategy([0.0, 1.0, 1.0, 0.0, 1.0])

    first = run_portfolio_backtest(make_job(), strategy, prices)
    second = run_portfolio_backtest(make_job(), strategy, prices)

    assert first.model_dump() == second.model_dump()
