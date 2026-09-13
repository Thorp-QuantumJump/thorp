"""다종목 리밸런싱 엔진(spec 004 T006) — 3종목 24개월 픽스처로 끝까지 돌린다.

여기서 보는 것은 **체결 규칙**이다(SC-004). 판단일 다음 거래일 시가 체결, 거래 정지일 이월, 분배금
1회 지급, 액면분할 수량 조정, 정수 수량과 비중 오차, 진행 콜백, 결정성. 성과 지표의 값과 수기 대조는
T008 이 맡는다.
"""

from collections.abc import Callable
from dataclasses import replace
from datetime import date, timedelta
from decimal import Decimal
from typing import Any

import pandas as pd
import pytest
from conftest import BATTERY, BIO, SEMICONDUCTOR, THREE_ETF_DIR, engine_input_body

from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import (
    EngineOutput,
    RunStatus,
    TradeReason,
    TradeStatus,
    WeightsSource,
)
from worker.contracts.events import ProgressPoint
from worker.engine_runner import PortfolioBacktestEngine, evaluation_start
from worker.market_data import CsvMarketDataProvider, MarketData
from worker.portfolio_execution import (
    HALT_DEFERRED,
    HALT_SUPERSEDED,
    WEIGHT_ROUNDING,
    CallbackError,
    EngineFailure,
    execute_portfolio,
    plan_days,
)

SPLIT_DAY = date(2024, 3, 4)
HALT_DAY = date(2024, 6, 11)
BATTERY_EX_DATE = date(2024, 4, 29)
BATTERY_PAY_DATE = date(2024, 5, 2)

THREE = [
    {"sectorId": "SEMICONDUCTOR", "instrumentId": SEMICONDUCTOR, "targetWeight": "0.4"},
    {"sectorId": "BATTERY", "instrumentId": BATTERY, "targetWeight": "0.3"},
    {"sectorId": "BIO", "instrumentId": BIO, "targetWeight": "0.3"},
]

FULL_COSTS = {
    "version": "PROVISIONAL-FULL-1",
    "commissionBps": "1.5",
    "spreadBps": "2",
    "slippageBps": "5",
    "taxBps": "23",
    "provisional": True,
}


def engine() -> PortfolioBacktestEngine:
    return PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR))


class PatchedProvider:
    """픽스처를 읽은 뒤 한 군데만 바꾼다 — 픽스처에 없는 사건(병합·장기 정지)을 만들 때."""

    def __init__(self, patch: Callable[[MarketData], MarketData]) -> None:
        self.patch = patch

    def load(self, *args: Any, **kwargs: Any) -> MarketData:
        return self.patch(CsvMarketDataProvider(THREE_ETF_DIR).load(*args, **kwargs))


def run_patched(patch: Callable[[MarketData], MarketData], **overrides: Any) -> EngineOutput:
    body = EngineInput.model_validate(engine_input_body(**overrides))
    return PortfolioBacktestEngine(PatchedProvider(patch)).run(body, None)


def run(on_progress: Any = None, **overrides: Any) -> EngineOutput:
    output = engine().run(EngineInput.model_validate(engine_input_body(**overrides)), on_progress)
    return output


def completed(on_progress: Any = None, **overrides: Any) -> EngineOutput:
    output = run(on_progress, **overrides)
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    return output


def three_etf(start: str, end: str, **overrides: Any) -> dict[str, Any]:
    body = {"run": {"start": start, "end": end}, "universe": {"items": THREE}}
    for key, value in overrides.items():
        body[key] = {**body.get(key, {}), **value} if isinstance(value, dict) else value
    return body


def kst_date(when: Any) -> date:
    return (when + timedelta(hours=9)).date()


def holding(output: EngineOutput, day: date, instrument_id: str) -> Decimal:
    """그날 보유 수량. positions 는 구성이 바뀐 날에만 있으므로 마지막 기록까지 되짚는다."""
    for snapshot in reversed([s for s in output.portfolio_series if s.date <= day]):
        if snapshot.positions is not None:
            return next(
                (p.quantity for p in snapshot.positions if p.instrument_id == instrument_id),
                Decimal(0),
            )
    return Decimal(0)


# --- 판단일·평가일 --------------------------------------------------------------------


