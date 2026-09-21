"""사례 연결 엔트리포인트와 pika 층(spec 011 T020).

브로커도 DB 도 없이 확인한다. ``pika`` 객체는 가짜로 갈아끼우고 **워커가 무엇을 하지 않는지**를
함께 단언한다 — 토폴로지를 선언하지 않는다는 규칙은 "선언 호출이 없다"로만 증명된다.
"""

from datetime import date
from decimal import Decimal
from typing import Any

import pytest
from pika.exceptions import AMQPConnectionError, ChannelClosedByBroker, UnroutableError

from worker import regime_link_main
from worker.regime_link import rabbitmq
from worker.regime_link.contracts import (
    ProcessInfo,
    RegimeLinkEvent,
    RegimeLinkEventType,
    RegimeLinkJob,
    RegimeLinkStartedPayload,
)
from worker.regime_link.market_data import PostgresClosePriceLoader
from worker.regime_link.messaging import EVENTS_ROUTING_KEY, EXCHANGE, JOBS_QUEUE
from worker.regime_link.rabbitmq import (
    PikaEventPublisher,
    PikaJobAcknowledger,
    QueueMissingError,
    require_jobs_queue,
    run_consumer,
)

from .test_consumer import job_body  # 같은 T014 픽스처를 읽는다

# --- 엔트리포인트 --------------------------------------------------------------------


