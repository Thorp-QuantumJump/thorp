"""백엔드 → 워커 제어 메시지. 기준: README §4.4, 픽스처 control-cancel.json.

fanout exchange ``backtest.control`` 로 모든 워커에 전달된다. 워커는 자기 프로세스가 실행 중인
``run_id`` 면 다음 거래일 처리 시점에 멈추고 FINISHED(CANCELLED)를 발행한다. 모르는 runId는
무시한다.
수신 루프(제어 채널 소비)는 취소 구현 티켓(T011)에서 붙인다 — 여기는 계약 모양만 둔다.
"""

from enum import StrEnum

from worker.contracts.base import ContractModel, UtcDatetime


class ControlType(StrEnum):
    CANCEL = "CANCEL"


class ControlMessage(ContractModel):
    schema_version: int  # 현재 2
    type: ControlType
    run_id: str
    requested_at: UtcDatetime
