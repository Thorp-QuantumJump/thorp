"""고정 비중(FR-021). 사용자가 지정한 비중을 그대로 쓴다."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from decimal import Decimal
from typing import Any

import pandas as pd

from worker.allocation.base import AllocationError, AllocationResult
from worker.contracts.engine_input import UniverseItem
from worker.contracts.engine_output import WeightsSource

STRATEGY_ID = "FIXED_WEIGHT"


def compute(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    parameters: Mapping[str, Any],
    rules: Mapping[str, Any],
    as_of: date,
) -> AllocationResult:
    """지정 비중을 그대로 돌려준다. 계산하지 않으므로 이력도 규칙도 보지 않는다.

    비중 합이 1인지는 접수(백엔드)가 이미 확인했다. 여기서 다시 보는 이유는 워커가 백엔드를 거치지
    않고도 돌 수 있기 때문이고, 무엇보다 **합이 1이 아닌 채로 계산하면 현금 비중이 사용자가
    의도하지 않은 값이 된다**.
    """
    missing = [item.instrument_id for item in universe if item.target_weight is None]
    if missing:
        raise AllocationError(
            "INVALID_REQUEST",
            f"{STRATEGY_ID} requires targetWeight on every universe item; missing: {missing}",
        )
    weights = {item.instrument_id: item.target_weight for item in universe}
    total = sum(weights.values())
    if total != Decimal(1):
        raise AllocationError(
            "INVALID_REQUEST", f"targetWeight must sum to 1 for {STRATEGY_ID}: {total}"
        )
    return AllocationResult(weights=weights, source=WeightsSource.USER)
