"""전략별 ``strategy.parameters`` 스키마와 ``StrategySpec`` 타입 접근자."""

import json
from typing import Any

import pytest
from conftest import FIXTURE_DIR
from pydantic import ConfigDict, ValidationError

from worker.contracts.base import ContractModel
from worker.contracts.engine_input import EngineInput, StrategySpec
from worker.contracts.strategy_params import (
    STRATEGY_PARAMETERS,
    MaCrossoverParameters,
    MomentumParameters,
)


def momentum_spec(parameters: dict[str, Any]) -> dict[str, Any]:
    return {"strategyId": "MOMENTUM", "version": "1.0.0", "parameters": parameters}


def test_parameter_model_keeps_camel_alias_and_forbids_extra() -> None:
    """자식 model_config가 부모의 alias_generator·populate_by_name을 유지한다."""

    class Probe(ContractModel):
        model_config = ConfigDict(extra="forbid")

        lookback_days: int

    assert Probe.model_config["extra"] == "forbid"
    assert Probe.model_validate({"lookbackDays": 5}).lookback_days == 5
    assert Probe.model_validate({"lookback_days": 5}).lookback_days == 5


def test_registry_covers_declared_strategies() -> None:
    assert STRATEGY_PARAMETERS == {
        "MOMENTUM": MomentumParameters,
        "MA_CROSSOVER": MaCrossoverParameters,
    }


def test_misspelled_momentum_key_is_rejected() -> None:
    """``lookbackDays`` 오타는 기본값으로 조용히 넘어가지 않고 검증 오류가 된다."""
    with pytest.raises(ValidationError) as error:
        StrategySpec.model_validate(momentum_spec({"lookbackDay": 120}))
    message = str(error.value)
    assert "lookbackDay" in message or "lookback_days" in message


@pytest.mark.parametrize("lookback_days", [0, -1])
def test_non_positive_lookback_days_is_rejected(lookback_days: int) -> None:
    with pytest.raises(ValidationError):
        StrategySpec.model_validate(momentum_spec({"lookbackDays": lookback_days}))


def test_momentum_typed_parameters() -> None:
    spec = StrategySpec.model_validate(momentum_spec({"lookbackDays": 120}))
    typed = spec.typed_parameters
    assert isinstance(typed, MomentumParameters)
    assert typed.lookback_days == 120


def test_momentum_typed_parameters_do_not_change_serialization() -> None:
    """PrivateAttr이므로 덤프에는 원본 dict만 남는다."""
    payload = momentum_spec({"lookbackDays": 120})
    spec = StrategySpec.model_validate(payload)
    assert json.loads(spec.to_json()) == payload


def test_contract_fixture_ma_crossover_typed_parameters() -> None:
    original = (FIXTURE_DIR / "engine-input.json").read_text(encoding="utf-8")
    engine_input = EngineInput.model_validate_json(original)
    typed = engine_input.strategy.typed_parameters
    assert isinstance(typed, MaCrossoverParameters)
    assert (typed.short_window, typed.long_window) == (20, 60)
    assert isinstance(typed.threshold, float)
    assert typed.threshold == 0.05
    # 타입 접근자를 붙여도 봉투 JSON은 그대로다 (threshold가 문자열이 되지 않는다).
    assert json.loads(engine_input.to_json()) == json.loads(original)


def test_ma_crossover_requires_short_window_below_long_window() -> None:
    for short_window in (60, 61):
        with pytest.raises(ValidationError):
            StrategySpec.model_validate(
                {
                    "strategyId": "MA_CROSSOVER",
                    "version": "1.0.0",
                    "parameters": {
                        "shortWindow": short_window,
                        "longWindow": 60,
                        "threshold": 0.05,
                    },
                }
            )


def test_unregistered_strategy_deserializes_but_accessor_explains() -> None:
    spec = StrategySpec.model_validate(
        {"strategyId": "PAIRS_TRADING", "version": "1.0.0", "parameters": {"anything": 1}}
    )
    assert spec.parameters == {"anything": 1}
    with pytest.raises(LookupError, match="PAIRS_TRADING"):
        _ = spec.typed_parameters
