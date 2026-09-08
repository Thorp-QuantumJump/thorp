"""모멘텀 전략 (MVP v0.1.1 대상 알고리즘 초안, 파라미터 고정)."""

import pandas as pd

from worker.strategies.base import Strategy


class MomentumStrategy(Strategy):
    name = "MOMENTUM"

    def __init__(self, lookback_days: int = 120) -> None:
        self.lookback_days = lookback_days

    def generate_signals(self, prices: pd.DataFrame) -> pd.Series:
        if self.lookback_days < 1:
            raise ValueError("lookback_days must be positive")
        return (prices["close"] / prices["close"].shift(self.lookback_days) - 1 > 0).astype(int)
