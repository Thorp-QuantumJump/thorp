"""엔진 지원 범위: 계약은 통과하지만 이 엔진이 계산할 수 없는 요청은 거절한다.

계약 모델(``contracts/engine_input.py``)은 "메시지만 보고 판단할 수 있는" 불변식만 보므로
아래 값들을 전부 통과시킨다. 엔진이 그걸 조용히 무시하고 ``COMPLETED`` 를 반환하면 요청한
정책과 다른 방식으로 계산한 숫자가 그 정책의 결과로 저장된다. 그래서 실행 전에
``run.status=FAILED`` + ``run.error.code=INVALID_REQUEST`` 로 거절하는지 확인한다.
"""

import json
from typing import Any

import pytest
from conftest import FIXTURE_DIR, THREE_ETF_DIR, engine_input_body

from worker.contracts.engine_input import EngineInput
from worker.contracts.engine_output import RunStatus
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider


def run(**overrides: Any) -> Any:
    """픽스처로 실행한다. 거절되면 자료에 닿기 전에 반환된다."""
    body = engine_input_body(**overrides)
    engine = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR))
    return engine.run(EngineInput.model_validate(body))


# (테스트 id, EngineInput 섹션 덮어쓰기, 오류 메시지에 반드시 들어가야 하는 필드 경로)
UNSUPPORTED: list[tuple[str, dict[str, Any], str]] = [
    ("run-scenario-cost-escalation", {"run": {"scenario": "COST_ESCALATION"}}, "run.scenario"),
    # 목표 위험 맞춤은 규칙 사본과 목표값이 둘 다 있어야 계산한다. 없으면 지어내지 않는다(R-17).
    (
        "target-risk-without-rules",
        {"run": {"scenario": "TARGET_RISK", "scenarioParams": {"targetVolatility": "0.1000"}}},
        "run.scenarioRules",
    ),
    (
        "target-risk-without-target",
        {
            "run": {
                "scenario": "TARGET_RISK",
                "scenarioRules": {"lookbackDays": 63, "scaleDownOnly": True},
            }
        },
        "run.scenarioParams.targetVolatility",
    ),
    (
        "target-risk-scale-up",
        {
            "run": {
                "scenario": "TARGET_RISK",
                "scenarioParams": {"targetVolatility": "0.1000"},
                "scenarioRules": {"lookbackDays": 63, "scaleDownOnly": False},
            }
        },
        "run.scenarioRules.scaleDownOnly",
    ),
    (
        "fresh-cash-without-start-date",
        {"run": {"scenario": "FRESH_CASH_FROM"}},
        "run.scenarioParams.startDate",
    ),
    (
        "fresh-cash-start-date-outside-run",
        {"run": {"scenario": "FRESH_CASH_FROM", "scenarioParams": {"startDate": "2030-01-01"}}},
        "run.scenarioParams.startDate",
    ),
    ("fill-price-adjusted", {"marketData": {"fillPrice": "ADJUSTED"}}, "marketData.fillPrice"),
    ("signal-price-raw", {"marketData": {"signalPrice": "RAW"}}, "marketData.signalPrice"),
    ("portfolio-leverage", {"portfolio": {"leverage": "2"}}, "portfolio.leverage"),
    ("portfolio-short-selling", {"portfolio": {"shortSelling": True}}, "portfolio.shortSelling"),
    ("portfolio-currency", {"portfolio": {"currency": "USD"}}, "portfolio.currency"),
    ("portfolio-fractional-lots", {"portfolio": {"integerLots": False}}, "portfolio.integerLots"),
    ("partial-fill", {"execution": {"partialFill": True}}, "execution.partialFill"),
    # 규칙 사본 없이 온 요청은 엔진이 기본값을 지어내지 않고 멈춘다(R-08).
    ("rules-missing", {"allocation": {"rules": None}}, "allocation.rules"),
    # 팀 미확정 전략은 "정하지 않았다"로, 모르는 전략은 "구현하지 않았다"로 거절한다.
    (
        "strategy-pending-decision",
        {"allocation": {"strategyId": "SECTOR_MOMENTUM", "parameters": {"rebalance": "MONTH_END"}}},
        "PD-03",
    ),
    (
        "strategy-unknown-everywhere",
        {"allocation": {"strategyId": "NOT_A_STRATEGY", "parameters": {"anything": 1}}},
        "allocation.strategyId",
    ),
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
        run={"scenario": "COST_ESCALATION"},
        marketData={"fillPrice": "ADJUSTED"},
        portfolio={"currency": "USD", "leverage": "3", "shortSelling": True},
        execution={"partialFill": True},
    )
    assert output.run.error.code == "INVALID_REQUEST"
    message = output.run.error.message
    for field in (
        "run.scenario",
        "marketData.fillPrice",
        "portfolio.currency",
        "portfolio.leverage",
        "portfolio.shortSelling",
        "execution.partialFill",
    ):
        assert field in message


