"""전략별 ``allocation.parameters`` 스키마와 ``AllocationSpec`` 타입 접근자."""

import json
from typing import Any

import pytest
from conftest import FIXTURE_DIR
from pydantic import ConfigDict, ValidationError

from worker.contracts.base import ContractModel
from worker.contracts.engine_input import AllocationSpec, EngineInput
from worker.contracts.strategy_params import (
    STRATEGY_PARAMETERS,
    CappedRebalanceParameters,
    LookbackRebalanceParameters,
    MinimumVarianceParameters,
    RebalanceParameters,
    SectorMomentumParameters,
)


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
    """엔진이 계산하는 전략은 파라미터 모양도 선언돼 있다.

    다섯이 다 등록된 것은 2026-09-17 PD-03·PD-04 확정 뒤다. 규칙이 아직 없는 전략을 나중에 더하면
    파라미터 모양도 미정이라 등록하지 않는다 — 그때는 엔진이 ``rules_registry`` 로 거절한다.
    """
    assert STRATEGY_PARAMETERS == {
        "FIXED_WEIGHT": CappedRebalanceParameters,
        "EQUAL_WEIGHT": RebalanceParameters,
        "INVERSE_VOLATILITY": LookbackRebalanceParameters,
        "MINIMUM_VARIANCE": MinimumVarianceParameters,
        "SECTOR_MOMENTUM": SectorMomentumParameters,
    }


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


def test_minimum_variance_takes_the_003_axes_it_offers() -> None:
    """003이 최소분산에 두는 축은 넷이다. 하나라도 받지 못하면 화면이 만든 요청이 거절된다.

    ``rebalancePeriod``는 003이 객체로 두는 축이고 백엔드 ``EngineInputAssembler``가
    ``rebalance``로 옮겨 싣는다 — 워커에는 옮겨진 이름으로 온다.
    """
    typed = AllocationSpec.model_validate(
        spec(
            "MINIMUM_VARIANCE",
            {
                "rebalance": "MONTH_END",
                "maxWeight": 40,
                "lookbackDays": 250,
                "estimationMethod": "SAMPLE_COV",
            },
        )
    ).typed_parameters

    assert isinstance(typed, MinimumVarianceParameters)
    assert (typed.lookback_days, typed.estimation_method) == (250, "SAMPLE_COV")


def test_inverse_volatility_takes_the_003_axes_it_offers() -> None:
    """003이 역변동성에 두는 축은 ``lookbackDays``·``maxWeight`` 둘이다.

    ``lookbackDays`` 를 받지 못하던 동안 007 자동 탐색이 보낸 후보가 **전부** 계약 위반으로 DLQ 에
    버려졌다(``allocation.lookbackDays``: extra inputs are not permitted). 축을 규칙표 전용 값으로
    착각한 자리라, 등록표가 축으로 선언하는 한 계약도 받아야 한다.
    """
    typed = AllocationSpec.model_validate(
        spec("INVERSE_VOLATILITY", {"rebalance": "MONTH_END", "maxWeight": 40, "lookbackDays": 21})
    ).typed_parameters

    assert isinstance(typed, LookbackRebalanceParameters)
    assert (typed.lookback_days, typed.max_weight) == (21, 40)
    # 고르지 않으면 규칙표 기본값이 쓰이도록 비워 둔다.
    assert (
        AllocationSpec.model_validate(
            spec("INVERSE_VOLATILITY", {"rebalance": "MONTH_END"})
        ).typed_parameters.lookback_days
        is None
    )


def test_misspelled_or_extra_parameter_key_is_rejected() -> None:
    """오타는 기본값으로 조용히 넘어가지 않고 검증 오류가 된다.

    축이 없는 전략에 축 이름을 실어 보내는 것도 같다 — 고정 비중에는 003 관측 기간 축이 없다.
    """
    with pytest.raises(ValidationError, match="rebalance"):
        AllocationSpec.model_validate(spec("FIXED_WEIGHT", {"rebalanse": "MONTH_END"}))
    with pytest.raises(ValidationError, match="lookbackDays"):
        AllocationSpec.model_validate(
            spec("FIXED_WEIGHT", {"rebalance": "MONTH_END", "lookbackDays": 63})
        )


def test_axis_keys_are_camel_case_only() -> None:
    """필드 이름(snake_case) 철자는 거절한다.

    계산은 원본 dict 를 ``maxWeight`` 로 읽으므로, ``max_weight`` 를 받아주면 검증만 통과하고
    상한은 규칙표 기본값으로 조용히 바뀜다.
    """
    with pytest.raises(ValidationError, match="max_weight"):
        AllocationSpec.model_validate(
            spec("FIXED_WEIGHT", {"rebalance": "MONTH_END", "max_weight": 40})
        )


def test_equal_weight_does_not_take_a_cap() -> None:
    """003 에 ``maxWeight`` 축이 없는 전략은 받지 않는다 — 1/N 은 상한을 지킬 수 없다."""
    with pytest.raises(ValidationError, match="maxWeight"):
        AllocationSpec.model_validate(
            spec("EQUAL_WEIGHT", {"rebalance": "MONTH_END", "maxWeight": 20})
        )
    AllocationSpec.model_validate(spec("FIXED_WEIGHT", {"rebalance": "MONTH_END", "maxWeight": 20}))


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