def test_first_decision_is_the_evaluation_start_then_month_ends() -> None:
    calendar = [
        date(2024, 1, 2),
        date(2024, 1, 3),
        date(2024, 1, 31),
        date(2024, 2, 1),
        date(2024, 2, 29),
        date(2024, 3, 4),
    ]
    plan = plan_days(calendar, date(2024, 1, 3), date(2024, 3, 4), "MONTH_END")

    assert plan.evaluation_days == calendar[1:]
    assert plan.decision_days == {date(2024, 1, 3), date(2024, 1, 31), date(2024, 2, 29)}
    assert plan.next_trading_day(date(2024, 2, 29)) == date(2024, 3, 4)


def test_last_evaluation_day_never_decides() -> None:
    """체결할 다음 거래일이 없다 — 마지막 날이 월말이어도 주문을 만들지 않는다."""
    calendar = [date(2024, 1, 30), date(2024, 1, 31)]
    plan = plan_days(calendar, date(2024, 1, 30), date(2024, 1, 31), "MONTH_END")
    assert plan.decision_days == {date(2024, 1, 30)}


def test_unapproved_rebalance_cadence_is_rejected() -> None:
    with pytest.raises(ValueError, match="WEEKLY"):
        plan_days([date(2024, 1, 2)], date(2024, 1, 2), date(2024, 1, 2), "WEEKLY")


# --- 체결 규칙(SC-004) ----------------------------------------------------------------


def test_every_fill_lands_on_the_trading_day_after_its_decision() -> None:
    """체결일 = 판단일 다음 거래일. 판단일 자료로 판단하고 그 뒤 첫 시가에 산다."""
    output = completed(**three_etf("2024-01-02", "2024-06-28"))
    calendar = sorted({snapshot.date for snapshot in output.portfolio_series})
    decisions = {
        date(2024, 1, 2),
        date(2024, 1, 31),
        date(2024, 2, 29),
        date(2024, 3, 29),
        date(2024, 4, 30),
        date(2024, 5, 31),
    }
    expected_fill_days = {calendar[calendar.index(day) + 1] for day in decisions}

    fill_days = {kst_date(trade.entry_time) for trade in output.trades}
    fill_days |= {kst_date(trade.exit_time) for trade in output.trades if trade.exit_time}

    assert fill_days <= expected_fill_days
    assert min(fill_days) == date(2024, 1, 3)
    assert output.run.effective_start == date(2024, 1, 2)
    assert output.run.effective_end == output.run.last_trading_date == date(2024, 6, 28)
    assert output.weights_source is WeightsSource.USER


def test_first_day_is_all_cash_and_quantities_are_whole_shares() -> None:
    output = completed(**three_etf("2024-01-02", "2024-01-31"))
    first = output.portfolio_series[0]
    assert first.cash == Decimal("10000000") and first.positions == []
    second = output.portfolio_series[1]
    assert second.positions is not None and len(second.positions) == 3
    for position in second.positions:
        assert position.quantity == position.quantity.to_integral_value()
        assert position.quantity > 0
    assert all(trade.quantity == trade.quantity.to_integral_value() for trade in output.trades)


def test_equity_is_cash_plus_positions_every_day() -> None:
    output = completed(**three_etf("2024-01-02", "2024-06-28"))
    for snapshot in output.portfolio_series:
        assert snapshot.equity == snapshot.cash + snapshot.position_value
        assert snapshot.cash >= 0, "공매도·차입 없음: 현금은 음수가 될 수 없다"


def test_weights_are_recorded_per_rebalance_with_error_not_renormalised() -> None:
    """정수 수량이 남긴 오차는 현금으로 남고 weightError 에 적힌다. 목표는 그대로다."""
    output = completed(**three_etf("2024-01-02", "2024-03-29"))
    assert [point.date for point in output.weights_series] == [
        date(2024, 1, 3),
        date(2024, 2, 1),
        date(2024, 3, 1),
    ]
    for point in output.weights_series:
        assert point.target == {
            SEMICONDUCTOR: Decimal("0.4"),
            BATTERY: Decimal("0.3"),
            BIO: Decimal("0.3"),
        }
        assert sum(point.actual.values()) < 1, "잔여 현금이 있다"
        assert point.weight_error is not None and 0 < point.weight_error < Decimal("0.02")
    assert not any(e.kind == WEIGHT_ROUNDING for e in output.exceptions or [])