def test_contract_fixture_stays_valid_but_is_out_of_engine_scope() -> None:
    """지원 범위 검증은 contracts/ 가 아니라 엔진 쪽에 있다.

    계약 픽스처(MA_CROSSOVER)는 구조 검증용이라 ``contracts/`` 를 계속 통과해야 하고(백엔드
    round-trip 테스트가 같은 파일을 읽는다), 엔진에서는 거절되어야 한다.
    """
    body = json.loads((FIXTURE_DIR / "engine-input.json").read_text(encoding="utf-8"))
    contract_input = EngineInput.model_validate(body)
    assert contract_input.allocation.strategy_id == "MA_CROSSOVER"
    engine = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR))
    output = engine.run(contract_input)
    assert output.run.status is RunStatus.FAILED
    assert output.run.error.code == "INVALID_REQUEST"


def test_v2_policies_are_now_computed_not_rejected() -> None:
    """데모 엔진이 거절하던 값들(다종목·effectiveStart·청산·비용 네 갈래)을 계산한다."""
    output = run(
        run={"effectiveStart": "2023-01-04"},
        execution={
            "costProfile": {
                "version": "PROVISIONAL-FULL-1",
                "commissionBps": "1.5",
                "spreadBps": "2",
                "slippageBps": "5",
                "taxBps": "23",
                "provisional": True,
            }
        },
        evaluation={"liquidationAtEnd": True},
    )
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    assert output.run.effective_start.isoformat() == "2023-01-04"
    assert output.liquidation is not None and output.costs is not None


def test_absent_random_seed_uses_the_fixed_default() -> None:
    """계약의 randomSeed 미지정도 재현되어야 한다 (FillModel에 None을 넘기지 않는다)."""
    body = engine_input_body()
    del body["run"]["randomSeed"]
    request = EngineInput.model_validate(body)
    assert request.run.random_seed is None
    engine = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR))
    output, artifacts = engine.run_with_artifacts(request)
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
    assert delisting[0].date == output.portfolio_series[0].date


def test_completed_run_reports_v2_run_and_version_blocks() -> None:
    output = run()
    assert output.run.partial is False
    assert output.run.last_trading_date == output.portfolio_series[-1].date
    assert output.run.effective_start == output.portfolio_series[0].date
    assert output.costs is not None and output.costs.total == output.costs.slippage
    assert output.performance.total_trading_cost == output.costs.total
    assert output.versions.cost_profile.version == "PROVISIONAL-DEMO-1"
    assert output.versions.rules.version == "1"
    assert output.versions.data == "FIXTURE-three_etf_24m"
    assert output.versions.engine.startswith("nautilus_trader ")


def test_failed_run_carries_no_warnings() -> None:
    """실패 결과에는 경고를 붙이지 않는다 (평가 구간이 없으면 경고의 거래일도 없다)."""
    output = run(run={"runId": "EMPTY", "start": "2030-01-01", "end": "2030-01-02"})
    assert output.run.status is RunStatus.FAILED
    assert output.diagnostics.warnings == []
