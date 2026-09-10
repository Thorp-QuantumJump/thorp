"""pika 런타임: ``worker.messaging`` Protocol의 RabbitMQ 구현과 소비 루프.

pika는 이 모듈에만 있다. 실행 Consumer 로직(``worker.consumer``)은 Protocol만 알기 때문에
브로커 없이 테스트된다.

**토폴로지를 선언하지 않는다.** exchange·queue·binding 선언은 백엔드 책임이므로(계약 §2)
여기서는 ``backtest.jobs`` 가 실제로 있는지 passive declare로 확인만 하고, 없으면
:class:`QueueMissingError` 로 명확히 알린다. 속성이 어긋난 큐를 워커가 만들면 백엔드 선언과
충돌해 훨씬 찾기 어려운 문제가 된다.
"""

import logging
import time
from collections.abc import Callable

import pika
from pika.adapters.blocking_connection import BlockingChannel, BlockingConnection
from pika.exceptions import AMQPError, ChannelClosedByBroker, NackError, UnroutableError

from worker.consumer import BacktestJobConsumer
from worker.contracts.engine import BacktestEngine
from worker.contracts.events import BacktestEvent
from worker.messaging import (
    EVENTS_ROUTING_KEY,
    EXCHANGE,
    JOBS_QUEUE,
    PREFETCH_COUNT,
    SCHEMA_VERSION,
    PublishError,
)

logger = logging.getLogger(__name__)

#: 발행 재시도(계약 §7 "일시 장애는 제한적 재시도"). 소진하면 PublishError → ACK하지 않는다.
PUBLISH_ATTEMPTS = 3
PUBLISH_RETRY_SECONDS = 1.0

QUEUE_MISSING_MESSAGE = (
    f"queue '{JOBS_QUEUE}' does not exist on the broker. Declaring the backtest topology "
    "(exchanges, queues, bindings) is the backend's job (Spring Declarables; see "
    "docs/specs/backtest-messaging/README.md section 2) and the worker must not create it: a "
    "queue with mismatched arguments would collide with the backend declaration. Start the "
    "backend once against this broker so the topology is declared, then start the worker again."
)


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


def require_jobs_queue(connection: BlockingConnection) -> None:
    """``backtest.jobs`` 존재만 확인한다. 없으면 :class:`QueueMissingError`.

    passive declare는 큐를 만들지 않고 없으면 404로 채널을 닫으므로, 소비 채널이 아닌
    임시 채널에서 확인한다.
    """
    probe = connection.channel()
    try:
        probe.queue_declare(queue=JOBS_QUEUE, passive=True)
    except ChannelClosedByBroker as exc:
        if exc.reply_code == 404:
            raise QueueMissingError(QUEUE_MISSING_MESSAGE) from exc
        raise
    probe.close()


def run_consumer(url: str, engine: BacktestEngine) -> None:
    """``backtest.jobs`` 를 manual ACK · prefetch 1로 소비한다 (계약 §7).

    한 연결·한 채널에서 소비와 발행을 함께 한다. BlockingConnection은 단일 스레드이므로
    엔진 실행 중에는 다른 Job을 받지 않는다(prefetch 1과 같은 방향이다).
    """
    connection = pika.BlockingConnection(pika.URLParameters(url))
    try:
        require_jobs_queue(connection)
        channel = connection.channel()
        channel.confirm_delivery()  # publisher confirm: 발행 확인 후에만 반환한다
        channel.basic_qos(prefetch_count=PREFETCH_COUNT)
        consumer = BacktestJobConsumer(engine, PikaEventPublisher(channel))
        logger.info("worker consuming queue=%s prefetch=%d", JOBS_QUEUE, PREFETCH_COUNT)
        for method, properties, body in channel.consume(JOBS_QUEUE, auto_ack=False):
            logger.info("received job delivery_tag=%s", method.delivery_tag)
            consumer.handle(
                body,
                properties.headers,
                PikaJobAcknowledger(channel, method.delivery_tag),
            )
    finally:
        if connection.is_open:
            connection.close()
