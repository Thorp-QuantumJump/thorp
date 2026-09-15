"""실행 Consumer: 발행 순서·ACK 순서·오류 3분류·멱등성.

브로커 없이 전체 흐름을 검증한다. 발행과 ACK는 ``worker.messaging`` Protocol 뒤에 있으므로
fake로 갈아끼우고, **발행과 ACK를 같은 로그 리스트에 기록해 순서를 직접 단언한다**
(계약 §7 "FINISHED 발행이 브로커에서 확인된 뒤 작업 메시지 ACK").

엔진은 실제 ``PortfolioBacktestEngine`` + 3종목 픽스처 ``CsvMarketDataProvider`` 를 쓴다(네트워크
없음). 묶음 경계만은 기준 본문이 7거래일뿐이라 콜백만 흉내내는 fake 엔진을 쓴다.
"""

import json
from collections.abc import Iterator, Mapping
from datetime import date, timedelta
from decimal import Decimal
from typing import Any

import pytest
from conftest import FIXTURE_DIR, THREE_ETF_DIR, engine_input_body

from worker.consumer import BacktestJobConsumer
from worker.contracts.base import utc_now
from worker.contracts.engine import BacktestEngine
from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import Diagnostics, EngineOutput, RunResult, RunStatus
from worker.contracts.events import (
    BacktestEvent,
    BacktestEventType,
    FinishedPayload,
    ProcessingStage,
    ProgressPayload,
    ProgressPoint,
    StartedPayload,
)
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider
from worker.messaging import SCHEMA_VERSION, PublishError

#: 기준 본문(2023-01-02 ~ 01-10)의 평가 거래일 수.
EVALUATION_TRADING_DAYS = 7


def progress(batch_trading_days: int) -> dict[str, Any]:
    return {"progress": {"batchTradingDays": batch_trading_days}}


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
                run_id=engine_input.run.run_id,
                status=RunStatus.COMPLETED,
                started_at=now,
                finished_at=now,
            ),
            portfolio_series=[],
            trades=[],
            diagnostics=Diagnostics(warnings=[]),
        )


class CountingEngine:
    """실제 엔진을 그대로 실행하면서 실행 횟수와 엔진이 실제로 받은 입력을 기록한다."""

    def __init__(self, engine: BacktestEngine) -> None:
        self._engine = engine
        self.runs = 0
        self.inputs: list[EngineInput] = []

    def run(self, engine_input: EngineInput, on_progress: Any = None) -> EngineOutput:
        self.runs += 1
        self.inputs.append(engine_input)
        return self._engine.run(engine_input, on_progress)


def real_engine() -> PortfolioBacktestEngine:
    return PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR))


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
    deterministic_ids: bool = True,
    **overrides: Any,
) -> tuple[list[str], FakePublisher]:
    """작업 메시지 한 건을 처리하고 (호출 로그, 발행자)를 돌려준다.

    ``overrides`` 는 기본 본문의 섹션 덮어쓰기다(``body`` 를 직접 주면 무시).
    """
    call_log: list[str] = []
    publisher = FakePublisher(call_log, fail_on)
    ids = sequential_ids()
    factory: dict[str, Any] = {"new_id": lambda: next(ids)} if deterministic_ids else {}
    consumer = BacktestJobConsumer(engine or real_engine(), publisher, **factory)
    if body is None:
        body = json.dumps(engine_input_body(**overrides))
    consumer.handle(body, headers, FakeAcknowledger(call_log))
    return call_log, publisher


# --- 발행 순서와 sequence -------------------------------------------------------------


def test_started_progress_finished_order_and_sequence() -> None:
    """STARTED(0) → PROGRESS(묶음, 마지막 잔여 구간 포함) → FINISHED → ACK."""
    call_log, publisher = handle(**progress(3))  # 7거래일 → 3 + 3 + 1

    assert call_log == [
        "publish:STARTED",
        "publish:PROGRESS",
        "publish:PROGRESS",
        "publish:PROGRESS",
        "publish:FINISHED",
        "ack",
    ]
    assert [event.sequence for event in publisher.events] == [0, 1, 2, 3, 4]
    progress_events = publisher.of_type(BacktestEventType.PROGRESS)
    assert [len(e.payload.portfolio_series) for e in progress_events] == [3, 3, 1]
    assert [e.payload.completed_trading_days for e in progress_events] == [3, 6, 7]
    assert {e.payload.total_trading_days for e in progress_events} == {EVALUATION_TRADING_DAYS}


