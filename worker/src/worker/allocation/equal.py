"""동일 비중(FR-022). 투자 가능한 종목에 1/N."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from decimal import Decimal
from typing import Any

import pandas as pd

from worker.allocation.base import AllocationError, AllocationResult
from worker.contracts.engine_input import UniverseItem
from worker.contracts.engine_output import WeightsSource

STRATEGY_ID = "EQUAL_WEIGHT"


def compute(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    parameters: Mapping[str, Any],
    rules: Mapping[str, Any],
    as_of: date,
) -> AllocationResult:
    """1/N. 나누는 대상은 **그날 투자 가능한 종목**이다.

    투자 가능 판정은 그날 이력 값이 있는지로 한다 — 상장 전이거나 자료가 없는 종목에 비중을 주면
    살 수 없는 주식에 돈을 배정한 것이 된다. 종목 수가 달라지면 비중도 달라지는 것이 이 전략의
    정의다(FR-022).
    """
    investable = [
        item.instrument_id
        for item in universe
        if item.instrument_id in history.columns
        and len(history.index) > 0
        and pd.notna(history[item.instrument_id].iloc[-1])
    ]
    if not investable:
        raise AllocationError(
            "CONSTRAINT_VIOLATION", f"no investable instrument on {as_of} for {STRATEGY_ID}"
        )
    share = Decimal(1) / Decimal(len(investable))
    return AllocationResult(
        weights={instrument: share for instrument in investable},
        source=WeightsSource.COMPUTED,
    )