def test_main_refuses_to_start_without_market_data_url(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(regime_link_main.settings, "market_data_url", None)
    monkeypatch.setattr(
        regime_link_main, "run_consumer", lambda *a, **k: pytest.fail("must not consume")
    )

    assert regime_link_main.main() == 1


def test_main_refuses_to_start_without_rabbitmq_url(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(regime_link_main.settings, "market_data_url", "postgresql://x/y")
    monkeypatch.setattr(regime_link_main.settings, "rabbitmq_url", "")
    monkeypatch.setattr(
        regime_link_main, "run_consumer", lambda *a, **k: pytest.fail("must not consume")
    )

    assert regime_link_main.main() == 1


def test_main_exits_with_1_when_the_queue_is_missing(monkeypatch: pytest.MonkeyPatch) -> None:
    def missing(*args: Any, **kwargs: Any) -> None:
        raise QueueMissingError(rabbitmq.queue_missing_message(JOBS_QUEUE))

    monkeypatch.setattr(regime_link_main.settings, "market_data_url", "postgresql://x/y")
    monkeypatch.setattr(regime_link_main.settings, "rabbitmq_url", "amqp://x/")
    monkeypatch.setattr(regime_link_main, "run_consumer", missing)

    assert regime_link_main.main() == 1


def test_main_exits_with_1_when_the_broker_is_unreachable(monkeypatch: pytest.MonkeyPatch) -> None:
    def down(*args: Any, **kwargs: Any) -> None:
        raise AMQPConnectionError("no broker")

    monkeypatch.setattr(regime_link_main.settings, "market_data_url", "postgresql://x/y")
    monkeypatch.setattr(regime_link_main.settings, "rabbitmq_url", "amqp://x/")
    monkeypatch.setattr(regime_link_main, "run_consumer", down)

    assert regime_link_main.main() == 1


# --- 큐 확인: 선언하지 않는다 ---------------------------------------------------------


class FakeChannel:
    """passive declare 로 404 를 내는 채널. 모든 호출을 기록한다."""

    def __init__(self, exists: bool) -> None:
        self.exists = exists
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def queue_declare(self, **kwargs: Any) -> Any:
        self.calls.append(("queue_declare", kwargs))
        if not self.exists:
            raise ChannelClosedByBroker(404, f"NOT_FOUND - no queue '{kwargs.get('queue')}'")
        return object()

    def exchange_declare(self, **kwargs: Any) -> None:
        self.calls.append(("exchange_declare", kwargs))

    def queue_bind(self, **kwargs: Any) -> None:
        self.calls.append(("queue_bind", kwargs))

    def close(self) -> None:
        self.calls.append(("close", {}))

    def confirm_delivery(self) -> None:
        self.calls.append(("confirm_delivery", {}))

    def basic_qos(self, **kwargs: Any) -> None:
        self.calls.append(("basic_qos", kwargs))

    def consume(self, queue: str, **kwargs: Any) -> Any:
        self.calls.append(("consume", {"queue": queue, **kwargs}))
        return iter(())


class FakeConnection:
    def __init__(self, exists: bool = True) -> None:
        self.channels: list[FakeChannel] = []
        self.exists = exists
        self.is_open = True
        self.closed = False

    def channel(self) -> FakeChannel:
        channel = FakeChannel(self.exists)
        self.channels.append(channel)
        return channel

    def close(self) -> None:
        self.closed = True
        self.is_open = False


def test_missing_queue_is_reported_and_not_created() -> None:
    connection = FakeConnection(exists=False)

    with pytest.raises(QueueMissingError) as caught:
        require_jobs_queue(connection)  # type: ignore[arg-type]

    assert JOBS_QUEUE in str(caught.value)
    calls = [name for channel in connection.channels for name, _ in channel.calls]
    assert calls == ["queue_declare"]
    assert connection.channels[0].calls[0][1]["passive"] is True


def test_run_consumer_stops_before_consuming_when_the_queue_is_missing() -> None:
    connection = FakeConnection(exists=False)

    with pytest.raises(QueueMissingError):
        run_consumer("amqp://x/", object(), connect=lambda url: connection)  # type: ignore[arg-type]

    names = [name for channel in connection.channels for name, _ in channel.calls]
    assert "consume" not in names
    assert "queue_bind" not in names
    assert "exchange_declare" not in names
    assert connection.closed is True


def test_run_consumer_sets_confirm_and_prefetch_before_consuming() -> None:
    connection = FakeConnection(exists=True)

    run_consumer("amqp://x/", object(), connect=lambda url: connection)  # type: ignore[arg-type]

    work = connection.channels[-1]
    names = [name for name, _ in work.calls]
    assert names == ["confirm_delivery", "basic_qos", "consume"]
    assert work.calls[1][1]["prefetch_count"] == 1
    assert work.calls[2][1]["queue"] == JOBS_QUEUE
    assert work.calls[2][1]["auto_ack"] is False


# --- 발행·ACK 매핑 -------------------------------------------------------------------


def started_event() -> RegimeLinkEvent[RegimeLinkStartedPayload]:
    return RegimeLinkEvent(
        schema_version=1,
        event_id="EVT-1",
        link_id="RL-1",
        attempt=1,
        type=RegimeLinkEventType.STARTED,
        occurred_at="2026-09-18T02:00:00Z",
        payload=RegimeLinkStartedPayload(
            started_at="2026-09-18T02:00:00Z", resource=ProcessInfo(pid=1)
        ),
    )


class PublishChannel:
    def __init__(self, failures: int = 0, unroutable: bool = False) -> None:
        self.failures = failures
        self.unroutable = unroutable
        self.published: list[dict[str, Any]] = []

    def basic_publish(self, **kwargs: Any) -> None:
        if self.unroutable:
            raise UnroutableError([])
        if self.failures:
            self.failures -= 1
            raise AMQPConnectionError("flaky broker")
        self.published.append(kwargs)


def test_publish_uses_the_contract_exchange_routing_key_and_properties() -> None:
    channel = PublishChannel()

    PikaEventPublisher(channel).publish(started_event())  # type: ignore[arg-type]

    sent = channel.published[0]
    assert sent["exchange"] == EXCHANGE
    assert sent["routing_key"] == EVENTS_ROUTING_KEY
    assert sent["mandatory"] is True
    properties = sent["properties"]
    assert properties.message_id == "EVT-1"
    assert properties.correlation_id == "RL-1"
    assert properties.headers == {"schemaVersion": 1}
    assert properties.delivery_mode == 2


def test_publish_retries_a_flaky_broker_then_succeeds() -> None:
    channel = PublishChannel(failures=2)
    slept: list[float] = []

    PikaEventPublisher(channel, sleep=slept.append).publish(started_event())  # type: ignore[arg-type]

    assert len(channel.published) == 1
    assert len(slept) == 2


def test_publish_gives_up_after_the_attempt_limit() -> None:
    channel = PublishChannel(failures=99)

    with pytest.raises(rabbitmq.RegimeLinkPublishError):
        PikaEventPublisher(channel, attempts=3, sleep=lambda s: None).publish(  # type: ignore[arg-type]
            started_event()
        )


def test_unroutable_event_fails_immediately() -> None:
    channel = PublishChannel(unroutable=True)
    slept: list[float] = []

    with pytest.raises(rabbitmq.RegimeLinkPublishError):
        PikaEventPublisher(channel, sleep=slept.append).publish(started_event())  # type: ignore[arg-type]

    assert slept == []  # binding 이 없으면 재시도해도 같다


class AckChannel:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, Any]]] = []

    def basic_ack(self, **kwargs: Any) -> None:
        self.calls.append(("ack", kwargs))

    def basic_reject(self, **kwargs: Any) -> None:
        self.calls.append(("reject", kwargs))

    def basic_nack(self, **kwargs: Any) -> None:
        self.calls.append(("nack", kwargs))


