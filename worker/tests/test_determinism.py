"""결정성(SC-002)과 체결 규칙 검증(SC-004) — spec 004 T008.

같은 EngineInput 을 두 번 실행하면 ``run.startedAt``·``run.finishedAt`` 두 필드를 뺀
``EngineOutput.to_json()`` 이 **바이트 동일**해야 한다(세 전략 + TARGET_RISK 오버레이).
자원 사용량은 EngineOutput 밖(FINISHED payload ``resource``)이라 비교 대상이 아니다.

SC-004: 판단일 이후 자료가 판단에 쓰인 사례 0건, 모든 체결은 판단일 다음 거래일 이후.
"""

import json
from datetime import timedelta
from pathlib import Path
from typing import Any

import pytest
from conftest import THREE_ETF_DIR

import worker.portfolio_execution as execution_module
from worker.contracts.engine_output import EngineOutput, RunStatus, TradeReason
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider

FIXTURES = Path(__file__).resolve().parent / "fixtures"
STRATEGY_FIXTURES = [
    "engine-input-three-etf-fixed.json",
    "engine-input-three-etf-equal.json",
    "engine-input-three-etf-inverse_vol.json",
    "engine-input-three-etf-target-risk.json",
]


def load(name: str) -> dict[str, Any]:
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def run(body: dict[str, Any]) -> EngineOutput:
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(body)
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    return output


def without_clock(output: EngineOutput) -> bytes:
    payload = json.loads(output.to_json())
    for field in ("startedAt", "finishedAt"):
        assert payload["run"].pop(field)  # 존재만 확인한다
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()


# --- SC-002 결정성 -----------------------------------------------------------------------


@pytest.mark.parametrize("name", STRATEGY_FIXTURES)
def test_same_engine_input_twice_is_byte_identical(name: str) -> None:
    first, second = run(load(name)), run(load(name))
    assert without_clock(first) == without_clock(second)
    assert first.analytics is not None and first.versions is not None
    assert first.versions.data == "FIXTURE-three_etf_24m"


def test_target_risk_fixture_records_the_overlay_version_and_scaling() -> None:
    output = run(load("engine-input-three-etf-target-risk.json"))
    assert output.versions is not None
    assert output.versions.scenario_rules == "TARGET_RISK_OVERLAY/1"
    assert any(
        point.risk_scale is not None and point.risk_scale < 1
        for point in output.weights_series or []
    )


# --- SC-004 체결 규칙 ----------------------------------------------------------------------


def test_every_fill_is_on_or_after_the_trading_day_after_its_decision() -> None:
    """REBALANCE·SIGNAL·LIQUIDATION 체결은 판단일 다음 거래일, HALT_DEFERRED 는 plannedDate 가
    판단일 다음 거래일이고 체결일은 그 뒤 첫 거래 가능일이다."""
    body = load("engine-input-three-etf-fixed.json")
    body["run"] = {
        **body["run"],
        "start": "2024-06-03",
        "end": "2024-06-28",
        "effectiveStart": "2024-06-10",
    }
    output = run(body)
    calendar = [snapshot.date for snapshot in output.portfolio_series]
    fill_days = {calendar[i + 1] for i in range(len(calendar) - 1)}  # 판단일 다음 거래일 집합
    for trade in output.trades:
        entry_day = (trade.entry_time + timedelta(hours=9)).date()
        if trade.reason is TradeReason.HALT_DEFERRED:
            assert trade.planned_date in fill_days
            assert entry_day > trade.planned_date
        else:
            assert entry_day in fill_days
        assert trade.quantity == trade.quantity.to_integral_value()
    assert output.run.effective_start == calendar[0] == output.portfolio_series[0].date


def test_allocation_never_sees_data_after_the_decision_day(monkeypatch: pytest.MonkeyPatch) -> None:
    """look-ahead 0건: 배분 모듈에 넘기는 이력의 마지막 날짜가 판단일을 넘지 않는다."""
    seen: list[tuple[Any, Any]] = []
    original = execution_module.compute_weights

    def spy(allocation: Any, items: Any, history: Any, as_of: Any) -> Any:
        seen.append((history.index.max().date(), as_of))
        return original(allocation, items, history, as_of)

    monkeypatch.setattr(execution_module, "compute_weights", spy)
    run(load("engine-input-three-etf-inverse_vol.json"))
    assert seen, "판단이 한 번도 없었다"
    assert all(last <= as_of for last, as_of in seen)
