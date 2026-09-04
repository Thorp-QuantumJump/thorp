"""백테스트 엔진.

전략은 '시장 데이터 -> 매매 신호'만 출력하고,
엔진이 체결/자산 계산과 성과지표(수익률, MDD, Sharpe) 산출을 담당한다.

계산 정의는 docs/specs/backtest-spec.md 를 따른다. 문서와 어긋나면 문서를 먼저 고친다.
"""

from collections.abc import Mapping

import numpy as np
import pandas as pd

from worker.models import BacktestJob, BacktestResult
from worker.strategies.base import Strategy

#: 연율화 계수 (KRX 연간 거래일 근사)
TRADING_DAYS_PER_YEAR = 252

#: 거래비용 기본값 (편도 10bp = 0.1%). 수수료+슬리피지+거래세의 단순 근사.
DEFAULT_COST_BPS = 10.0


def to_positions(signals: pd.Series) -> pd.Series:
    """신호를 실제 보유 포지션으로 변환한다.

    당일 신호는 다음 거래일에 체결되므로 shift(1) 한다. (look-ahead bias 차단)
    """
    return signals.shift(1).fillna(0.0).astype("float64")


def simulate(prices: pd.DataFrame, positions: pd.Series, cost_bps: float) -> pd.Series:
    """포지션을 적용한 일별 순수익률(거래비용 차감)을 계산한다."""
    asset_return = prices["close"].pct_change().fillna(0.0)
    turnover = positions.diff().abs().fillna(positions.abs())
    cost = turnover * (cost_bps / 10_000.0)
    net_return = positions * asset_return - cost
    net_return.name = "net_return"
    return net_return


def compute_metrics(returns: pd.Series, trade_count: int) -> dict[str, float | int]:
    """일별 수익률 시계열에서 성과지표를 산출한다."""
    returns = returns.dropna()
    if returns.empty:
        return {"total_return": 0.0, "mdd": 0.0, "sharpe": 0.0, "trade_count": trade_count}

    equity = (1.0 + returns).cumprod()
    total_return = float(equity.iloc[-1] - 1.0)
    mdd = float((equity / equity.cummax() - 1.0).min())

    std = returns.std(ddof=1)
    if len(returns) < 2 or not np.isfinite(std) or std == 0.0:
        sharpe = 0.0
    else:
        sharpe = float(returns.mean() / std * np.sqrt(TRADING_DAYS_PER_YEAR))

    return {
        "total_return": total_return,
        "mdd": mdd,
        "sharpe": sharpe,
        "trade_count": trade_count,
    }


def count_trades(positions: pd.Series) -> int:
    """포지션이 바뀐 날의 수. 진입/청산을 각각 1회로 센다."""
    if positions.empty:
        return 0
    changes = positions.diff()
    changes.iloc[0] = positions.iloc[0]
    return int((changes != 0).sum())


def _asset_net_returns(
    strategy: Strategy, prices: pd.DataFrame, cost_bps: float
) -> tuple[pd.Series, int]:
    """단일 종목의 순수익률 시계열과 거래 횟수."""
    signals = strategy.generate_signals(prices)
    positions = to_positions(signals)
    return simulate(prices, positions, cost_bps), count_trades(positions)


def run_backtest(
    job: BacktestJob,
    strategy: Strategy,
    prices: pd.DataFrame,
    cost_bps: float = DEFAULT_COST_BPS,
) -> BacktestResult:
    """단일 종목 백테스트 실행.

    Args:
        job: Kafka에서 받은 백테스트 Job.
        strategy: 실행할 전략 구현체.
        prices: OHLCV 일봉 데이터 (index: date).
        cost_bps: 편도 거래비용(bp).
    """
    net_return, trades = _asset_net_returns(strategy, prices, cost_bps)
    metrics = compute_metrics(net_return, trades)
    return BacktestResult(job_id=job.job_id, **metrics)


def run_portfolio_backtest(
    job: BacktestJob,
    strategy: Strategy,
    prices_by_ticker: Mapping[str, pd.DataFrame],
    cost_bps: float = DEFAULT_COST_BPS,
) -> BacktestResult:
    """유니버스(여러 종목) 백테스트 실행. 동일 가중으로 합성한다.

    Job 1건 = 전략 1개 × 파라미터 1조합 × 유니버스 전체 × 기간 전체.
    (docs/specs/backtest-spec.md 1절)
    """
    if not prices_by_ticker:
        raise ValueError("유니버스가 비어 있다. 최소 1개 종목이 필요하다.")

    per_ticker: dict[str, pd.Series] = {}
    total_trades = 0
    for ticker, prices in prices_by_ticker.items():
        net_return, trades = _asset_net_returns(strategy, prices, cost_bps)
        per_ticker[ticker] = net_return
        total_trades += trades

    # 거래일 합집합 기준 정렬. 값이 없는 종목은 그날 평균에서 제외(skipna).
    portfolio_return = pd.DataFrame(per_ticker).mean(axis=1)
    metrics = compute_metrics(portfolio_return, total_trades)
    return BacktestResult(job_id=job.job_id, **metrics)
