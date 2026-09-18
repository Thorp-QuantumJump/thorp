"""역변동성(FR-023). 최근 변동성이 낮은 종목에 더 많이 싣는다.

**규칙 값이 전부 임시다**(등록표 `provisional=true`, 결정 항목 PD-02). 관측 63거래일은 spec이
정했지만 0 변동성 처리·비중 상한·투자 비중 합계는 아직 팀 결정이 아니다. 그래서 이 모듈은 값을
하나도 갖고 있지 않고 전부 `allocation.rules` 에서 읽는다 — 값이 정해지면 등록표 행만 고치면 되고
코드는 그대로다.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from decimal import Decimal
from typing import Any

import pandas as pd

from worker.allocation.base import (
    INSUFFICIENT_HISTORY,
    ZERO,
    ZERO_VOLATILITY,
    AllocationError,
    AllocationResult,
    apply_cap,
    decimal_rule,
    effective_cap,
    int_rule,
    normalise,
    quantise,
    require_rule_value,
    volatility_of,
)
from worker.contracts.engine_input import UniverseItem
from worker.contracts.engine_output import ExceptionRecord, WeightsSource

STRATEGY_ID = "INVERSE_VOLATILITY"

#: ``rules.zeroVolatility`` 가 가질 수 있는 값. 모르는 값은 임의 해석하지 않고 거절한다.
EXCLUDE_THEN_ALLOCATE = "EXCLUDE_THEN_ALLOCATE"


def compute(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    parameters: Mapping[str, Any],
    rules: Mapping[str, Any],
    as_of: date,
) -> AllocationResult:
    lookback = int_rule(rules, "lookbackDays", STRATEGY_ID)
    zero_policy = require_rule_value(rules, "zeroVolatility", STRATEGY_ID)
    cap = effective_cap(parameters, rules, STRATEGY_ID)
    invested = decimal_rule(rules, "investedFraction", STRATEGY_ID)
    if zero_policy != EXCLUDE_THEN_ALLOCATE:
        raise AllocationError(
            "INVALID_REQUEST",
            f"allocation.rules.zeroVolatility={zero_policy!r} is not implemented;"
            f" this engine only knows {EXCLUDE_THEN_ALLOCATE} (PD-02)",
        )

    volatility, exceptions = _volatilities(universe, history, lookback, as_of)
    investable = {instrument: value for instrument, value in volatility.items() if value > ZERO}
    if not investable:
        # 전부 0 변동성이면 제외할 대상이 남지 않는다. 임의로 동일 비중을 주면 사용자가 고른 것과
        # 다른 전략을 돌린 것이 되므로 멈춘다.
        raise AllocationError(
            "CONSTRAINT_VIOLATION",
            f"every instrument has zero volatility on {as_of}; {STRATEGY_ID} has nothing to weight",
        )

    inverse = {instrument: Decimal(1) / value for instrument, value in investable.items()}
    weights = normalise(inverse, invested)
    # 상한 뒤에 다시 정규화하면 상한을 도로 깬다. 채우지 못한 몫은 현금으로 둔다(PD-03 6).
    weights, cap_exceptions = apply_cap(weights, cap, invested, rules, STRATEGY_ID, as_of)
    exceptions.extend(cap_exceptions)
    # 채워야 할 합계는 **정책**이 정한다(최소분산과 같은 식) — 정규화 결과의 합을 그대로 쓰면
    # Decimal 잔차로 1 에 미치지 못한 합이 목표가 되고, 단위 내림이 멀쩡한 100% 배분을
    # 99.9999% 로 깎는다.
    weights = quantise(weights, min(invested, cap * len(weights)), cap, rules, STRATEGY_ID)
    return AllocationResult(weights=weights, source=WeightsSource.COMPUTED, exceptions=exceptions)


def _volatilities(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    lookback: int,
    as_of: date,
) -> tuple[dict[str, Decimal], list[ExceptionRecord]]:
    """관측 구간의 일별 수익률 표준편차.

    구간이 모자란 종목은 변동성을 지어내지 않고 제외한다 — 짧은 표본으로 계산한 값은 다른 종목의
    값과 같은 척도가 아니어서 역수를 비교하는 순간 비중이 왜곡된다.
    """
    volatility: dict[str, Decimal] = {}
    exceptions: list[ExceptionRecord] = []
    for item in universe:
        instrument = item.instrument_id
        if instrument not in history.columns:
            continue
        value = volatility_of(history[instrument], lookback)
        if value is None:
            exceptions.append(
                ExceptionRecord(
                    date=as_of,
                    instrumentId=instrument,
                    kind=INSUFFICIENT_HISTORY,
                    rule=f"lookbackDays={lookback}",
                    action="제외 후 나머지에 배분",
                )
            )
            continue
        if value <= ZERO:
            exceptions.append(
                ExceptionRecord(
                    date=as_of,
                    instrumentId=instrument,
                    kind=ZERO_VOLATILITY,
                    rule=f"zeroVolatility={EXCLUDE_THEN_ALLOCATE}",
                    action="제외 후 나머지에 배분",
                )
            )
        volatility[instrument] = value
    return volatility, exceptions
