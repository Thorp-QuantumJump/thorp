"""모멘텀 전략 (MVP v0.1.1 대상 알고리즘, 파라미터 고정).

계산 정의는 docs/specs/backtest-spec.md 8절을 따른다.
"""

import pandas as pd

from worker.strategies.base import Strategy


class MomentumStrategy(Strategy):
    """최근 `lookback_days` 수익률이 양수면 매수(1), 아니면 현금(0)."""

    name = "MOMENTUM"

    def __init__(self, lookback_days: int = 120) -> None:
        if lookback_days < 1:
            raise ValueError(f"lookback_days는 1 이상이어야 한다: {lookback_days}")
        self.lookback_days = lookback_days

    def generate_signals(self, prices: pd.DataFrame) -> pd.Series:
        """일별 목표 포지션(1=매수, 0=현금)을 반환한다.

        신호는 당일까지의 데이터만 사용한다. 다음 거래일 적용을 위한 shift(1)은
        엔진이 수행하므로 여기서 하지 않는다.
        """
        momentum = prices["close"].pct_change(self.lookback_days)
        signals = (momentum > 0).astype("float64")
        # lookback 구간이 채워지기 전에는 판단 불가 -> 현금
        signals[momentum.isna()] = 0.0
        signals.name = "signal"
        return signals
