"""수기 대조(SC-003, T008): 대표 거래일 세 개의 체결·수량·현금·비용·분배금을 독립 계산과 맞춘다.

기대값은 ``tests/fixtures/manual_reconciliation.json`` 에 고정돼 있다. 그 값은 워커 코드를 import
하지 않는 ``docs/specs/backtest-messaging/examples/reconciliation/reconcile.py`` 가 픽스처 CSV
원본에서 계산한 것이고, 원본 CSV 도 같은 디렉터리에 보관한다. 허용 오차는 PD-12 전까지 0 이다.
"""

import json
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest
from conftest import THREE_ETF_DIR

from worker.contracts.engine_output import EngineOutput, RunStatus, TradeReason
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider

#: PD-12 확정 시 이 상수만 바꾼다.
PROVISIONAL_TOLERANCE = Decimal("0")
FIXTURE = Path(__file__).resolve().parent / "fixtures" / "manual_reconciliation.json"
RECONCILIATION = json.loads(FIXTURE.read_text(encoding="utf-8"))
CASES = {case["name"]: case for case in RECONCILIATION["cases"]}


def run(body: dict[str, Any]) -> EngineOutput:
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(body)
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    return output


def close_enough(actual: Decimal, expected: str) -> bool:
    return abs(actual - Decimal(expected)) <= PROVISIONAL_TOLERANCE


def kst(when: Any) -> date:
    return (when + timedelta(hours=9)).date()


def cash_on(output: EngineOutput, day: str) -> Decimal:
    return next(s.cash for s in output.portfolio_series if s.date == date.fromisoformat(day))


@pytest.mark.parametrize("name", ["first_rebalance", "distribution", "halt_deferral"])
def test_fills_match_the_independent_calculation(name: str) -> None:
    case = CASES[name]
    output = run(case["engineInput"])
    for expected in case["fills"]:
        fill_day = date.fromisoformat(expected["fillDate"])
        trade = next(
            t
            for t in output.trades
            if t.instrument_id == expected["instrumentId"] and kst(t.entry_time) == fill_day
        )
        assert trade.quantity == Decimal(expected["quantity"])
        assert close_enough(trade.entry_price, expected["fillPrice"])
        assert close_enough(trade.commission, expected["commission"])
        spread_and_slippage = Decimal(expected["spread"]) + Decimal(expected["slippage"])
        assert close_enough(trade.slippage_cost, str(spread_and_slippage))
        planned = date.fromisoformat(expected["plannedDate"])
        if planned != fill_day:
            assert trade.reason is TradeReason.HALT_DEFERRED and trade.planned_date == planned
        else:
            assert trade.reason is TradeReason.REBALANCE and trade.planned_date is None
    assert len(output.trades) == len(case["fills"])
    for day, expected_cash in case["cashAfter"].items():
        assert close_enough(cash_on(output, day), expected_cash), (day, cash_on(output, day))


def test_distribution_pays_ex_date_holdings_times_the_declared_amount() -> None:
    expected = CASES["distribution"]["distribution"]
    output = run(expected["engineInput"])
    pay_day = next(
        s for s in output.portfolio_series if s.date == date.fromisoformat(expected["payDate"])
    )
    (flow,) = [f for f in pay_day.cash_flows or [] if f.type == "DISTRIBUTION"]
    assert flow.instrument_id == expected["instrumentId"]
    assert close_enough(flow.amount, expected["amount"])
    # 권리락일 보유는 04-02 체결분 그대로다(그 사이 리밸런싱 없음).
    ex_day = date.fromisoformat(expected["exDate"])
    held = next(
        p.quantity
        for s in reversed([s for s in output.portfolio_series if s.date <= ex_day and s.positions])
        for p in s.positions or []
        if p.instrument_id == expected["instrumentId"]
    )
    assert held == Decimal(expected["heldOnExDate"])
