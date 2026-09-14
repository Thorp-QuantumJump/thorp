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
    ZERO,
    ZERO_VOLATILITY,
    AllocationError,
    AllocationResult,
    apply_cap,
    decimal_rule,
    int_rule,
    normalise,
    require_rule_value,
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
    cap = decimal_rule(rules, "weightCap", STRATEGY_ID)
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
    weights = apply_cap(weights, cap, invested, STRATEGY_ID)
    weights = normalise(weights, invested) if sum(weights.values()) != invested else weights
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
        closes = history[instrument].dropna()
        if len(closes) < lookback + 1:
            exceptions.append(
                ExceptionRecord(
                    date=as_of,
                    instrumentId=instrument,
                    kind="INSUFFICIENT_HISTORY",
                    rule=f"lookbackDays={lookback}",
                    action="제외 후 나머지에 배분",
                )
            )
            continue
        returns = closes.iloc[-(lookback + 1) :].pct_change().dropna()
        deviation = returns.std(ddof=1)
        value = ZERO if pd.isna(deviation) else Decimal(str(deviation))
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
