"""취소 제어 채널의 워커 쪽(spec 004 R-04, T011).

백엔드는 fanout ``backtest.control`` 로 ``CANCEL{runId}`` 를 모든 워커에 보낸다. 워커는 별도
스레드가 받은 runId 를 :class:`CancelRegistry` 에 표시하고, 엔진은 거래일마다(``_end_day``)
자기 runId 가 표시됐는지 보고 그날 평가를 마친 뒤 멈춘다. 결과는 ``lastTradingDate`` 까지의
부분 ``EngineOutput``(``status=CANCELLED, partial=true``)이고 소비자는 그것을 FINISHED 로
발행한 뒤 ACK 한다.

- 모르는·끝난 runId 는 무시한다(활성 실행으로 등록된 runId 만 받는다). 실행이 끝나면 등록과 표시를
  함께 지운다 — 같은 runId 가 뒤에 재전달되면(R-06) 옛 취소가 새 실행을 멈추면 안 된다.
- 제어 메시지는 auto-ack 다. 계약을 어긴 메시지는 로그만 남긴다 — 제어 스레드가 죽으면 그 뒤
  모든 취소가 조용히 무시된다.
- pika 는 여기 없다. 큐 바인딩·소비 루프는 ``worker.rabbitmq`` 가 맡는다.
"""

import logging
import threading

from pydantic import ValidationError

from worker.contracts.control import ControlMessage, ControlType
from worker.messaging import SCHEMA_VERSION, ContractViolation

logger = logging.getLogger(__name__)


class CancelRegistry:
    """이 워커가 실행 중인 runId 와 그 가운데 취소가 요청된 runId. 제어 스레드가 쓰고 엔진
    스레드가 읽는다.

    fanout 이라 모든 워커가 모든 CANCEL 을 받는다. 남의 실행·이미 끝난 실행의 취소를 쌓아
    두면 집합이
    끝없이 자라고, 같은 runId 가 재전달됐을 때(R-06) 옛 취소가 새 실행을 멈춘다. 그래서
    **활성 실행으로
    등록된 runId 의 취소만 받고**, 실행이 끝나면 등록과 표시를 함께 지운다. 등록은 소비자가
    STARTED 를
    발행하기 전에 한다 — STARTED 에 대한 답으로 오는 CANCEL(대기 중 취소의 늦은 STARTED)을
    놓치지 않기
    위해서다.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._active: set[str] = set()
        self._requested: set[str] = set()

    def begin(self, run_id: str) -> None:
        """실행 시작. 같은 runId 의 이전 실행이 남긴 표시는 물려받지 않는다."""
        with self._lock:
            self._active.add(run_id)
            self._requested.discard(run_id)

    def end(self, run_id: str) -> None:
        with self._lock:
            self._active.discard(run_id)
            self._requested.discard(run_id)

    def request(self, run_id: str) -> bool:
        """취소 표시. 활성 실행이 아니면 무시하고 False."""
        with self._lock:
            if run_id not in self._active:
                return False
            self._requested.add(run_id)
            return True

    def is_cancelled(self, run_id: str) -> bool:
        with self._lock:
            return run_id in self._requested


def parse_control(body: bytes | str) -> ControlMessage:
    """계약(README §4.4) 검증. 어긋나면 :class:`ContractViolation`."""
    try:
        message = ControlMessage.model_validate_json(body)
    except ValidationError as exc:
        raise ContractViolation(f"invalid control message: {exc}") from exc
    if message.schema_version != SCHEMA_VERSION:
        raise ContractViolation(
            f"unsupported control schemaVersion {message.schema_version}; expected {SCHEMA_VERSION}"
        )
    return message


def handle_control_body(body: bytes | str, registry: CancelRegistry) -> None:
    """제어 메시지 한 건. 깨진 메시지는 버리고 스레드는 계속 산다."""
    try:
        message = parse_control(body)
    except ContractViolation as exc:
        logger.error("ignoring control message: %s", exc)
        return
    if message.type is ControlType.CANCEL:
        if registry.request(message.run_id):
            logger.info("cancel requested for runId=%s", message.run_id)
        else:
            logger.debug(
                "ignoring CANCEL for a run this worker is not executing: %s", message.run_id
            )
