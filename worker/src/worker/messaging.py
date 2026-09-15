"""RabbitMQ 계약 상수와 실행 Consumer가 의존하는 경계 인터페이스.

**토폴로지는 워커가 선언하지 않는다.** exchange·queue·binding 선언은 백엔드
(Spring ``Declarables``)의 책임이고 워커는 이미 있는 큐를 소비만 한다(계약 README §2).
아래 이름 상수는 백엔드 ``BacktestRabbitConfig`` 의 상수와 **같은 값이어야 하는 계약**이므로
환경별로 달라질 수 있는 설정(``config.py``)으로 빼지 않는다.

이 모듈에는 pika가 없다. 실행 Consumer(``worker.consumer``)는 여기 Protocol만 알고 pika
구현체는 ``worker.rabbitmq`` 에 있다. 그래서 브로커 없이 전체 흐름을 테스트할 수 있다.
"""

from typing import Protocol

from worker.contracts.events import BacktestEvent

# --- 계약 상수 (backend BacktestRabbitConfig와 일치) ---------------------------------

EXCHANGE = "backtest"
DEAD_LETTER_EXCHANGE = "backtest.dlx"
#: 백엔드 → 워커 제어(CANCEL) fanout. 소비 루프는 취소 티켓(T011)에서 붙인다.
CONTROL_EXCHANGE = "backtest.control"

#: 단건(SINGLE) 작업 큐. 큐 분리(spec 004 R-20) 전의 유일한 작업 큐라 이름을 그대로 둔다.
JOBS_QUEUE = "backtest.jobs"
JOBS_ROUTING_KEY = "backtest.job.requested"
#: 탐색·경제환경 파생 실행의 작업 큐. 단건 큐와 속성이 같고 DLX·DLQ 도 같다(R-20).
BULK_JOBS_QUEUE = "backtest.jobs.bulk"
BULK_JOBS_ROUTING_KEY = "backtest.job.requested.bulk"
JOBS_DEAD_LETTER_QUEUE = "backtest.jobs.dlq"
JOBS_DEAD_LETTER_ROUTING_KEY = "backtest.job.dead"

EVENTS_QUEUE = "backtest.events"
EVENTS_ROUTING_KEY = "backtest.event"
EVENTS_DEAD_LETTER_QUEUE = "backtest.events.dlq"
EVENTS_DEAD_LETTER_ROUTING_KEY = "backtest.event.dead"

#: 메시지 스키마 버전(계약 §3 header ``schemaVersion``, §4.2 envelope). 수신 검증과 발행에
#: 같은 값을 쓴다. enum 멤버 추가는 하위호환이 아니므로(ADR-0004 부수 결정) 계약이 바뀌면
#: 이 값을 올리고 backend·worker를 함께 배포한다. v2: spec 004(runId·allocation·costProfile·
#: CANCELLED·제어 메시지).
SCHEMA_VERSION = 2

#: manual ACK + prefetch 1 (계약 §7). 한 번에 Job 하나만 받아 CPU 병목을 워커 수로 조절한다.
#: 작업 큐는 quorum + x-delivery-limit=1 이라 requeue된 메시지는 한 번만 다시 배달된다.
PREFETCH_COUNT = 1

#: 공용(flex) 워커가 두 큐가 모두 비었을 때 다시 확인하기까지 기다리는 시간(초). 이만큼 단건 응답이
#: 늦을 수 있다 — 단건 전용 워커는 push 라 즉시 받으므로 실질 영향은 작다(R-20).
FLEX_POLL_SECONDS = 1.0

#: 공용 워커가 큐를 확인하는 순서. 벌크 먼저, 없으면 단건 — 단건 최소 처리량은 단건 전용 워커가
#: 보장하고, 공용 워커가 단건을 먼저 보면 단건이 계속 들어오는 동안 벌크가 굶는다(R-20).
FLEX_QUEUE_ORDER = (BULK_JOBS_QUEUE, JOBS_QUEUE)


# --- 오류 분류 (계약 §7·§8) ----------------------------------------------------------


class ContractViolation(Exception):
    """계약 오류. 재전달해도 계속 실패하므로 requeue 없이 reject → DLQ.

    JSON 파싱 실패, pydantic ``ValidationError``, 지원하지 않는 ``schemaVersion``.
    """


class PublishError(RuntimeError):
    """이벤트 발행이 브로커에서 확인되지 않았다. 일시 장애로 보고 ACK하지 않는다.

    발행 실패를 완료로 숨기지 않는다(ADR-0003 §6).
    """


# --- 경계 인터페이스 ------------------------------------------------------------------


class EventPublisher(Protocol):
    """``backtest.events`` 발행자."""

    def publish(self, event: BacktestEvent) -> None:
        """브로커 확인(publisher confirm)과 라우팅 확인까지 끝난 뒤 정상 반환한다.

        확인되지 않으면 :class:`PublishError`. 즉 "정상 반환 == 브로커가 받았다"이므로
        호출자는 FINISHED 발행 뒤에만 ACK하면 계약 §7 순서를 지킬 수 있다.
        """


class JobAcknowledger(Protocol):
    """작업 메시지 한 건의 ACK/reject. 구현체가 delivery tag를 들고 있다."""

    def ack(self) -> None:
        """처리 완료. FINISHED 발행이 확인된 뒤에만 호출한다(계약 §7)."""

    def reject(self) -> None:
        """계약 오류. requeue 없이 거부해 DLQ로 보낸다."""

    def requeue(self) -> None:
        """일시 장애. 큐로 되돌려 나중에 다시 받는다. ACK하지 않는다."""
