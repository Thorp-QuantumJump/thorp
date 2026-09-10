"""실행 Consumer: 작업 메시지 → 엔진 실행 → STARTED / PROGRESS / FINISHED 발행 → ACK.

계약 기준: ``docs/specs/backtest-messaging/README.md`` §3·§4.2·§7·§8.

pika를 직접 부르지 않는다. 발행과 ACK는 ``worker.messaging`` 의 Protocol
(:class:`~worker.messaging.EventPublisher`, :class:`~worker.messaging.JobAcknowledger`)
뒤에 있고 pika 구현체는 ``worker.rabbitmq`` 에 있다. 엔진이 ``DataProvider`` 를 생성자로
주입받는 것과 같은 패턴이며, 덕분에 브로커 없이 전체 흐름을 pytest로 검증한다.

**EngineOutput 내용을 해석하지 않는다.** ``run.status`` 가 COMPLETED든 FAILED든 같은
FINISHED 이벤트에 그대로 담는다. 엔진이 계약대로 답한 것이므로 DLQ가 아니다. FINISHED payload의
``status``·``partial``·``lastTradingDate`` 는 ``run`` 블록의 값을 그대로 옮긴 것이다.
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
from worker.contracts.engine import BacktestEngine
from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import EngineOutput
from worker.contracts.events import (
    BacktestEvent,
    BacktestEventType,
    FinishedPayload,
    ProcessInfo,
    ProcessingStage,
    ProgressPayload,
    ProgressPoint,
    ResourceUsage,
    StartedPayload,
)
from worker.messaging import (
    SCHEMA_VERSION,
    ContractViolation,
    EventPublisher,
    JobAcknowledger,
    PublishError,
)
from worker.nautilus_engine import CallbackError

logger = logging.getLogger(__name__)

IdFactory = Callable[[], str]
Clock = Callable[[], datetime]


def _new_id() -> str:
    return str(uuid.uuid4())


def verify_schema_version(headers: Mapping[str, Any] | None, body: Mapping[str, Any]) -> None:
    """헤더·본문의 ``schemaVersion`` 이 이 워커가 아는 버전인지 확인한다(계약 §3·§8).

    작업 메시지 본문은 ``EngineInput`` 전체이고 계약 모델에 ``schemaVersion`` 필드가 없으므로
    (픽스처 ``engine-input.json`` 에도 없다) 값은 원본 dict에서 직접 읽는다. 둘 다 없으면
    "표시하지 않았다"로 보고 통과시키고, **표시했는데 값이 다르면** 계약 오류로 거절한다 —
    모르는 버전의 메시지를 아는 버전인 척 계산하면 요청과 다른 결과가 저장된다.
    """
    declared = (
        ("header", (headers or {}).get("schemaVersion")),
        ("body", body.get("schemaVersion")),
    )
    for source, value in declared:
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, int) or value != SCHEMA_VERSION:
            raise ContractViolation(
                f"unsupported schemaVersion in {source}: {value!r} (this worker speaks "
                f"{SCHEMA_VERSION})"
            )


def parse_job(body: bytes | str, headers: Mapping[str, Any] | None) -> EngineInput:
    """작업 메시지를 계약 모델로 바꾼다. 계약 위반은 :class:`ContractViolation`."""
    try:
        decoded = json.loads(body)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ContractViolation(f"job body is not valid JSON: {exc}") from exc
    if not isinstance(decoded, dict):
        raise ContractViolation(f"job body must be a JSON object, got {type(decoded).__name__}")
    verify_schema_version(headers, decoded)
    try:
        return EngineInput.model_validate(decoded)
    except ValidationError as exc:
        raise ContractViolation(f"job body violates the EngineInput contract: {exc}") from exc


def _seconds(value: float) -> Decimal:
    return Decimal(f"{value:.3f}")


class _EventStream:
    """한 번의 엔진 실행이 발행하는 이벤트 순서와 ``sequence`` 를 관리한다.

    ``sequence`` 는 STARTED=0에서 시작해 발행이 확인된 뒤 1씩 증가한다. ``eventId`` 는
    이벤트마다 새로 발급하지만 같은 이벤트를 재발행할 때는 같은 객체를 다시 넘기므로
    유지된다(중복 방지 키 — 계약 §4.2).
    """

    def __init__(
        self,
        publisher: EventPublisher,
        run_id: str,
        execution_id: str,
        new_id: IdFactory,
        clock: Clock,
    ) -> None:
        self._publisher = publisher
        self._run_id = run_id
        self._execution_id = execution_id
        self._new_id = new_id
        self._clock = clock
        self._sequence = 0
        self._wall_started = time.monotonic()
        self._cpu_started = time.process_time()

    @property
    def sequence(self) -> int:
        return self._sequence

    def started(self) -> None:
        now = self._clock()
        payload = StartedPayload(
            started_at=now,
            stage=ProcessingStage.PREPARING_DATA,
            resource=ProcessInfo(pid=os.getpid(), hostname=socket.gethostname()),
        )
        self._emit(BacktestEventType.STARTED, payload, now)

    def progress(self, payload: ProgressPayload) -> None:
        self._emit(BacktestEventType.PROGRESS, payload, self._clock())

    def finished(self, output: EngineOutput) -> None:
        # ponytail: peakRssMb는 재지 않는다(resource 모듈이 Windows에 없다). 필요하면 psutil.
        usage = ResourceUsage(
            cpu_seconds=_seconds(time.process_time() - self._cpu_started),
            wall_seconds=_seconds(time.monotonic() - self._wall_started),
        )
        payload = FinishedPayload(
            status=output.run.status,
            partial=bool(output.run.partial),
            last_trading_date=output.run.last_trading_date,
            engine_output=output,
            resource=usage,
        )
        self._emit(BacktestEventType.FINISHED, payload, self._clock())

    def _emit(
        self,
        event_type: BacktestEventType,
        payload: StartedPayload | ProgressPayload | FinishedPayload,
        occurred_at: datetime,
    ) -> None:
        event: BacktestEvent = BacktestEvent(
            schema_version=SCHEMA_VERSION,
            event_id=self._new_id(),
            run_id=self._run_id,
            execution_id=self._execution_id,
            type=event_type,
            sequence=self._sequence,
            occurred_at=occurred_at,
            payload=payload,
        )
        self._publisher.publish(event)  # 확인되지 않으면 PublishError
        self._sequence += 1


class _ProgressBatcher:
    """거래일 1개 단위 콜백을 ``progress.batchTradingDays`` 묶음 PROGRESS로 바꾼다(계약 §4.2).

    이번 구간에서 **새로 계산한 포인트만** 담고, 발행이 확인된 뒤에 버퍼를 비운다. 발행이
    실패하면 버퍼를 유지한 채 예외가 콜백 밖으로 나가므로 같은 포인트를 잃지 않는다.
    """

    def __init__(self, stream: _EventStream, batch_size: int) -> None:
        self._stream = stream
        self._batch_size = batch_size
        self._buffer: list[ProgressPoint] = []
        self._completed = 0
        self._total = 0

    def on_progress(
        self, point: ProgressPoint, completed_trading_days: int, total_trading_days: int
    ) -> None:
        self._buffer.append(point)
        self._completed = completed_trading_days
        self._total = total_trading_days
        if len(self._buffer) >= self._batch_size:
            self._publish()

    def flush(self) -> None:
        """마지막 잔여 구간을 발행한다. 묶음 경계에서 끝났으면 아무 것도 하지 않는다."""
        if self._buffer:
            self._publish()

    def _publish(self) -> None:
        self._stream.progress(
            ProgressPayload(
                stage=ProcessingStage.COMPUTING,
                completed_trading_days=self._completed,
                total_trading_days=self._total,
                last_trading_date=self._buffer[-1].date,
                portfolio_series=list(self._buffer),
            )
        )
        self._buffer.clear()


class BacktestJobConsumer:
    """``backtest.jobs`` 한 건을 처리한다. 브로커 런타임(pika)과 분리돼 있다."""

    def __init__(
        self,
        engine: BacktestEngine,
        publisher: EventPublisher,
        *,
        new_id: IdFactory = _new_id,
        clock: Clock = utc_now,
    ) -> None:
        self._engine = engine
        self._publisher = publisher
        self._new_id = new_id
        self._clock = clock

    def handle(
        self,
        body: bytes | str,
        headers: Mapping[str, Any] | None,
        acknowledger: JobAcknowledger,
    ) -> None:
        """수신 → 역직렬화 → 실행 → 발행 → ACK. 오류 3분류가 여기서 갈린다(계약 §7).

        같은 ``runId`` 가 재전달되면 그냥 다시 실행하고 ``executionId`` 만 새로 발급한다.
        워커는 DB에 쓰지 않아(계약 §1) 처리 이력을 남길 곳이 없다. 중복 방어는 계약 §7이
        백엔드 결과 Consumer에 요구하며 ``eventId`` 가 그 키다.
        """
        try:
            engine_input = parse_job(body, headers)
        except ContractViolation as exc:
            # 재전달해도 결과가 같으므로 되돌리지 않는다. 이벤트도 발행하지 않는다 —
            # runId를 신뢰할 수 없다.
            logger.error("contract violation; rejecting to DLQ without requeue: %s", exc)
            acknowledger.reject()
            return

        run_id = engine_input.run.run_id
        execution_id = self._new_id()
        try:
            output = self._execute(engine_input, run_id, execution_id)
        except PublishError as exc:
            logger.error(
                "event publication failed for runId=%s executionId=%s; not acking: %s",
                run_id,
                execution_id,
                exc,
            )
            acknowledger.requeue()
            return
        except CallbackError as exc:
            # 진행 콜백(= PROGRESS 발행) 실패를 엔진이 감싸 올린 것이다.
            logger.error(
                "progress publication failed for runId=%s executionId=%s; not acking: %s",
                run_id,
                execution_id,
                exc,
            )
            acknowledger.requeue()
            return
        except Exception:
            # 엔진 오류는 FAILED EngineOutput으로 돌아오는 것이 계약이므로 여기까지 오는
            # 예외는 워커 버그나 브로커 장애다. 어느 쪽이든 결과를 잃은 상태를 완료로
            # 숨기지 않는다(ADR-0003 §6). 반복 재전달되면 로그로 드러난다.
            logger.exception(
                "unexpected failure for runId=%s executionId=%s; not acking",
                run_id,
                execution_id,
            )
            acknowledger.requeue()
            return

        # 여기 도달하면 FINISHED 발행이 브로커에서 확인된 것이다(publish는 확인 후 반환).
        # 그 뒤에만 ACK한다 (계약 §7).
        acknowledger.ack()
        logger.info(
            "job done: runId=%s executionId=%s status=%s",
            run_id,
            execution_id,
            output.run.status,
        )

    def _execute(self, engine_input: EngineInput, run_id: str, execution_id: str) -> EngineOutput:
        stream = _EventStream(self._publisher, run_id, execution_id, self._new_id, self._clock)
        stream.started()
        batcher = _ProgressBatcher(stream, engine_input.progress.batch_trading_days)
        output = self._engine.run(engine_input, batcher.on_progress)
        batcher.flush()
        stream.finished(output)
        return output
