"""사례 연결 소비자: 발행 순서·ACK 순서·오류 세 갈래·``eventId`` 멱등성(spec 011 T019).

브로커 없이 전체 흐름을 검증한다. 발행·ACK 는 ``regime_link.messaging`` 의 Protocol 뒤에 있으므로
fake 로 갈아끼우고, **발행과 ACK 를 같은 로그 리스트에 기록해 순서를 직접 단언한다** —
"FINISHED 발행이 확인된 뒤에만 ACK" 는 순서 규칙이라 호출 여부만으로는 지켜졌는지 알 수 없다.

계산은 진짜 ``slicer`` 와 T014 픽스처(``fixtures/*.job.json`` + ``*.closes.json``)로 돌린다. 시장
자료만 가짜 적재기로 넣는다 — DB 는 소비자의 관심사가 아니다.
"""

import json
from copy import deepcopy
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from worker.regime_link.consumer import RegimeLinkJobConsumer, event_id_for
from worker.regime_link.contracts import (
    RegimeLinkErrorCode,
    RegimeLinkEvent,
    RegimeLinkEventType,
    RegimeLinkJob,
    RegimeLinkStatus,
)
from worker.regime_link.messaging import SCHEMA_VERSION, RegimeLinkPublishError
from worker.regime_link.slicer import MappingClosePrices

FIXTURE_DIR = Path(__file__).resolve().parent / "fixtures"

HEADERS: dict[str, Any] = {"schemaVersion": SCHEMA_VERSION}


def job_body(name: str) -> dict[str, Any]:
    return json.loads((FIXTURE_DIR / f"{name}.job.json").read_text(encoding="utf-8"))


def load_prices(name: str) -> MappingClosePrices:
    raw = json.loads((FIXTURE_DIR / f"{name}.closes.json").read_text(encoding="utf-8"))
    return MappingClosePrices(
        {
            iid: {date.fromisoformat(day): Decimal(price) for day, price in series.items()}
            for iid, series in raw.items()
        }
    )


class FakePublisher:
    """``publish`` 가 정상 반환하면 "브로커가 확인했다"는 뜻. 실패는 PublishError."""

    def __init__(self, call_log: list[str], fail_on: tuple[RegimeLinkEventType, ...] = ()) -> None:
        self.call_log = call_log
        self.fail_on = set(fail_on)
        self.events: list[RegimeLinkEvent[Any]] = []

    def publish(self, event: RegimeLinkEvent[Any]) -> None:
        if event.type in self.fail_on:
            self.call_log.append(f"publish-failed:{event.type.value}")
            raise RegimeLinkPublishError(f"fake broker refused {event.type.value}")
        self.events.append(event)
        self.call_log.append(f"publish:{event.type.value}")

    def of_type(self, event_type: RegimeLinkEventType) -> list[RegimeLinkEvent[Any]]:
        return [event for event in self.events if event.type is event_type]

    def finished(self) -> RegimeLinkEvent[Any]:
        events = self.of_type(RegimeLinkEventType.FINISHED)
        assert len(events) == 1
        return events[0]


class FakeAcknowledger:
    def __init__(self, call_log: list[str]) -> None:
        self.call_log = call_log

    def ack(self) -> None:
        self.call_log.append("ack")

    def reject(self) -> None:
        self.call_log.append("reject")

    def requeue(self) -> None:
        self.call_log.append("requeue")


class FixturePrices:
    """픽스처 종가를 그대로 돌려주는 적재기. 부른 작업을 기록한다."""

    def __init__(self, prices: MappingClosePrices) -> None:
        self.prices = prices
        self.jobs: list[RegimeLinkJob] = []

    def load(self, job: RegimeLinkJob) -> MappingClosePrices:
        self.jobs.append(job)
        return self.prices


class BrokenPrices:
    """DB 접속 실패 같은 일시 장애. 결과로 바꾸지 않고 그대로 올린다."""

    def load(self, job: RegimeLinkJob) -> MappingClosePrices:
        raise ConnectionError("market data DB is unreachable")


