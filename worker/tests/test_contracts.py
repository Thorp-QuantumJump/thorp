"""계약 픽스처 round-trip과 계약 오류 거부.

백엔드 BacktestMessageRoundTripTest와 같은 픽스처·같은 규칙.
"""

import json

import pytest
from conftest import FIXTURE_DIR
from pydantic import ValidationError

from worker.contracts.base import ContractModel
from worker.contracts.engine import BacktestEngine, ProgressCallback
from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import EngineOutput, RunStatus
from worker.contracts.events import (
    BacktestEvent,
    FinishedPayload,
    ProgressPayload,
    StartedPayload,
)

FIXTURES: list[tuple[str, type[ContractModel]]] = [
    ("engine-input.json", EngineInput),
    ("event-started.json", BacktestEvent[StartedPayload]),
    ("event-progress.json", BacktestEvent[ProgressPayload]),
    ("event-finished-completed.json", BacktestEvent[FinishedPayload]),
    ("event-finished-failed.json", BacktestEvent[FinishedPayload]),
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
    params = engine_input.strategy.parameters
    assert params["shortWindow"] == 20 and isinstance(params["shortWindow"], int)
    assert params["threshold"] == 0.05 and isinstance(params["threshold"], float)
    assert str(engine_input.portfolio.initial_cash) == "10000000"
    assert engine_input.market_data.interval == "1D"


def test_finished_completed_shape() -> None:
    event = BacktestEvent[FinishedPayload].model_validate_json(
        load("event-finished-completed.json")
    )
    output = event.payload.engine_output
    assert output.run.status is RunStatus.COMPLETED and output.run.error is None
    assert output.portfolio_series[0].positions == []  # 시작일: 빈 구성
    assert output.portfolio_series[1].positions is None  # 구성 변경 없는 날: 키 없음
    assert event.payload.benchmark is not None


def test_finished_failed_shape() -> None:
    event = BacktestEvent[FinishedPayload].model_validate_json(load("event-finished-failed.json"))
    output = event.payload.engine_output
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == "NO_MARKET_DATA"
    assert output.performance is None and output.execution is None
    assert event.payload.benchmark is None


BASE_EVENT = {
    "schemaVersion": 1,
    "eventId": "E",
    "backtestId": "B",
    "executionId": "X",
    "type": "STARTED",
    "sequence": 0,
    "occurredAt": "2026-09-07T01:00:00Z",
    "payload": {"startedAt": "2026-09-07T01:00:00Z"},
}


def test_event_accepts_base() -> None:
    BacktestEvent[StartedPayload].model_validate(BASE_EVENT)


@pytest.mark.parametrize(
    "override",
    [
        pytest.param({"sequence": None}, id="required-int-null"),
        pytest.param({"type": "PAUSED"}, id="unknown-enum"),
        pytest.param({"type": 0}, id="number-for-enum"),
        pytest.param({"sequence": 1.9}, id="float-for-int"),
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
        StartedPayload.model_validate({"startedAt": "2026-09-08T09:00:00"})


def test_offset_datetime_is_normalized_to_utc_z() -> None:
    event = BacktestEvent[StartedPayload].model_validate(
        {
            **BASE_EVENT,
            "occurredAt": "2026-09-08T09:00:00+09:00",
            "payload": {"startedAt": "2026-09-08T09:00:00+09:00"},
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


def test_dummy_engine_satisfies_protocol() -> None:
    class DummyEngine:
        def run(self, engine_input: EngineInput, on_progress: ProgressCallback) -> EngineOutput:
            raise NotImplementedError

    assert isinstance(DummyEngine(), BacktestEngine)
    assert not isinstance(object(), BacktestEngine)
