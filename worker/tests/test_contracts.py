"""계약 픽스처 round-trip과 계약 오류 거부.

백엔드 BacktestMessageRoundTripTest와 같은 픽스처·같은 규칙.
"""

import json

import pytest
from conftest import FIXTURE_DIR, THREE_ETF_DIR
from pydantic import ValidationError

from worker.contracts.base import ContractModel
from worker.contracts.control import ControlMessage, ControlType
from worker.contracts.engine import BacktestEngine
from worker.contracts.engine_input import EngineInput, Scenario
from worker.contracts.engine_output import EngineOutput, RunStatus, TradeReason
from worker.contracts.events import (
    BacktestEvent,
    FinishedPayload,
    ProcessingStage,
    ProgressPayload,
    StartedPayload,
)
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider
from worker.messaging import SCHEMA_VERSION

FIXTURES: list[tuple[str, type[ContractModel]]] = [
    ("engine-input.json", EngineInput),
    ("engine-input-target-risk.json", EngineInput),
    ("event-started.json", BacktestEvent[StartedPayload]),
    ("event-progress.json", BacktestEvent[ProgressPayload]),
    ("event-finished-completed.json", BacktestEvent[FinishedPayload]),
    ("event-finished-failed.json", BacktestEvent[FinishedPayload]),
    ("event-finished-cancelled.json", BacktestEvent[FinishedPayload]),
    ("control-cancel.json", ControlMessage),
]


def load(name: str) -> str:
    return (FIXTURE_DIR / name).read_text(encoding="utf-8")


@pytest.mark.parametrize(("name", "model"), FIXTURES, ids=[f[0] for f in FIXTURES])
def test_roundtrip_exact(name: str, model: type[ContractModel]) -> None:
    """픽스처 → 모델 → JSON 이 원본과 정확 동치 (null 키 생략 규칙, 정규화 헬퍼 없음)."""
    original = load(name)
    assert json.loads(model.model_validate_json(original).to_json()) == json.loads(original)


def test_engine_input_parameters_keep_number_types() -> None:
    engine_input = EngineInput.model_validate_json(load("engine-input.json"))
    params = engine_input.allocation.parameters
    assert params["shortWindow"] == 20 and isinstance(params["shortWindow"], int)
    assert params["threshold"] == 0.05 and isinstance(params["threshold"], float)
    assert str(engine_input.portfolio.initial_cash) == "10000000"
    assert engine_input.market_data.interval == "1D"
    assert engine_input.universe.instrument_ids == ["005930.XKRX", "000660.XKRX"]
    assert engine_input.run.scenario is Scenario.FULL_PERIOD
    assert engine_input.execution.cost_profile.provisional is True


def test_target_risk_input_carries_scenario_blocks() -> None:
    engine_input = EngineInput.model_validate_json(load("engine-input-target-risk.json"))
    assert engine_input.run.scenario is Scenario.TARGET_RISK
    assert engine_input.run.scenario_params == {"targetVolatility": "0.1000"}
    assert engine_input.run.scenario_rules["lookbackDays"] == 63
    assert engine_input.universe.mapping_version == "SECTOR-ETF-2026-09"
    assert [item.sector_id for item in engine_input.universe.items][0] == "SEMICONDUCTOR"
    assert engine_input.allocation.rules is not None


def test_finished_completed_shape() -> None:
    event = BacktestEvent[FinishedPayload].model_validate_json(
        load("event-finished-completed.json")
    )
    assert event.payload.status is RunStatus.COMPLETED and event.payload.partial is False
    output = event.payload.engine_output
    assert output.run.status is RunStatus.COMPLETED and output.run.error is None
    assert output.portfolio_series[0].positions == []  # 시작일: 빈 구성
    assert output.portfolio_series[1].positions is None  # 구성 변경 없는 날: 키 없음
    assert output.trades[0].reason is TradeReason.SIGNAL
    assert output.costs is not None and output.versions is not None
    assert event.payload.resource is not None


def test_finished_failed_shape() -> None:
    event = BacktestEvent[FinishedPayload].model_validate_json(load("event-finished-failed.json"))
    assert event.payload.status is RunStatus.FAILED
    output = event.payload.engine_output
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == "DATA_INSUFFICIENT"
    assert output.performance is None and output.execution is None