def run(
    name: str = "continuous_63",
    *,
    body: Any = None,
    headers: Any = HEADERS,
    prices: Any = None,
    fail_on: tuple[RegimeLinkEventType, ...] = (),
) -> tuple[list[str], FakePublisher, Any]:
    call_log: list[str] = []
    publisher = FakePublisher(call_log, fail_on=fail_on)
    loader = FixturePrices(load_prices(name)) if prices is None else prices
    consumer = RegimeLinkJobConsumer(loader, publisher)
    raw = json.dumps(job_body(name)) if body is None else body
    consumer.handle(raw, headers, FakeAcknowledger(call_log))
    return call_log, publisher, loader


# --- 정상 흐름 -----------------------------------------------------------------------


def test_publishes_started_then_finished_then_acks() -> None:
    call_log, publisher, _ = run()

    assert call_log == ["publish:STARTED", "publish:FINISHED", "ack"]
    finished = publisher.finished()
    assert finished.payload.status is RegimeLinkStatus.COMPLETED
    assert finished.payload.result is not None
    assert finished.payload.error is None
    assert finished.schema_version == SCHEMA_VERSION


def test_finished_carries_partial_and_delisted_on_from_the_slicer() -> None:
    _, publisher, _ = run("partial_delisted")

    payload = publisher.finished().payload
    assert payload.partial is True
    assert payload.delisted_on is not None
    assert payload.result is not None
    assert payload.result.window63.complete is False


def test_started_event_names_the_link_and_the_process() -> None:
    _, publisher, _ = run()

    started = publisher.of_type(RegimeLinkEventType.STARTED)[0]
    assert started.link_id == "RL-T014-continuous_63"
    assert started.attempt == 1
    assert started.payload.resource is not None
    assert started.payload.resource.pid is not None


# --- ① 계약 오류 → reject (DLQ) ------------------------------------------------------


def test_broken_json_is_rejected_without_publishing() -> None:
    call_log, publisher, _ = run(body="{ this is not json")

    assert call_log == ["reject"]
    assert publisher.events == []


def test_validation_error_is_rejected() -> None:
    broken = job_body("continuous_63")
    del broken["window"]["d21"]

    call_log, publisher, _ = run(body=json.dumps(broken))

    assert call_log == ["reject"]
    assert publisher.events == []


def test_unknown_field_is_rejected() -> None:
    """계약에 없는 키는 양쪽이 어긋났다는 신호라 조용히 무시하지 않는다."""
    broken = job_body("continuous_63")
    broken["window"]["d99"] = "2012-06-27"

    call_log, _, _ = run(body=json.dumps(broken))

    assert call_log == ["reject"]


@pytest.mark.parametrize("headers", [None, {}, {"schemaVersion": 2}, {"schemaVersion": "1"}])
def test_missing_or_unsupported_schema_version_is_rejected(headers: Any) -> None:
    call_log, publisher, _ = run(headers=headers)

    assert call_log == ["reject"]
    assert publisher.events == []


# --- ② 계산 실패 → FINISHED(FAILED) 후 ACK -------------------------------------------


def test_incomplete_input_finishes_failed_and_acks() -> None:
    broken = job_body("continuous_63")
    # baselineDate 행을 빼면 연속 운용의 기준 평가액이 없다.
    broken["inputs"]["portfolioSeries"] = broken["inputs"]["portfolioSeries"][1:]

    call_log, publisher, _ = run(body=json.dumps(broken))

    assert call_log == ["publish:STARTED", "publish:FINISHED", "ack"]
    payload = publisher.finished().payload
    assert payload.status is RegimeLinkStatus.FAILED
    assert payload.error is not None
    assert payload.error.code is RegimeLinkErrorCode.INPUT_INCOMPLETE
    assert payload.result is None
    assert payload.partial is False


def test_missing_market_data_finishes_failed_and_acks() -> None:
    empty = MappingClosePrices({})

    call_log, publisher, _ = run(prices=FixturePrices(empty))

    assert call_log == ["publish:STARTED", "publish:FINISHED", "ack"]
    payload = publisher.finished().payload
    assert payload.error is not None
    assert payload.error.code is RegimeLinkErrorCode.MARKET_DATA_MISSING


