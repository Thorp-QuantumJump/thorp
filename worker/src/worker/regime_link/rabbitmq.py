"""사례 연결 소비·발행의 pika 구현체.

소비 prefetch 1, 발행 confirm과 제한적 재시도, ACK/reject 매핑을 담당한다. 토폴로지(exchange·queue·
binding)를 **선언하지 않는다** — 선언은 백엔드 책임이라 큐가 없으면 만들지 않고 "백엔드를 먼저
띄워라"라는 오류로 종료한다. 속성이 어긋난 큐를 워커가 만들면 백엔드 선언과 충돌한다.

pika 는 이 모듈에만 있다. 소비자 로직(``worker.regime_link.consumer``)은 Protocol 만 알기 때문에
브로커 없이 테스트된다.

백테스트 워커의 ``worker.rabbitmq``는 수정하지 않는다. 상수·예외를 그쪽에서 가져다 쓰지도
않는다 — 큐 이름도 스키마 버전도 다른 별개의 계약이다.
"""

import logging
import time
from collections.abc import Callable
from typing import Any

import pika
from pika.adapters.blocking_connection import BlockingChannel, BlockingConnection
from pika.exceptions import AMQPError, ChannelClosedByBroker, NackError, UnroutableError

from worker.regime_link.consumer import RegimeLinkJobConsumer
from worker.regime_link.contracts import RegimeLinkEvent
from worker.regime_link.market_data import ClosePriceLoader
from worker.regime_link.messaging import (
    EVENTS_ROUTING_KEY,
    EXCHANGE,
    JOBS_QUEUE,
    PREFETCH_COUNT,
    SCHEMA_VERSION,
    RegimeLinkPublishError,
)

logger = logging.getLogger(__name__)

#: 발행 재시도(일시 장애는 제한적 재시도). 소진하면 PublishError → ACK 하지 않는다.
PUBLISH_ATTEMPTS = 3
PUBLISH_RETRY_SECONDS = 1.0


def queue_missing_message(queue: str) -> str:
    return (
        f"queue '{queue}' does not exist on the broker. Declaring the regime-link topology "
        "(exchanges, queues, bindings) is the backend's job (Spring Declarables; see "
        "docs/specs/regime-link-messaging/README.md) and the worker must not create it: a queue "
        "with mismatched arguments would collide with the backend declaration. Start the backend "
        "once against this broker so the topology is declared, then start the worker again."
    )


class QueueMissingError(RuntimeError):
    """작업 큐가 없다. 백엔드를 한 번 띄워 토폴로지를 선언해야 한다."""


class PikaEventPublisher:
    """``regime-link.events`` 발행. publisher confirm + ``mandatory`` 로 확인 후 반환한다."""

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

    def publish(self, event: RegimeLinkEvent[Any]) -> None:
        # 직렬화는 계약 규약 그대로(camelCase + 값 없는 키 생략). json.dumps 로 만들지 않는다.
        body = event.to_json().encode("utf-8")
        properties = pika.BasicProperties(
            content_type="application/json",
            delivery_mode=2,  # persistent
            message_id=event.event_id,  # 본문 eventId 와 같은 값
            correlation_id=event.link_id,
            headers={"schemaVersion": SCHEMA_VERSION},
            # Spring 의 __TypeId__ 헤더는 보내지 않는다.
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
                # binding 이 없다는 뜻이라 재시도해도 같다. 즉시 실패로 올린다.
                raise RegimeLinkPublishError(
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
        raise RegimeLinkPublishError(
            f"event {event.type} (eventId={event.event_id}) was not confirmed after "
            f"{self._attempts} attempts: {last_error}"
        ) from last_error


class PikaJobAcknowledger:
    """작업 메시지 한 건의 ACK/reject. delivery tag 를 들고 있다."""

    def __init__(self, channel: BlockingChannel, delivery_tag: int) -> None:
        self._channel = channel
        self._delivery_tag = delivery_tag

    def ack(self) -> None:
        self._channel.basic_ack(delivery_tag=self._delivery_tag)

    def reject(self) -> None:
        # requeue=False → 큐의 DLX(regime-link.dlx)를 타고 regime-link.jobs.dlq 로 간다.
        self._channel.basic_reject(delivery_tag=self._delivery_tag, requeue=False)

    def requeue(self) -> None:
        self._channel.basic_nack(delivery_tag=self._delivery_tag, requeue=True)


def require_jobs_queue(connection: BlockingConnection, queue: str = JOBS_QUEUE) -> None:
    """소비할 큐의 존재만 확인한다. 없으면 :class:`QueueMissingError`.

    passive declare 는 큐를 만들지 않고 없으면 404 로 채널을 닫으므로 임시 채널에서 확인한다
    (닫힌 채널은 다시 쓸 수 없다).
    """
    probe = connection.channel()
    try:
        probe.queue_declare(queue=queue, passive=True)
    except ChannelClosedByBroker as exc:
        if exc.reply_code == 404:
            raise QueueMissingError(queue_missing_message(queue)) from exc
        raise
    probe.close()


def _connect(url: str) -> BlockingConnection:
    return pika.BlockingConnection(pika.URLParameters(url))


def run_consumer(
    url: str,
    prices: ClosePriceLoader,
    *,
    connect: Callable[[str], BlockingConnection] = _connect,
) -> None:
    """``regime-link.jobs`` 를 manual ACK · 한 번에 한 건으로 소비한다.

    한 연결·한 채널에서 소비와 발행을 함께 한다. BlockingConnection 은 단일 스레드이므로 계산
    중에는 다음 작업을 받지 않는다. 백테스트 워커와 달리 제어(CANCEL) 채널이 없다 — 63거래일
    계산은 취소를 기다릴 만큼 길지 않다.
    """
    connection = connect(url)
    try:
        require_jobs_queue(connection)
        channel = connection.channel()
        channel.confirm_delivery()  # publisher confirm: 발행 확인 후에만 반환한다
        channel.basic_qos(prefetch_count=PREFETCH_COUNT)
        consumer = RegimeLinkJobConsumer(prices, PikaEventPublisher(channel))
        logger.info("regime link worker consuming queue=%s prefetch=%d", JOBS_QUEUE, PREFETCH_COUNT)
        for method, properties, body in channel.consume(JOBS_QUEUE, auto_ack=False):
            logger.info("received regime link job delivery_tag=%s", method.delivery_tag)
            consumer.handle(
                body,
                properties.headers if properties is not None else None,
                PikaJobAcknowledger(channel, method.delivery_tag),
            )
    finally:
        if connection.is_open:
            connection.close()
