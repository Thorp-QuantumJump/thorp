"""Design-document metrics calculated from daily closing equity, not engine defaults."""

from decimal import Decimal

import numpy as np

from worker.contracts.engine_output import (
    PerformanceResult,
    PortfolioSnapshot,
    TradeResult,
    TradeStatus,
)


def calculate_performance(
    initial: Decimal,
    series: list[PortfolioSnapshot],
    trades: list[TradeResult],
    currency: str,
) -> PerformanceResult:
    """``currency``는 요청의 ``portfolio.currency``를 그대로 싣는다 (계약 필수 필드)."""
    final = series[-1].equity
    returns = np.array([float(point.daily_return) for point in series])
    volatility = float(returns.std(ddof=1) * np.sqrt(252)) if len(returns) >= 2 else None
    sharpe = float(returns.mean() * 252 / volatility) if volatility else None
    elapsed = (series[-1].date - series[0].date).days
    cagr = (final / initial) ** (Decimal(365) / elapsed) - 1 if elapsed > 0 else None
    closed = [trade for trade in trades if trade.status == TradeStatus.CLOSED]
    winners = sum(trade.realized_pnl > 0 for trade in closed)
    return PerformanceResult(
        initial_equity=initial,
        final_equity=final,
        currency=currency,
        total_return=final / initial - 1,
        cagr=cagr,
        max_drawdown=min(point.drawdown for point in series),
        sharpe_ratio=Decimal(str(sharpe)) if sharpe is not None else None,
        volatility=Decimal(str(volatility)) if volatility is not None else None,
        trade_count=len(closed),
        win_rate=Decimal(winners) / len(closed) if closed else None,
    )