def test_progress_carries_stage_and_last_trading_date() -> None:
    _, publisher = handle(**progress(3))
    for event in publisher.of_type(BacktestEventType.PROGRESS):
        assert event.payload.stage is ProcessingStage.COMPUTING
        assert event.payload.last_trading_date == event.payload.portfolio_series[-1].date
    started = publisher.of_type(BacktestEventType.STARTED)[0]
    assert started.payload.stage is ProcessingStage.PREPARING_DATA
    assert started.payload.resource is not None and started.payload.resource.pid is not None
    # 어느 큐·어느 워커가 집었나(R-20·T012). 기본은 단건 전용 워커가 단건 큐에서 집은 것이다.
    assert started.payload.resource.queue == "backtest.jobs"
    assert started.payload.resource.role == "single"


def test_progress_points_are_not_republished() -> None:
    """이번 구간에서 새로 계산한 포인트만 담는다 (계약 §4.2)."""
    _, publisher = handle(**progress(3))
    dates = [
        point.date
        for event in publisher.of_type(BacktestEventType.PROGRESS)
        for point in event.payload.portfolio_series
    ]
    assert len(dates) == len(set(dates)) == EVALUATION_TRADING_DAYS
    assert dates == sorted(dates)


def test_batch_size_comes_from_the_job_message() -> None:
    """묶음 크기는 상수가 아니라 ``progress.batchTradingDays`` 다. 잔여 5거래일도 발행한다."""
    _, publisher = handle(engine=FakeEngine(trading_days=45), **progress(20))
    progress_events = publisher.of_type(BacktestEventType.PROGRESS)
    assert [len(e.payload.portfolio_series) for e in progress_events] == [20, 20, 5]
    assert [e.payload.completed_trading_days for e in progress_events] == [20, 40, 45]


def test_exact_batch_boundary_does_not_publish_empty_progress() -> None:
    _, publisher = handle(engine=FakeEngine(trading_days=40), **progress(20))
    progress_events = publisher.of_type(BacktestEventType.PROGRESS)
    assert [len(e.payload.portfolio_series) for e in progress_events] == [20, 20]


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
    call_log, publisher = handle(fail_on=(BacktestEventType.PROGRESS,), **progress(3))
    assert "ack" not in call_log
    assert call_log == ["publish:STARTED", "publish-failed:PROGRESS", "requeue"]
    assert publisher.of_type(BacktestEventType.FINISHED) == []


# --- 계약 오류 → requeue 없이 reject → DLQ ---------------------------------------------


def _body_without_initial_cash() -> str:
    body = engine_input_body()
    del body["portfolio"]["initialCash"]
    return json.dumps(body)


def _v1_body() -> str:
    """v1 모양(backtestId·strategy·commission)의 작업 메시지는 v2 워커에서 계약 오류다."""
    body = engine_input_body()
    body["run"] = {"backtestId": "OLD", "start": "2023-01-02", "end": "2023-01-10"}
    body["strategy"] = body.pop("allocation")
    return json.dumps(body)


