"""MOMENTUM 전략 파라미터."""

from pydantic import ConfigDict, Field

from worker.contracts.base import ContractModel


class MomentumParameters(ContractModel):
    """``allocation.parameters`` 중 ``strategyId=MOMENTUM`` 인 경우의 스키마.

    기본값을 두지 않는다. 기본값이 있으면 ``lookbackDays`` 를 ``lookbackDay`` 로 잘못 보내도
    조용히 기본값으로 백테스트가 돌아 잘못된 수익률이 나온다. 필수 필드 + ``extra="forbid"``
    조합이라 오타는 역직렬화 시점에 바로 검증 오류가 된다.
    """

    model_config = ConfigDict(extra="forbid")

    lookback_days: int = Field(gt=0)
