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
from decimal import ROUND_FLOOR, Decimal
from typing import Any, Protocol

import pandas as pd

from worker.contracts.engine_input import UniverseItem
from worker.contracts.engine_output import ExceptionRecord, WeightsSource

ZERO = Decimal(0)
ONE = Decimal(1)

#: 결과 계약 ``exceptions[].kind``. 개방 집합이다.
ZERO_VOLATILITY = "ZERO_VOLATILITY"
#: 상한에 걸려 어디에도 더 나눌 수 없는 몫을 현금으로 남겼다(PD-03 ⑥).
CAP_OVERFLOW_CASH = "CAP_OVERFLOW_CASH"
#: 계산 가능 종목이 모자라 투자 목표 자체를 낮췄다(PD-04 ⑦).
EFFECTIVE_TARGET_REDUCED = "EFFECTIVE_TARGET_REDUCED"
#: 계산 가능 종목이 하나뿐이라 최적화 없이 배분했다(PD-04 ⑦).
SINGLE_ASSET_FALLBACK = "SINGLE_ASSET_FALLBACK"

#: ``rules.capOverflow`` 가 가질 수 있는 값. 모르는 값은 임의 해석하지 않고 거절한다.
CASH = "CASH"


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


def int_parameter(parameters: Mapping[str, Any], key: str) -> int:
    """``parameters`` 의 정수 축을 읽는다. **정수로 떨어지는 표기면 전부 같은 값으로 본다.**

    계약 모델(pydantic)은 ``40``·``40.0``·``"40"`` 을 모두 40으로 받아들이는데, 계산 경로는 원본
    dict 를 그대로 보므로 여기서도 같게 읽지 않으면 **접수와 계약을 통과한 요청이 계산에서 죽는다.**
    소수부가 있는 값은 다른 값이므로 거절한다 — 40.5 를 40으로 깎으면 사용자가 고른 것과 다른
    조건으로 계산하게 된다.
    """
    raw = parameters[key]
    if isinstance(raw, bool):
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.parameters.{key} must be a number: {raw!r}"
        )
    try:
        value = Decimal(str(raw))
    except (ArithmeticError, ValueError) as exc:
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.parameters.{key} is not a number: {raw!r}"
        ) from exc
    if value != value.to_integral_value():
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.parameters.{key} must be a whole number: {raw!r}"
        )
    return int(value)


def effective_cap(
    parameters: Mapping[str, Any], rules: Mapping[str, Any], strategy_id: str
) -> Decimal:
    """이 실행에 실제로 적용하는 종목별 상한(PD-03 ⑥).

    **요청의 ``parameters.maxWeight`` 가 있으면 그 값(정수 %)을 100으로 나눈 것이고, 없으면 규칙표의
    ``weightCap`` 이다.** 사용자가 화면에서 고른 상한이 우선이고 규칙표 값은 그것을 고르지 않은
    요청의 기본값 역할을 한다. 접수 관문과 이 엔진이 같은 규칙으로 읽어야 "접수는 통과했는데 계산이
    조건을 못 지킨다"가 생기지 않는다.

    단위가 둘(% ↔ 소수)이라 변환은 여기 한 곳에서만 한다.
    """
    raw = parameters.get("maxWeight")
    if raw is None:
        return decimal_rule(rules, "weightCap", strategy_id)
    percent = int_parameter(parameters, "maxWeight")
    if not 0 < percent <= 100:
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.parameters.maxWeight must be in (0, 100]: {raw!r}"
        )
    return Decimal(percent) / Decimal(100)


