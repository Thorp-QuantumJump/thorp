"""MOMENTUM 전략 테스트. 계산 정의는 docs/specs/backtest-spec.md 8절."""

import pandas as pd
import pytest

from worker.strategies.momentum import MomentumStrategy


def make_prices(closes: list[float]) -> pd.DataFrame:
    index = pd.bdate_range("2024-01-01", periods=len(closes))
    return pd.DataFrame({"close": closes}, index=index)


def test_signal_is_one_when_momentum_positive() -> None:
    prices = make_prices([100, 101, 102, 103, 104])
    signals = MomentumStrategy(lookback_days=2).generate_signals(prices)

    # index 0,1은 lookback 미충족 -> 현금
    assert signals.iloc[0] == 0.0
    assert signals.iloc[1] == 0.0
    # 이후는 2일 전 대비 상승 -> 매수
    assert signals.iloc[2:].tolist() == [1.0, 1.0, 1.0]


def test_signal_is_zero_when_momentum_not_positive() -> None:
    prices = make_prices([100, 99, 98, 97, 96])
    signals = MomentumStrategy(lookback_days=2).generate_signals(prices)

    assert signals.tolist() == [0.0, 0.0, 0.0, 0.0, 0.0]


def test_no_look_ahead_bias() -> None:
    """미래 가격을 바꿔도 과거 신호는 변하지 않아야 한다."""
    base = [100, 105, 103, 108, 110, 112]
    strategy = MomentumStrategy(lookback_days=2)

    original = strategy.generate_signals(make_prices(base))
    tampered = strategy.generate_signals(make_prices([*base[:4], 9999, 1]))

    pd.testing.assert_series_equal(original.iloc[:4], tampered.iloc[:4])


def test_invalid_lookback_rejected() -> None:
    with pytest.raises(ValueError):
        MomentumStrategy(lookback_days=0)
