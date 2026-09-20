"""개별 체결 기록(``EngineOutput.fills``, spec 011 DEP-004-011).

체결 기록은 회계에 쓰이지 않는 사본이라, 회계가 낸 값과 어긋나지 않는지를 세 방향에서 본다.

1. 독립 계산(``manual_reconciliation.json``)의 체결과 한 건씩 같다 — 비용을 갈래별로 따로 남기는지.
2. 체결을 다 더하면 결과의 비용 합계·체결 수와 같다.
3. 체결과 분배금만으로 현금을 다시 세면 시계열의 현금과 같다 — 임의 구간의 현금 흐름을 체결에서
   되살릴 수 있다는 뜻이고, 011 이 필요로 하는 성질이 이것이다.
"""

import json
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest
from conftest import THREE_ETF_DIR
from golden_inputs import engine_input_for, load_json, rule_snapshots

from worker import reproducibility
from worker.contracts.engine_output import EngineOutput, FillSide, RunStatus, TradeReason
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "manual_reconciliation.json"
CASES = {case["name"]: case for case in json.loads(FIXTURE.read_text(encoding="utf-8"))["cases"]}
ZERO = Decimal(0)


def run(body: dict[str, Any]) -> EngineOutput:
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(body)
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    return output


def kst(when: Any) -> date:
    return (when + timedelta(hours=9)).date()


@pytest.mark.parametrize("name", ["first_rebalance", "distribution", "halt_deferral"])
def test_each_fill_matches_the_independent_calculation(name: str) -> None:
    case = CASES[name]
    output = run(case["engineInput"])
    assert output.fills is not None
    for expected in case["fills"]:
        fill_day = date.fromisoformat(expected["fillDate"])
        fill = next(
            f
            for f in output.fills
            if f.instrument_id == expected["instrumentId"] and kst(f.time) == fill_day
        )
        assert fill.quantity == Decimal(expected["quantity"])
        assert fill.price == Decimal(expected["fillPrice"])
        assert fill.costs.commission == Decimal(expected["commission"])
        assert fill.costs.spread == Decimal(expected["spread"])
        assert fill.costs.slippage == Decimal(expected["slippage"])
        planned = date.fromisoformat(expected["plannedDate"])
        if planned != fill_day:
            assert fill.reason is TradeReason.HALT_DEFERRED and fill.planned_date == planned
        else:
            assert fill.reason is TradeReason.REBALANCE and fill.planned_date is None


@pytest.mark.parametrize("snapshot_path", rule_snapshots(), ids=lambda p: p.stem)
def test_fills_add_up_to_the_reported_costs_and_cash(snapshot_path: Path) -> None:
    body = engine_input_for(load_json(snapshot_path))
    output = run(body)
    fills = output.fills
    assert fills is not None and output.costs is not None and output.execution is not None

    assert len(fills) == output.execution.fill_count
    assert [f.fill_id for f in fills] == [f"FL-{i + 1:06d}" for i in range(len(fills))]
    assert [f.time for f in fills] == sorted(f.time for f in fills)
    assert sum((f.costs.commission for f in fills), ZERO) == output.costs.commission
    assert sum((f.costs.spread for f in fills), ZERO) == output.costs.spread
    assert sum((f.costs.slippage for f in fills), ZERO) == output.costs.slippage
    assert sum((f.costs.tax for f in fills), ZERO) == output.costs.tax
    for fill in fills:
        assert fill.cash_cost == fill.costs.commission + fill.costs.tax
        # 가격 충격은 체결가에 들어 있다 — 사면 기준가보다 비싸고 팔면 싸다.
        assert (fill.price >= fill.reference_price) == (fill.side is FillSide.BUY) or (
            fill.price == fill.reference_price
        )

    cash = Decimal(str(body["portfolio"]["initialCash"]))
    by_day: dict[date, list[Any]] = {}
    for fill in fills:
        by_day.setdefault(kst(fill.time), []).append(fill)
    for snapshot in output.portfolio_series:
        for fill in by_day.pop(snapshot.date, []):
            notional = fill.price * fill.quantity
            if fill.side is FillSide.BUY:
                cash -= notional + fill.cash_cost
            else:
                cash += notional - fill.cash_cost
        cash += sum((flow.amount for flow in snapshot.cash_flows or []), ZERO)
        assert cash == snapshot.cash, snapshot.date
    # 워밍업 구간에는 체결이 없다 — 시계열 밖에 남은 체결이 있으면 위 대조가 빈 것을 맞춘 셈이다.
    assert not by_day


def test_fills_stay_out_of_the_golden_hash() -> None:
    """체결 기록은 이미 해시에 든 값의 다른 표기다. 넣으면 숫자가 같은데도 골든이 전부 바뀐다."""
    output = run(CASES["first_rebalance"]["engineInput"])
    assert output.fills
    without = output.model_copy(update={"fills": None})
    assert reproducibility.computed(output) == reproducibility.computed(without)
    assert "fills" in json.loads(output.to_json())
