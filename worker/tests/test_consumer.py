"""실행 Consumer: 발행 순서·ACK 순서·오류 3분류·멱등성.

브로커 없이 전체 흐름을 검증한다. 발행과 ACK는 ``worker.messaging`` Protocol 뒤에 있으므로
fake로 갈아끼우고, **발행과 ACK를 같은 로그 리스트에 기록해 순서를 직접 단언한다**
(계약 §7 "FINISHED 발행이 브로커에서 확인된 뒤 작업 메시지 ACK").

엔진은 실제 ``NautilusMomentumEngine`` + ``CsvDataProvider`` 를 쓴다(네트워크 없음).
20거래일 묶음 경계만은 고정 데이터가 7거래일뿐이라 콜백만 흉내내는 fake 엔진을 쓴다.
"""

import json
from collections.abc import Iterator, Mapping
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest
from conftest import FIXTURE_DIR, engine_input_body

from worker.consumer import BacktestJobConsumer
from worker.contracts.base import utc_now
from worker.contracts.engine import BacktestEngine
from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import Diagnostics, EngineOutput, RunResult, RunStatus
from worker.contracts.events import (
    BacktestEvent,
    BacktestEventType,
    FinishedPayload,
    ProgressPayload,
    ProgressPoint,
    StartedPayload,
)
from worker.engine_runner import NautilusMomentumEngine
from worker.market_data import CsvDataProvider
from worker.messaging import PROGRESS_BATCH_TRADING_DAYS, SCHEMA_VERSION, PublishError

CSV_FIXTURE = Path(__file__).parents[1] / "examples" / "momentum_fixture.csv"

#: 고정 데이터 회귀 기준 구간의 평가 거래일 수 (README "검증 결과와 제약").
EVALUATION_TRADING_DAYS = 7


class FakePublisher:
    """``publish`` 가 정상 반환하면 "브로커가 확인했다"는 뜻. 실패는 PublishError."""

    def __init__(self, call_log: list[str], fail_on: tuple[BacktestEventType, ...] = ()) -> None:
        self.call_log = call_log
        self.fail_on = set(fail_on)
        self.events: list[BacktestEvent] = []

    def publish(self, event: BacktestEvent) -> None:
        if event.type in self.fail_on:
            self.call_log.append(f"publish-failed:{event.type.value}")
            raise PublishError(f"fake broker refused {event.type.value}")
        self.events.append(event)
        self.call_log.append(f"publish:{event.type.value}")

    def of_type(self, event_type: BacktestEventType) -> list[BacktestEvent]:
        return [event for event in self.events if event.type is event_type]


class FakeAcknowledger:
    def __init__(self, call_log: list[str]) -> None:
        self.call_log = call_log

    def ack(self) -> None:
        self.call_log.append("ack")

    def reject(self) -> None:
        self.call_log.append("reject")

    def requeue(self) -> None:
        self.call_log.append("requeue")


class FakeEngine:
    """진행 콜백만 흉내내는 엔진. 묶음 경계 테스트용."""

    def __init__(self, trading_days: int) -> None:
        self.trading_days = trading_days
        self.runs = 0

    def run(self, engine_input: EngineInput, on_progress: Any = None) -> EngineOutput:
        self.runs += 1
        for completed in range(1, self.trading_days + 1):
            on_progress(
                ProgressPoint(
                    date=date(2025, 1, 1) + timedelta(days=completed),
                    equity=Decimal("1000"),
                    cumulative_return=Decimal("0"),
                    drawdown=Decimal("0"),
                ),
                completed,
                self.trading_days,
            )
        now = utc_now()
        return EngineOutput(
            run=RunResult(
                backtest_id=engine_input.run.backtest_id,
                status=RunStatus.COMPLETED,
                started_at=now,
                finished_at=now,
            ),
            portfolio_series=[],
            trades=[],
            diagnostics=Diagnostics(warnings=[]),
        )


class CountingEngine:
    """실제 엔진을 그대로 실행하면서 실행 횟수만 센다 (재전달 확인용)."""

    def __init__(self, engine: BacktestEngine) -> None:
        self._engine = engine
        self.runs = 0

    def run(self, engine_input: EngineInput, on_progress: Any = None) -> EngineOutput:
        self.runs += 1
        return self._engine.run(engine_input, on_progress)


