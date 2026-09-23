"""사례 연결 작업 소비자.

헤더 ``schemaVersion`` 확인 → 작업 파싱 → ``STARTED`` 발행 → ``slicer`` 호출 → ``FINISHED`` 발행 →
브로커 확인 뒤 ACK. 브로커 기술을 모르고 ``messaging``의 Protocol로만 말한다.

오류는 세 갈래다(백테스트 소비자와 같은 규칙, ``worker/CLAUDE.md``). ① 계약 오류는 requeue 없이
reject해 DLQ로 보내고 ② 계산 실패는 ``FINISHED(FAILED)``를 발행한 뒤 ACK하며 ③ 발행 실패와 시장
자료 적재 중 일시 장애는 ACK하지 않고 재전달 경로로 넘긴다.

**``eventId``는 ``linkId``·``attempt``·이벤트 종류에서 결정적으로 만든다.** 백테스트 경로는
실행마다 새 ``executionId``를 발급해 그 밑에서 ``eventId``를 새로 뽑지만, 011에는 그런 실행
식별자가 없고 재전달은 같은 작업을 그대로 다시 하는 것이다. 매번 새 값을 뽑으면 백엔드의 중복
방어 키(``eventId``)가 재전달마다 달라져 같은 결과가 두 번 기록된다.

백테스트 소비자(``worker.consumer``)는 건드리지 않는다.
"""

import json
import logging
import os
import socket
import time
import uuid
from collections.abc import Callable, Mapping
from datetime import datetime
from decimal import Decimal
from typing import Any

from pydantic import ValidationError

from worker.contracts.base import utc_now
from worker.regime_link.contracts import (
    ErrorDetail,
    ProcessInfo,
    RegimeLinkErrorCode,
    RegimeLinkEvent,
    RegimeLinkEventType,
    RegimeLinkFinishedPayload,
    RegimeLinkJob,
    RegimeLinkStartedPayload,
    RegimeLinkStatus,
    ResourceUsage,
)
from worker.regime_link.market_data import ClosePriceLoader
from worker.regime_link.messaging import (
    SCHEMA_VERSION,
    EventPublisher,
    JobAcknowledger,
    RegimeLinkContractViolation,
    RegimeLinkPublishError,
)
from worker.regime_link.slicer import ClosePrices, SliceError, slice_window

logger = logging.getLogger(__name__)

Clock = Callable[[], datetime]

#: ``eventId`` 를 결정적으로 뽑는 이름공간. 값 자체에 뜻은 없고 다른 uuid5 용도와 섞이지 않게
#: 고정한다. **이 값을 바꾸면 이미 발행한 이벤트와 다른 ``eventId`` 가 나오므로 바꾸지 않는다.**
EVENT_NAMESPACE = uuid.UUID("6f1c0f8a-2f1e-5a3d-9d6e-0b0f0d5a7c11")


def event_id_for(link_id: str, attempt: int, event_type: RegimeLinkEventType) -> str:
    """같은 작업의 같은 이벤트면 언제 불러도 같은 값. 재전달의 중복 방어 키다."""
    return str(uuid.uuid5(EVENT_NAMESPACE, f"{link_id}:{attempt}:{event_type.value}"))


def verify_schema_version(headers: Mapping[str, Any] | None) -> None:
    """작업 헤더의 ``schemaVersion`` 확인(계약 "메시지 속성과 토폴로지").

    작업 본문에는 ``schemaVersion`` 이 없다 — 011은 버전을 AMQP 헤더로만 싣는다. 그래서
    헤더가 없으면 통과시키지 않고 계약 오류로 본다. 백테스트 경로는 본문이 ``EngineInput``
    전체라 "아무 데도 표시하지 않은" 경우를 통과시키지만, 여기서는 표시할 자리가 하나뿐이라
    비어 있다는 것은 곧 보낸 쪽이 계약을 지키지 않았다는 뜻이다. 모르는 버전을 아는 버전인 척
    계산하면 요청과 다른 숫자가 저장된다.
    """
    value = (headers or {}).get("schemaVersion")
    if value is None:
        raise RegimeLinkContractViolation(
            f"job header 'schemaVersion' is missing (this worker speaks {SCHEMA_VERSION})"
        )
    if isinstance(value, bool) or not isinstance(value, int) or value != SCHEMA_VERSION:
        raise RegimeLinkContractViolation(
            f"unsupported schemaVersion in header: {value!r} (this worker speaks {SCHEMA_VERSION})"
        )


def parse_job(body: bytes | str, headers: Mapping[str, Any] | None) -> RegimeLinkJob:
    """작업 메시지를 계약 모델로 바꾼다. 계약 위반은 :class:`RegimeLinkContractViolation`."""
    verify_schema_version(headers)
    try:
        decoded = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RegimeLinkContractViolation(f"job body is not valid JSON: {exc}") from exc
    if not isinstance(decoded, dict):
        raise RegimeLinkContractViolation(
            f"job body must be a JSON object, got {type(decoded).__name__}"
        )
    try:
        return RegimeLinkJob.model_validate(decoded)
    except ValidationError as exc:
        raise RegimeLinkContractViolation(
            f"job body violates the RegimeLinkJob contract: {exc}"
        ) from exc


def _seconds(value: float) -> Decimal:
    return Decimal(f"{value:.3f}")


