"""워커 → 백엔드 이벤트 envelope(schemaVersion 2)와 payload 3종.

기준: README §4.2, 픽스처 event-*.json.
"""

from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Generic, TypeVar

from worker.contracts.base import ContractModel, UtcDatetime
from worker.contracts.engine_output import EngineOutput, RunStatus


class BacktestEventType(StrEnum):
    STARTED = "STARTED"
    PROGRESS = "PROGRESS"
    FINISHED = "FINISHED"


class ProcessingStage(StrEnum):
    PREPARING_DATA = "PREPARING_DATA"
    COMPUTING = "COMPUTING"


class ProcessInfo(ContractModel):
    """실행 프로세스 식별. 자동 재시도 진단용.

    ``queue``·``role`` 은 이 작업을 어느 큐에서 어느 역할의 워커가 집었는지다(spec 004 R-20,
    T012). 공용(flex) 워커가 단건을 지원한 비율은 이 값 없이 셀 수 없다. 선택 필드라 v2 안에서
    하위호환이다.
    """

    pid: int | None = None
    hostname: str | None = None
    queue: str | None = None
    role: str | None = None


class StartedPayload(ContractModel):
    started_at: UtcDatetime
    stage: ProcessingStage  # 항상 PREPARING_DATA
    resource: ProcessInfo | None = None


class ProgressPoint(ContractModel):
    date: date
    equity: Decimal
    cumulative_return: Decimal
    drawdown: Decimal


class ProgressPayload(ContractModel):
    """``EngineInput.progress.batchTradingDays`` 단위. 이번 구간에서 새로 계산한 포인트만 담는다.

    엔진은 거래일마다 ProgressPoint 1개를 콜백으로 넘기고, 이 payload로 묶어 발행하는 것은
    실행 Consumer 책임이다. ``last_trading_date`` 는 취소·한도 시 완료 범위가 된다.
    """

    stage: ProcessingStage  # 항상 COMPUTING
    completed_trading_days: int
    total_trading_days: int
    last_trading_date: date
    portfolio_series: list[ProgressPoint]


class ResourceUsage(ContractModel):
    """실행 프로세스 자원 사용(측정 기록용). 못 잰 값은 생략."""

    peak_rss_mb: Decimal | None = None
    cpu_seconds: Decimal | None = None
    wall_seconds: Decimal | None = None


class FinishedPayload(ContractModel):
    """``status`` 는 ``engine_output.run.status`` 와 같아야 한다(백엔드가 검증)."""

    status: RunStatus
    partial: bool  # True = 취소·한도 도달로 끊긴 부분 결과(CANCELLED)
    last_trading_date: date | None = None  # FAILED는 None 가능
    engine_output: EngineOutput
    resource: ResourceUsage | None = None


P = TypeVar("P", StartedPayload, ProgressPayload, FinishedPayload)


class BacktestEvent(ContractModel, Generic[P]):
    """``backtest.events`` 큐로 발행하는 envelope.

    ``BacktestEvent[FinishedPayload]`` 처럼 payload 타입을 지정한다.
    수신 측은 본문 ``type`` 을 먼저 읽고 그에 맞는 payload 타입으로 검증한다.
    """

    schema_version: int  # 현재 2
    event_id: str  # 재발행 시 유지 (중복 방지 키)
    run_id: str  # EngineInput.run.run_id와 같다
    execution_id: str  # 엔진 실행 시도 식별자. 재실행마다 새 값
    type: BacktestEventType
    sequence: int  # STARTED=0, 이후 1씩 증가
    occurred_at: UtcDatetime
    payload: P