def real_engine() -> NautilusMomentumEngine:
    return NautilusMomentumEngine(CsvDataProvider(CSV_FIXTURE))


def sequential_ids() -> Iterator[str]:
    counter = 0
    while True:
        counter += 1
        yield f"ID-{counter}"


def handle(
    body: bytes | str | None = None,
    *,
    engine: Any = None,
    fail_on: tuple[BacktestEventType, ...] = (),
    headers: Mapping[str, Any] | None = None,
    progress_batch_size: int = PROGRESS_BATCH_TRADING_DAYS,
    deterministic_ids: bool = True,
) -> tuple[list[str], FakePublisher]:
    """작업 메시지 한 건을 처리하고 (호출 로그, 발행자)를 돌려준다."""
    call_log: list[str] = []
    publisher = FakePublisher(call_log, fail_on)
    ids = sequential_ids()
    overrides: dict[str, Any] = {"new_id": lambda: next(ids)} if deterministic_ids else {}
    consumer = BacktestJobConsumer(
        engine or real_engine(),
        publisher,
        progress_batch_size=progress_batch_size,
        **overrides,
    )
    if body is None:
        body = json.dumps(engine_input_body())
    consumer.handle(body, headers, FakeAcknowledger(call_log))
    return call_log, publisher


# --- 발행 순서와 sequence -------------------------------------------------------------


def test_started_progress_finished_order_and_sequence() -> None:
    """STARTED(0) → PROGRESS(20거래일 묶음, 마지막 잔여 구간 포함) → FINISHED → ACK."""
    call_log, publisher = handle(progress_batch_size=3)  # 7거래일 → 3 + 3 + 1

    assert call_log == [
        "publish:STARTED",
        "publish:PROGRESS",
        "publish:PROGRESS",
        "publish:PROGRESS",
        "publish:FINISHED",
        "ack",
    ]
    assert [event.sequence for event in publisher.events] == [0, 1, 2, 3, 4]
    progress = publisher.of_type(BacktestEventType.PROGRESS)
    assert [len(event.payload.portfolio_series) for event in progress] == [3, 3, 1]
    assert [event.payload.completed_trading_days for event in progress] == [3, 6, 7]
    assert {event.payload.total_trading_days for event in progress} == {EVALUATION_TRADING_DAYS}


def test_progress_points_are_not_republished() -> None:
    """이번 구간에서 새로 계산한 포인트만 담는다 (계약 §4.2)."""
    _, publisher = handle(progress_batch_size=3)
    dates = [
        point.date
        for event in publisher.of_type(BacktestEventType.PROGRESS)
        for point in event.payload.portfolio_series
    ]
    assert len(dates) == len(set(dates)) == EVALUATION_TRADING_DAYS
    assert dates == sorted(dates)


def test_default_batch_is_twenty_trading_days_with_remainder() -> None:
    """기본 묶음은 20거래일이고 마지막 잔여 5거래일도 발행한다."""
    assert PROGRESS_BATCH_TRADING_DAYS == 20
    _, publisher = handle(engine=FakeEngine(trading_days=45))
    progress = publisher.of_type(BacktestEventType.PROGRESS)
    assert [len(event.payload.portfolio_series) for event in progress] == [20, 20, 5]
    assert [event.payload.completed_trading_days for event in progress] == [20, 40, 45]


def test_exact_batch_boundary_does_not_publish_empty_progress() -> None:
    _, publisher = handle(engine=FakeEngine(trading_days=40))
    progress = publisher.of_type(BacktestEventType.PROGRESS)
    assert [len(event.payload.portfolio_series) for event in progress] == [20, 20]


# --- FINISHED 발행 확인 → ACK 순서 -----------------------------------------------------


def test_ack_comes_only_after_finished_is_confirmed() -> None:
    call_log, _ = handle()
    assert call_log[-2:] == ["publish:FINISHED", "ack"]
    assert call_log.index("ack") > call_log.index("publish:FINISHED")


def test_finished_publish_failure_does_not_ack() -> None:
    """발행 실패는 일시 장애다. ACK하지 않는다 — 결과를 잃고 완료로 숨기지 않는다."""
    call_log, publisher = handle(fail_on=(BacktestEventType.FINISHED,))
    assert "ack" not in call_log
    assert call_log[-2:] == ["publish-failed:FINISHED", "requeue"]
    assert publisher.of_type(BacktestEventType.FINISHED) == []


