"""배분 규칙(spec 004 T007, FR-021·FR-022·FR-023, R-08).

두 가지를 지킨다. **엔진은 규칙 값을 스스로 갖지 않는다** — 값이 없으면 지어내지 않고 멈춘다.
그리고 **미확정 전략은 돌리지 않는다** — 그럴듯한 기본값으로 돌리면 나중에 규칙이 정해졌을 때
같은 요청이 다른 숫자를 내고, 그사이 사용자가 본 결과는 근거 없는 값이었던 것이 된다.
"""

from datetime import date
from decimal import Decimal

import pandas as pd
import pytest

from worker.allocation import compute_weights
from worker.allocation.base import AllocationError
from worker.contracts.engine_input import AllocationSpec, UniverseItem
from worker.contracts.engine_output import WeightsSource

SEMI = "091160.XKRX"
BATTERY = "305720.XKRX"
BIO = "244580.XKRX"
AS_OF = date(2024, 6, 28)

FIXED_RULES = {"targetWeights": "USER_SPECIFIED", "weightsSource": "USER"}
EQUAL_RULES = {"targetWeights": "EQUAL_AMONG_INVESTABLE", "weightsSource": "COMPUTED"}
INVERSE_RULES = {
    "lookbackDays": 3,
    "zeroVolatility": "EXCLUDE_THEN_ALLOCATE",
    "weightCap": "1.0000",
    "investedFraction": "1.0000",
    "weightsSource": "COMPUTED",
}


def item(instrument: str, weight: str | None = None) -> UniverseItem:
    payload = {"instrumentId": instrument}
    if weight is not None:
        payload["targetWeight"] = weight
    return UniverseItem.model_validate(payload)


def allocation(strategy_id: str, rules: dict | None) -> AllocationSpec:
    return AllocationSpec.model_validate(
        {"strategyId": strategy_id, "rulesVersion": "1", "rules": rules, "parameters": {}}
    )


def history(**columns: list[float]) -> pd.DataFrame:
    """조정 종가 이력. 짧은 계열은 앞쪽을 NaN 으로 채운다(상장이 늦은 종목과 같은 모양)."""
    length = max(len(values) for values in columns.values())
    index = pd.date_range("2024-06-01", periods=length, freq="D")
    padded = {
        name: [float("nan")] * (length - len(values)) + list(values)
        for name, values in columns.items()
    }
    return pd.DataFrame(padded, index=index)


# --- 고정 비중 ----------------------------------------------------------------


def test_fixed_weight_passes_user_weights_through():
    result = compute_weights(
        allocation("FIXED_WEIGHT", FIXED_RULES),
        [item(SEMI, "0.4"), item(BATTERY, "0.6")],
        history(**{SEMI: [1.0], BATTERY: [1.0]}),
        AS_OF,
    )

    assert result.source is WeightsSource.USER
    assert result.weights == {SEMI: Decimal("0.4"), BATTERY: Decimal("0.6")}


def test_fixed_weight_rejects_missing_weight():
    with pytest.raises(AllocationError) as caught:
        compute_weights(
            allocation("FIXED_WEIGHT", FIXED_RULES),
            [item(SEMI, "0.4"), item(BATTERY)],
            history(**{SEMI: [1.0], BATTERY: [1.0]}),
            AS_OF,
        )

    assert caught.value.code == "INVALID_REQUEST"


def test_fixed_weight_rejects_sum_other_than_one():
    """합이 1이 아니면 현금 비중이 사용자가 의도하지 않은 값이 된다."""
    with pytest.raises(AllocationError, match="sum to 1"):
        compute_weights(
            allocation("FIXED_WEIGHT", FIXED_RULES),
            [item(SEMI, "0.4"), item(BATTERY, "0.4")],
            history(**{SEMI: [1.0], BATTERY: [1.0]}),
            AS_OF,
        )


# --- 동일 비중 ----------------------------------------------------------------