def test_tiny_cash_records_weight_rounding_and_keeps_cash() -> None:
    """한 주도 못 사는 종목은 예외로 남기고 그 몫은 현금으로 둔다 — 비중을 다시 나누지 않는다."""
    output = completed(**three_etf("2024-01-02", "2024-01-31", portfolio={"initialCash": "40000"}))
    rounding = [e for e in output.exceptions or [] if e.kind == WEIGHT_ROUNDING]
    assert rounding and rounding[0].date == date(2024, 1, 2)
    assert {e.instrument_id for e in rounding} <= {SEMICONDUCTOR, BATTERY, BIO}
    assert output.portfolio_series[1].cash > 0


# --- 거래 정지 이월(FR-018) ------------------------------------------------------------


def test_halted_instrument_is_deferred_to_the_next_tradable_day() -> None:
    """2024-06-11 반도체 ETF 거래 정지. 그날 예정된 주문은 06-12 에 체결되고 사유·예정일이 남는다.

    다른 두 종목은 예정일에 그대로 체결된다 — 한 종목의 정지가 포트폴리오 전체를 멈추지 않는다.
    """
    output = completed(
        **three_etf("2024-06-03", "2024-06-28", run={"effectiveStart": "2024-06-10"})
    )
    assert output.run.effective_start == date(2024, 6, 10)

    deferred = [t for t in output.trades if t.reason is TradeReason.HALT_DEFERRED]
    on_time = [t for t in output.trades if t.reason is TradeReason.REBALANCE]
    assert {t.instrument_id for t in deferred} == {SEMICONDUCTOR}
    assert all(t.planned_date == HALT_DAY for t in deferred)
    assert all(kst_date(t.entry_time) == date(2024, 6, 12) for t in deferred)
    assert {t.instrument_id for t in on_time} == {BATTERY, BIO}
    assert all(kst_date(t.entry_time) == HALT_DAY for t in on_time)

    halts = [e for e in output.exceptions or [] if e.kind == HALT_DEFERRED]
    assert len(halts) == 1 and halts[0].date == HALT_DAY and halts[0].instrument_id == SEMICONDUCTOR
    # 정지일에는 그 종목 없이 두 종목만 들고 있다.
    halted_day = next(s for s in output.portfolio_series if s.date == HALT_DAY)
    assert {p.instrument_id for p in halted_day.positions or []} == {BATTERY, BIO}
    # 이월 체결 뒤 비중 기록은 정지일이 아니라 실제 체결일에 남는다.
    assert output.weights_series[0].date == date(2024, 6, 12)


def test_zero_volume_without_a_suspension_flag_is_still_tradable() -> None:
    """정지가 아니면서 거래량이 0인 날은 호가가 있어 체결할 수 있다(001 contracts/market-dataset.md
    「거래정지 처리」). 판정 기준은 ``trading_suspended`` 하나다 — volume 0 을 정지로 읽으면 정상
    주문을 잘못 이월한다.
    """

    def zero_volume(market: MarketData) -> MarketData:
        market.prices[BATTERY].loc[pd.Timestamp(HALT_DAY), "volume"] = 0
        return market

    output = run_patched(
        zero_volume, **three_etf("2024-06-03", "2024-06-28", run={"effectiveStart": "2024-06-10"})
    )
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    battery = [t for t in output.trades if t.instrument_id == BATTERY]
    assert battery and all(t.reason is TradeReason.REBALANCE for t in battery)
    assert kst_date(battery[0].entry_time) == HALT_DAY
    assert {e.instrument_id for e in output.exceptions or [] if e.kind == HALT_DEFERRED} == {
        SEMICONDUCTOR
    }


# --- 기업행위(FR-013) ------------------------------------------------------------------


def test_distribution_is_paid_once_on_the_pay_date_for_ex_date_holdings() -> None:
    output = completed(**three_etf("2024-04-01", "2024-05-31"))
    with_flows = [s for s in output.portfolio_series if s.cash_flows]
    assert [s.date for s in with_flows] == [BATTERY_PAY_DATE]
    flow = with_flows[0].cash_flows[0]
    assert flow.type == "DISTRIBUTION" and flow.instrument_id == BATTERY

    held = holding(output, BATTERY_EX_DATE - timedelta(days=1), BATTERY)
    assert held > 0 and flow.amount == Decimal("85") * held

    before = next(s for s in output.portfolio_series if s.date == date(2024, 5, 1))
    assert with_flows[0].cash == before.cash + flow.amount, "지급일에 현금이 정확히 그만큼 는다"


