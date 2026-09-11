"""배분 규칙 레지스트리(spec 004 T007).

전략을 추가할 때 손댈 곳은 이 파일의 한 줄과 모듈 하나다. 엔진(T006)은 ``compute_weights`` 만
부르고 어떤 전략이 있는지 모른다.

**여기 없는 전략은 임의로 계산하지 않는다.** 팀이 규칙을 확정하지 않은 전략
(``SECTOR_MOMENTUM``·``MINIMUM_VARIANCE``, PD-03·PD-04)을 그럴듯한 기본값으로 돌리면, 나중에
규칙이 정해졌을 때 같은 요청이 다른 숫자를 내고 그 차이를 아무도 설명할 수 없다. 실행을 멈추고
왜 멈췄는지 말한다(FR-010).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from typing import Any

import pandas as pd

from worker.allocation import equal, fixed, inverse_vol
from worker.allocation.base import (
    AllocationError,
    AllocationResult,
    AllocationRule,
)
from worker.contracts.engine_input import AllocationSpec, UniverseItem
from worker.rules_registry import rules_not_registered_message

#: ``allocation.strategyId`` → 배분 함수.
ALLOCATION_RULES: dict[str, AllocationRule] = {
    fixed.STRATEGY_ID: fixed.compute,
    equal.STRATEGY_ID: equal.compute,
    inverse_vol.STRATEGY_ID: inverse_vol.compute,
}


def compute_weights(
    allocation: AllocationSpec,
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    as_of: date,
) -> AllocationResult:
    """리밸런싱 판단일의 목표 비중.

    :param history: 조정 종가 이력(index=거래일, columns=종목). **판단일까지만** 담겨 있어야
        한다 — 뒷날 값이 섞여 들어오면 미래를 본 신호가 된다
    :param as_of: 판단일. 예외 기록의 날짜로 쓴다
    """
    rule = ALLOCATION_RULES.get(allocation.strategy_id)
    if rule is None:
        # 팀 결정 대기인지 엔진 미구현인지를 구분해 말한다 — 사용자가 할 일이 다르다.
        raise AllocationError(
            "INVALID_REQUEST", rules_not_registered_message(allocation.strategy_id)
        )
    rules: Mapping[str, Any] = allocation.rules or {}
    if not rules:
        raise AllocationError(
            "INVALID_REQUEST",
            f"RULES_NOT_REGISTERED: allocation.rules is empty for {allocation.strategy_id};"
            " the engine does not carry its own rule values (spec 004 R-08)",
        )
    return rule(universe, history, allocation.parameters, rules, as_of)


__all__ = [
    "ALLOCATION_RULES",
    "AllocationError",
    "AllocationResult",
    "compute_weights",
]