def test_equal_weight_splits_among_investable():
    result = compute_weights(
        allocation("EQUAL_WEIGHT", EQUAL_RULES),
        [item(SEMI), item(BATTERY), item(BIO)],
        history(**{SEMI: [1.0], BATTERY: [1.0], BIO: [1.0]}),
        AS_OF,
    )

    assert result.source is WeightsSource.COMPUTED
    assert all(weight == Decimal(1) / Decimal(3) for weight in result.weights.values())


def test_equal_weight_skips_instruments_without_data():
    """상장 전이거나 자료가 없는 종목에 비중을 주면 살 수 없는 주식에 돈을 배정한 것이 된다."""
    result = compute_weights(
        allocation("EQUAL_WEIGHT", EQUAL_RULES),
        [item(SEMI), item(BIO)],
        history(**{SEMI: [1.0], BIO: [float("nan")]}),
        AS_OF,
    )

    assert set(result.weights) == {SEMI}
    assert result.weights[SEMI] == Decimal(1)


def test_equal_weight_without_any_investable_stops():
    with pytest.raises(AllocationError) as caught:
        compute_weights(
            allocation("EQUAL_WEIGHT", EQUAL_RULES),
            [item(SEMI)],
            history(**{SEMI: [float("nan")]}),
            AS_OF,
        )

    assert caught.value.code == "CONSTRAINT_VIOLATION"


# --- 역변동성 ----------------------------------------------------------------


def test_inverse_volatility_favours_the_calmer_instrument():
    calm = [100.0, 100.5, 101.0, 101.5]
    wild = [100.0, 110.0, 95.0, 115.0]

    result = compute_weights(
        allocation("INVERSE_VOLATILITY", INVERSE_RULES),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: calm, BATTERY: wild}),
        AS_OF,
    )

    assert result.source is WeightsSource.COMPUTED
    assert result.weights[SEMI] > result.weights[BATTERY]
    assert sum(result.weights.values()) == pytest.approx(Decimal(1))


def test_zero_volatility_instrument_is_excluded_and_recorded():
    flat = [100.0, 100.0, 100.0, 100.0]
    moving = [100.0, 101.0, 100.0, 102.0]

    result = compute_weights(
        allocation("INVERSE_VOLATILITY", INVERSE_RULES),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: flat, BATTERY: moving}),
        AS_OF,
    )

    assert result.weights[BATTERY] == Decimal(1)
    assert result.weights.get(SEMI, Decimal(0)) == 0
    kinds = [record.kind for record in result.exceptions]
    assert "ZERO_VOLATILITY" in kinds, "제외했다는 사실을 결과에 남겨야 한다"


def test_short_history_is_excluded_with_a_reason():
    result = compute_weights(
        allocation("INVERSE_VOLATILITY", INVERSE_RULES),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: [100.0, 101.0, 100.5, 102.0], BATTERY: [100.0, 101.0]}),
        AS_OF,
    )

    assert set(result.weights) == {SEMI}
    assert [record.kind for record in result.exceptions] == ["INSUFFICIENT_HISTORY"]


def test_exactly_enough_history_is_included():
    """경계: 수익률 lookback 개를 만들려면 종가가 lookback+1 개 필요하다."""
    lookback = INVERSE_RULES["lookbackDays"]
    just_enough = [100.0 + index for index in range(lookback + 1)]

    result = compute_weights(
        allocation("INVERSE_VOLATILITY", INVERSE_RULES),
        [item(SEMI)],
        history(**{SEMI: just_enough}),
        AS_OF,
    )

    assert set(result.weights) == {SEMI}
    assert result.exceptions == []


def test_one_day_short_is_excluded_and_the_rest_renormalises():
    """경계 반대쪽: 딱 하루 모자란 종목만 빠지고 남은 종목이 투자 비중을 채운다."""
    lookback = INVERSE_RULES["lookbackDays"]
    enough = [100.0, 101.0, 100.5, 102.0]
    one_short = [100.0 + index for index in range(lookback)]

    result = compute_weights(
        allocation("INVERSE_VOLATILITY", INVERSE_RULES),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: enough, BATTERY: one_short}),
        AS_OF,
    )

    assert set(result.weights) == {SEMI}
    assert result.weights[SEMI] == Decimal(1), "남은 종목이 투자 비중을 전부 가져간다"
    assert [(record.kind, record.instrument_id) for record in result.exceptions] == [
        ("INSUFFICIENT_HISTORY", BATTERY)
    ]