def test_split_doubles_quantity_without_a_trade() -> None:
    output = completed(**three_etf("2024-02-01", "2024-03-29"))
    after = next(s for s in output.portfolio_series if s.date == SPLIT_DAY)
    held_before = holding(output, date(2024, 3, 1), BIO)
    held_after = holding(output, SPLIT_DAY, BIO)
    assert held_before > 0 and held_after == held_before * 2
    assert not any(kst_date(t.entry_time) == SPLIT_DAY for t in output.trades)
    assert not any(e.kind == "SPLIT_FRACTION" for e in output.exceptions or [])
    # 분할 전 묶음의 진입가도 반으로 나눠 미실현 손익이 튀지 않는다.
    bio = next(p for p in after.positions or [] if p.instrument_id == BIO)
    assert abs(bio.unrealized_pnl) < bio.market_value * Decimal("0.2")


# --- 비용·청산 -------------------------------------------------------------------------


def test_costs_are_reported_in_four_buckets_and_liquidation_is_pro_forma() -> None:
    output = completed(
        **three_etf(
            "2024-01-02",
            "2024-03-29",
            execution={"costProfile": FULL_COSTS},
            evaluation={"warmupTradingDays": 0, "liquidationAtEnd": True},
        )
    )
    costs = output.costs
    assert costs is not None
    assert all(v > 0 for v in (costs.commission, costs.spread, costs.slippage, costs.tax))
    assert costs.total == costs.commission + costs.spread + costs.slippage + costs.tax
    assert output.performance is not None
    assert output.performance.total_trading_cost == costs.total

    liquidation = output.liquidation
    assert liquidation is not None
    last = output.portfolio_series[-1]
    assert liquidation.mark_to_market.final_equity == last.equity
    assert liquidation.liquidated.final_equity < last.equity
    assert liquidation.liquidated.liquidation_cost > 0
    # 청산은 가정이다 — 거래·비용 합계에는 들어가지 않는다.
    assert not any(t.reason is TradeReason.LIQUIDATION for t in output.trades)
    assert output.execution is not None
    assert output.execution.rejected_order_count == 0
    assert output.execution.fill_count == output.execution.order_count > 0
    assert output.execution.total_commission == costs.commission
    assert output.execution.total_slippage_cost == costs.spread + costs.slippage


def test_closed_trades_carry_both_sides_costs_and_pnl() -> None:
    output = completed(
        **three_etf("2024-01-02", "2024-03-29", execution={"costProfile": FULL_COSTS})
    )
    closed = [t for t in output.trades if t.status is TradeStatus.CLOSED]
    assert closed, "월말 리밸런싱이 일부를 판다"
    for trade in closed:
        assert trade.exit_time is not None and trade.exit_price is not None
        assert trade.gross_pnl is not None and trade.realized_pnl is not None
        assert trade.realized_pnl < trade.gross_pnl, "가격 충격·수수료·세금만큼 순손익이 작다"
        assert trade.return_rate == trade.realized_pnl / (trade.entry_price * trade.quantity)


# --- 워밍업·시나리오 ---------------------------------------------------------------------


def test_warmup_consumes_trading_days_before_evaluation() -> None:
    output = completed(**three_etf("2024-01-02", "2024-02-29", evaluation={"warmupTradingDays": 5}))
    assert output.run.effective_start == date(2024, 1, 9)
    assert output.portfolio_series[0].date == date(2024, 1, 9)


def test_inverse_volatility_uses_rules_lookback_as_default_warmup() -> None:
    body = three_etf(
        "2023-08-01",
        "2024-01-31",
        allocation={
            "strategyId": "INVERSE_VOLATILITY",
            "rules": {
                "lookbackDays": 63,
                "zeroVolatility": "EXCLUDE_THEN_ALLOCATE",
                "weightCap": "1.0000",
                "investedFraction": "1.0000",
            },
        },
        evaluation={"warmupTradingDays": None, "liquidationAtEnd": False},
    )
    body["universe"] = {
        "items": [{k: v for k, v in item.items() if k != "targetWeight"} for item in THREE]
    }
    output = completed(**body)
    calendar = (
        CsvMarketDataProvider(THREE_ETF_DIR)
        .load([SEMICONDUCTOR, BATTERY, BIO], date(2023, 8, 1), date(2024, 1, 31))
        .trading_days
    )
    assert output.run.effective_start == calendar[63]
    assert output.weights_source is WeightsSource.COMPUTED
    assert not any(e.kind == "INSUFFICIENT_HISTORY" for e in output.exceptions or [])


