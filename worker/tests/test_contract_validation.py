"""EngineInput 값 불변식 검증 (README §8).

역직렬화 직후 명시 검증한 메시지만 처리한다는 규칙을 모델 안에서 지키는지 확인한다.
여기서 다루는 것은 "메시지만 보고 판단할 수 있고 수신자가 누구든 답이 같은" 불변식뿐이다.
"""

import json
from collections.abc import Callable
from typing import Any

import pytest
from conftest import FIXTURE_DIR
from pydantic import ValidationError

from worker.contracts.engine_input import EngineInput


def fixture_body() -> dict[str, Any]:
    """계약 픽스처를 매 테스트가 독립적으로 변형할 수 있게 새로 읽는다."""
    return json.loads((FIXTURE_DIR / "engine-input.json").read_text(encoding="utf-8"))


def test_fixture_passes_validation() -> None:
    """픽스처(종목 2개, maxPositionPct 0.50, PROVISIONAL-DEMO-1 비용, leverage 1.0)는 통과한다."""
    engine_input = EngineInput.model_validate(fixture_body())
    assert len(engine_input.universe.items) == 2
    assert str(engine_input.portfolio.position_sizing.max_position_pct) == "0.50"
    assert engine_input.execution.cost_profile.version == "PROVISIONAL-DEMO-1"
    assert engine_input.universe.mapping_version is None  # 대응표 없이 종목 직접 지정


def _mutate(body: dict[str, Any], path: str, value: Any) -> dict[str, Any]:
    node = body
    *parents, leaf = path.split(".")
    for key in parents:
        node = node[key]
    node[leaf] = value
    return body


def _drop(body: dict[str, Any], path: str) -> dict[str, Any]:
    node = body
    *parents, leaf = path.split(".")
    for key in parents:
        node = node[key]
    del node[leaf]
    return body


def _items(*instrument_ids: str) -> list[dict[str, str]]:
    return [{"instrumentId": item} for item in instrument_ids]


VIOLATIONS: list[tuple[str, Callable[[dict[str, Any]], dict[str, Any]]]] = [
    # RunSpec: runId 비어 있지 않음, end >= start, effectiveStart는 [start, end] 안
    ("run-blank-id", lambda b: _mutate(b, "run.runId", " ")),
    ("run-end-before-start", lambda b: _mutate(b, "run.end", "2024-12-31")),
    ("run-effective-start-before-start", lambda b: _mutate(b, "run.effectiveStart", "2024-12-31")),
    ("run-effective-start-after-end", lambda b: _mutate(b, "run.effectiveStart", "2025-07-01")),
    ("run-unknown-scenario", lambda b: _mutate(b, "run.scenario", "STRESS")),
    # UniverseSpec: 비어 있지 않고, {6글자}.XKRX 형식이며, 중복이 없다
    ("universe-empty", lambda b: _mutate(b, "universe.items", [])),
    ("universe-not-krx", lambda b: _mutate(b, "universe.items", _items("AAPL"))),
    ("universe-wrong-venue", lambda b: _mutate(b, "universe.items", _items("005930.XNAS"))),
    ("universe-short-code", lambda b: _mutate(b, "universe.items", _items("5930.XKRX"))),
    # 영문 대문자는 받지만 소문자는 받지 않는다. 둘 다 받으면 같은 종목이 두 값이 된다.
    ("universe-lowercase-code", lambda b: _mutate(b, "universe.items", _items("00593a.XKRX"))),
    ("universe-long-code", lambda b: _mutate(b, "universe.items", _items("00593A0.XKRX"))),
    (
        "universe-duplicated",
        lambda b: _mutate(b, "universe.items", _items("005930.XKRX", "005930.XKRX")),
    ),
    (
        "universe-target-weight-above-one",
        lambda b: _mutate(
            b, "universe.items", [{"instrumentId": "005930.XKRX", "targetWeight": "1.5"}]
        ),
    ),
    # MarketDataSpec: datasetId 가 빈 문자열이 아니다, 가격 계열 enum
    ("market-data-blank-dataset-id", lambda b: _mutate(b, "marketData.datasetId", "  ")),
    ("market-data-unknown-fill-price", lambda b: _mutate(b, "marketData.fillPrice", "VWAP")),
    # AllocationSpec: strategyId, rulesVersion 이 빈 문자열이 아니다
    ("allocation-empty-id", lambda b: _mutate(b, "allocation.strategyId", "")),
    ("allocation-empty-rules-version", lambda b: _mutate(b, "allocation.rulesVersion", "")),
    # PositionSizing: maxPositionPct 가 있으면 0 < 값 <= 1
    (
        "position-sizing-pct-zero",
        lambda b: _mutate(b, "portfolio.positionSizing.maxPositionPct", "0"),
    ),
    (
        "position-sizing-pct-negative",
        lambda b: _mutate(b, "portfolio.positionSizing.maxPositionPct", "-0.10"),
    ),
    (
        "position-sizing-pct-above-one",
        lambda b: _mutate(b, "portfolio.positionSizing.maxPositionPct", "1.5"),
    ),
    # PortfolioSpec: initialCash > 0, leverage >= 1, currency 3글자 대문자, integerLots 필수
    ("portfolio-zero-cash", lambda b: _mutate(b, "portfolio.initialCash", "0")),
    ("portfolio-negative-cash", lambda b: _mutate(b, "portfolio.initialCash", "-1")),
    ("portfolio-leverage-below-one", lambda b: _mutate(b, "portfolio.leverage", "0.5")),
    ("portfolio-currency-lowercase", lambda b: _mutate(b, "portfolio.currency", "krw")),
    ("portfolio-currency-too-long", lambda b: _mutate(b, "portfolio.currency", "KRWW")),
    ("portfolio-integer-lots-missing", lambda b: _drop(b, "portfolio.integerLots")),
    # CostProfile: version 비어 있지 않음, bps >= 0, provisional 필수
    ("cost-profile-blank-version", lambda b: _mutate(b, "execution.costProfile.version", " ")),
    ("cost-profile-negative-bps", lambda b: _mutate(b, "execution.costProfile.slippageBps", "-1")),
    ("cost-profile-provisional-missing", lambda b: _drop(b, "execution.costProfile.provisional")),
    ("cost-profile-missing", lambda b: _drop(b, "execution.costProfile")),
    # ExecutionSpec: orderTypes 가 비어 있지 않다, v1 정책값은 더 이상 유효하지 않다
    ("execution-order-types-empty", lambda b: _mutate(b, "execution.orderTypes", [])),
    (
        "execution-v1-trading-halt",
        lambda b: _mutate(b, "execution.tradingHalt", "WAIT_UNTIL_RESUME"),
    ),
    # EvaluationSpec / ProgressSpec
    ("evaluation-negative-warmup", lambda b: _mutate(b, "evaluation.warmupTradingDays", -1)),
    ("evaluation-missing", lambda b: _drop(b, "evaluation")),
    ("progress-zero-batch", lambda b: _mutate(b, "progress.batchTradingDays", 0)),
    ("progress-missing", lambda b: _drop(b, "progress")),
]


