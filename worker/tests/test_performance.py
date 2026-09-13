"""파생 지표(spec 004 FR-027 ``analytics``, T008) — 손으로 만든 작은 입력으로 정의를 고정한다.

각 지표의 정의는 ``performance.calculate_analytics`` docstring 에 있다. 여기서는 그 정의대로
계산되는지와 계산 불가일 때 0 을 지어내지 않고 None 을 내는지를 본다.
"""

from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import pandas as pd

from worker.contracts.engine_output import (
    CashFlow,
    PortfolioSnapshot,
    PositionSnapshot,
    TradeResult,
    TradeSide,
    TradeStatus,
    WeightsPoint,
)
from worker.performance import METHOD, calculate_analytics

A, B = "000001.XKRX", "000002.XKRX"
DAY0 = date(2024, 1, 2)
T0 = datetime(2024, 1, 2, tzinfo=UTC)


def snapshots(
    equities: list[int],
    positions_last: list[PositionSnapshot] | None = None,
    cash_flows: dict[int, list[CashFlow]] | None = None,
) -> list[PortfolioSnapshot]:
    out: list[PortfolioSnapshot] = []
    peak = Decimal(0)
    for i, value in enumerate(equities):
        equity = Decimal(value)
        peak = max(peak, equity)
        out.append(
            PortfolioSnapshot(
                date=DAY0 + timedelta(days=i),
                equity=equity,
                cash=equity,
                position_value=Decimal(0),
                daily_return=equity / Decimal(equities[i - 1]) - 1 if i else Decimal(0),
                cumulative_return=equity / Decimal(equities[0]) - 1,
                drawdown=equity / peak - 1,
                positions=positions_last if i == len(equities) - 1 else None,
                cash_flows=(cash_flows or {}).get(i),
            )
        )
    return out


def trade(
    iid: str, qty: int, entry: int, exit_: int | None = None, pnl: int | None = None
) -> TradeResult:
    closed = exit_ is not None
    return TradeResult(
        trade_id=f"TR-{iid}-{entry}",
        instrument_id=iid,
        side=TradeSide.LONG,
        status=TradeStatus.CLOSED if closed else TradeStatus.OPEN,
        entry_time=T0,
        exit_time=T0 if closed else None,
        entry_price=Decimal(entry),
        exit_price=Decimal(exit_) if closed else None,
        quantity=Decimal(qty),
        commission=Decimal(0),
        slippage_cost=Decimal(0),
        realized_pnl=Decimal(pnl) if closed else None,
    )


def returns(frame: dict[str, list[float]]) -> pd.DataFrame:
    index = pd.bdate_range(DAY0, periods=len(next(iter(frame.values()))))
    return pd.DataFrame(frame, index=index)


NO_RETURNS = returns({A: [0.0, 0.0], B: [0.0, 0.0]})


def analytics(**overrides):  # noqa: ANN003, ANN201 - 테스트 편의
    base = dict(
        series=snapshots([100, 100]),
        weights_series=[],
        trades=[],
        instrument_returns=NO_RETURNS,
    )
    return calculate_analytics(**{**base, **overrides})


# --- recoveryDays -----------------------------------------------------------------------


def test_recovery_days_counts_trading_days_from_the_trough_back_to_the_prior_peak() -> None:
    # 고점 100 → 저점 90(1일째) → 95 → 100(3일째) 회복. 저점에서 회복까지 2 거래일.
    assert analytics(series=snapshots([100, 90, 95, 100, 101])).recovery_days == 2


def test_recovery_days_is_none_when_the_drawdown_never_recovers() -> None:
    assert analytics(series=snapshots([100, 90, 95, 99])).recovery_days is None


def test_recovery_days_is_zero_without_any_drawdown() -> None:
    assert analytics(series=snapshots([100, 101, 102])).recovery_days == 0


# --- turnover -----------------------------------------------------------------------------


def test_turnover_is_half_the_traded_notional_over_mean_equity_annualised() -> None:
    # 매수 1,000 → 편도 거래대금 1,000 / 2 = 500. 평균 자산 1,000. 2 거래일 → × 252/2.
    result = analytics(series=snapshots([1000, 1000]), trades=[trade(A, 10, 100)])
    assert result.turnover == Decimal("63")


