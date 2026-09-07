"""Backend와 공유하는 Job 계약. 백엔드 BacktestJobRequest와 필드를 맞춘다."""

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
