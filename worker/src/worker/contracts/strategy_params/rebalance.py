"""배분 전략 공통 ``allocation.parameters`` (spec 004 FR-011).

규칙 값은 ``allocation.rules`` 에서 읽으므로 ``parameters`` 에는 **사용자가 화면에서 고른 것**만
온다. 리밸런싱 주기(승인된 값은 월말 하나)와 종목별 비중 상한이다.

``maxWeight`` 는 003 축과 같은 **정수 %** 다. 규칙표와 엔진은 소수로 다루므로 변환은
``allocation.base.effective_cap`` 한 곳에서만 한다 — 두 곳에서 나누면 한 곳이 100배 어긋난다.
"""

from typing import Literal

from pydantic import ConfigDict, Field

from worker.contracts.base import ContractModel


class RebalanceParameters(ContractModel):
    model_config = ConfigDict(extra="forbid")

    rebalance: Literal["MONTH_END"]
    # None = 미지정. 그때는 규칙표의 weightCap 을 쓴다(PD-03 6).
    max_weight: int | None = Field(default=None, gt=0, le=100)
