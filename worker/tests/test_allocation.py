"""배분 규칙(spec 004 T007, FR-021·FR-022·FR-023, R-08).

두 가지를 지킨다. **엔진은 규칙 값을 스스로 갖지 않는다** — 값이 없으면 지어내지 않고 멈춘다.
그리고 **미확정 전략은 돌리지 않는다** — 그럴듯한 기본값으로 돌리면 나중에 규칙이 정해졌을 때
같은 요청이 다른 숫자를 내고, 그사이 사용자가 본 결과는 근거 없는 값이었던 것이 된다.
"""

from datetime import date
from decimal import Decimal

import pandas as pd
import pytest
from pydantic import ValidationError

from worker.allocation import compute_weights
from worker.allocation.base import AllocationError, effective_cap
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
    "capOverflow": "CASH",
    "weightUnit": "0.000001",
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
        {
            "strategyId": strategy_id,
            "rulesVersion": "1",
            "rules": rules,
            "parameters": {"rebalance": "MONTH_END"},
        }
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


def test_unreachable_cap_leaves_the_remainder_in_cash():
    """상한 x 종목 수가 투자 비중에 못 미쳐도 멈추지 않는다 — 못 채운 몫은 현금이고 사유를 남긴다.

    상한을 몰래 풀어 사용자가 고른 조건과 다른 포트폴리오를 돌리는 것보다 낫다(PD-03 6).
    """
    rules = {**INVERSE_RULES, "weightCap": "0.3000"}

    result = compute_weights(
        allocation("INVERSE_VOLATILITY", rules),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: [100.0, 101.0, 100.5, 102.0], BATTERY: [100.0, 99.0, 100.0, 98.0]}),
        AS_OF,
    )

    assert result.weights == {SEMI: Decimal("0.3"), BATTERY: Decimal("0.3")}
    assert [record.kind for record in result.exceptions] == ["CAP_OVERFLOW_CASH"]


def test_request_max_weight_overrides_the_registry_cap():
    """화면에서 고른 상한(정수 %)이 규칙표 기본값보다 앞선다(PD-03 6)."""
    spec = AllocationSpec.model_validate(
        {
            "strategyId": "INVERSE_VOLATILITY",
            "rulesVersion": "1",
            "rules": INVERSE_RULES,
            "parameters": {"rebalance": "MONTH_END", "maxWeight": 30},
        }
    )

    result = compute_weights(
        spec,
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: [100.0, 101.0, 100.5, 102.0], BATTERY: [100.0, 99.0, 100.0, 98.0]}),
        AS_OF,
    )

    assert max(result.weights.values()) == Decimal("0.3")
    assert [record.kind for record in result.exceptions] == ["CAP_OVERFLOW_CASH"]


def test_weights_land_on_the_fixed_output_unit():
    """세 종목 1/3 은 딱 떨어지지 않는다. 0.000001 단위로 내리고 남은 단위를 되돌려 준다."""
    flat = [100.0, 101.0, 100.0, 101.0]

    result = compute_weights(
        allocation("INVERSE_VOLATILITY", INVERSE_RULES),
        [item(SEMI), item(BATTERY), item(BIO)],
        history(**{SEMI: flat, BATTERY: flat, BIO: flat}),
        AS_OF,
    )

    assert sum(result.weights.values()) == Decimal(1)
    unit = Decimal("0.000001")
    assert all(weight % unit == 0 for weight in result.weights.values())


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


# --- 최소분산 ----------------------------------------------------------------

MIN_VARIANCE_RULES = {
    "lookbackDays": 3,
    "estimationMethod": "SAMPLE_COV",
    "weightCap": "1.0000",
    "capOverflow": "CASH",
    "weightUnit": "0.000001",
    "investedFraction": "1.0000",
    "weightsSource": "COMPUTED",
}


def min_variance(parameters: dict | None = None, **rule_overrides) -> AllocationSpec:
    return AllocationSpec.model_validate(
        {
            "strategyId": "MINIMUM_VARIANCE",
            "rulesVersion": "1",
            "rules": {**MIN_VARIANCE_RULES, **rule_overrides},
            "parameters": {"rebalance": "MONTH_END", **(parameters or {})},
        }
    )


