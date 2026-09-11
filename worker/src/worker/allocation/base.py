"""배분 모듈의 공통 타입(spec 004 T007).

배분은 **순수 함수**다. 조정 종가 이력과 규칙 명세를 받아 목표 비중을 돌려준다. 주문도 현금도
모르고, 같은 입력이면 언제 불러도 같은 답이 나온다 — 재사용(R-03)의 전제가 이것이다.

**규칙 값은 요청에 실려 온 명세에서만 읽는다.** 모듈이 기본값을 들고 있으면 등록표에 새 규칙
버전이 생겼을 때 옛 실행의 해석이 조용히 달라진다(R-08). 명세에 없는 값을 쓰려 하면 실행을 멈추고
왜 멈췄는지 말한다.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Any, Protocol

import pandas as pd

from worker.contracts.engine_input import UniverseItem
from worker.contracts.engine_output import ExceptionRecord, WeightsSource

ZERO = Decimal(0)
ONE = Decimal(1)

#: 결과 계약 ``exceptions[].kind``. 개방 집합이다.
ZERO_VOLATILITY = "ZERO_VOLATILITY"


class AllocationError(ValueError):
    """배분을 할 수 없다. ``code`` 가 그대로 엔진 오류 코드가 된다.

    ``INVALID_REQUEST`` 는 요청이 잘못됐다는 뜻이고 ``CONSTRAINT_VIOLATION`` 은 요청은
    말이 되지만 그 조건을 만족하는 배분이 존재하지 않는다는 뜻이다. 둘을 섞으면 사용자가
    무엇을 고쳐야 하는지 알 수 없다.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class AllocationResult:
    """목표 비중과 그것을 정하며 겪은 예외.

    :param weights: 종목 → 비중. 합은 투자 비중(기본 1)이고 나머지는 현금이다
    :param source: 사용자 지정(USER)인지 계산(COMPUTED)인지. 결과 화면이 이 값으로 설명을 바꾼다
    """

    weights: dict[str, Decimal]
    source: WeightsSource
    exceptions: list[ExceptionRecord] = field(default_factory=list)


class AllocationRule(Protocol):
    """전략 하나의 배분 규칙."""

    def __call__(
        self,
        universe: Sequence[UniverseItem],
        history: pd.DataFrame,
        parameters: Mapping[str, Any],
        rules: Mapping[str, Any],
        as_of: date,
    ) -> AllocationResult: ...


def require_rule_value(rules: Mapping[str, Any], key: str, strategy_id: str) -> Any:
    """규칙 명세에서 값을 읽는다. 없으면 기본값을 지어내지 않고 멈춘다."""
    if key not in rules or rules[key] is None:
        raise AllocationError(
            "INVALID_REQUEST",
            f"allocation.rules.{key} is required for {strategy_id};"
            " this engine has no default for it (spec 004 R-08)",
        )
    return rules[key]


def decimal_rule(rules: Mapping[str, Any], key: str, strategy_id: str) -> Decimal:
    raw = require_rule_value(rules, key, strategy_id)
    try:
        return Decimal(str(raw))
    except (ArithmeticError, ValueError) as exc:
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.rules.{key} is not a decimal: {raw!r}"
        ) from exc


def int_rule(rules: Mapping[str, Any], key: str, strategy_id: str) -> int:
    raw = require_rule_value(rules, key, strategy_id)
    if isinstance(raw, bool) or not isinstance(raw, int):
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.rules.{key} must be an integer: {raw!r}"
        )
    return raw


def apply_cap(
    weights: dict[str, Decimal], cap: Decimal, invested: Decimal, strategy_id: str
) -> dict[str, Decimal]:
    """종목별 상한을 지키도록 비중을 다시 나눈다.

    상한에 걸린 종목은 상한으로 고정하고 남은 몫을 나머지가 원래 비율대로 나눠 갖는다. 반복하는
    이유는 재분배가 다른 종목을 상한 위로 밀어 올릴 수 있기 때문이다. 상한 × 종목 수가 투자 비중에
    못 미치면 어떤 배분으로도 조건을 만족할 수 없으므로 ``CONSTRAINT_VIOLATION`` 이다 — 조용히
    미달로 두면 요청한 조건과 다른 포트폴리오의 성과를 그 조건의 성과로 보여 주게 된다.
    """
    if cap <= ZERO:
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.rules.weightCap must be positive: {cap}"
        )
    if cap * len(weights) < invested:
        raise AllocationError(
            "CONSTRAINT_VIOLATION",
            f"weightCap {cap} x {len(weights)} instruments cannot reach investedFraction"
            f" {invested} for {strategy_id}",
        )
    capped = dict(weights)
    for _ in range(len(weights)):
        over = {key: value for key, value in capped.items() if value > cap}
        if not over:
            break
        spill = sum(value - cap for value in over.values())
        free = {key: value for key, value in capped.items() if value <= cap}
        room = sum(cap - value for value in free.values())
        for key in over:
            capped[key] = cap
        if room <= ZERO:
            break
        for key, value in free.items():
            capped[key] = value + spill * (cap - value) / room
    return capped


def normalise(weights: dict[str, Decimal], invested: Decimal) -> dict[str, Decimal]:
    """합이 투자 비중이 되도록 비례 조정한다. 전부 0이면 현금 100%다."""
    total = sum(weights.values())
    if total <= ZERO:
        return {key: ZERO for key in weights}
    return {key: value * invested / total for key, value in weights.items()}
