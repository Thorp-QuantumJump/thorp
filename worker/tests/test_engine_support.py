"""엔진 지원 범위: 계약은 통과하지만 이 엔진이 계산할 수 없는 요청은 거절한다.

계약 모델(``contracts/engine_input.py``)은 "메시지만 보고 판단할 수 있는" 불변식만 보므로
아래 값들을 전부 통과시킨다. 엔진이 그걸 조용히 무시하고 ``COMPLETED`` 를 반환하면 요청한
정책과 다른 방식으로 계산한 숫자가 그 정책의 결과로 저장된다. 그래서 실행 전에
``run.status=FAILED`` + ``run.error.code=INVALID_REQUEST`` 로 거절하는지 확인한다.
"""

import json
from pathlib import Path
from typing import Any

import pytest
from conftest import FIXTURE_DIR, engine_input_body

from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import RunStatus
from worker.engine_runner import NautilusMomentumEngine
from worker.market_data import CsvDataProvider
from worker.strategies import STRATEGIES, prepare_strategy, unsupported_strategy_reason

FIXTURE = Path(__file__).parents[1] / "examples" / "momentum_fixture.csv"


def run(**overrides: Any) -> Any:
    """고정 데이터로 실행한다. 거절되면 데이터에 닿기 전에 반환된다."""
    body = engine_input_body(**overrides)
    return NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run(EngineInput.model_validate(body))


# (테스트 id, EngineInput 섹션 덮어쓰기, 오류 메시지에 반드시 들어가야 하는 필드 경로)
UNSUPPORTED: list[tuple[str, dict[str, Any], str]] = [
    # 엔진은 instrumentIds[0]만 쓴다. 나머지를 조용히 버리면 요청하지 않은 단일 종목 결과가
    # 다종목 백테스트 결과로 저장된다.
    (
        "universe-multiple-instruments",
        {"universe": {"instrumentIds": ["000660.XKRX", "005930.XKRX"]}},
        "universe.instrumentIds",
    ),
    ("portfolio-leverage", {"portfolio": {"leverage": "2"}}, "portfolio.leverage"),
    ("portfolio-short-selling", {"portfolio": {"shortSelling": True}}, "portfolio.shortSelling"),
    # 엔진은 KRW_SIM을 하드코딩하고 currency는 성과 라벨링에만 쓴다 → USD를 받으면 KRW로
    # 계산해 놓고 결과에 USD라고 적는다.
    ("portfolio-currency", {"portfolio": {"currency": "USD"}}, "portfolio.currency"),
    # 계약에 ZERO를 추가하면서 BROKER가 새로 통과한다. 엔진은 수수료·세금 0만 구현했다.
    (
        "commission-broker",
        {"execution": {"commission": {"model": "BROKER", "broker": "SOME-BROKER"}}},
        "execution.commission.model",
    ),
    # 엔진은 SLIPPAGE_BPS=5를 하드코딩하고 execution.slippage.bps를 읽지 않는다.
    (
        "slippage-bps",
        {"execution": {"slippage": {"model": "FIXED_BPS", "bps": 10}}},
        "execution.slippage.bps",
    ),
    ("partial-fill", {"execution": {"partialFill": True}}, "execution.partialFill"),
    ("benchmark", {"benchmark": {"instrumentId": "069500.XKRX"}}, "benchmark"),
    # 미지원 전략·버전은 엔진 레지스트리에 없다는 사실로 자동 거절된다.
    # MA_CROSSOVER는 계약(STRATEGY_PARAMETERS)에는 있고 엔진 구현체는 없는 경우다.
    (
        "strategy-known-to-contract-only",
        {
            "strategy": {
                "strategyId": "MA_CROSSOVER",
                "parameters": {"shortWindow": 20, "longWindow": 60, "threshold": 0.05},
            }
        },
        "strategy.strategyId",
    ),
    # 계약이 아예 모르는 strategyId는 parameters 봉투를 그대로 통과시킨다.
    (
        "strategy-unknown-everywhere",
        {"strategy": {"strategyId": "NOT_A_STRATEGY", "parameters": {"anything": 1}}},
        "strategy.strategyId",
    ),
    ("strategy-version", {"strategy": {"version": "2.0.0"}}, "strategy.version"),
]


@pytest.mark.parametrize(
    ("_id", "overrides", "field"), UNSUPPORTED, ids=[case[0] for case in UNSUPPORTED]
)
def test_unsupported_request_is_rejected(_id: str, overrides: dict[str, Any], field: str) -> None:
    output = run(**overrides)
    assert output.run.status is RunStatus.FAILED
    assert output.run.error.code == "INVALID_REQUEST"
    assert field in output.run.error.message
    # 계산하지 않은 성과를 0으로 채우지 않는다 (계약 §4.3).
    assert output.performance is None and output.execution is None
    payload = json.loads(output.to_json())
    assert "performance" not in payload and "execution" not in payload
    assert payload["portfolioSeries"] == [] and payload["trades"] == []


