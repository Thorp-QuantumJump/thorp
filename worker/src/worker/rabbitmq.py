"""pika 런타임: ``worker.messaging`` Protocol의 RabbitMQ 구현과 소비 루프.

pika는 이 모듈에만 있다. 실행 Consumer 로직(``worker.consumer``)은 Protocol만 알기 때문에
브로커 없이 테스트된다.

**토폴로지를 선언하지 않는다.** exchange·queue·binding 선언은 백엔드 책임이므로(계약 §2)
여기서는 소비할 큐가 실제로 있는지 passive declare로 확인만 하고, 없으면
:class:`QueueMissingError` 로 명확히 알린다. 속성이 어긋난 큐를 워커가 만들면 백엔드 선언과
충돌해 훨씬 찾기 어려운 문제가 된다.

**소비 루프는 역할에 따라 둘이다(R-20).** ``single`` 은 ``backtest.jobs`` 를 ``basic_consume``
(push, prefetch 1)로 받는다. ``flex`` 는 ``backtest.jobs.bulk`` → ``backtest.jobs`` 순서로
``basic_get`` (pull) 한 건씩 꺼낸다 — quorum 큐는 채널 전체 prefetch(global QoS)를 지원하지
않아 한 채널에서 두 큐를 구독하며 "합쳐서 1건"을 표현할 수 없고, 컨슈머별 prefetch 1 로 두 큐를
구독하면 처리하지도 않은 두 번째 메시지가 unacked 로 잡혀 크래시 때 유일한 재시도 기회
(``x-delivery-limit=1``)를 실행 없이 태운다.
"""

import logging
import threading
import time
from collections.abc import Callable, Sequence
from typing import Any

import pika
from pika.adapters.blocking_connection import BlockingChannel, BlockingConnection
from pika.exceptions import AMQPError, ChannelClosedByBroker, NackError, UnroutableError

from worker.consumer import BacktestJobConsumer
from worker.contracts.engine import BacktestEngine
from worker.contracts.events import BacktestEvent
from worker.control import CancelRegistry, handle_control_body
from worker.messaging import (
    CONTROL_EXCHANGE,
    EVENTS_ROUTING_KEY,
    EXCHANGE,
    FLEX_POLL_SECONDS,
    FLEX_QUEUE_ORDER,
    JOBS_QUEUE,
    PREFETCH_COUNT,
    SCHEMA_VERSION,
    PublishError,
)

logger = logging.getLogger(__name__)

#: 발행 재시도(계약 §7 "일시 장애는 제한적 재시도"). 소진하면 PublishError → ACK하지 않는다.
PUBLISH_ATTEMPTS = 3
PUBLISH_RETRY_SECONDS = 1.0


def queue_missing_message(queue: str) -> str:
    return (
        f"queue '{queue}' does not exist on the broker. Declaring the backtest topology "
        "(exchanges, queues, bindings) is the backend's job (Spring Declarables; see "
        "docs/specs/backtest-messaging/README.md section 2) and the worker must not create it: a "
        "queue with mismatched arguments would collide with the backend declaration. Start the "
        "backend once against this broker so the topology is declared, then start the worker "
        "again."
    )


QUEUE_MISSING_MESSAGE = queue_missing_message(JOBS_QUEUE)


class QueueMissingError(RuntimeError):
    """작업 큐가 없다. 백엔드를 한 번 띄워 토폴로지를 선언해야 한다."""