def test_reject_does_not_requeue_but_nack_does() -> None:
    channel = AckChannel()
    acknowledger = PikaJobAcknowledger(channel, 7)  # type: ignore[arg-type]

    acknowledger.ack()
    acknowledger.reject()
    acknowledger.requeue()

    assert channel.calls == [
        ("ack", {"delivery_tag": 7}),
        ("reject", {"delivery_tag": 7, "requeue": False}),
        ("nack", {"delivery_tag": 7, "requeue": True}),
    ]


# --- 시장 자료 적재 ------------------------------------------------------------------


class FakeCursor:
    def __init__(self, rows: dict[str, list[tuple[Any, ...]]]) -> None:
        self.rows = rows
        self.queries: list[tuple[str, tuple[Any, ...]]] = []
        self._result: list[tuple[Any, ...]] = []

    def execute(self, sql: str, params: tuple[Any, ...]) -> None:
        self.queries.append((sql, params))
        self._result = self.rows["daily_index" if "daily_index" in sql else "daily_price"]

    def fetchall(self) -> list[tuple[Any, ...]]:
        return self._result

    def __enter__(self) -> "FakeCursor":
        return self

    def __exit__(self, *args: Any) -> None:
        return None


class FakeDbConnection:
    def __init__(self, cursor: FakeCursor) -> None:
        self._cursor = cursor

    def cursor(self) -> FakeCursor:
        return self._cursor

    def __enter__(self) -> "FakeDbConnection":
        return self

    def __exit__(self, *args: Any) -> None:
        return None


def test_loader_reads_raw_closes_for_the_universe_and_the_benchmark() -> None:
    job = RegimeLinkJob.model_validate(job_body("continuous_63"))
    cursor = FakeCursor(
        {
            "daily_price": [("091160.XKRX", date(2012, 4, 2), Decimal("17500"))],
            "daily_index": [(date(2012, 4, 2), Decimal("2020.50"))],
        }
    )
    loader = PostgresClosePriceLoader(
        "postgresql://x/y", connect=lambda url: FakeDbConnection(cursor)
    )

    prices = loader.load(job)

    assert prices.close("091160.XKRX", date(2012, 4, 2)) == Decimal("17500")
    assert prices.close("KOSPI", date(2012, 4, 2)) == Decimal("2020.50")
    # 없는 날은 채우지 않는다 — 판정은 slicer 가 MARKET_DATA_MISSING 으로 한다.
    assert prices.close("091160.XKRX", date(2012, 4, 3)) is None
    price_sql, price_params = cursor.queries[0]
    assert "daily_price" in price_sql and "close" in price_sql
    assert "adjusted_close" not in price_sql  # 평가액은 실제 가격으로 낸다
    assert price_params[0] == job.market_data.dataset_id
    assert price_params[2:] == (job.window.baseline_date, job.window.last_available)
    index_sql, index_params = cursor.queries[1]
    assert "daily_index" in index_sql
    assert index_params[1] == "KOSPI"