def test_turnover_counts_both_legs_of_a_closed_trade() -> None:
    # 100 에 10주 사고 110 에 팔았다: 1,000 + 1,100 = 2,100 / 2 = 1,050 / 1,000 × 252/2 = 132.3
    result = analytics(series=snapshots([1000, 1000]), trades=[trade(A, 10, 100, 110, 100)])
    assert result.turnover == Decimal("132.3")


# --- correlationMatrix · riskContribution --------------------------------------------------


def test_correlation_and_risk_contribution_come_from_the_sample_covariance() -> None:
    frame = returns({A: [0.01, -0.01, 0.02], B: [0.02, -0.02, 0.04]})  # B = 2A
    weights = [
        WeightsPoint(
            date=DAY0,
            target={A: Decimal("0.5"), B: Decimal("0.5")},
            actual={A: Decimal("0.5"), B: Decimal("0.5")},
        )
    ]
    result = analytics(instrument_returns=frame, weights_series=weights)
    assert result.correlation_matrix == {
        A: {A: Decimal("1.00000000"), B: Decimal("1.00000000")},
        B: {A: Decimal("1.00000000"), B: Decimal("1.00000000")},
    }
    # Σ = v·[[1,2],[2,4]], w = (½,½): wᵢ(Σw)ᵢ = (0.75v, 1.5v) → 1/3, 2/3
    assert result.risk_contribution == {A: Decimal("0.33333333"), B: Decimal("0.66666667")}
    assert result.method == METHOD == "sample-covariance-daily"


def test_risk_contribution_is_none_without_recorded_weights_or_variance() -> None:
    frame = returns({A: [0.01, -0.01, 0.02], B: [0.02, -0.02, 0.04]})
    assert analytics(instrument_returns=frame).risk_contribution is None
    weights = [WeightsPoint(date=DAY0, target={A: Decimal(1)}, actual={A: Decimal(1)})]
    flat = returns({A: [0.0, 0.0, 0.0]})
    assert analytics(instrument_returns=flat, weights_series=weights).risk_contribution is None


def test_risk_contribution_uses_the_average_actual_weight_across_rebalances() -> None:
    frame = returns({A: [0.01, -0.01, 0.02], B: [0.02, -0.02, 0.04]})
    weights = [
        WeightsPoint(date=DAY0, target={}, actual={A: Decimal(1), B: Decimal(0)}),
        WeightsPoint(date=DAY0, target={}, actual={A: Decimal(0), B: Decimal(1)}),
    ]  # 평균 (½, ½) → 위와 같은 답
    result = analytics(instrument_returns=frame, weights_series=weights)
    assert result.risk_contribution == {A: Decimal("0.33333333"), B: Decimal("0.66666667")}


# --- lossContribution ---------------------------------------------------------------------


def test_loss_contribution_is_each_instruments_share_of_total_pnl() -> None:
    # A: 실현 +30, 분배금 +10 = 40. B: 실현 -10, 미실현 +20 = 10. 합 50 → 0.8 / 0.2
    last_positions = [
        PositionSnapshot(
            instrument_id=B,
            quantity=Decimal(1),
            market_value=Decimal(1),
            weight=Decimal(1),
            unrealized_pnl=Decimal(20),
        )
    ]
    series = snapshots(
        [100, 100, 150],
        last_positions,
        {1: [CashFlow(type="DISTRIBUTION", instrument_id=A, amount=Decimal(10))]},
    )
    trades = [trade(A, 1, 100, 130, 30), trade(B, 1, 100, 90, -10), trade(B, 1, 100)]
    result = analytics(series=series, trades=trades)
    assert result.loss_contribution == {A: Decimal("0.8"), B: Decimal("0.2")}


def test_loss_contribution_is_none_when_total_pnl_is_zero() -> None:
    trades = [trade(A, 1, 100, 110, 10), trade(B, 1, 100, 90, -10)]
    assert analytics(trades=trades).loss_contribution is None


# --- weightError --------------------------------------------------------------------------


def test_weight_error_is_the_mean_over_rebalances() -> None:
    weights = [
        WeightsPoint(date=DAY0, target={}, actual={}, weight_error=Decimal("0.01")),
        WeightsPoint(date=DAY0, target={}, actual={}, weight_error=Decimal("0.03")),
    ]
    assert analytics(weights_series=weights).weight_error == Decimal("0.02")
    assert analytics().weight_error is None