class PikaEventPublisher:
    """``backtest.events`` 발행. publisher confirm + ``mandatory`` 로 확인 후 반환한다.

    채널은 ``confirm_delivery()`` 가 켜진 상태여야 한다. 그러면 ``basic_publish`` 는 브로커
    ack를 받은 뒤 반환하고, 라우팅되지 않으면 ``UnroutableError``, nack이면 ``NackError`` 다.
    """

    def __init__(
        self,
        channel: BlockingChannel,
        *,
        attempts: int = PUBLISH_ATTEMPTS,
        retry_seconds: float = PUBLISH_RETRY_SECONDS,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._channel = channel
        self._attempts = attempts
        self._retry_seconds = retry_seconds
        self._sleep = sleep

    def publish(self, event: BacktestEvent) -> None:
        # 직렬화는 계약 규약 그대로(camelCase + 값 없는 키 생략). json.dumps로 만들지 않는다.
        body = event.to_json().encode("utf-8")
        properties = pika.BasicProperties(
            content_type="application/json",
            delivery_mode=2,  # persistent (계약 §3)
            message_id=event.event_id,  # 본문 eventId와 같은 값
            correlation_id=event.run_id,
            headers={"schemaVersion": SCHEMA_VERSION},
            # Spring의 __TypeId__ 헤더는 보내지 않는다 (계약 §3).
        )
        last_error: Exception | None = None
        for attempt in range(1, self._attempts + 1):
            try:
                self._channel.basic_publish(
                    exchange=EXCHANGE,
                    routing_key=EVENTS_ROUTING_KEY,
                    body=body,
                    properties=properties,
                    mandatory=True,  # 라우팅 실패를 조용히 버리지 않는다
                )
                return
            except UnroutableError as exc:
                # binding이 없다는 뜻이라 재시도해도 같다. 즉시 실패로 올린다.
                raise PublishError(
                    f"event {event.type} (eventId={event.event_id}) was returned as unroutable "
                    f"from exchange '{EXCHANGE}' with routing key '{EVENTS_ROUTING_KEY}'"
                ) from exc
            except (NackError, AMQPError) as exc:
                last_error = exc
                logger.warning(
                    "publish attempt %d/%d failed for event %s (eventId=%s): %s",
                    attempt,
                    self._attempts,
                    event.type,
                    event.event_id,
                    exc,
                )
                if attempt < self._attempts:
                    self._sleep(self._retry_seconds)
        raise PublishError(
            f"event {event.type} (eventId={event.event_id}) was not confirmed after "
            f"{self._attempts} attempts: {last_error}"
        ) from last_error


class PikaJobAcknowledger:
    """작업 메시지 한 건의 ACK/reject. delivery tag를 들고 있다."""

    def __init__(self, channel: BlockingChannel, delivery_tag: int) -> None:
        self._channel = channel
        self._delivery_tag = delivery_tag

    def ack(self) -> None:
        self._channel.basic_ack(delivery_tag=self._delivery_tag)

    def reject(self) -> None:
        # requeue=False → 큐의 DLX(backtest.dlx)를 타고 backtest.jobs.dlq로 간다 (계약 §2·§7).
        self._channel.basic_reject(delivery_tag=self._delivery_tag, requeue=False)

    def requeue(self) -> None:
        self._channel.basic_nack(delivery_tag=self._delivery_tag, requeue=True)


def require_jobs_queue(
    connection: BlockingConnection, queues: Sequence[str] = (JOBS_QUEUE,)
) -> None:
    """소비할 큐의 존재만 확인한다. 없으면 :class:`QueueMissingError`.

    passive declare는 큐를 만들지 않고 없으면 404로 채널을 닫으므로, 큐마다 임시 채널에서
    확인한다(닫힌 채널은 다시 쓸 수 없다).
    """
    for queue in queues:
        probe = connection.channel()
        try:
            probe.queue_declare(queue=queue, passive=True)
        except ChannelClosedByBroker as exc:
            if exc.reply_code == 404:
                raise QueueMissingError(queue_missing_message(queue)) from exc
            raise
        probe.close()


Delivery = tuple[str, Any, Any, bytes]


def fetch_next_job(
    channel: BlockingChannel, queues: Sequence[str] = FLEX_QUEUE_ORDER
) -> Delivery | None:
    """``queues`` 순서로 한 큐씩 ``basic_get`` 해 첫 메시지를 돌려준다. 모두 비면 None.

    한 번에 한 건만 꺼내므로 unacked 는 항상 1건 이하다 — 두 큐에서 미리 받아 두지 않는다(R-20).
    반환값은 ``(queue, method, properties, body)``.
    """
    for queue in queues:
        method, properties, body = channel.basic_get(queue, auto_ack=False)
        if method is not None:
            return queue, method, properties, body
    return None


def run_flex_loop(
    channel: BlockingChannel,
    consumer: BacktestJobConsumer,
    *,
    keep_going: Callable[[], bool] = lambda: True,
    sleep: Callable[[float], None] = time.sleep,
    poll_seconds: float = FLEX_POLL_SECONDS,
) -> None:
    """공용 워커의 소비 루프: 벌크 먼저, 없으면 단건, 둘 다 없으면 잠시 기다린다.

    처리·ACK 가 끝난 뒤에만 다음 메시지를 집는다. ``keep_going`` 은 테스트가 루프를 끝내는 자리다.
    """
    while keep_going():
        fetched = fetch_next_job(channel)
        if fetched is None:
            sleep(poll_seconds)
            continue
        queue, method, properties, body = fetched
        logger.info("received job role=flex queue=%s delivery_tag=%s", queue, method.delivery_tag)
        consumer.handle(
            body,
            properties.headers if properties is not None else None,
            PikaJobAcknowledger(channel, method.delivery_tag),
            queue=queue,
        )


def bind_control_queue(channel: BlockingChannel) -> str:
    """``backtest.control`` 에 이 워커만의 exclusive·auto-delete 큐를 묶는다(계약 §2·§4.4).

    exchange 는 백엔드가 선언하므로 passive 로 존재만 확인한다(없으면 404 로 채널이 닫힌다 —
    ``run_consumer`` 의 큐 확인과 같은 안내다). 큐는 서버 이름이고 연결이 끊기면 함께 사라진다.
    취소는 살아 있는 워커에게만 뜻이 있다.
    """
    channel.exchange_declare(exchange=CONTROL_EXCHANGE, passive=True)
    declared = channel.queue_declare(queue="", exclusive=True, auto_delete=True)
    queue = declared.method.queue
    channel.queue_bind(queue=queue, exchange=CONTROL_EXCHANGE)
    return queue


def _connect(url: str) -> BlockingConnection:
    return pika.BlockingConnection(pika.URLParameters(url))


def run_control_listener(
    url: str,
    registry: CancelRegistry,
    stop: threading.Event,
    ready: threading.Event,
    connect: Callable[[str], BlockingConnection] = _connect,
) -> None:
    """제어 채널 소비 루프. 별도 연결·별도 스레드다(BlockingConnection 은 스레드 간 공유 불가).

    큐를 바인딩한 **뒤에** ``ready`` 를 켠다. 소비자는 그것을 기다린 뒤에야 작업을 받는다 —
    작업이 먼저 시작되고 큐가 아직 없는 사이에 온 CANCEL 은 이 워커에 닿지 않는다. auto-ack 다:
    취소는 살아 있는 워커에게만 뜻이 있고 재전달할 이유가 없다. 브로커 장애로 끊기면 잠시 뒤 다시
    붙는다.
    """
    while not stop.is_set():
        try:
            connection = connect(url)
            try:
                channel = connection.channel()
                queue = bind_control_queue(channel)
                ready.set()
                logger.info("control listener bound queue=%s exchange=%s", queue, CONTROL_EXCHANGE)
                for method, _properties, body in channel.consume(
                    queue, auto_ack=True, inactivity_timeout=1.0
                ):
                    if stop.is_set():
                        break
                    if method is None:
                        continue
                    handle_control_body(body, registry)
            finally:
                if connection.is_open:
                    connection.close()
        except AMQPError as exc:
            if stop.is_set():
                return
            logger.warning("control listener disconnected; retrying: %s", exc)
            time.sleep(PUBLISH_RETRY_SECONDS)


#: 제어 큐가 이 시간 안에 준비되지 않으면 작업 소비를 시작하지 않는다.
#: 취소가 닿지 않는 워커를 만들지 않는다.
CONTROL_READY_TIMEOUT_SECONDS = 30.0

ControlListener = Callable[
    [str, CancelRegistry, threading.Event, threading.Event, Callable[[str], BlockingConnection]],
    None,
]


def run_consumer(
    url: str,
    engine: BacktestEngine,
    registry: CancelRegistry | None = None,
    *,
    role: str = "single",
    connect: Callable[[str], BlockingConnection] = _connect,
    control_listener: ControlListener = run_control_listener,
    ready_timeout: float = CONTROL_READY_TIMEOUT_SECONDS,
) -> None:
    """작업 큐를 manual ACK · 한 번에 한 건으로 소비한다 (계약 §7, R-20).

    ``role="single"`` 은 ``backtest.jobs`` 를 prefetch 1 push 로, ``role="flex"`` 는 벌크 큐 →
    단건 큐 순서의 pull 로 받는다. 한 연결·한 채널에서 소비와 발행을 함께 한다. BlockingConnection은
    단일 스레드이므로 엔진 실행 중에는 다른 Job을 받지 않는다. ``registry`` 를 주면 제어 채널
    스레드를 먼저 띄우고 **그 큐가 바인딩된 뒤에야** 작업을 받기 시작한다(R-04) — 그 전에 작업을
    시작하면 그 사이 온 CANCEL 을 잃는다. 소비자와 엔진이 같은 레지스트리를 봐야 취소가 닿는다.
    """
    if role not in ("single", "flex"):
        raise ValueError(f"unknown worker role {role!r}; expected 'single' or 'flex'")
    stop = threading.Event()
    ready = threading.Event()
    listener: threading.Thread | None = None
    if registry is not None:
        listener = threading.Thread(
            target=control_listener,
            args=(url, registry, stop, ready, connect),
            name="control",
            daemon=True,
        )
        listener.start()
        if not ready.wait(ready_timeout):
            stop.set()
            raise RuntimeError(
                f"control queue was not bound within {ready_timeout:.0f}s; refusing to consume jobs"
                " without a cancel channel (check the broker and the backend topology)"
            )
    connection = connect(url)
    try:
        queues = FLEX_QUEUE_ORDER if role == "flex" else (JOBS_QUEUE,)
        require_jobs_queue(connection, queues)
        channel = connection.channel()
        channel.confirm_delivery()  # publisher confirm: 발행 확인 후에만 반환한다
        channel.basic_qos(prefetch_count=PREFETCH_COUNT)
        consumer = BacktestJobConsumer(
            engine, PikaEventPublisher(channel), registry=registry, role=role
        )
        if role == "flex":
            logger.info("worker consuming role=flex queues=%s (pull, bulk first)", list(queues))
            # time.sleep 이 아니라 connection.sleep — 기다리는 동안에도 heartbeat 를 처리해야
            # 브로커가 이 연결을 죽은 것으로 보지 않는다.
            run_flex_loop(channel, consumer, sleep=connection.sleep)
            return
        logger.info("worker consuming queue=%s prefetch=%d", JOBS_QUEUE, PREFETCH_COUNT)
        for method, properties, body in channel.consume(JOBS_QUEUE, auto_ack=False):
            logger.info("received job delivery_tag=%s", method.delivery_tag)
            consumer.handle(
                body,
                properties.headers,
                PikaJobAcknowledger(channel, method.delivery_tag),
            )
    finally:
        stop.set()
        if connection.is_open:
            connection.close()
        if listener is not None:
            listener.join(timeout=5.0)
