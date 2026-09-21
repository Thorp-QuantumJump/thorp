"""사례 연결 브로커 계약 상수와 경계 Protocol.

exchange·queue·routing key·``SCHEMA_VERSION``·prefetch를 둔다. 이 값들은 설정이
아니라 계약이라 ``worker.config``(환경변수)로 빼지 않고, 백엔드
``RegimeLinkRabbitConfig``의 ``public static final String``과 같은 값을 유지한다.
**pika를 import하지 않는다** — 그래서 브로커 없이 전체 흐름을 테스트할 수 있다.

**토폴로지는 워커가 선언하지 않는다.** exchange·queue·binding 선언은 백엔드
책임이고 워커는 이미 있는 큐를 소비만 한다. 큐가 없으면 만들지 않고 종료한다(계약
"메시지 속성과 토폴로지") — 속성이 어긋난 큐를 워커가 만들면 백엔드 선언과 충돌한다.

백테스트 경로(``worker.messaging``)와 이름이 겹치지 않는 별도 계약이다. 두 모듈의
상수를 공유하거나 한쪽을 import해 쓰지 않는다.
"""

from typing import Protocol

from worker.regime_link.contracts import RegimeLinkEvent

# --- 계약 상수 (backend RegimeLinkRabbitConfig와 일치) --------------------------------

EXCHANGE = "regime-link"
DEAD_LETTER_EXCHANGE = "regime-link.dlx"

#: 작업 큐. quorum, ``x-delivery-limit=1``, DLX 는 위 ``DEAD_LETTER_EXCHANGE`` 다.
JOBS_QUEUE = "regime-link.jobs"
JOBS_ROUTING_KEY = "regime-link.job.requested"

#: 재전달 상한을 넘긴 작업이 가는 큐. **백엔드가 소비**해 링크를
#: ``FAILED(SYSTEM_FAILURE)`` 로 끝낸다. 워커는 이 큐를 읽지 않는다.
JOBS_DEAD_LETTER_QUEUE = "regime-link.jobs.dlq"

EVENTS_QUEUE = "regime-link.events"
EVENTS_ROUTING_KEY = "regime-link.event"

#: 메시지 스키마 버전. 작업은 AMQP 헤더 ``schemaVersion`` 으로, 이벤트는 본문 첫
#: 필드로 싣는다. 백테스트 계약(``worker.messaging.SCHEMA_VERSION``)과 **별개로
#: 센다** — 같은 브로커를 쓸 뿐 스키마가 다르므로 한쪽이 올라간다고 다른 쪽이
#: 따라가지 않는다.
SCHEMA_VERSION = 1

#: 작업 큐의 quorum 재전달 상한. 워커가 죽어 unacked 메시지가 재전달되면 한 번만
#: 다시 실행되고, 두 번째도 끊기면 브로커가 DLQ 로 보낸다.
JOBS_DELIVERY_LIMIT = 1

#: manual ACK + prefetch 1. 한 번에 작업 하나만 받아 CPU 병목을 워커 수로
#: 조절한다. 재전달 상한이 1 이라, 처리하지 않은 메시지를 unacked 로 잡아 두면
#: 유일한 재시도를 실행 없이 태운다.
PREFETCH_COUNT = 1


# --- 오류 분류 (계약 "처리 규칙") ------------------------------------------------------


class RegimeLinkContractViolation(Exception):
    """계약 오류. 재전달해도 계속 실패하므로 requeue 없이 reject → DLQ.

    JSON 파싱 실패, pydantic ``ValidationError``, 지원하지 않는
    ``schemaVersion``. 계산이 실패한 것과 다르다 — 그쪽은 ``FINISHED(FAILED)`` 를
    발행하고 ACK 한다.

    백테스트 경로의 ``worker.messaging.ContractViolation`` 과 **이름을 가른다.**
    두 모듈에 같은 이름의 무관한 예외를 두면 import 를 잘못 적은 ``except`` 가
    조용히 아무것도 잡지 않아, 계약 오류가 DLQ 로 가는 대신 처리되지 않은 예외로
    빠져나간다. 타입이 다르다는 사실이 이름에 드러나야 한다.
    """


class RegimeLinkPublishError(RuntimeError):
    """이벤트 발행이 브로커에서 확인되지 않았다. 일시 장애로 보고 ACK 하지 않는다.

    발행 실패를 완료로 숨기지 않는다 — 숨기면 백엔드가 모르는 채 링크가 영영
    ``RUNNING`` 으로 남는다. 이름을 가르는 이유는
    :class:`RegimeLinkContractViolation` 과 같다.
    """


# --- 경계 인터페이스 ------------------------------------------------------------------


class EventPublisher(Protocol):
    """``regime-link.events`` 발행자. 구현은 ``worker.regime_link.rabbitmq`` 에 있다."""

    def publish(self, event: RegimeLinkEvent) -> None:
        """브로커 확인(publisher confirm)과 라우팅 확인까지 끝난 뒤 정상 반환한다.

        확인되지 않으면 :class:`RegimeLinkPublishError`. 즉 "정상 반환 == 브로커가 받았다"
        이므로 호출자는 ``FINISHED`` 발행 뒤에만 ACK 하면 계약의 순서를 지킬 수 있다.
        """


class JobAcknowledger(Protocol):
    """작업 메시지 한 건의 ACK/reject. 구현체가 delivery tag 를 들고 있다."""

    def ack(self) -> None:
        """처리 완료. ``FINISHED`` 발행이 확인된 뒤에만 호출한다."""

    def reject(self) -> None:
        """계약 오류. requeue 없이 거부해 DLQ 로 보낸다."""

    def requeue(self) -> None:
        """일시 장애. 큐로 되돌려 나중에 다시 받는다. ACK 하지 않는다."""