def test_minimum_variance_leans_on_the_calmer_instrument():
    """AC5: 비중은 비음수·상한 이하이고 합이 투자 비중이다. 분산이 작은 쪽이 더 무겁다."""
    calm = [100.0, 100.5, 100.0, 100.5]
    wild = [100.0, 110.0, 95.0, 115.0]

    result = compute_weights(
        min_variance(),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: calm, BATTERY: wild}),
        AS_OF,
    )

    assert result.source is WeightsSource.COMPUTED
    assert all(weight >= 0 for weight in result.weights.values())
    assert max(result.weights.values()) <= Decimal(1)
    assert sum(result.weights.values()) == Decimal(1)
    assert result.weights[SEMI] > result.weights[BATTERY]


def test_cap_fixes_the_effective_target_before_optimising():
    """상한 40%·계산 가능 2개면 채울 수 있는 최대가 80%다. 나머지 20%는 현금이고 사유가 남는다."""
    result = compute_weights(
        min_variance({"maxWeight": 40}),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: [100.0, 100.5, 100.0, 100.5], BATTERY: [100.0, 110.0, 95.0, 115.0]}),
        AS_OF,
    )

    assert sum(result.weights.values()) == Decimal("0.8")
    assert max(result.weights.values()) <= Decimal("0.4")
    reduced = [record for record in result.exceptions if record.kind == "EFFECTIVE_TARGET_REDUCED"]
    assert len(reduced) == 1
    assert "0.8" in (reduced[0].action or "")


def test_single_computable_instrument_skips_the_optimiser():
    result = compute_weights(
        min_variance({"maxWeight": 40}),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: [100.0, 100.5, 100.0, 100.5], BATTERY: [100.0, 101.0]}),
        AS_OF,
    )

    assert result.weights == {SEMI: Decimal("0.4")}
    kinds = [record.kind for record in result.exceptions]
    assert kinds == ["INSUFFICIENT_HISTORY", "EFFECTIVE_TARGET_REDUCED", "SINGLE_ASSET_FALLBACK"]


def test_delisted_instrument_is_not_computable():
    """길이만 보면 관측 구간을 채웠지만 판단일 종가가 없는 종목(상장폐지)은 살 수 없다."""
    result = compute_weights(
        min_variance(),
        [item(SEMI), item(BATTERY)],
        history(
            **{
                SEMI: [100.0, 100.5, 100.0, 100.5, 100.2],
                BATTERY: [100.0, 110.0, 95.0, 115.0, float("nan")],
            }
        ),
        AS_OF,
    )

    assert BATTERY not in result.weights
    assert result.weights == {SEMI: Decimal(1)}


def test_no_computable_instrument_holds_cash():
    result = compute_weights(
        min_variance(),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: [100.0, 101.0], BATTERY: [100.0, 101.0]}),
        AS_OF,
    )

    assert result.weights == {}
    assert "EFFECTIVE_TARGET_REDUCED" in [record.kind for record in result.exceptions]


def test_unusable_covariance_fails_the_whole_run():
    """자료가 망가져 공분산이 유한값이 아니면 보정하지 않고 실행 전체를 실패시킨다(PD-04 3)."""
    with pytest.raises(AllocationError) as caught:
        compute_weights(
            min_variance(),
            [item(SEMI), item(BATTERY)],
            history(**{SEMI: [100.0, 0.0, 100.0, 101.0], BATTERY: [100.0, 110.0, 95.0, 115.0]}),
            AS_OF,
        )

    assert caught.value.code == "ESTIMATION_FAILED"


def test_minimum_variance_is_repeatable():
    """같은 입력이면 같은 답이다 — 솔버가 끼어도 마찬가지다(SC-002)."""
    prices = {
        SEMI: [100.0, 100.5, 100.0, 100.5],
        BATTERY: [100.0, 110.0, 95.0, 115.0],
        BIO: [100.0, 102.0, 101.0, 103.0],
    }
    universe = [item(SEMI), item(BATTERY), item(BIO)]

    first = compute_weights(min_variance(), universe, history(**prices), AS_OF)
    second = compute_weights(min_variance(), list(reversed(universe)), history(**prices), AS_OF)

    assert first.weights == second.weights