def test_fresh_cash_from_starts_evaluation_at_the_scenario_date() -> None:
    output = completed(
        **three_etf(
            "2024-01-02",
            "2024-03-29",
            run={"scenario": "FRESH_CASH_FROM", "scenarioParams": {"startDate": "2024-02-15"}},
        )
    )
    assert output.run.effective_start == date(2024, 2, 15)
    assert output.portfolio_series[0].cash == Decimal("10000000")
    assert min(kst_date(t.entry_time) for t in output.trades) == date(2024, 2, 16)


def test_evaluation_start_prefers_scenario_then_effective_start_then_warmup() -> None:
    calendar = [date(2024, 1, 2) + timedelta(days=i) for i in range(10)]
    base = engine_input_body(run={"start": "2024-01-02", "end": "2024-01-11"})
    warm = EngineInput.model_validate(
        {**base, "evaluation": {"warmupTradingDays": 3, "liquidationAtEnd": False}}
    )
    assert evaluation_start(warm, calendar) == date(2024, 1, 5)
    explicit = EngineInput.model_validate(
        {**base, "run": {**base["run"], "effectiveStart": "2024-01-08"}}
    )
    assert evaluation_start(explicit, calendar) == date(2024, 1, 8)
    fresh = EngineInput.model_validate(
        {
            **base,
            "run": {
                **base["run"],
                "scenario": "FRESH_CASH_FROM",
                "scenarioParams": {"startDate": "2024-01-10"},
            },
        }
    )
    assert evaluation_start(fresh, calendar) == date(2024, 1, 10)


# --- 실패는 계약 오류로 -------------------------------------------------------------------


def test_missing_rows_fail_as_data_insufficient() -> None:
    output = run(run={"start": "2025-01-02", "end": "2025-01-31"})
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == "DATA_INSUFFICIENT"
    assert output.portfolio_series == [] and output.performance is None


def test_warmup_longer_than_data_fails_as_data_insufficient() -> None:
    output = run(evaluation={"warmupTradingDays": 500})
    assert output.run.error is not None and output.run.error.code == "DATA_INSUFFICIENT"
    assert "warmup" in output.run.error.message


def test_allocation_error_code_is_carried_through() -> None:
    """상한 × 종목 수 < 투자 비중 → CONSTRAINT_VIOLATION (INVALID_REQUEST 가 아니다)."""
    body = three_etf(
        "2023-08-01",
        "2024-01-31",
        allocation={
            "strategyId": "INVERSE_VOLATILITY",
            "rules": {
                "lookbackDays": 20,
                "zeroVolatility": "EXCLUDE_THEN_ALLOCATE",
                "weightCap": "0.2000",
                "investedFraction": "1.0000",
            },
        },
    )
    body["universe"] = {
        "items": [{k: v for k, v in item.items() if k != "targetWeight"} for item in THREE]
    }
    output = run(**body)
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == "CONSTRAINT_VIOLATION"


# --- 진행 콜백·결정성 -----------------------------------------------------------------------


def test_progress_callback_fires_once_per_evaluation_day() -> None:
    calls: list[tuple[ProgressPoint, int, int]] = []
    output = completed(lambda p, c, t: calls.append((p, c, t)))
    assert [c for _, c, _ in calls] == list(range(1, len(output.portfolio_series) + 1))
    assert {t for _, _, t in calls} == {len(output.portfolio_series)}
    assert [p.date for p, _, _ in calls] == [s.date for s in output.portfolio_series]


def test_progress_callback_failure_surfaces_as_callback_error() -> None:
    def broken(point: ProgressPoint, completed: int, total: int) -> None:
        raise ConnectionError("broker gone")

    with pytest.raises(CallbackError):
        run(broken)


def test_artifacts_hold_orders_and_reproducibility_metadata() -> None:
    output, artifacts = engine().run_with_artifacts(engine_input_body())
    assert output.run.status is RunStatus.COMPLETED
    assert artifacts.metadata["datasetId"] == "FIXTURE-three_etf_24m"
    assert artifacts.metadata["randomSeed"] == 42
    assert artifacts.metadata["dataHash"].startswith("sha256:")
    assert {o.status for o in artifacts.orders} == {"FILLED"}
    assert all(o.submitted_time and o.fill_time and o.reference_price for o in artifacts.orders)
    assert output.versions is not None and output.versions.data == "FIXTURE-three_etf_24m"
    assert output.versions.mapping == "SECTOR-ETF-2026-09"


# --- 지표·참조 버전(T008) --------------------------------------------------------------------