@pytest.mark.parametrize(("_id", "break_it"), VIOLATIONS, ids=[case[0] for case in VIOLATIONS])
def test_invariant_violation_is_rejected(
    _id: str, break_it: Callable[[dict[str, Any]], dict[str, Any]]
) -> None:
    with pytest.raises(ValidationError):
        EngineInput.model_validate(break_it(fixture_body()))


def test_alphanumeric_issue_code_is_allowed() -> None:
    """영문이 섞인 6글자 단축코드를 받는다 (spec 001 T031, PD-01).

    2026-09-11 실측에서 ETF 1,168종 가운데 304종(26%)의 코드가 ``0111J0``·``0182R0`` 같은
    형태였다. 숫자만 받으면 원천의 4분의 1이 비교 대상에서 빠진다.
    """
    body = _mutate(fixture_body(), "universe.items", _items("0111J0.XKRX", "0182R0.XKRX"))
    engine_input = EngineInput.model_validate(body)
    assert engine_input.universe.instrument_ids == ["0111J0.XKRX", "0182R0.XKRX"]


def test_same_start_and_end_is_allowed() -> None:
    """end >= start 이므로 하루 기간(start == end)은 계약 위반이 아니다."""
    body = _mutate(fixture_body(), "run.end", fixture_body()["run"]["start"])
    assert EngineInput.model_validate(body).run.start == EngineInput.model_validate(body).run.end


def test_absent_max_position_pct_is_allowed() -> None:
    """maxPositionPct 는 생략 가능(제한 없음)하므로 키가 없어도 통과한다."""
    body = _drop(fixture_body(), "portfolio.positionSizing.maxPositionPct")
    assert EngineInput.model_validate(body).portfolio.position_sizing.max_position_pct is None


def test_optional_v2_blocks_may_be_absent() -> None:
    """dataVersion·mappingVersion·rules·scenarioParams·effectiveStart는 계약상 생략 가능하다."""
    engine_input = EngineInput.model_validate(fixture_body())
    assert engine_input.allocation.rules is None
    assert engine_input.run.scenario_params is None and engine_input.run.effective_start is None
    assert engine_input.evaluation.warmup_trading_days is None


def test_absent_data_version_is_allowed() -> None:
    """dataVersion 은 생략 가능하다.

    **픽스처에 값이 없는 것으로는 이 성질을 더 보일 수 없다.** 백엔드가 접수 시점에 자료 버전을
    정해 실행에 고정하게 되면서 계약 예시도 그 값을 담게 됐다(spec 001 research.md 결정 4).
    그래서 키를 지워서 확인한다 — maxPositionPct 와 같은 방식이다.
    """
    body = _drop(fixture_body(), "marketData.dataVersion")
    assert EngineInput.model_validate(body).market_data.data_version is None


def test_validation_message_names_the_field() -> None:
    """실패 메시지로 어떤 필드가 왜 잘못됐는지 알 수 있어야 한다."""
    with pytest.raises(ValidationError) as excinfo:
        EngineInput.model_validate(_mutate(fixture_body(), "portfolio.initialCash", "0"))
    assert "portfolio.initialCash" in str(excinfo.value)