def test_started_publish_failure_skips_execution_and_does_not_ack() -> None:
    engine = CountingEngine(real_engine())
    call_log, _ = handle(engine=engine, fail_on=(BacktestEventType.STARTED,))
    assert call_log == ["publish-failed:STARTED", "requeue"]
    assert engine.runs == 0


def test_progress_publish_failure_does_not_ack() -> None:
    """엔진이 콜백 실패를 CallbackError로 올린다. 그래도 ACK하지 않는다."""
    call_log, publisher = handle(fail_on=(BacktestEventType.PROGRESS,), progress_batch_size=3)
    assert "ack" not in call_log
    assert call_log == ["publish:STARTED", "publish-failed:PROGRESS", "requeue"]
    assert publisher.of_type(BacktestEventType.FINISHED) == []


# --- 계약 오류 → requeue 없이 reject → DLQ ---------------------------------------------


def _body_without_initial_cash() -> str:
    body = engine_input_body()
    del body["portfolio"]["initialCash"]
    return json.dumps(body)


@pytest.mark.parametrize(
    ("body", "headers"),
    [
        pytest.param("{not json", None, id="broken-json"),
        pytest.param("[]", None, id="json-array-not-object"),
        pytest.param(_body_without_initial_cash(), None, id="missing-required-field"),
        pytest.param(
            json.dumps(engine_input_body(marketData={"interval": "1H"})),
            None,
            id="unknown-enum-value",
        ),
        pytest.param(json.dumps(engine_input_body()), {"schemaVersion": 2}, id="header-version"),
        pytest.param(
            json.dumps({**engine_input_body(), "schemaVersion": 2}), None, id="body-version"
        ),
        pytest.param(
            json.dumps(engine_input_body()), {"schemaVersion": "1"}, id="header-version-not-int"
        ),
    ],
)
def test_contract_errors_are_rejected_without_requeue(
    body: str, headers: Mapping[str, Any] | None
) -> None:
    engine = CountingEngine(real_engine())
    call_log, publisher = handle(body, engine=engine, headers=headers)
    assert call_log == ["reject"]  # ACK도 requeue도 아니다
    assert publisher.events == []
    assert engine.runs == 0


def test_supported_schema_version_is_accepted_in_header_and_body() -> None:
    body = json.dumps({**engine_input_body(), "schemaVersion": SCHEMA_VERSION})
    call_log, _ = handle(body, headers={"schemaVersion": SCHEMA_VERSION})
    assert call_log[-1] == "ack"


def test_absent_schema_version_is_accepted() -> None:
    """작업 메시지 본문은 EngineInput 전체이고 계약 픽스처에도 schemaVersion 키가 없다."""
    call_log, _ = handle(json.dumps(engine_input_body()), headers=None)
    assert call_log[-1] == "ack"


# --- 엔진 FAILED 는 정상 결과 ---------------------------------------------------------


@pytest.mark.parametrize(
    ("overrides", "code"),
    [
        pytest.param({"run": {"start": "2030-01-02", "end": "2030-01-10"}}, "NO_MARKET_DATA"),
        pytest.param({"portfolio": {"leverage": "2"}}, "INVALID_REQUEST"),
    ],
)
def test_engine_failure_is_published_as_finished_and_acked(
    overrides: dict[str, Any], code: str
) -> None:
    """엔진이 계약대로 답한 것이므로 DLQ가 아니다. 같은 FINISHED에 담아 ACK한다."""
    call_log, publisher = handle(json.dumps(engine_input_body(**overrides)))
    assert call_log[-2:] == ["publish:FINISHED", "ack"]
    assert "reject" not in call_log and "requeue" not in call_log
    finished = publisher.of_type(BacktestEventType.FINISHED)[0]
    output = finished.payload.engine_output
    assert output.run.status is RunStatus.FAILED
    assert output.run.error is not None and output.run.error.code == code
    assert output.performance is None and output.execution is None


def test_consumer_does_not_branch_on_engine_status() -> None:
    """성공·실패 모두 type=FINISHED 하나다."""
    ok, _ = handle()
    failed, _ = handle(json.dumps(engine_input_body(portfolio={"leverage": "2"})))
    assert [entry for entry in ok if entry.startswith("publish:F")] == ["publish:FINISHED"]
    assert [entry for entry in failed if entry.startswith("publish:F")] == ["publish:FINISHED"]