def test_finished_cancelled_is_partial_with_last_trading_date() -> None:
    event = BacktestEvent[FinishedPayload].model_validate_json(
        load("event-finished-cancelled.json")
    )
    assert event.payload.status is RunStatus.CANCELLED and event.payload.partial is True
    assert event.payload.last_trading_date == event.payload.engine_output.run.last_trading_date
    assert event.payload.engine_output.performance is not None
    flows = [p.cash_flows for p in event.payload.engine_output.portfolio_series if p.cash_flows]
    assert flows and flows[0][0].type == "DISTRIBUTION"


def test_control_cancel_shape() -> None:
    message = ControlMessage.model_validate_json(load("control-cancel.json"))
    assert message.type is ControlType.CANCEL
    assert message.schema_version == SCHEMA_VERSION
    assert message.run_id == "BT-20260907-000001"


BASE_EVENT = {
    "schemaVersion": SCHEMA_VERSION,
    "eventId": "E",
    "runId": "B",
    "executionId": "X",
    "type": "STARTED",
    "sequence": 0,
    "occurredAt": "2026-09-07T01:00:00Z",
    "payload": {"startedAt": "2026-09-07T01:00:00Z", "stage": "PREPARING_DATA"},
}


def test_event_accepts_base() -> None:
    event = BacktestEvent[StartedPayload].model_validate(BASE_EVENT)
    assert event.payload.stage is ProcessingStage.PREPARING_DATA
    assert event.payload.resource is None


@pytest.mark.parametrize(
    "override",
    [
        pytest.param({"sequence": None}, id="required-int-null"),
        pytest.param({"type": "PAUSED"}, id="unknown-enum"),
        pytest.param({"type": 0}, id="number-for-enum"),
        pytest.param({"sequence": 1.9}, id="float-for-int"),
        pytest.param({"payload": {"startedAt": "2026-09-07T01:00:00Z"}}, id="stage-missing"),
    ],
)
def test_event_contract_errors_are_rejected(override: dict) -> None:
    with pytest.raises(ValidationError):
        BacktestEvent[StartedPayload].model_validate({**BASE_EVENT, **override})


def test_naive_datetime_is_rejected() -> None:
    """시간대 없는 시각은 백엔드 Instant가 읽지 못하므로 워커에서 먼저 거부한다 (README §5)."""
    with pytest.raises(ValidationError):
        BacktestEvent[StartedPayload].model_validate(
            {**BASE_EVENT, "occurredAt": "2026-09-08T00:00:00"}
        )
    with pytest.raises(ValidationError):
        StartedPayload.model_validate({"startedAt": "2026-09-08T09:00:00", "stage": "COMPUTING"})


def test_offset_datetime_is_normalized_to_utc_z() -> None:
    event = BacktestEvent[StartedPayload].model_validate(
        {
            **BASE_EVENT,
            "occurredAt": "2026-09-08T09:00:00+09:00",
            "payload": {"startedAt": "2026-09-08T09:00:00+09:00", "stage": "PREPARING_DATA"},
        }
    )
    dumped = json.loads(event.to_json())
    assert dumped["occurredAt"] == "2026-09-08T00:00:00Z"
    assert dumped["payload"]["startedAt"] == "2026-09-08T00:00:00Z"


def test_event_missing_required_int_is_rejected() -> None:
    body = {k: v for k, v in BASE_EVENT.items() if k != "sequence"}
    with pytest.raises(ValidationError):
        BacktestEvent[StartedPayload].model_validate(body)


def test_engine_input_missing_required_is_rejected() -> None:
    body = json.loads(load("engine-input.json"))
    del body["portfolio"]["initialCash"]
    with pytest.raises(ValidationError):
        EngineInput.model_validate(body)


def test_engine_output_non_final_status_is_rejected() -> None:
    body = json.loads(load("event-finished-failed.json"))["payload"]["engineOutput"]
    body["run"]["status"] = "RUNNING"
    with pytest.raises(ValidationError):
        EngineOutput.model_validate(body)


def test_portfolio_engine_satisfies_protocol() -> None:
    """``main`` 이 실행 Consumer 에 넘기는 실제 엔진이 경계 Protocol 을 만족한다.

    CI 는 타입 검사기를 돌리지 않으므로 엔진의 ``run`` 이 이름을 잃으면 여기서 잡는다.
    """
    engine = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR))

    assert isinstance(engine, BacktestEngine)
    assert not isinstance(object(), BacktestEngine)
