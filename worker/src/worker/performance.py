"""성과 지표와 파생 지표(spec 004 FR-027). 일별 종가 자산 곡선에서 계산하고 엔진 기본값을 쓰지
않는다.

계산 규약(계약 §5): 연환산 252, ddof=1, 무위험수익률 0, CAGR 은 달력 일수, ``tradeCount`` 는
CLOSED 만.
계산 불가는 0 이 아니라 None 이다 — 존재하지 않는 성과를 지어내지 않는다.
"""

from decimal import Decimal

import numpy as np
import pandas as pd

from worker.contracts.engine_output import (
    Analytics,
    PerformanceResult,
    PortfolioSnapshot,
    TradeResult,
    TradeStatus,
    WeightsPoint,
)

TRADING_DAYS_PER_YEAR = 252
#: ``analytics.method`` — 상관·위험 기여는 평가 기간 일별 수익률의 표본 공분산(ddof=1)이다
#: (spec 004 research).
METHOD = "sample-covariance-daily"
QUANTUM = Decimal("0.00000001")
ZERO = Decimal(0)


def _dec(value: float) -> Decimal:
    return Decimal(repr(float(value))).quantize(QUANTUM)


def calculate_performance(
    initial: Decimal,
    series: list[PortfolioSnapshot],
    trades: list[TradeResult],
    currency: str,
    total_trading_cost: Decimal | None = None,
) -> PerformanceResult:
    """``currency``는 요청의 ``portfolio.currency``를 그대로 싣는다 (계약 필수 필드).

    자산 곡선이 이미 비용을 뺀 값이라 ``netTotalReturn`` 은 ``totalReturn`` 과 같다(FR-027).
    """
    final = series[-1].equity
    returns = np.array([float(point.daily_return) for point in series])
    volatility = float(returns.std(ddof=1) * np.sqrt(252)) if len(returns) >= 2 else None
    sharpe = float(returns.mean() * 252 / volatility) if volatility else None
    elapsed = (series[-1].date - series[0].date).days
    cagr = (final / initial) ** (Decimal(365) / elapsed) - 1 if elapsed > 0 else None
    closed = [trade for trade in trades if trade.status == TradeStatus.CLOSED]
    winners = sum(trade.realized_pnl > 0 for trade in closed)
    total_return = final / initial - 1
    return PerformanceResult(
        initial_equity=initial,
        final_equity=final,
        currency=currency,
        total_return=total_return,
        net_total_return=total_return,
        cagr=cagr,
        max_drawdown=min(point.drawdown for point in series),
        sharpe_ratio=Decimal(str(sharpe)) if sharpe is not None else None,
        volatility=Decimal(str(volatility)) if volatility is not None else None,
        trade_count=len(closed),
        win_rate=Decimal(winners) / len(closed) if closed else None,
        total_trading_cost=total_trading_cost,
    )


def calculate_analytics(
    series: list[PortfolioSnapshot],
    weights_series: list[WeightsPoint],
    trades: list[TradeResult],
    instrument_returns: pd.DataFrame,
) -> Analytics:
    """파생 지표(FR-027). spec 은 이름만 정했으므로 정의는 여기(T008 결정)다.

    - ``recoveryDays``: 최대 하락 폭의 저점에서 그 전 고점을 다시 넘은 첫날까지의 **거래일 수**.
      하락이 없으면 0, 끝까지 회복하지 못하면 None.
    - ``turnover``: (매수 대금 + 매도 대금) / 2 / 평균 자산 × 252 / 평가 거래일 수. 대금은
      체결가 × 수량이다.
    - ``correlationMatrix``: 평가 기간 종목 일별(조정 종가) 수익률의 표본 상관. 정의되지 않는
      쌍(변동성 0)은 키를 뺀다.
    - ``riskContribution``: wᵢ(Σw)ᵢ / wᵀΣw. w 는 리밸런싱 기록의 실제 비중 평균, Σ 는 표본 공분산.
      기록이 없거나 포트폴리오 분산이 0 이면 None.
    - ``lossContribution``: 종목별 손익(실현 + 종료일 미실현 + 분배금)이 전체 손익에서 차지하는
      비율. 합은 1 이다. 전체 손익이 0 이면 None.
    - ``weightError``: 리밸런싱마다 기록한 Σ|target − actual| 의 평균.
    """
    return Analytics(
        recovery_days=_recovery_days(series),
        turnover=_turnover(series, trades),
        correlation_matrix=_correlation(instrument_returns),
        risk_contribution=_risk_contribution(instrument_returns, weights_series),
        loss_contribution=_loss_contribution(series, trades),
        weight_error=_mean_weight_error(weights_series),
        method=METHOD,
    )