def apply_cap(
    weights: dict[str, Decimal],
    cap: Decimal,
    invested: Decimal,
    rules: Mapping[str, Any],
    strategy_id: str,
    as_of: date,
) -> tuple[dict[str, Decimal], list[ExceptionRecord]]:
    """종목별 상한을 지키도록 비중을 다시 나누고, **더 나눌 곳이 없으면 남는 몫은 현금으로 둔다**.

    상한에 걸린 종목은 상한으로 고정하고 남은 몫을 나머지가 여유 비율대로 나눠 갖는다. 반복하는
    이유는 재분배가 다른 종목을 상한 위로 밀어 올릴 수 있기 때문이다. 상한 × 종목 수가 투자 비중에
    못 미쳐도 실행을 멈추지 않는다(PD-03 ⑥ ``capOverflow: CASH``) — 상한을 몰래 풀어 사용자가 고른
    조건과 다른 포트폴리오를 돌리는 것보다, 채우지 못한 몫을 현금으로 두고 **그 사실을 기록**하는
    쪽이 설명 가능하다. 접수 때 이미 알 수 있는 모순은 접수 관문이 422로 거절한다.
    """
    policy = require_rule_value(rules, "capOverflow", strategy_id)
    if policy != CASH:
        raise AllocationError(
            "INVALID_REQUEST",
            f"allocation.rules.capOverflow={policy!r} is not implemented;"
            f" this engine only knows {CASH} (PD-03 6)",
        )
    if cap <= ZERO:
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.rules.weightCap must be positive: {cap}"
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
    exceptions: list[ExceptionRecord] = []
    total = sum(capped.values(), ZERO)
    if total < invested:
        exceptions.append(
            ExceptionRecord(
                date=as_of,
                kind=CAP_OVERFLOW_CASH,
                rule=f"weightCap={cap} x {len(capped)} instruments < investedFraction={invested}",
                action=f"남은 {invested - total} 은 현금으로 보유",
            )
        )
    return capped, exceptions


def quantise(
    weights: dict[str, Decimal],
    total: Decimal,
    cap: Decimal,
    rules: Mapping[str, Any],
    strategy_id: str,
) -> dict[str, Decimal]:
    """비중을 고정 단위(``rules.weightUnit``)로 떨어뜨린다 — 모든 배분 전략의 마지막 단계.

    종목코드 순으로 단위 내림을 하고, 내림으로 잃은 단위를 소수부가 큰 순(동률이면 종목코드 순,
    이미 상한인 종목은 제외)으로 하나씩 돌려준다. 부동소수 계산이 환경마다 마지막 자리에서 갈리는
    것을 덮는 2차 장치다(1차는 실행환경 고정).

    ``total`` 은 **채워야 할 합계**이고 호출자가 정한다 — 상한을 적용한 뒤의 비중 합(역변동성·
    모멘텀)이거나 최적화 전에 고정한 유효 투자 목표(최소분산)다. 여기서 현금 잔여를 다시
    채우지 않는다.
    """
    unit = decimal_rule(rules, "weightUnit", strategy_id)
    if unit <= ZERO:
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.rules.weightUnit must be positive: {unit}"
        )
    keys = sorted(weights)
    units = {key: _floor_units(weights[key], unit) for key in keys}
    fraction = {key: weights[key] / unit - units[key] for key in keys}
    cap_units = _floor_units(cap, unit)
    leftover = _floor_units(total, unit) - sum(units.values())
    if leftover < 0:
        raise AllocationError(
            "CONSTRAINT_VIOLATION",
            f"{strategy_id} weights sum to more than the target {total} before rounding",
        )
    for key in sorted(keys, key=lambda k: (-fraction[k], k)):
        if leftover == 0:
            break
        if units[key] >= cap_units:
            continue
        units[key] += 1
        leftover -= 1
    if leftover != 0:
        raise AllocationError(
            "CONSTRAINT_VIOLATION",
            f"{strategy_id} cannot place {leftover} weight unit(s) of {unit} under cap {cap}",
        )
    result = {key: units[key] * unit for key in keys}
    _verify(result, _floor_units(total, unit) * unit, cap, strategy_id)
    return result


def _floor_units(value: Decimal, unit: Decimal) -> int:
    return int((value / unit).to_integral_value(rounding=ROUND_FLOOR))


def _verify(weights: dict[str, Decimal], total: Decimal, cap: Decimal, strategy_id: str) -> None:
    """고정 단위 출력이 지켜야 할 셋을 다시 확인한다. 어긋나면 숫자를 내보내지 않는다."""
    for key, value in weights.items():
        if value < ZERO:
            raise AllocationError(
                "CONSTRAINT_VIOLATION", f"{strategy_id} produced a negative weight for {key}"
            )
        if value > cap:
            raise AllocationError(
                "CONSTRAINT_VIOLATION",
                f"{strategy_id} weight {value} for {key} exceeds the cap {cap}",
            )
    actual = sum(weights.values(), ZERO)
    if actual != total:
        raise AllocationError(
            "CONSTRAINT_VIOLATION", f"{strategy_id} weights sum to {actual}, expected {total}"
        )


def normalise(weights: dict[str, Decimal], invested: Decimal) -> dict[str, Decimal]:
    """합이 투자 비중이 되도록 비례 조정한다. 전부 0이면 현금 100%다."""
    total = sum(weights.values())
    if total <= ZERO:
        return {key: ZERO for key in weights}
    return {key: value * invested / total for key, value in weights.items()}