def test_completed_output_carries_analytics_and_reference_versions() -> None:
    output = completed(
        **three_etf(
            "2024-01-02",
            "2024-06-28",
            execution={"costProfile": FULL_COSTS},
            allocation={
                "strategyId": "FIXED_WEIGHT",
                "rulesVersion": "1",
                "rules": {"investedFraction": "1.0000", "provisional": True},
                "parameters": {"rebalance": "MONTH_END"},
            },
        )
    )
    analytics = output.analytics
    assert analytics is not None and analytics.method == "sample-covariance-daily"
    instruments = {SEMICONDUCTOR, BATTERY, BIO}
    assert analytics.correlation_matrix is not None
    assert set(analytics.correlation_matrix) == instruments
    assert analytics.risk_contribution is not None
    assert set(analytics.risk_contribution) == instruments
    assert abs(sum(analytics.risk_contribution.values()) - 1) < Decimal("1e-6")
    assert analytics.loss_contribution is not None
    assert abs(sum(analytics.loss_contribution.values()) - 1) < Decimal("1e-6")
    assert analytics.turnover is not None and analytics.turnover > 0
    assert analytics.weight_error is not None and analytics.weight_error >= 0
    assert analytics.recovery_days is None or analytics.recovery_days >= 0

    versions = output.versions
    assert versions is not None
    assert versions.worker == "thorp-worker 0.1.1"
    assert versions.rules is not None and versions.rules.provisional is True
    assert versions.engine == "nautilus_trader 1.219.0"


def test_rules_provisional_is_omitted_when_the_rules_copy_does_not_say() -> None:
    output = completed(**three_etf("2024-01-02", "2024-03-29"))
    assert output.versions is not None and output.versions.rules is not None
    assert output.versions.rules.provisional is None


def test_partial_output_is_assembled_from_the_series_up_to_last_trading_date() -> None:
    """취소·한도(T011·T014)는 lastTradingDate 까지의 시계열로 같은 함수가 결과를 만든다."""
    from datetime import UTC, datetime

    from worker.engine_runner import assemble_output

    request = EngineInput.model_validate(engine_input_body(**three_etf("2024-01-02", "2024-03-29")))
    market = CsvMarketDataProvider(THREE_ETF_DIR).load(
        request.universe.instrument_ids, request.run.start, request.run.end
    )
    calendar = market.trading_days
    first = evaluation_start(request, calendar)
    plan = plan_days(calendar, first, request.run.end, "MONTH_END")
    execution = execute_portfolio(request, market, plan, None)
    execution.series = execution.series[:20]  # T011 은 여기서 엔진을 멈춘다

    output = assemble_output(
        request, market, execution, datetime.now(UTC), status=RunStatus.CANCELLED, partial=True
    )
    assert output.run.status is RunStatus.CANCELLED and output.run.partial is True
    assert output.run.last_trading_date == execution.series[-1].date
    assert output.run.effective_end == execution.series[-1].date
    assert output.performance is not None
    assert output.performance.final_equity == execution.series[-1].equity
    assert output.analytics is not None and output.execution is not None


# --- 리뷰 반영 (1차) ----------------------------------------------------------------------------


def test_pending_order_is_scaled_on_the_split_day() -> None:
    """3/1 판단(분할 전 가격) → 3/4 체결(분할 후 가격). 수량을 같은 비율로 늘려야 목표 비중이 찬다.

    늘리지 않으면 분할 전 수량(약 763주)이 그대로 체결돼 1천만 원 중 절반이 현금으로 남는다.
    """
    items = [{"sectorId": "BIO", "instrumentId": BIO, "targetWeight": "1"}]
    output = completed(run={"start": "2024-03-01", "end": "2024-03-08"}, universe={"items": items})

    fill_day = next(s for s in output.portfolio_series if s.date == SPLIT_DAY)
    assert fill_day.cash < Decimal("6554") * 2, "분할 후 두 주 값도 안 되는 현금만 남아야 한다"
    assert output.trades[0].quantity > 1400
    assert output.weights_series[0].weight_error is not None
    assert output.weights_series[0].weight_error < Decimal("0.01")