class RegimeLinkJobConsumer:
    """``regime-link.jobs`` 한 건을 처리한다. 브로커 런타임(pika)과 분리돼 있다."""

    def __init__(
        self,
        prices: ClosePriceLoader,
        publisher: EventPublisher,
        *,
        clock: Clock = utc_now,
    ) -> None:
        self._prices = prices
        self._publisher = publisher
        self._clock = clock

    def handle(
        self,
        body: bytes | str,
        headers: Mapping[str, Any] | None,
        acknowledger: JobAcknowledger,
    ) -> None:
        """수신 → 파싱 → 발행 → ACK. 오류 세 갈래가 여기서 갈린다.

        같은 ``linkId``·``attempt`` 가 재전달되면 그냥 다시 계산한다. 워커는 DB에 쓰지 않아
        처리 이력을 남길 곳이 없고, 구간 계산은 고정 입력에 대해 결정적이라 재실행이 안전하다.
        중복 방어는 백엔드 몫이며 그 키인 ``eventId`` 는 재전달에도 같은 값이다.
        """
        try:
            job = parse_job(body, headers)
        except RegimeLinkContractViolation as exc:
            # 재전달해도 결과가 같으므로 되돌리지 않는다. 이벤트도 발행하지 않는다 —
            # linkId 를 신뢰할 수 없다.
            logger.error("contract violation; rejecting to DLQ without requeue: %s", exc)
            acknowledger.reject()
            return

        link_id = job.job.link_id
        attempt = job.job.attempt
        started = time.monotonic()
        try:
            self._publish(job, RegimeLinkEventType.STARTED, self._started_payload())
            # 자료 적재만 try 안에서 결과로 바뀌지 않는다 — 접속 실패 같은 일시 장애를
            # FINISHED(FAILED) 로 바꾸면 발행·ACK 되어 최종 실패로 굳는다.
            prices = self._prices.load(job)
            payload = self._compute(job, prices, started)
            self._publish(job, RegimeLinkEventType.FINISHED, payload)
        except RegimeLinkPublishError as exc:
            logger.error(
                "event publication failed for linkId=%s attempt=%d; not acking: %s",
                link_id,
                attempt,
                exc,
            )
            acknowledger.requeue()
            return
        except Exception:
            # 계산 오류는 FINISHED(FAILED) 로 돌아오는 것이 계약이므로 여기까지 오는 예외는
            # 자료 적재의 일시 장애이거나 워커 버그다. 어느 쪽이든 결과를 잃은 상태를 완료로
            # 숨기지 않는다.
            logger.exception(
                "unexpected failure for linkId=%s attempt=%d; not acking", link_id, attempt
            )
            acknowledger.requeue()
            return

        # 여기 도달하면 FINISHED 발행이 브로커에서 확인된 것이다(publish 는 확인 후 반환).
        # 그 뒤에만 ACK 한다.
        acknowledger.ack()
        logger.info(
            "regime link done: linkId=%s attempt=%d status=%s",
            link_id,
            attempt,
            payload.status.value,
        )

    # --- 이벤트 조립 ---------------------------------------------------------------

    def _started_payload(self) -> RegimeLinkStartedPayload:
        return RegimeLinkStartedPayload(
            started_at=self._clock(),
            resource=ProcessInfo(pid=os.getpid(), hostname=socket.gethostname()),
        )

    def _failed(
        self, code: RegimeLinkErrorCode, message: str, started: float
    ) -> RegimeLinkFinishedPayload:
        return RegimeLinkFinishedPayload(
            status=RegimeLinkStatus.FAILED,
            # 실패에는 잘라낸 구간이 없다. 상장폐지 표시는 결과가 있을 때만 뜻이 있다.
            partial=False,
            error=ErrorDetail(code=code, message=message),
            resource=ResourceUsage(wall_seconds=_seconds(time.monotonic() - started)),
        )

    def _compute(
        self, job: RegimeLinkJob, prices: ClosePrices, started: float
    ) -> RegimeLinkFinishedPayload:
        """구간 계산. 여기서 난 오류는 전부 정상 결과(``FINISHED(FAILED)``)로 바뀐다.

        자료가 메모리에 오른 뒤의 예외는 결정적이다 — 같은 입력이면 다시 난다. 전파해
        재전달시키면 같은 자리에서 또 죽어 DLQ 로 가는데 그건 장애가 아니라 결함이다.
        """
        try:
            outcome = slice_window(job, prices)
        except SliceError as exc:
            logger.warning(
                "slice failed for linkId=%s: %s %s", job.job.link_id, exc.code.value, exc.message
            )
            return self._failed(exc.code, exc.message, started)
        except Exception as exc:
            logger.exception("slice raised unexpectedly for linkId=%s", job.job.link_id)
            return self._failed(
                RegimeLinkErrorCode.ENGINE_ERROR, f"{type(exc).__name__}: {exc}", started
            )
        return RegimeLinkFinishedPayload(
            status=RegimeLinkStatus.COMPLETED,
            partial=outcome.partial,
            delisted_on=outcome.delisted_on,
            result=outcome.result,
            resource=ResourceUsage(wall_seconds=_seconds(time.monotonic() - started)),
        )

    def _publish(
        self,
        job: RegimeLinkJob,
        event_type: RegimeLinkEventType,
        payload: RegimeLinkStartedPayload | RegimeLinkFinishedPayload,
    ) -> None:
        event: RegimeLinkEvent[Any] = RegimeLinkEvent(
            schema_version=SCHEMA_VERSION,
            event_id=event_id_for(job.job.link_id, job.job.attempt, event_type),
            link_id=job.job.link_id,
            attempt=job.job.attempt,
            type=event_type,
            occurred_at=self._clock(),
            payload=payload,
        )
        self._publisher.publish(event)  # 확인되지 않으면 RegimeLinkPublishError