# --- 멱등성: 재전달은 재실행 + 새 executionId -----------------------------------------


def test_redelivery_reruns_with_a_new_execution_id() -> None:
    """워커는 처리 이력을 남길 곳이 없다(계약 §1). 중복 방어 키는 eventId다 (계약 §7)."""
    engine = CountingEngine(real_engine())
    body = json.dumps(engine_input_body())
    first_log, first = handle(body, engine=engine, deterministic_ids=False)
    second_log, second = handle(body, engine=engine, deterministic_ids=False)

    assert first_log[-1] == "ack" and second_log[-1] == "ack"
    assert engine.runs == 2
    first_execution = {event.execution_id for event in first.events}
    second_execution = {event.execution_id for event in second.events}
    assert len(first_execution) == len(second_execution) == 1  # 실행 안에서는 하나
    assert first_execution != second_execution  # 실행 시도마다 새 값
    assert {event.backtest_id for event in first.events + second.events} == {"TEST"}
    assert [event.sequence for event in second.events] == [0, 1, 2]  # 새 실행은 0부터


def test_event_ids_are_unique_across_redelivery() -> None:
    body = json.dumps(engine_input_body())
    _, first = handle(body, deterministic_ids=False)
    _, second = handle(body, deterministic_ids=False)
    ids = [event.event_id for event in first.events + second.events]
    assert len(ids) == len(set(ids))


# --- 메시지 모양: 계약 픽스처와 대조 --------------------------------------------------


def fixture_json(name: str) -> dict[str, Any]:
    return json.loads((FIXTURE_DIR / name).read_text(encoding="utf-8"))


def test_published_events_match_contract_fixture_shape() -> None:
    _, publisher = handle(progress_batch_size=3)
    published = {event.type: json.loads(event.to_json()) for event in publisher.events}

    envelope_keys = set(fixture_json("event-started.json"))
    for event_type, body in published.items():
        assert set(body) == envelope_keys, event_type
        assert body["schemaVersion"] == SCHEMA_VERSION
        assert body["backtestId"] == "TEST"
        assert body["occurredAt"].endswith("Z")

    started = fixture_json("event-started.json")
    assert set(published[BacktestEventType.STARTED]["payload"]) == set(started["payload"])

    progress_fixture = fixture_json("event-progress.json")["payload"]
    progress = published[BacktestEventType.PROGRESS]["payload"]
    assert set(progress) == set(progress_fixture)
    assert set(progress["portfolioSeries"][0]) == set(progress_fixture["portfolioSeries"][0])

    # benchmark 를 계산하지 않으므로(별도 티켓) 모양 기준은 benchmark 없는 실패 픽스처다.
    finished_fixture = fixture_json("event-finished-failed.json")["payload"]
    assert set(published[BacktestEventType.FINISHED]["payload"]) == set(finished_fixture)


def test_published_events_revalidate_through_the_contract() -> None:
    _, publisher = handle(progress_batch_size=3)
    models = {
        BacktestEventType.STARTED: BacktestEvent[StartedPayload],
        BacktestEventType.PROGRESS: BacktestEvent[ProgressPayload],
        BacktestEventType.FINISHED: BacktestEvent[FinishedPayload],
    }
    for event in publisher.events:
        model = models[event.type]
        assert model.model_validate_json(event.to_json()).sequence == event.sequence


def test_finished_never_carries_a_benchmark() -> None:
    """벤치마크 계산은 이 티켓 범위가 아니다. 항상 None → 직렬화에서 키 생략."""
    _, publisher = handle()
    finished = publisher.of_type(BacktestEventType.FINISHED)[0]
    assert finished.payload.benchmark is None
    assert "benchmark" not in json.loads(finished.to_json())["payload"]


def test_regression_baseline_is_untouched() -> None:
    """엔진을 건드리지 않았는지 확인 (최종 자산 1068.7, 평가 7거래일)."""
    _, publisher = handle()
    output = publisher.of_type(BacktestEventType.FINISHED)[0].payload.engine_output
    assert output.run.status is RunStatus.COMPLETED
    assert output.performance is not None
    assert output.performance.final_equity == Decimal("1068.7")
    assert len(output.portfolio_series) == EVALUATION_TRADING_DAYS