def test_reverse_split_that_rounds_a_pending_order_to_zero_drops_it_with_a_record() -> None:
    """병합(비율 0.5). 3/1 에 1주 매수를 정했는데 3/4 분할 뒤 0.5주 → 0주다.

    0주 주문을 Nautilus 에 내면 SELL 은 터지고 BUY 는 INSUFFICIENT_CASH 라는 틀린 사유로 남는다.
    """

    def merge(market: MarketData) -> MarketData:
        splits = tuple(replace(s, ratio=Decimal("0.5")) for s in market.splits)
        return replace(market, splits=splits)

    items = [{"sectorId": "BIO", "instrumentId": BIO, "targetWeight": "1"}]
    output = run_patched(
        merge,
        run={"start": "2024-03-01", "end": "2024-03-08"},
        universe={"items": items},
        portfolio={"initialCash": "20000"},  # 13,097원짜리 한 주만 산다
    )
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    assert output.trades == []
    assert [e.kind for e in output.exceptions or [] if e.date == SPLIT_DAY] == ["SPLIT_FRACTION"]
    warned = [w.code for w in output.diagnostics.warnings if w.date == SPLIT_DAY]
    assert warned == ["SPLIT_ROUNDING"]


def test_halt_spanning_a_rebalance_supersedes_the_deferred_order() -> None:
    """반도체 ETF 가 5/20~6/4 정지. 5/17 판단의 이월 주문이 5/31 월말 판단을 넘긴다.

    옛 수량으로 체결하면 기록(새 목표)과 주문(옛 목표)이 어긋나고 5/17 리밸런싱의 비중 기록은
    사라진다. 5/31 에 이월 주문을 새 목표로 바꾸고, 미완료였던 5/17 목표는 그날 실제 비중으로
    남긴다.
    """
    halted = pd.date_range("2024-05-20", "2024-06-04")

    def long_halt(market: MarketData) -> MarketData:
        frame = market.prices[SEMICONDUCTOR]
        rows = frame.index.isin(halted)
        frame.loc[rows, "trading_suspended"] = True
        frame.loc[rows, "volume"] = 0
        return market

    output = run_patched(
        long_halt, **three_etf("2024-05-13", "2024-06-28", run={"effectiveStart": "2024-05-17"})
    )
    assert output.run.status is RunStatus.COMPLETED, output.run.error

    superseded = [e for e in output.exceptions or [] if e.kind == HALT_SUPERSEDED]
    assert [(e.date, e.instrument_id) for e in superseded] == [(date(2024, 5, 31), SEMICONDUCTOR)]

    # 5/17 리밸런싱은 미완료 상태로 5/31 에 기록되고, 5/31 리밸런싱은 정지 해제 뒤 6/5 에 기록된다.
    assert [w.date for w in output.weights_series] == [date(2024, 5, 31), date(2024, 6, 5)]
    unfinished = output.weights_series[0]
    assert unfinished.target[SEMICONDUCTOR] == Decimal("0.4")
    assert unfinished.actual[SEMICONDUCTOR] == 0
    assert unfinished.weight_error is not None and unfinished.weight_error > Decimal("0.3")
    # 5/31 종가로 정한 수량을 6/5 종가로 평가한 차이만 남는다(옛 5/17 수량이면 훨씬 크다).
    assert output.weights_series[1].weight_error < Decimal("0.05")

    # 실제 체결은 5/31 판단의 주문이다 — 예정일이 5/20 이 아니라 6/3 이다.
    semis = [t for t in output.trades if t.instrument_id == SEMICONDUCTOR]
    assert len(semis) == 1 and semis[0].reason is TradeReason.HALT_DEFERRED
    assert semis[0].planned_date == date(2024, 6, 3)
    assert kst_date(semis[0].entry_time) == date(2024, 6, 5)


def test_transient_provider_failure_propagates_instead_of_failing_the_run() -> None:
    """일시 장애를 FAILED 결과로 바꾸면 소비자가 FINISHED 를 발행·ACK 해 최종 실패로 굳는다.

    계약 §7 ③: 일시 장애는 ACK 하지 않고 재전달 경로로 넘긴다.
    """

    class FlakyProvider:
        def load(self, *args: Any, **kwargs: Any) -> Any:
            raise ConnectionError("market data DB unreachable")

    with pytest.raises(ConnectionError):
        PortfolioBacktestEngine(FlakyProvider()).run(
            EngineInput.model_validate(engine_input_body())
        )


