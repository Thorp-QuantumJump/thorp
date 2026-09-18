"""섹터 모멘텀(FR-024, PD-03 확정 2026-09-17). 최근에 더 오른 섹터를 골라 배분한다.

네 단계다 — **점수 → 선정 → 배분 → 공통 출력**. 규칙 값은 하나도 갖고 있지 않고 전부
``allocation.rules`` 와 003 축(``allocation.parameters``)에서 읽는다(R-08).

읽는 값 가운데 **사용자가 화면에서 고르는 것은 축**(``lookbackDays``·``skipRecentDays``·
``selectionMode``·``topN``·``threshold``·``topPercent``·``allocationMethod``·``maxWeight``)이고,
규칙표 값은 축을 고르지 않은 요청의 기본값 역할이다. 최소분산·역변동성과 같은 규칙이다.

**적용한 모멘텀 유형은 규칙표가 소유한다.** ``rules.selectionTypes`` 가 선정 방식마다 상대
(``RELATIVE``)·절대(``ABSOLUTE``)·선별 없음(``NONE``)을 적어 두고, 실행에는 규칙 버전과 고른
``selectionMode`` 가 함께 기록되므로 둘을 맞추면 그 실행이 어느 유형이었는지 확정된다(AC4).
유형을 결과 계약의 새 필드로 두지 않은 이유는 그것이 이미 기록된 두 값의 함수이기 때문이다.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from datetime import date
from decimal import Decimal
from typing import Any

import pandas as pd

from worker.allocation.base import (
    CASH,
    INSUFFICIENT_HISTORY,
    NO_SELECTION,
    ZERO,
    ZERO_VOLATILITY,
    AllocationError,
    AllocationResult,
    apply_cap,
    decimal_rule,
    effective_cap,
    int_parameter,
    int_rule,
    normalise,
    quantise,
    require_rule_value,
    volatility_of,
)
from worker.contracts.engine_input import UniverseItem
from worker.contracts.engine_output import ExceptionRecord, WeightsSource

STRATEGY_ID = "SECTOR_MOMENTUM"

#: ``rules.scoreMethod``. 관측 구간의 단순 수익률 하나다(PD-03 ①).
SIMPLE_RETURN = "SIMPLE_RETURN"
#: ``rules.priceField``. 신호는 조정 종가, 체결은 원시 가격(PD-03 ②·FR-013).
ADJUSTED_CLOSE = "ADJUSTED_CLOSE"
#: ``rules.tieBreak``. 동점은 대표 ETF 종목코드 오름차순으로 자른다(PD-03 ④).
INSTRUMENT_ID_ASC = "INSTRUMENT_ID_ASC"
#: ``rules.topPercentRounding``. 계산 가능 후보 수에 올림(PD-03 ④).
CEIL = "CEIL"
#: ``rules.noSelection``. 조건을 통과한 섹터가 없으면 전액 현금(PD-03 ③).
CASH_WHEN_NONE = "CASH"
#: ``rules.insufficientHistory``. 그 시점만 빼고 이력이 쌓이면 자동 편입(PD-03 ⑤).
EXCLUDE_THEN_INCLUDE = "EXCLUDE_THEN_INCLUDE"

#: ``parameters.selectionMode``.
TOP_N = "TOP_N"
THRESHOLD = "THRESHOLD"
TOP_PERCENT = "TOP_PERCENT"
ALL = "ALL"

#: ``parameters.allocationMethod``. 선정한 섹터에 나누는 방식(PD-03 ⓪).
EQUAL = "EQUAL"
INVERSE_VOLATILITY = "INVERSE_VOLATILITY"

#: ``rules.inverseVolAllocation.lookbackSource``. 모멘텀의 관측 길이를 그대로 쓴다.
MOMENTUM_LOOKBACK = "MOMENTUM_LOOKBACK"
#: ``rules.inverseVolAllocation.zeroVolatility``. 독립 역변동성과 같은 값이다.
EXCLUDE_THEN_ALLOCATE = "EXCLUDE_THEN_ALLOCATE"

_PERCENT = Decimal(100)


def compute(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    parameters: Mapping[str, Any],
    rules: Mapping[str, Any],
    as_of: date,
) -> AllocationResult:
    lookback = _axis_int(parameters, rules, "lookbackDays")
    skip = _axis_int(parameters, rules, "skipRecentDays")
    mode = _axis_value(parameters, rules, "selectionMode")
    method = _axis_value(parameters, rules, "allocationMethod")
    cap = effective_cap(parameters, rules, STRATEGY_ID)
    invested = decimal_rule(rules, "investedFraction", STRATEGY_ID)
    _require(rules, "scoreMethod", SIMPLE_RETURN, "PD-03 1")
    _require(rules, "priceField", ADJUSTED_CLOSE, "PD-03 2")
    _require(rules, "tieBreak", INSTRUMENT_ID_ASC, "PD-03 4")
    _require(rules, "topPercentRounding", CEIL, "PD-03 4")
    _require(rules, "noSelection", CASH_WHEN_NONE, "PD-03 3")
    _require(rules, "insufficientHistory", EXCLUDE_THEN_INCLUDE, "PD-03 5")
    _require(rules, "capOverflow", CASH, "PD-03 6")
    if skip < 0:
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.parameters.skipRecentDays must not be negative: {skip}"
        )

    scores, exceptions = _scores(universe, history, lookback, skip, as_of)
    if not scores:
        # 이력이 모자라 점수를 만든 섹터가 하나도 없다. 위에 INSUFFICIENT_HISTORY 가 이미 쌓였고
        # 그것이 ③(조건 미통과)과 구분되는 사유다(PD-03 ⑤). 현금 100%로 그 시점을 넘긴다.
        return AllocationResult(weights={}, source=WeightsSource.COMPUTED, exceptions=exceptions)

    selected = _select(scores, mode, parameters, rules)
    if not selected:
        # 점수는 다 만들었는데 조건을 통과한 섹터가 없다 — 실질적으로 THRESHOLD 뿐이다.
        # 억지로 제일 나은 하나를 사지 않는다(PD-03 ③).
        exceptions.append(
            ExceptionRecord(
                date=as_of,
                kind=NO_SELECTION,
                rule=f"selectionMode={mode}, candidates={len(scores)}",
                action="전액 현금으로 보유",
            )
        )
        return AllocationResult(weights={}, source=WeightsSource.COMPUTED, exceptions=exceptions)

    weights, allocation_exceptions = _allocate(
        selected, scores, history, method, lookback, invested, rules, as_of
    )
    exceptions.extend(allocation_exceptions)
    if not weights:
        raise AllocationError(
            "CONSTRAINT_VIOLATION",
            f"{STRATEGY_ID} selected {len(selected)} sector(s) on {as_of}"
            " but none could be weighted",
        )
    weights, cap_exceptions = apply_cap(weights, cap, invested, rules, STRATEGY_ID, as_of)
    exceptions.extend(cap_exceptions)
    # 채워야 할 합계는 정책이 정한다(역변동성·최소분산과 같은 식). 비중의 합에서 역산하면
    # Decimal 잔차가 단위 하나만큼 목표를 깎는다.
    weights = quantise(weights, min(invested, cap * len(weights)), cap, rules, STRATEGY_ID)
    return AllocationResult(weights=weights, source=WeightsSource.COMPUTED, exceptions=exceptions)


def _scores(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    lookback: int,
    skip: int,
    as_of: date,
) -> tuple[dict[str, Decimal], list[ExceptionRecord]]:
    """관측 구간의 단순 수익률 ``P(t-S) / P(t-S-L) - 1``.

    필요한 이력은 **``L + S + 1`` 거래일**이다 — 독립 역변동성의 ``L + 1`` 과 다르다. 최근 구간을
    건너뛴 만큼 더 앞을 봐야 하기 때문이고, 이 기준을 맞추지 않으면 최근 제외를 크게 잡은 요청이
    조용히 짧은 구간으로 계산된다.

    모자란 섹터는 점수를 지어내지 않고 그 시점에서만 뺀다. 이력이 쌓이면 다음 리밸런싱부터 다시
    들어온다(PD-03 ⑤) — 미래 자료를 당겨 쓰지 않으므로 편입 시점이 저절로 정해진다.
    """
    scores: dict[str, Decimal] = {}
    exceptions: list[ExceptionRecord] = []
    needed = lookback + skip + 1
    for item in universe:
        instrument = item.instrument_id
        if instrument not in history.columns:
            continue
        closes = history[instrument].dropna()
        if len(closes) < needed:
            exceptions.append(
                ExceptionRecord(
                    date=as_of,
                    instrumentId=instrument,
                    kind=INSUFFICIENT_HISTORY,
                    rule=f"lookbackDays={lookback}, skipRecentDays={skip} (needs {needed})",
                    action="이 시점만 제외, 이력이 쌓이면 자동 편입",
                )
            )
            continue
        end = Decimal(str(closes.iloc[-1 - skip]))
        start = Decimal(str(closes.iloc[-1 - skip - lookback]))
        if start <= ZERO:
            raise AllocationError(
                "CONSTRAINT_VIOLATION",
                f"non-positive adjusted close for {instrument} in the {STRATEGY_ID}"
                f" observation window ending {as_of}",
            )
        scores[instrument] = end / start - 1
    return scores, exceptions


def _select(
    scores: Mapping[str, Decimal],
    mode: str,
    parameters: Mapping[str, Any],
    rules: Mapping[str, Any],
) -> list[str]:
    """선정 방식 **하나만** 적용한다(PD-03 ①). 상대 조건과 절대 조건을 겹쳐 쓰지 않는다.

    순위를 매기는 방식(``TOP_N``·``TOP_PERCENT``)은 점수 내림차순, 동점이면 종목코드 오름차순으로
    자른다 — 같은 입력이 언제나 같은 결과를 내야 하고(FR-004), 동점을 다 담으면 사용자가 고른
    선정 수를 넘긴다. 자를 필요가 없는 ``THRESHOLD``·``ALL`` 은 조건을 만족한 동점을 모두 담는다.
    """
    ranked = sorted(scores, key=lambda instrument: (-scores[instrument], instrument))
    if mode == TOP_N:
        return ranked[: _axis_int(parameters, rules, "topN")]
    if mode == TOP_PERCENT:
        percent = _axis_int(parameters, rules, "topPercent")
        # 올림은 **그 시점에 계산 가능한 후보 수**에 적용한다. 전체 섹터 수에 적용하면 이력 부족으로
        # 후보가 줄어든 구간에서 있지도 않은 섹터 자리를 세게 된다.
        count = math.ceil(len(ranked) * percent / 100)
        return ranked[: max(1, count)]
    if mode == THRESHOLD:
        # 003 축의 단위가 %(수익률)라 소수로 바꿔 비교한다.
        threshold = Decimal(str(_axis_number(parameters, rules, "threshold"))) / _PERCENT
        return [instrument for instrument in ranked if scores[instrument] >= threshold]
    if mode == ALL:
        return ranked
    raise AllocationError(
        "INVALID_REQUEST",
        f"allocation.parameters.selectionMode={mode!r} is not implemented;"
        f" this engine knows {TOP_N}, {TOP_PERCENT}, {THRESHOLD}, {ALL} (PD-03 1)",
    )


def _allocate(
    selected: Sequence[str],
    scores: Mapping[str, Decimal],
    history: pd.DataFrame,
    method: str,
    lookback: int,
    invested: Decimal,
    rules: Mapping[str, Any],
    as_of: date,
) -> tuple[dict[str, Decimal], list[ExceptionRecord]]:
    """선정한 섹터에 나눈다 — 똑같이, 또는 변동성이 낮은 쪽에 더(PD-03 ⓪)."""
    if method == EQUAL:
        share = invested / Decimal(len(selected))
        return {instrument: share for instrument in selected}, []
    if method != INVERSE_VOLATILITY:
        raise AllocationError(
            "INVALID_REQUEST",
            f"allocation.parameters.allocationMethod={method!r} is not implemented;"
            f" this engine knows {EQUAL} and {INVERSE_VOLATILITY} (PD-03 0)",
        )

    spec = require_rule_value(rules, "inverseVolAllocation", STRATEGY_ID)
    if not isinstance(spec, Mapping):
        raise AllocationError(
            "INVALID_REQUEST", f"allocation.rules.inverseVolAllocation must be an object: {spec!r}"
        )
    _require(spec, "lookbackSource", MOMENTUM_LOOKBACK, "PD-03 0")
    _require(spec, "zeroVolatility", EXCLUDE_THEN_ALLOCATE, "PD-03 0")

    exceptions: list[ExceptionRecord] = []
    inverse: dict[str, Decimal] = {}
    for instrument in selected:
        # 관측 길이는 모멘텀의 것을 그대로 쓰고 **최근 구간 제외는 적용하지 않는다**. 점수에서
        # 최근을 빼는 이유는 단기 반전을 피하려는 것이고, 변동성은 위험 추정이라 가장 최근 자료가
        # 가장 중요하다 — S 를 여기 적용하면 한 달 묵은 위험으로 배분하게 된다.
        value = volatility_of(history[instrument], lookback)
        if value is None or value <= ZERO:
            # 점수를 만든 섹터라 이력은 충분하다. 여기 걸리는 것은 변동성 0 뿐이고,
            # 독립 역변동성과 같이 제외한 뒤 나머지에 나눈다.
            exceptions.append(
                ExceptionRecord(
                    date=as_of,
                    instrumentId=instrument,
                    kind=ZERO_VOLATILITY,
                    rule=f"inverseVolAllocation.zeroVolatility={EXCLUDE_THEN_ALLOCATE}",
                    action="제외 후 나머지 선정 섹터에 배분",
                )
            )
            continue
        inverse[instrument] = Decimal(1) / value
    if not inverse:
        # 선정한 섹터가 전부 변동성 0 이다. 임의로 동일 비중을 주면 사용자가 고른 것과 다른
        # 배분을 돌린 것이 된다(독립 역변동성과 같은 판단).
        raise AllocationError(
            "CONSTRAINT_VIOLATION",
            f"every selected sector has zero volatility on {as_of};"
            f" {STRATEGY_ID} has nothing to weight",
        )
    _ = scores  # 배분은 점수를 쓰지 않는다 — 점수비례는 PD-03 ⓪에서 뺐다.
    return normalise(inverse, invested), exceptions


def _axis_int(parameters: Mapping[str, Any], rules: Mapping[str, Any], key: str) -> int:
    """003 축이 오면 그것이 사용자가 고른 값이고, 없으면 규칙표 값이 기본값이다."""
    if parameters.get(key) is not None:
        return int_parameter(parameters, key)
    return int_rule(rules, key, STRATEGY_ID)


def _axis_value(parameters: Mapping[str, Any], rules: Mapping[str, Any], key: str) -> Any:
    if parameters.get(key) is not None:
        return parameters[key]
    return require_rule_value(rules, key, STRATEGY_ID)


def _axis_number(parameters: Mapping[str, Any], rules: Mapping[str, Any], key: str) -> Any:
    """``threshold`` 는 음수·소수가 될 수 있어 정수 축과 따로 읽는다."""
    if parameters.get(key) is not None:
        raw = parameters[key]
        if isinstance(raw, bool) or not isinstance(raw, (int, float, str, Decimal)):
            raise AllocationError(
                "INVALID_REQUEST", f"allocation.parameters.{key} must be a number: {raw!r}"
            )
        return raw
    return require_rule_value(rules, key, STRATEGY_ID)


def _require(rules: Mapping[str, Any], key: str, expected: str, decision: str) -> None:
    """엔진이 아는 값 하나만 받는다. 모르는 값을 만나면 임의 해석하지 않고 멈춘다."""
    value = require_rule_value(rules, key, STRATEGY_ID)
    if value != expected:
        raise AllocationError(
            "INVALID_REQUEST",
            f"allocation.rules.{key}={value!r} is not implemented;"
            f" this engine only knows {expected} ({decision})",
        )
