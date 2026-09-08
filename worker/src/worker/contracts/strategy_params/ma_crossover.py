"""MA_CROSSOVER 전략 파라미터."""

from pydantic import ConfigDict, Field, model_validator

from worker.contracts.base import ContractModel


class MaCrossoverParameters(ContractModel):
    """``strategy.parameters`` 중 ``strategyId=MA_CROSSOVER`` 인 경우의 스키마.

    ``threshold`` 는 ``Decimal`` 이 아니라 ``float`` 이다. 계약(README §4.1)은
    ``strategy.parameters`` 의 숫자를 JSON 숫자 그대로 통과시키고 문자열로 바꾸지 않도록
    규정하는데, pydantic v2의 JSON 모드는 ``Decimal`` 을 문자열로 내보내 그 규칙을 깬다.
    """

    model_config = ConfigDict(extra="forbid")

    short_window: int = Field(gt=0)
    long_window: int = Field(gt=0)
    threshold: float

    @model_validator(mode="after")
    def _windows_ordered(self) -> "MaCrossoverParameters":
        if self.short_window >= self.long_window:
            raise ValueError(
                "strategy.parameters.shortWindow must be smaller than longWindow; "
                f"got shortWindow={self.short_window}, longWindow={self.long_window}"
            )
        return self
