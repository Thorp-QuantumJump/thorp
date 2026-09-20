"""규칙 버전 골든의 기준 입력과 실행(spec 004 T029·T030).

골든 테스트(``test_rules_goldens.py``)와 환경 간 비교 스크립트
(``scripts/reproducibility_report.py``)가 같은 입력으로 엔진을 돌리도록 한 곳에 둔다.
스크립트는 pytest 가 없는 운영 이미지 안에서도 돌아야 하므로 이 모듈은 pytest 를 import 하지
않는다.
"""

from __future__ import annotations

import functools
import json
from pathlib import Path
from typing import Any

from fixture_paths import FIXTURE_DIR, THREE_ETF_DIR

from worker.contracts.engine_output import EngineOutput, RunStatus
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider
from worker.reproducibility import canonical_json, computed

RULES_DIR = FIXTURE_DIR / "rules"
GOLDEN_DIR = Path(__file__).resolve().parent / "goldens"
INPUT_DIR = Path(__file__).resolve().parent / "fixtures"

#: 전략 → 골든을 돌릴 기준 입력. 규칙 사본만 스냅샷의 것으로 바꿔 끼운다.
BASE_INPUTS: dict[str, str] = {
    "FIXED_WEIGHT": "engine-input-three-etf-fixed.json",
    "EQUAL_WEIGHT": "engine-input-three-etf-equal.json",
    "INVERSE_VOLATILITY": "engine-input-three-etf-inverse_vol.json",
    "SECTOR_MOMENTUM": "engine-input-three-etf-momentum.json",
    "MINIMUM_VARIANCE": "engine-input-three-etf-min_variance.json",
    # 전략이 아니라 시나리오 오버레이라 run.scenarioRules 로 실린다.
    "TARGET_RISK_OVERLAY": "engine-input-three-etf-target-risk.json",
}
OVERLAY = "TARGET_RISK_OVERLAY"


def rule_snapshots() -> list[Path]:
    return sorted(RULES_DIR.glob("*.json"))


def load_json(path: Path) -> dict[str, Any]:
    loaded: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
    return loaded


def engine_input_for(snapshot: dict[str, Any], rules_version: str | None = None) -> dict[str, Any]:
    """기준 입력에 스냅샷의 명세를 규칙 사본으로 끼운다.

    모양은 백엔드 ``EngineInputAssembler`` 가 싣는 것과 같다 — 명세에 ``provisional`` 을
    더한 사본이다.
    """
    strategy_id = snapshot["strategyId"]
    assert strategy_id in BASE_INPUTS, f"{strategy_id} 의 골든 기준 입력을 BASE_INPUTS 에 더한다"
    body = load_json(INPUT_DIR / BASE_INPUTS[strategy_id])
    version = rules_version or snapshot["rulesVersion"]
    if strategy_id == OVERLAY:
        body["run"]["scenarioRules"] = {**snapshot["spec"], "version": f"{OVERLAY}/{version}"}
    else:
        body["allocation"]["rulesVersion"] = version
        body["allocation"]["rules"] = {**snapshot["spec"], "provisional": snapshot["provisional"]}
    return body


def run(body: dict[str, Any]) -> EngineOutput:
    """같은 입력은 한 번만 돌린다 — 골든과 분기 테스트가 같은 기준 입력을 쓴다."""
    return _run_cached(canonical_json(body))


@functools.cache
def _run_cached(body_json: str) -> EngineOutput:
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(
        json.loads(body_json)
    )
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    return output


def summary(output: EngineOutput) -> dict[str, Any]:
    """골든이 어긋났을 때 어디가 달라졌는지 가늠할 숫자 몇 개. 판정에는 쓰지 않는다."""
    payload = computed(output)
    series = payload.get("weightsSeries") or []
    return {
        "performance": payload.get("performance"),
        "tradeCount": len(payload.get("trades") or []),
        "exceptionCount": len(payload.get("exceptions") or []),
        "firstTarget": series[0]["target"] if series else None,
        "lastTarget": series[-1]["target"] if series else None,
    }


def golden_path(snapshot_path: Path) -> Path:
    return GOLDEN_DIR / snapshot_path.name
