"""pika 런타임: 메시지 속성, 발행 확인·재시도, ACK/reject 매핑, 큐 부재 처리.

브로커 없이 검증한다. pika 채널·연결 자리에 fake를 넣어 "무엇을 어떤 속성으로 보내는가"만
확인한다(계약 §3). 실제 브로커 연동은 `worker/README.md` "실제 브로커 연동 확인"을 따른다.
"""

import json
import re
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import pytest
from pika.exceptions import AMQPConnectionError, ChannelClosedByBroker, UnroutableError

from worker import messaging
from worker.contracts.base import utc_now
from worker.contracts.events import (
    BacktestEvent,
    BacktestEventType,
    ProcessingStage,
    StartedPayload,
)
from worker.messaging import (
    BULK_JOBS_QUEUE,
    EVENTS_ROUTING_KEY,
    EXCHANGE,
    FLEX_QUEUE_ORDER,
    JOBS_QUEUE,
    SCHEMA_VERSION,
    PublishError,
)
from worker.rabbitmq import (
    PUBLISH_ATTEMPTS,
    QUEUE_MISSING_MESSAGE,
    PikaEventPublisher,
    PikaJobAcknowledger,
    QueueMissingError,
    fetch_next_job,
    require_jobs_queue,
    run_flex_loop,
)

BACKEND_CONFIG = (
    Path(__file__).resolve().parents[2]
    / "backend"
    / "src"
    / "main"
    / "java"
    / "com"
    / "ssafy"
    / "thorp"
    / "backtest"
    / "infrastructure"
    / "messaging"
    / "BacktestRabbitConfig.java"
)


def started_event(event_id: str = "EVT-1", run_id: str = "BT-1") -> BacktestEvent:
    now = utc_now()
    return BacktestEvent(
        schema_version=SCHEMA_VERSION,
        event_id=event_id,
        run_id=run_id,
        execution_id="EXEC-1",
        type=BacktestEventType.STARTED,
        sequence=0,
        occurred_at=now,
        payload=StartedPayload(started_at=now, stage=ProcessingStage.PREPARING_DATA),
    )


class FakeChannel:
    """basic_publish/ack만 기록하는 pika 채널 대역."""

    def __init__(self, errors: list[Exception | None] | None = None) -> None:
        self.published: list[dict[str, Any]] = []
        self.acked: list[tuple[str, int, bool | None]] = []
        self._errors = list(errors or [])

    def basic_publish(self, **kwargs: Any) -> None:
        self.published.append(kwargs)
        if self._errors:
            error = self._errors.pop(0)
            if error is not None:
                raise error

    def basic_ack(self, delivery_tag: int) -> None:
        self.acked.append(("ack", delivery_tag, None))

    def basic_reject(self, delivery_tag: int, requeue: bool) -> None:
        self.acked.append(("reject", delivery_tag, requeue))

    def basic_nack(self, delivery_tag: int, requeue: bool) -> None:
        self.acked.append(("nack", delivery_tag, requeue))


# --- 계약 상수 -----------------------------------------------------------------------


def test_topology_constants_match_the_backend() -> None:
    """큐·exchange·routing key는 백엔드와 합의한 계약 상수다 (계약 §2)."""
    source = BACKEND_CONFIG.read_text(encoding="utf-8")
    declared = dict(
        re.findall(r'public static final String (\w+) = "([^"]+)";', source),
    )
    assert declared, "backend BacktestRabbitConfig constants not found"
    for name, value in declared.items():
        assert getattr(messaging, name) == value, name


def test_prefetch_is_one() -> None:
    assert messaging.PREFETCH_COUNT == 1


def test_schema_version_matches_the_backend() -> None:
    """``SCHEMA_VERSION`` 은 String 상수가 아니라 위 대조에 안 걸리므로 따로 본다."""
    source = BACKEND_CONFIG.read_text(encoding="utf-8")
    match = re.search(r"public static final int SCHEMA_VERSION = (\d+);", source)
    assert match is not None, "backend SCHEMA_VERSION constant not found"
    assert messaging.SCHEMA_VERSION == int(match.group(1)) == 2


# --- 메시지 속성 (계약 §3) ------------------------------------------------------------


def test_publish_sets_contract_message_properties() -> None:
    channel = FakeChannel()
    PikaEventPublisher(channel).publish(started_event("EVT-9", "BT-42"))

    (call,) = channel.published
    assert call["exchange"] == EXCHANGE and call["routing_key"] == EVENTS_ROUTING_KEY
    assert call["mandatory"] is True  # 라우팅 실패를 조용히 버리지 않는다
    properties = call["properties"]
    assert properties.content_type == "application/json"
    assert properties.delivery_mode == 2
    assert properties.message_id == "EVT-9"  # 본문 eventId
    assert properties.correlation_id == "BT-42"  # runId
    assert properties.headers == {"schemaVersion": SCHEMA_VERSION}
    assert "__TypeId__" not in properties.headers  # 워커는 Spring 헤더를 보내지 않는다


