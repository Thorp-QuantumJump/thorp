"""취소 제어 채널의 워커 쪽(spec 004 R-04, T011).

백엔드는 fanout ``backtest.control`` 로 ``CANCEL{runId}`` 를 모든 워커에 보낸다. 워커는 별도
스레드가 받은 runId 를 :class:`CancelRegistry` 에 표시하고, 엔진은 거래일마다(``_end_day``)
자기 runId 가 표시됐는지 보고 그날 평가를 마친 뒤 멈춘다. 결과는 ``lastTradingDate`` 까지의
부분 ``EngineOutput``(``status=CANCELLED, partial=true``)이고 소비자는 그것을 FINISHED 로
발행한 뒤 ACK 한다.

- 모르는·끝난 runId 는 무시한다. 레지스트리에 남은 표시는 그 실행이 끝날 때 지운다 — 같은 runId 가
  뒤에 재전달되면(R-06) 옛 취소가 새 실행을 멈추면 안 된다.
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
    """취소가 요청된 runId 집합. 제어 스레드가 쓰고 엔진 스레드가 읽는다."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._requested: set[str] = set()

    def request(self, run_id: str) -> None:
        with self._lock:
            self._requested.add(run_id)

    def is_cancelled(self, run_id: str) -> bool:
        with self._lock:
            return run_id in self._requested

    def discard(self, run_id: str) -> None:
        with self._lock:
            self._requested.discard(run_id)


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
        logger.info("cancel requested for runId=%s", message.run_id)
        registry.request(message.run_id)
