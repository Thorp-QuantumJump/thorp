"""전략별 ``allocation.parameters`` 스키마와 ``AllocationSpec`` 타입 접근자."""

import json
from typing import Any

import pytest
from conftest import FIXTURE_DIR
from pydantic import ConfigDict, ValidationError

from worker.contracts.base import ContractModel
from worker.contracts.engine_input import AllocationSpec, EngineInput
from worker.contracts.strategy_params import STRATEGY_PARAMETERS, RebalanceParameters


def spec(strategy_id: str, parameters: dict[str, Any]) -> dict[str, Any]:
    return {"strategyId": strategy_id, "rulesVersion": "1", "parameters": parameters}


def test_parameter_model_keeps_camel_alias_and_forbids_extra() -> None:
    """자식 model_config가 부모의 alias_generator·populate_by_name을 유지한다."""

    class Probe(ContractModel):
        model_config = ConfigDict(extra="forbid")

        lookback_days: int

    assert Probe.model_config["extra"] == "forbid"
    assert Probe.model_validate({"lookbackDays": 5}).lookback_days == 5
    assert Probe.model_validate({"lookback_days": 5}).lookback_days == 5


def test_registry_covers_the_registered_allocation_strategies() -> None:
    """미확정 전략(PD-03)은 파라미터 모양도 미정이라 등록하지 않는다."""
    assert STRATEGY_PARAMETERS == {
        "FIXED_WEIGHT": RebalanceParameters,
        "EQUAL_WEIGHT": RebalanceParameters,
        "INVERSE_VOLATILITY": RebalanceParameters,
        "MINIMUM_VARIANCE": RebalanceParameters,
    }
    assert "SECTOR_MOMENTUM" not in STRATEGY_PARAMETERS


def test_max_weight_is_an_integer_percent_and_optional() -> None:
    """003 축이 정수 %다. 소수·범위 밖은 조용히 고쳐지지 않고 거절된다(T022)."""
    typed = AllocationSpec.model_validate(
        spec("MINIMUM_VARIANCE", {"rebalance": "MONTH_END", "maxWeight": 40})
    )
    assert typed.typed_parameters.max_weight == 40
    assert (
        AllocationSpec.model_validate(
            spec("MINIMUM_VARIANCE", {"rebalance": "MONTH_END"})
        ).typed_parameters.max_weight
        is None
    )
    for rejected in (0, 101, 0.4):
        with pytest.raises(ValidationError, match="maxWeight"):
            AllocationSpec.model_validate(
                spec("INVERSE_VOLATILITY", {"rebalance": "MONTH_END", "maxWeight": rejected})
            )


@pytest.mark.parametrize("strategy_id", sorted(STRATEGY_PARAMETERS))
def test_month_end_rebalance_is_the_only_approved_cadence(strategy_id: str) -> None:
    typed = AllocationSpec.model_validate(spec(strategy_id, {"rebalance": "MONTH_END"}))
    assert isinstance(typed.typed_parameters, RebalanceParameters)
    with pytest.raises(ValidationError):
        AllocationSpec.model_validate(spec(strategy_id, {"rebalance": "WEEKLY"}))


def test_misspelled_or_extra_parameter_key_is_rejected() -> None:
    """오타는 기본값으로 조용히 넘어가지 않고 검증 오류가 된다. 규칙 값은 parameters 에 오지 않는다.

    규칙 값(lookbackDays 등)은 ``allocation.rules`` 의 몫이다.
    """
    with pytest.raises(ValidationError, match="rebalance"):
        AllocationSpec.model_validate(spec("FIXED_WEIGHT", {"rebalanse": "MONTH_END"}))
    with pytest.raises(ValidationError, match="lookbackDays"):
        AllocationSpec.model_validate(
            spec("INVERSE_VOLATILITY", {"rebalance": "MONTH_END", "lookbackDays": 63})
        )


def test_typed_parameters_do_not_change_serialization() -> None:
    """PrivateAttr이므로 직렬화는 원본 dict를 낸다."""
    payload = spec("EQUAL_WEIGHT", {"rebalance": "MONTH_END"})
    assert json.loads(AllocationSpec.model_validate(payload).to_json()) == payload


def test_contract_fixture_strategy_is_unregistered_but_round_trips() -> None:
    """픽스처의 MA_CROSSOVER는 미등록 전략이다. 검증은 통과하고 접근자만 설명하며 실패한다."""
    original = (FIXTURE_DIR / "engine-input.json").read_text(encoding="utf-8")
    engine_input = EngineInput.model_validate_json(original)
    assert engine_input.allocation.parameters["threshold"] == 0.05
    with pytest.raises(LookupError, match="MA_CROSSOVER"):
        _ = engine_input.allocation.typed_parameters
    # 원본 JSON을 그대로 낸다 (threshold가 문자열로 변하지 않는다).
    assert json.loads(engine_input.to_json()) == json.loads(original)


def test_unregistered_strategy_deserializes_but_accessor_explains() -> None:
    spec_ = AllocationSpec.model_validate(spec("PAIRS_TRADING", {"anything": 1}))
    assert spec_.parameters == {"anything": 1}
    with pytest.raises(LookupError, match="PAIRS_TRADING"):
        _ = spec_.typed_parameters