def test_publish_body_is_contract_json() -> None:
    channel = FakeChannel()
    PikaEventPublisher(channel).publish(started_event())
    body = json.loads(channel.published[0]["body"].decode("utf-8"))
    assert body["eventId"] == "EVT-1" and body["type"] == "STARTED"
    assert body["schemaVersion"] == SCHEMA_VERSION
    assert set(body["payload"]) == {"startedAt", "stage"}  # camelCase, 값 없는 키 생략


# --- 발행 확인과 제한적 재시도 --------------------------------------------------------


def test_unroutable_event_fails_immediately() -> None:
    """binding이 없으면 재시도해도 같다. 발행 실패로 올려 ACK를 막는다."""
    channel = FakeChannel([UnroutableError([])])
    with pytest.raises(PublishError, match="unroutable"):
        PikaEventPublisher(channel).publish(started_event())
    assert len(channel.published) == 1  # 재시도하지 않는다


def test_transient_broker_error_is_retried_then_reported() -> None:
    channel = FakeChannel([AMQPConnectionError()] * PUBLISH_ATTEMPTS)
    slept: list[float] = []
    publisher = PikaEventPublisher(channel, retry_seconds=0.0, sleep=slept.append)
    with pytest.raises(PublishError, match="not confirmed"):
        publisher.publish(started_event())
    assert len(channel.published) == PUBLISH_ATTEMPTS
    assert len(slept) == PUBLISH_ATTEMPTS - 1


def test_retry_keeps_the_same_event_id() -> None:
    """재발행에도 eventId는 유지된다 (중복 방지 키 — 계약 §4.2)."""
    channel = FakeChannel([AMQPConnectionError(), None])
    publisher = PikaEventPublisher(channel, retry_seconds=0.0, sleep=lambda _: None)
    publisher.publish(started_event("EVT-KEEP"))
    assert [call["properties"].message_id for call in channel.published] == ["EVT-KEEP"] * 2


# --- ACK / reject 매핑 ---------------------------------------------------------------


def test_acknowledger_maps_to_pika_calls() -> None:
    channel = FakeChannel()
    PikaJobAcknowledger(channel, 7).ack()
    PikaJobAcknowledger(channel, 8).reject()
    PikaJobAcknowledger(channel, 9).requeue()
    assert channel.acked == [
        ("ack", 7, None),
        ("reject", 8, False),  # requeue 없이 거부 → DLQ (계약 §7)
        ("nack", 9, True),  # 일시 장애: 큐로 되돌린다
    ]


# --- 큐가 없을 때 (토폴로지는 백엔드 책임) --------------------------------------------


class FakeProbeConnection:
    def __init__(self, error: Exception | None) -> None:
        self._error = error
        self.closed = False
        self.probed: list[str] = []

    def channel(self) -> Any:
        connection = self

        class Probe:
            def queue_declare(self, queue: str, passive: bool) -> None:
                assert passive is True
                connection.probed.append(queue)
                if connection._error is not None:
                    raise connection._error

            def close(self) -> None:
                connection.closed = True

        return Probe()


def test_missing_jobs_queue_is_reported_not_created() -> None:
    connection = FakeProbeConnection(ChannelClosedByBroker(404, "NOT_FOUND - no queue"))
    with pytest.raises(QueueMissingError) as caught:
        require_jobs_queue(connection)
    message = str(caught.value)
    assert JOBS_QUEUE in message
    assert "backend" in message and "must not create" in message


def test_other_channel_errors_are_not_disguised_as_missing_queue() -> None:
    connection = FakeProbeConnection(ChannelClosedByBroker(403, "ACCESS_REFUSED"))
    with pytest.raises(ChannelClosedByBroker):
        require_jobs_queue(connection)


def test_existing_jobs_queue_passes_without_declaring() -> None:
    connection = FakeProbeConnection(None)
    require_jobs_queue(connection)
    assert connection.closed is True
    assert connection.probed == [JOBS_QUEUE]


def test_flex_worker_requires_both_queues() -> None:
    """공용 워커는 벌크·단건 큐 둘 다 있어야 시작한다. 없는 큐 이름이 오류에 실린다."""
    connection = FakeProbeConnection(None)
    require_jobs_queue(connection, FLEX_QUEUE_ORDER)
    assert connection.probed == [BULK_JOBS_QUEUE, JOBS_QUEUE]

    missing = FakeProbeConnection(ChannelClosedByBroker(404, "NOT_FOUND - no queue"))
    with pytest.raises(QueueMissingError) as caught:
        require_jobs_queue(missing, FLEX_QUEUE_ORDER)
    assert BULK_JOBS_QUEUE in str(caught.value)


