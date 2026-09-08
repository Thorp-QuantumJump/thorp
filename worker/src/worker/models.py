"""옛 Kafka 스캐폴딩의 Job/Result 계약.

`worker.main`만 참조하는 4필드 모델이다. 새 코드는 이 모듈이 아니라
`worker.contracts/`(메시지 계약)와 `worker.engine_contracts`(EngineInput)를 직접 쓴다.
워커 런타임을 pika로 교체할 때 `contracts/`로 대체·삭제한다.
"""

from datetime import date

from pydantic import BaseModel, Field


class BacktestJob(BaseModel):
    """Kafka로 전달되는 백테스트 Job 메시지 (JSON, camelCase)."""

    job_id: int = Field(alias="jobId")
    strategy: str
    start_date: date = Field(alias="startDate")
    end_date: date = Field(alias="endDate")

    model_config = {"populate_by_name": True}


class BacktestResult(BaseModel):
    """백테스트 결과. 최소 지표부터 시작해 스펙 확정 시 확장한다."""

    job_id: int
    total_return: float
    mdd: float
    sharpe: float
    trade_count: int