def test_weight_cap_is_enforced():
    calm = [100.0, 100.1, 100.2, 100.3]
    wild = [100.0, 110.0, 95.0, 115.0]
    rules = {**INVERSE_RULES, "weightCap": "0.6000"}

    result = compute_weights(
        allocation("INVERSE_VOLATILITY", rules),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: calm, BATTERY: wild}),
        AS_OF,
    )

    assert result.weights[SEMI] <= Decimal("0.6")
    assert sum(result.weights.values()) == pytest.approx(Decimal(1))


def test_unreachable_cap_is_a_constraint_violation():
    """상한 x 종목 수가 투자 비중에 못 미치면 어떤 배분도 조건을 만족하지 못한다."""
    rules = {**INVERSE_RULES, "weightCap": "0.3000"}

    with pytest.raises(AllocationError) as caught:
        compute_weights(
            allocation("INVERSE_VOLATILITY", rules),
            [item(SEMI), item(BATTERY)],
            history(**{SEMI: [100.0, 101.0, 100.5, 102.0], BATTERY: [100.0, 99.0, 100.0, 98.0]}),
            AS_OF,
        )

    assert caught.value.code == "CONSTRAINT_VIOLATION"


@pytest.mark.parametrize(
    "missing", ["lookbackDays", "zeroVolatility", "weightCap", "investedFraction"]
)
def test_missing_rule_value_stops_instead_of_guessing(missing):
    rules = {key: value for key, value in INVERSE_RULES.items() if key != missing}

    with pytest.raises(AllocationError, match=missing):
        compute_weights(
            allocation("INVERSE_VOLATILITY", rules),
            [item(SEMI)],
            history(**{SEMI: [100.0, 101.0, 100.5, 102.0]}),
            AS_OF,
        )


def test_unknown_zero_volatility_policy_is_rejected():
    rules = {**INVERSE_RULES, "zeroVolatility": "SPREAD_EVENLY"}

    with pytest.raises(AllocationError, match="not implemented"):
        compute_weights(
            allocation("INVERSE_VOLATILITY", rules),
            [item(SEMI)],
            history(**{SEMI: [100.0, 101.0, 100.5, 102.0]}),
            AS_OF,
        )


# --- 레지스트리 ---------------------------------------------------------------


@pytest.mark.parametrize("strategy_id", ["SECTOR_MOMENTUM", "MINIMUM_VARIANCE"])
def test_pending_decision_strategies_say_so(strategy_id):
    """팀 결정 대기와 엔진 미구현은 사용자가 할 일이 다르다 — 메시지도 달라야 한다."""
    with pytest.raises(AllocationError) as caught:
        compute_weights(
            allocation(strategy_id, {"anything": 1}),
            [item(SEMI)],
            history(**{SEMI: [100.0]}),
            AS_OF,
        )

    assert caught.value.code == "INVALID_REQUEST"
    assert "RULES_NOT_REGISTERED" in str(caught.value)
    assert "not decided yet" in str(caught.value)


def test_unknown_strategy_is_rejected_without_a_decision_reference():
    with pytest.raises(AllocationError) as caught:
        compute_weights(
            allocation("MA_CROSSOVER", {"anything": 1}),
            [item(SEMI)],
            history(**{SEMI: [100.0]}),
            AS_OF,
        )

    assert "RULES_NOT_REGISTERED" in str(caught.value)
    assert "not decided yet" not in str(caught.value)


@pytest.mark.parametrize("rules", [None, {}])
def test_empty_rules_are_rejected(rules):
    """규칙 사본이 없으면 엔진이 값을 지어내야 한다. 그러느니 멈춘다."""
    with pytest.raises(AllocationError, match="RULES_NOT_REGISTERED"):
        compute_weights(
            allocation("EQUAL_WEIGHT", rules),
            [item(SEMI)],
            history(**{SEMI: [100.0]}),
            AS_OF,
        )
