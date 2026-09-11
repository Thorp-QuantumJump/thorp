"""전략별 ``allocation.parameters`` 스키마와 ``AllocationSpec`` 타입 접근자."""

import json
from typing import Any

import pytest
from conftest import FIXTURE_DIR
from pydantic import ConfigDict, ValidationError

from worker.contracts.base import ContractModel
from worker.contracts.engine_input import AllocationSpec, EngineInput
from worker.contracts.strategy_params import STRATEGY_PARAMETERS, MomentumParameters


def momentum_spec(parameters: dict[str, Any]) -> dict[str, Any]:
    return {"strategyId": "MOMENTUM", "rulesVersion": "1.0.0", "parameters": parameters}


def test_parameter_model_keeps_camel_alias_and_forbids_extra() -> None:
    """자식 model_config가 부모의 alias_generator·populate_by_name을 유지한다."""

    class Probe(ContractModel):
        model_config = ConfigDict(extra="forbid")

        lookback_days: int

    assert Probe.model_config["extra"] == "forbid"
    assert Probe.model_validate({"lookbackDays": 5}).lookback_days == 5
    assert Probe.model_validate({"lookback_days": 5}).lookback_days == 5


def test_registry_covers_declared_strategies() -> None:
    assert STRATEGY_PARAMETERS == {"MOMENTUM": MomentumParameters}


def test_misspelled_momentum_key_is_rejected() -> None:
    """``lookbackDays`` 오타는 기본값으로 조용히 넘어가지 않고 검증 오류가 된다."""
    with pytest.raises(ValidationError) as error:
        AllocationSpec.model_validate(momentum_spec({"lookbackDay": 120}))
    message = str(error.value)
    assert "lookbackDay" in message or "lookback_days" in message


@pytest.mark.parametrize("lookback_days", [0, -1])
def test_non_positive_lookback_days_is_rejected(lookback_days: int) -> None:
    with pytest.raises(ValidationError):
        AllocationSpec.model_validate(momentum_spec({"lookbackDays": lookback_days}))


def test_momentum_typed_parameters() -> None:
    spec = AllocationSpec.model_validate(momentum_spec({"lookbackDays": 120}))
    typed = spec.typed_parameters
    assert isinstance(typed, MomentumParameters)
    assert typed.lookback_days == 120


def test_momentum_typed_parameters_do_not_change_serialization() -> None:
    """PrivateAttr이므로 덤프에는 원본 dict만 남는다."""
    payload = momentum_spec({"lookbackDays": 120})
    spec = AllocationSpec.model_validate(payload)
    assert json.loads(spec.to_json()) == payload


def test_contract_fixture_strategy_is_unregistered_but_round_trips() -> None:
    """픽스처의 MA_CROSSOVER는 미등록 전략이다. 봉투는 통과하고 접근자만 설명하며 실패한다."""
    original = (FIXTURE_DIR / "engine-input.json").read_text(encoding="utf-8")
    engine_input = EngineInput.model_validate_json(original)
    assert engine_input.allocation.parameters["threshold"] == 0.05
    with pytest.raises(LookupError, match="MA_CROSSOVER"):
        _ = engine_input.allocation.typed_parameters
    # 봉투 JSON은 그대로다 (threshold가 문자열이 되지 않는다).
    assert json.loads(engine_input.to_json()) == json.loads(original)


def test_unregistered_strategy_deserializes_but_accessor_explains() -> None:
    spec = AllocationSpec.model_validate(
        {"strategyId": "PAIRS_TRADING", "rulesVersion": "1.0.0", "parameters": {"anything": 1}}
    )
    assert spec.parameters == {"anything": 1}
    with pytest.raises(LookupError, match="PAIRS_TRADING"):
        _ = spec.typed_parameters
