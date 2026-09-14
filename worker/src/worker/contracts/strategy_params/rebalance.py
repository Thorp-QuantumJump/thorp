"""배분 전략 공통 ``allocation.parameters`` (spec 004 FR-011).

고정·동일·역변동성 비중은 규칙 값을 ``allocation.rules`` 에서 읽으므로 ``parameters`` 에는 리밸런싱
주기만 온다. 승인된 주기는 월말 하나다 — 다른 값은 파라미터 후보(등록표)가 생기면 여기서 연다.
"""

from typing import Literal

from pydantic import ConfigDict

from worker.contracts.base import ContractModel


class RebalanceParameters(ContractModel):
    model_config = ConfigDict(extra="forbid")

    rebalance: Literal["MONTH_END"]