def _recovery_days(series: list[PortfolioSnapshot]) -> int | None:
    drawdowns = [point.drawdown for point in series]
    trough = drawdowns.index(min(drawdowns))
    if drawdowns[trough] == ZERO:
        return 0
    peak = max(point.equity for point in series[: trough + 1])
    for offset, point in enumerate(series[trough + 1 :], start=1):
        if point.equity >= peak:
            return offset
    return None


def _turnover(series: list[PortfolioSnapshot], trades: list[TradeResult]) -> Decimal | None:
    notional = ZERO
    for trade in trades:
        notional += trade.entry_price * trade.quantity
        if trade.exit_price is not None:
            notional += trade.exit_price * trade.quantity
    mean_equity = sum((point.equity for point in series), ZERO) / len(series)
    if mean_equity <= ZERO:
        return None
    annualised = notional / 2 / mean_equity * TRADING_DAYS_PER_YEAR / len(series)
    return annualised.quantize(QUANTUM)


def _correlation(returns: pd.DataFrame) -> dict[str, dict[str, Decimal]] | None:
    if returns.shape[1] == 0 or len(returns) < 2:
        return None
    matrix = returns.corr()
    return {
        str(row): {
            str(col): _dec(value) for col, value in matrix.loc[row].items() if pd.notna(value)
        }
        for row in matrix.index
    }


def _risk_contribution(
    returns: pd.DataFrame, weights_series: list[WeightsPoint]
) -> dict[str, Decimal] | None:
    if not weights_series or len(returns) < 2:
        return None
    columns = [str(c) for c in returns.columns]
    averaged = {
        iid: sum((point.actual.get(iid, ZERO) for point in weights_series), ZERO)
        / len(weights_series)
        for iid in columns
    }
    weights = np.array([float(averaged[iid]) for iid in columns])
    covariance = returns.cov().to_numpy()
    marginal = covariance @ weights
    variance = float(weights @ marginal)
    if variance <= 0:
        return None
    return {iid: _dec(weights[i] * marginal[i] / variance) for i, iid in enumerate(columns)}


def _loss_contribution(
    series: list[PortfolioSnapshot], trades: list[TradeResult]
) -> dict[str, Decimal] | None:
    pnl: dict[str, Decimal] = {}
    for trade in trades:
        if trade.realized_pnl is not None:
            pnl[trade.instrument_id] = pnl.get(trade.instrument_id, ZERO) + trade.realized_pnl
    for position in series[-1].positions or []:
        pnl[position.instrument_id] = (
            pnl.get(position.instrument_id, ZERO) + position.unrealized_pnl
        )
    for point in series:
        for flow in point.cash_flows or []:
            if flow.instrument_id is not None:
                pnl[flow.instrument_id] = pnl.get(flow.instrument_id, ZERO) + flow.amount
    total = sum(pnl.values(), ZERO)
    if total == ZERO:
        return None
    return {iid: (value / total).quantize(QUANTUM) for iid, value in sorted(pnl.items())}


def _mean_weight_error(weights_series: list[WeightsPoint]) -> Decimal | None:
    errors = [point.weight_error for point in weights_series if point.weight_error is not None]
    if not errors:
        return None
    return (sum(errors, ZERO) / len(errors)).quantize(QUANTUM)