@pytest.mark.parametrize(
    ("body", "headers"),
    [
        pytest.param("{not json", None, id="broken-json"),
        pytest.param("[]", None, id="json-array-not-object"),
        pytest.param(_body_without_initial_cash(), None, id="missing-required-field"),
        pytest.param(_v1_body(), None, id="v1-shape"),
        pytest.param(
            json.dumps(engine_input_body(marketData={"interval": "1H"})),
            None,
            id="unknown-enum-value",
        ),
        pytest.param(json.dumps(engine_input_body()), {"schemaVersion": 1}, id="header-version"),
        pytest.param(
            json.dumps({**engine_input_body(), "schemaVersion": 99}), None, id="body-version"
        ),
        pytest.param(
            json.dumps(engine_input_body()), {"schemaVersion": "2"}, id="header-version-not-int"
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
        pytest.param({"run": {"start": "2030-01-02", "end": "2030-01-10"}}, "DATA_INSUFFICIENT"),
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
    assert finished.payload.status is RunStatus.FAILED
    assert finished.payload.partial is False and finished.payload.last_trading_date is None
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


def test_finished_payload_mirrors_run_block() -> None:
    """payload.status·partial·lastTradingDate는 run 블록의 값 그대로다(백엔드가 일치를 검증)."""
    _, publisher = handle()
    finished = publisher.of_type(BacktestEventType.FINISHED)[0]
    run = finished.payload.engine_output.run
    assert finished.payload.status is run.status is RunStatus.COMPLETED
    assert finished.payload.partial is False and run.partial is False
    assert finished.payload.last_trading_date == run.last_trading_date is not None
    assert finished.payload.resource is not None
    assert finished.payload.resource.wall_seconds is not None
    assert finished.payload.resource.cpu_seconds is not None
    # 결과를 낸 워커의 큐·역할이 FINISHED 에도 실린다(T012) — 중복 실행에서 STARTED 와 다를 수 있다.
    assert finished.payload.resource.queue == "backtest.jobs"
    assert finished.payload.resource.role == "single"


def test_peak_rss_is_the_max_sample_of_this_run(monkeypatch: pytest.MonkeyPatch) -> None:
    """peakRssMb 는 프로세스 누적 최고값이 아니라 이 실행 동안 표본의 최댓값이다(리뷰 P2)."""
    samples = iter([Decimal("120.0"), Decimal("310.5"), Decimal("200.0"), Decimal("150.0")])
    monkeypatch.setattr("worker.consumer.current_rss_mb", lambda: next(samples, Decimal("90.0")))

    _, publisher = handle(**progress(3))

    finished = publisher.of_type(BacktestEventType.FINISHED)[0]
    assert finished.payload.resource is not None
    assert finished.payload.resource.peak_rss_mb == Decimal("310.5")


def test_peak_rss_is_omitted_when_it_cannot_be_measured(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("worker.consumer.current_rss_mb", lambda: None)
    _, publisher = handle()
    finished = publisher.of_type(BacktestEventType.FINISHED)[0]
    assert finished.payload.resource is not None
    assert finished.payload.resource.peak_rss_mb is None


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
    assert {event.run_id for event in first.events + second.events} == {"TEST"}
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
    _, publisher = handle(**progress(3))
    published = {event.type: json.loads(event.to_json()) for event in publisher.events}

    envelope_keys = set(fixture_json("event-started.json"))
    for event_type, body in published.items():
        assert set(body) == envelope_keys, event_type
        assert body["schemaVersion"] == SCHEMA_VERSION
        assert body["runId"] == "TEST"
        assert body["occurredAt"].endswith("Z")

    started = fixture_json("event-started.json")
    assert set(published[BacktestEventType.STARTED]["payload"]) == set(started["payload"])

    progress_fixture = fixture_json("event-progress.json")["payload"]
    progress_payload = published[BacktestEventType.PROGRESS]["payload"]
    assert set(progress_payload) == set(progress_fixture)
    assert set(progress_payload["portfolioSeries"][0]) == set(
        progress_fixture["portfolioSeries"][0]
    )

    finished_fixture = fixture_json("event-finished-completed.json")["payload"]
    assert set(published[BacktestEventType.FINISHED]["payload"]) == set(finished_fixture)


def test_published_events_revalidate_through_the_contract() -> None:
    _, publisher = handle(**progress(3))
    models = {
        BacktestEventType.STARTED: BacktestEvent[StartedPayload],
        BacktestEventType.PROGRESS: BacktestEvent[ProgressPayload],
        BacktestEventType.FINISHED: BacktestEvent[FinishedPayload],
    }
    for event in publisher.events:
        model = models[event.type]
        assert model.model_validate_json(event.to_json()).sequence == event.sequence


def test_real_engine_output_rides_the_finished_event() -> None:
    """기준 본문(두 종목 고정 비중, 7거래일)이 실제 엔진으로 끝까지 돈다."""
    _, publisher = handle()
    output = publisher.of_type(BacktestEventType.FINISHED)[0].payload.engine_output
    assert output.run.status is RunStatus.COMPLETED
    assert output.performance is not None
    assert output.performance.final_equity == output.portfolio_series[-1].equity
    assert output.portfolio_series[0].cash == Decimal("10000000")
    assert len(output.portfolio_series) == EVALUATION_TRADING_DAYS
    assert output.trades and output.weights_series