# --- 공용(flex) 워커 소비 루프 (R-20) -------------------------------------------------


class FakeGetChannel:
    """``basic_get`` 만 있는 채널 대역. 큐별로 미리 넣어 둔 메시지를 순서대로 내준다."""

    def __init__(self, queues: dict[str, list[bytes]]) -> None:
        self._queues = {name: list(bodies) for name, bodies in queues.items()}
        self.gets: list[str] = []
        self.acked: list[int] = []
        self._tag = 0

    def basic_get(self, queue: str, auto_ack: bool) -> tuple[Any, Any, Any]:
        assert auto_ack is False  # manual ACK — 처리·발행 확인 뒤에만 ACK 한다(계약 §7)
        self.gets.append(queue)
        pending = self._queues.get(queue, [])
        if not pending:
            return None, None, None
        self._tag += 1
        method = SimpleNamespace(delivery_tag=self._tag)
        properties = SimpleNamespace(headers={"schemaVersion": SCHEMA_VERSION})
        return method, properties, pending.pop(0)

    def basic_ack(self, delivery_tag: int) -> None:
        self.acked.append(delivery_tag)


class RecordingConsumer:
    """받은 본문을 기록하고 바로 ACK 한다. 실행 사이의 unacked 수를 잡아내기 위한 대역."""

    def __init__(self, channel: FakeGetChannel) -> None:
        self._channel = channel
        self.handled: list[tuple[bytes, int]] = []

    def handle(self, body: bytes, headers: Any, acknowledger: Any) -> None:
        # 처리 시점에 채널이 내준 메시지 수와 ACK 수의 차가 1 이어야 한다 — 미리 받아 둔 것이 없다.
        outstanding = self._channel._tag - len(self._channel.acked)
        self.handled.append((body, outstanding))
        acknowledger.ack()


def _run_flex(channel: FakeGetChannel, rounds: int) -> tuple[RecordingConsumer, list[float]]:
    consumer = RecordingConsumer(channel)
    slept: list[float] = []
    remaining = [rounds]

    def keep_going() -> bool:
        remaining[0] -= 1
        return remaining[0] >= 0

    run_flex_loop(channel, consumer, keep_going=keep_going, sleep=slept.append, poll_seconds=0.5)  # type: ignore[arg-type]
    return consumer, slept


def test_flex_takes_bulk_first_then_single() -> None:
    channel = FakeGetChannel({BULK_JOBS_QUEUE: [b"bulk-1", b"bulk-2"], JOBS_QUEUE: [b"single-1"]})

    consumer, slept = _run_flex(channel, rounds=3)

    assert [body for body, _ in consumer.handled] == [b"bulk-1", b"bulk-2", b"single-1"]
    # 벌크가 남아 있는 동안은 단건 큐를 보지 않는다. 벌크가 비어서야 단건을 집는다.
    assert channel.gets == [BULK_JOBS_QUEUE, BULK_JOBS_QUEUE, BULK_JOBS_QUEUE, JOBS_QUEUE]
    assert slept == []


def test_flex_holds_at_most_one_message_at_a_time() -> None:
    """두 큐에서 미리 받아 두지 않는다 — 처리 중인 메시지 말고는 unacked 가 없다(R-20)."""
    channel = FakeGetChannel({BULK_JOBS_QUEUE: [b"b"], JOBS_QUEUE: [b"s1", b"s2"]})

    consumer, _ = _run_flex(channel, rounds=3)

    assert [outstanding for _, outstanding in consumer.handled] == [1, 1, 1]
    assert channel.acked == [1, 2, 3]


def test_flex_waits_when_both_queues_are_empty() -> None:
    channel = FakeGetChannel({BULK_JOBS_QUEUE: [], JOBS_QUEUE: []})

    consumer, slept = _run_flex(channel, rounds=2)

    assert consumer.handled == []
    # 매 회 두 큐를 순서대로 확인한 뒤 폴링 간격만큼 기다린다 — 빈 큐를 쉼 없이 두드리지 않는다.
    assert channel.gets == [BULK_JOBS_QUEUE, JOBS_QUEUE] * 2
    assert slept == [0.5, 0.5]


def test_fetch_next_job_returns_queue_name() -> None:
    channel = FakeGetChannel({BULK_JOBS_QUEUE: [], JOBS_QUEUE: [b"s"]})
    fetched = fetch_next_job(channel)  # type: ignore[arg-type]
    assert fetched is not None
    assert fetched[0] == JOBS_QUEUE and fetched[3] == b"s"
    assert fetch_next_job(channel) is None  # type: ignore[arg-type]


def test_queue_missing_message_points_at_the_backend() -> None:
    assert "Declaring the backtest topology" in QUEUE_MISSING_MESSAGE
    assert "Start the backend once" in QUEUE_MISSING_MESSAGE
