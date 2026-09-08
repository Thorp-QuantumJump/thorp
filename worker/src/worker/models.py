"""공개 엔진 계약의 재노출과 기존 Kafka 스캐폴딩 계약.

EngineInput/EngineOutput은 Jira 101 경로이고, BacktestJob/BacktestResult는 아직 연결되지 않은
기존 Consumer용 모델이다.
"""

from datetime import date

from pydantic import BaseModel, Field

# Public integration models; legacy Job/Result are retained below for existing consumers.
from worker.engine_contracts import EngineInput as EngineInput
from worker.engine_contracts import EngineOutput as EngineOutput
from worker.engine_contracts import ProgressSnapshot as ProgressSnapshot


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