def test_engine_invariant_failure_is_returned_as_engine_error(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """엔진 자체 결함은 같은 입력이면 다시 나므로 결과로 돌려준다 — 재전달해도 달라지지 않는다."""
    import worker.engine_runner as runner_module

    def broken(*args: Any, **kwargs: Any) -> Any:
        raise EngineFailure("Cash invariant violated")

    monkeypatch.setattr(runner_module, "execute_portfolio", broken)
    output = run()
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == "ENGINE_ERROR"


def test_defect_after_data_is_loaded_is_an_engine_error(monkeypatch: pytest.MonkeyPatch) -> None:
    """자료가 메모리에 오른 뒤의 예외는 전부 결정적 결함이다.

    전파해 재전달시키면 같은 자리에서 또 죽어 DLQ 로 간다 — 장애가 아니라 결함이다.
    """
    import worker.engine_runner as runner_module

    def broken(*args: Any, **kwargs: Any) -> Any:
        raise ZeroDivisionError("division by zero in metrics")

    monkeypatch.setattr(runner_module, "calculate_performance", broken)
    output = run()
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == "ENGINE_ERROR"
    assert "ZeroDivisionError" in output.run.error.message


# --- TARGET_RISK 오버레이(PD-18) -----------------------------------------------------------------

TARGET_RISK_RULES: dict[str, Any] = {
    "version": "TARGET_RISK_OVERLAY/1",
    "lookbackDays": 63,
    "adjustAt": "REBALANCE",
    "scaleDownOnly": True,
}


def target_risk(target: str, evaluation: dict[str, Any] | None = None) -> dict[str, Any]:
    return three_etf(
        "2023-08-01",
        "2024-06-28",
        run={
            "scenario": "TARGET_RISK",
            "scenarioParams": {"targetVolatility": target},
            "scenarioRules": TARGET_RISK_RULES,
        },
        evaluation=evaluation or {"warmupTradingDays": None, "liquidationAtEnd": False},
    )


def test_target_risk_scales_down_only_when_the_estimate_exceeds_the_target() -> None:
    output = completed(**target_risk("0.0500"))
    assert output.versions is not None
    assert output.versions.scenario_rules == "TARGET_RISK_OVERLAY/1"
    points = output.weights_series
    assert points
    for point in points:
        assert point.estimated_volatility is not None and point.estimated_volatility > 0
        assert point.risk_scale is not None and 0 < point.risk_scale <= 1
        # 고정 비중 합 1 × 조절 비율 = 조절 뒤 목표 합. 나머지는 현금이다.
        assert abs(sum(point.target.values()) - point.risk_scale) < Decimal("1e-12")
        if point.risk_scale < 1:
            assert point.risk_scale == Decimal("0.0500") / point.estimated_volatility
    assert any(point.risk_scale < 1 for point in points), "합성 자료의 변동성은 연 5%를 넘는다"
    # 조절된 달에는 그만큼 현금이 남는다.
    scaled = next(
        point for point in points if point.risk_scale is not None and point.risk_scale < 1
    )
    snapshot = next(s for s in output.portfolio_series if s.date == scaled.date)
    assert snapshot.cash / snapshot.equity > (1 - scaled.risk_scale) * Decimal("0.9")


def test_loose_target_never_scales_up_and_equals_the_main_analysis() -> None:
    """추정치가 목표 아래면 그대로다 — 올리지 않는다(레버리지 금지).

    그러면 같은 워밍업의 주 분석과 결과가 같아야 한다.
    """
    loose = completed(**target_risk("0.9000"))
    assert all(point.risk_scale == 1 for point in loose.weights_series)
    main = completed(
        **three_etf(
            "2023-08-01",
            "2024-06-28",
            evaluation={"warmupTradingDays": 63, "liquidationAtEnd": False},
        )
    )
    assert loose.run.effective_start == main.run.effective_start
    assert loose.portfolio_series == main.portfolio_series
    assert loose.trades == main.trades


def test_target_risk_default_warmup_is_the_overlay_lookback() -> None:
    output = completed(**target_risk("0.9000"))
    calendar = (
        CsvMarketDataProvider(THREE_ETF_DIR)
        .load([SEMICONDUCTOR, BATTERY, BIO], date(2023, 8, 1), date(2024, 6, 28))
        .trading_days
    )
    assert output.run.effective_start == calendar[63]


def test_target_risk_without_enough_history_fails_estimation() -> None:
    """추정할 수익률이 모자라면 대충 추정하지 않고 ESTIMATION_FAILED 로 멈춘다."""
    output = run(**target_risk("0.1000", {"warmupTradingDays": 0, "liquidationAtEnd": False}))
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == "ESTIMATION_FAILED"
    assert "warmup" in output.run.error.message