def test_unexpected_slice_failure_becomes_engine_error(monkeypatch: pytest.MonkeyPatch) -> None:
    """자료가 메모리에 오른 뒤의 예외는 결정적이다 — 재전달시키지 않고 결과로 답한다."""

    def boom(job: Any, prices: Any) -> Any:
        raise ZeroDivisionError("slicer bug")

    monkeypatch.setattr("worker.regime_link.consumer.slice_window", boom)

    call_log, publisher, _ = run()

    assert call_log == ["publish:STARTED", "publish:FINISHED", "ack"]
    payload = publisher.finished().payload
    assert payload.error is not None
    assert payload.error.code is RegimeLinkErrorCode.ENGINE_ERROR
    assert "ZeroDivisionError" in payload.error.message


def test_failed_event_reports_wall_seconds() -> None:
    broken = job_body("continuous_63")
    broken["inputs"]["portfolioSeries"] = broken["inputs"]["portfolioSeries"][1:]

    _, publisher, _ = run(body=json.dumps(broken))

    resource = publisher.finished().payload.resource
    assert resource is not None
    assert resource.wall_seconds >= 0


# --- ③ 발행 실패·일시 장애 → ACK 없음 -------------------------------------------------


def test_finished_publish_failure_does_not_ack() -> None:
    call_log, publisher, _ = run(fail_on=(RegimeLinkEventType.FINISHED,))

    assert call_log == ["publish:STARTED", "publish-failed:FINISHED", "requeue"]
    assert "ack" not in call_log


def test_started_publish_failure_does_not_compute_or_ack() -> None:
    call_log, publisher, loader = run(fail_on=(RegimeLinkEventType.STARTED,))

    assert call_log == ["publish-failed:STARTED", "requeue"]
    assert loader.jobs == []  # 발행되지 않은 실행을 계산하지 않는다
    assert publisher.events == []


def test_market_data_outage_is_not_turned_into_a_result() -> None:
    call_log, publisher, _ = run(prices=BrokenPrices())

    assert call_log == ["publish:STARTED", "requeue"]
    assert publisher.of_type(RegimeLinkEventType.FINISHED) == []


def test_no_path_acks_before_the_finished_event_is_confirmed() -> None:
    """ACK 가 있는 모든 경로에서 바로 앞이 FINISHED 발행 확인이다."""
    cases = [
        run(),
        run("fresh_cash"),
        run("partial_delisted"),
        run(prices=FixturePrices(MappingClosePrices({}))),
        run(fail_on=(RegimeLinkEventType.FINISHED,)),
        run(body="{ this is not json"),
    ]
    for call_log, _, _ in cases:
        if "ack" in call_log:
            assert call_log[call_log.index("ack") - 1] == "publish:FINISHED"


# --- 멱등성 -------------------------------------------------------------------------


def test_redelivery_of_the_same_link_and_attempt_keeps_event_ids() -> None:
    _, first, _ = run()
    _, second, _ = run()

    first_ids = [event.event_id for event in first.events]
    assert first_ids == [event.event_id for event in second.events]


def test_event_ids_differ_by_type_attempt_and_link() -> None:
    base = event_id_for("RL-1", 1, RegimeLinkEventType.STARTED)
    assert base != event_id_for("RL-1", 1, RegimeLinkEventType.FINISHED)
    assert base != event_id_for("RL-1", 2, RegimeLinkEventType.STARTED)
    assert base != event_id_for("RL-2", 1, RegimeLinkEventType.STARTED)


def test_a_second_attempt_gets_new_event_ids() -> None:
    retried = deepcopy(job_body("continuous_63"))
    retried["job"]["attempt"] = 2

    _, first, _ = run()
    _, second, _ = run(body=json.dumps(retried))

    assert {event.event_id for event in first.events}.isdisjoint(
        event.event_id for event in second.events
    )
    assert all(event.attempt == 2 for event in second.events)