def test_user_lookback_beats_the_registry_default():
    """003 축이 오면 그 값으로 계산한다 — 받아 놓고 쓰지 않으면 고른 것과 다른 결과가 나온다."""
    prices = {
        SEMI: [100.0, 101.0, 100.0, 102.0, 101.0, 103.0],
        BATTERY: [100.0, 99.0, 101.0, 98.0, 102.0, 97.0],
    }
    universe = [item(SEMI), item(BATTERY)]

    chosen = compute_weights(min_variance({"lookbackDays": 3}), universe, history(**prices), AS_OF)
    same_by_rule = compute_weights(
        min_variance(**{"lookbackDays": 3}), universe, history(**prices), AS_OF
    )
    longer = compute_weights(min_variance({"lookbackDays": 5}), universe, history(**prices), AS_OF)

    assert chosen.weights == same_by_rule.weights, "축이 규칙표 기본값과 같으면 결과도 같다"
    assert chosen.weights != longer.weights, "관측 기간을 바꾸면 결과가 바뀌어야 한다"


def test_max_weight_accepts_the_same_shapes_the_contract_does():
    """계약이 40·40.0·"40" 을 모두 40으로 받으므로 계산도 같게 읽는다.

    계약은 봉투를 모델로 검증하지만 배분 모듈에는 **원본 dict** 가 그대로 간다. 두 곳이 다르게
    읽으면 접수와 계약을 통과한 요청이 계산에서 죽는다.
    """
    prices = {SEMI: [100.0, 100.5, 100.0, 100.5], BATTERY: [100.0, 110.0, 95.0, 115.0]}
    universe = [item(SEMI), item(BATTERY)]
    reference = compute_weights(min_variance({"maxWeight": 40}), universe, history(**prices), AS_OF)

    for equivalent in (40.0, "40"):
        result = compute_weights(
            min_variance({"maxWeight": equivalent}), universe, history(**prices), AS_OF
        )
        assert result.weights == reference.weights


def test_fractional_max_weight_is_rejected_by_both_layers():
    """40.5 는 40과 다른 값이다 — 깎아서 통과시키면 고른 것과 다른 조건으로 계산하게 된다."""
    with pytest.raises(ValidationError, match="maxWeight"):
        min_variance({"maxWeight": 40.5})

    with pytest.raises(AllocationError, match="whole number"):
        effective_cap({"maxWeight": 40.5}, MIN_VARIANCE_RULES, "MINIMUM_VARIANCE")


def test_singular_but_valid_covariance_still_computes():
    """완전히 같이 움직이는 두 종목의 공분산은 특이 행렬이다 — 그래도 계산은 된다.

    최소분산이 멈추는 것은 "역행렬이 없을 때"가 아니라 **믿을 수 없는 행렬일 때**다(비유한값이거나
    허용오차보다 큰 음의 고유값). 특이 행렬은 양의 준정부호라 최소 분산 해가 멀쩡히 존재한다.
    """
    moves = [100.0, 101.0, 100.0, 102.0]

    result = compute_weights(
        min_variance(),
        [item(SEMI), item(BATTERY)],
        history(**{SEMI: moves, BATTERY: [value * 2 for value in moves]}),
        AS_OF,
    )

    assert sum(result.weights.values()) == Decimal(1)
    assert all(weight >= 0 for weight in result.weights.values())


@pytest.mark.parametrize("missing", ["lookbackDays", "estimationMethod", "capOverflow"])
def test_minimum_variance_missing_rule_value_stops(missing):
    rules = {key: value for key, value in MIN_VARIANCE_RULES.items() if key != missing}

    with pytest.raises(AllocationError, match=missing):
        compute_weights(
            AllocationSpec.model_validate(
                {
                    "strategyId": "MINIMUM_VARIANCE",
                    "rulesVersion": "1",
                    "rules": rules,
                    "parameters": {"rebalance": "MONTH_END"},
                }
            ),
            [item(SEMI)],
            history(**{SEMI: [100.0, 101.0, 100.5, 102.0]}),
            AS_OF,
        )


# --- 레지스트리 ---------------------------------------------------------------


@pytest.mark.parametrize("strategy_id", ["SECTOR_MOMENTUM"])
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