def test_every_unsupported_field_is_reported_at_once() -> None:
    """여러 항목이 동시에 미지원이면 한 번의 실패로 전부 알 수 있어야 한다."""
    output = run(
        universe={"instrumentIds": ["000660.XKRX", "005930.XKRX"]},
        portfolio={"currency": "USD", "leverage": "3", "shortSelling": True},
        execution={
            "commission": {"model": "BROKER", "broker": "SOME-BROKER"},
            "slippage": {"model": "FIXED_BPS", "bps": 10},
            "partialFill": True,
        },
        benchmark={"instrumentId": "069500.XKRX"},
    )
    assert output.run.error.code == "INVALID_REQUEST"
    message = output.run.error.message
    for field in (
        "universe.instrumentIds",
        "portfolio.currency",
        "portfolio.leverage",
        "portfolio.shortSelling",
        "execution.commission.model",
        "execution.slippage.bps",
        "execution.partialFill",
        "benchmark",
    ):
        assert field in message


def test_contract_fixture_stays_valid_but_is_out_of_engine_scope() -> None:
    """지원 범위 검증은 contracts/ 가 아니라 엔진 쪽에 있다.

    계약 픽스처는 구조 검증용이라 ``contracts/`` 를 계속 통과해야 하고(백엔드 round-trip
    테스트가 같은 파일을 읽는다), 엔진에서는 거절되어야 한다.
    """
    body = json.loads((FIXTURE_DIR / "engine-input.json").read_text(encoding="utf-8"))
    contract_input = EngineInput.model_validate(body)
    assert contract_input.strategy.strategy_id == "MA_CROSSOVER"
    output = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run(contract_input)
    assert output.run.status is RunStatus.FAILED
    assert output.run.error.code == "INVALID_REQUEST"


def test_supported_request_reaches_the_engine() -> None:
    output = run()
    assert output.run.status is RunStatus.COMPLETED, output.run.error


def test_absent_max_position_pct_means_no_limit() -> None:
    """계약의 maxPositionPct 생략은 '제한 없음'이다 (None으로 곱하면 TypeError)."""
    unlimited = run(portfolio={"positionSizing": {"type": "STRATEGY_DEFINED"}})
    full = run()
    assert unlimited.run.status is RunStatus.COMPLETED, unlimited.run.error
    assert unlimited.performance == full.performance


def test_absent_random_seed_uses_the_fixed_default() -> None:
    """계약의 randomSeed 미지정도 재현되어야 한다 (FillModel에 None을 넘기지 않는다)."""
    body = engine_input_body()
    del body["run"]["randomSeed"]
    request = EngineInput.model_validate(body)
    assert request.run.random_seed is None
    output, artifacts = NautilusMomentumEngine(CsvDataProvider(FIXTURE)).run_with_artifacts(request)
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    assert artifacts.metadata["randomSeed"] == 42


def test_delisting_cause_based_is_accepted_with_exactly_one_warning() -> None:
    """ADR-0003 D-상장폐지: 거부하지 않고 받되 미지원 경고를 실행당 1건 남긴다."""
    output = run()
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    delisting = [
        w for w in output.diagnostics.warnings if w.code == "DELISTING_CLASSIFICATION_UNSUPPORTED"
    ]
    assert len(delisting) == 1
    # 실행 전체에 걸리는 경고라 특정 거래일이 없다 → 평가 구간 첫 거래일을 쓴다.
    assert delisting[0].date == output.portfolio_series[0].date
    assert delisting[0].instrument_id == "000660.XKRX"


def test_failed_run_carries_no_warnings() -> None:
    """실패 결과에는 경고를 붙이지 않는다 (평가 구간이 없으면 경고의 거래일도 없다)."""
    output = run(run={"backtestId": "EMPTY", "start": "2026-01-01", "end": "2026-01-02"})
    assert output.run.status is RunStatus.FAILED
    assert output.diagnostics.warnings == []


def test_registry_declares_versions_and_builds_from_typed_parameters() -> None:
    entry = STRATEGIES["MOMENTUM"]
    assert entry.versions == frozenset({"1.0.0"})
    spec = EngineInput.model_validate(engine_input_body()).strategy
    assert unsupported_strategy_reason(spec) is None
    prepared = prepare_strategy(spec)
    assert prepared.required_history == 2
    assert prepared.signal.name == "MOMENTUM"


def test_prepare_strategy_never_substitutes_another_strategy() -> None:
    """미등록 전략을 조용히 다른 전략으로 대체하지 않는다."""
    spec = EngineInput.model_validate(
        engine_input_body(strategy={"strategyId": "NOPE", "parameters": {}})
    ).strategy
    assert "NOPE" in (unsupported_strategy_reason(spec) or "")
    with pytest.raises(LookupError, match="NOPE"):
        prepare_strategy(spec)
