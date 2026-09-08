"""워커 → 백엔드 이벤트 envelope와 payload 3종. 기준: README §4.2, 픽스처 event-*.json."""

from datetime import date
from decimal import Decimal
from enum import StrEnum
from typing import Generic, TypeVar

from worker.contracts.base import ContractModel, UtcDatetime
from worker.contracts.engine_output import BenchmarkResult, EngineOutput


class BacktestEventType(StrEnum):
    STARTED = "STARTED"
    PROGRESS = "PROGRESS"
    FINISHED = "FINISHED"


class StartedPayload(ContractModel):
    started_at: UtcDatetime


class ProgressPoint(ContractModel):
    date: date
    equity: Decimal
    cumulative_return: Decimal
    drawdown: Decimal


class ProgressPayload(ContractModel):
    """20거래일 단위. 이번 구간에서 새로 계산한 포인트만 담는다 (마지막 잔여 구간도 발행).

    엔진은 거래일마다 ProgressPoint 1개를 콜백으로 넘기고, 이 payload로 묶어 발행하는 것은
    실행 Consumer 책임이다.
    """

    completed_trading_days: int
    total_trading_days: int
    portfolio_series: list[ProgressPoint]


class FinishedPayload(ContractModel):
    """성공/실패는 engine_output.run.status로 판단."""

    engine_output: EngineOutput
    benchmark: BenchmarkResult | None = None  # None = 미지정 또는 실패


P = TypeVar("P", StartedPayload, ProgressPayload, FinishedPayload)


class BacktestEvent(ContractModel, Generic[P]):
    """``backtest.events`` 큐로 발행하는 envelope.

    ``BacktestEvent[FinishedPayload]`` 처럼 payload 타입을 지정한다.
    수신 측은 본문 ``type`` 을 먼저 읽고 그에 맞는 payload 타입으로 검증한다.
    """

    schema_version: int  # 현재 1
    event_id: str  # 재발행 시 유지 (중복 방지 키)
    backtest_id: str  # EngineInput.run.backtest_id와 같다
    execution_id: str  # 엔진 실행 시도 식별자. 재실행마다 새 값
    type: BacktestEventType
    sequence: int  # STARTED=0, 이후 1씩 증가
    occurred_at: UtcDatetime
    payload: P
