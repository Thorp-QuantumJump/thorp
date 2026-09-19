"""규칙 버전별 골든(spec 004 T029).

등록표의 규칙 버전 하나가 결과 하나로 고정되는지 본다. 규칙 명세는 백엔드와 같은 스냅샷 파일
(``docs/specs/backtest-messaging/examples/rules/{전략}-{버전}.json``)에서 읽는다 — 백엔드
``StrategyRulesSnapshotTest`` 가 그 파일이 DB 행과 같은지 보고, 여기서는 그 파일을 규칙 사본으로
실어 엔진을 돌린 결과가 골든(``tests/goldens/{전략}-{버전}.json``)과 같은지 본다.

**골든은 버전마다 따로 두고 지우지 않는다.** 규칙이 바뀌면 새 버전 행·스냅샷·골든을 더하고 옛
것은 그대로 둔다. 옛 버전으로 접수된 실행은 발행·재시도에서 옛 명세로 계산되므로(T029), 옛 골든이
계속 통과해야 그 재계산이 처음과 같은 숫자를 낸다는 것이 보장된다.

**엔진은 버전 문자열이 아니라 규칙 키로 분기한다.** ``allocation.rulesVersion`` 은 결과에 남기는
이름표일 뿐이고, 계산이 달라지는 것은 명세의 키(``capOverflow``·``weightUnit`` 등)가 달라서다.
버전 문자열로 분기하면 같은 명세를 가진 두 버전이 다른 숫자를 내거나, 새 버전을 더할 때마다 엔진을
고쳐야 한다.

새 규칙 버전의 골든은 없는 것만 만든다(있는 골든은 덮어쓰지 않는다)::

    THORP_WRITE_RULES_GOLDENS=1 pytest tests/test_rules_goldens.py

이미 있는 골든을 다시 만들어야 한다면(의존성·엔진 변경으로 결과가 바뀐 경우) 그 파일을 지우고 위
명령을 돌린다. 그것은 "같은 버전이 다른 숫자를 낸다"는 뜻이므로 MR 본문에 이유와 차이를 적는다.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any

import pytest
from conftest import FIXTURE_DIR, THREE_ETF_DIR

from worker.contracts.engine_output import EngineOutput, RunStatus
from worker.engine_runner import PortfolioBacktestEngine
from worker.market_data import CsvMarketDataProvider

RULES_DIR = FIXTURE_DIR / "rules"
GOLDEN_DIR = Path(__file__).resolve().parent / "goldens"
INPUT_DIR = Path(__file__).resolve().parent / "fixtures"
WRITE_ENV = "THORP_WRITE_RULES_GOLDENS"

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
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(body)
    assert output.run.status is RunStatus.COMPLETED, output.run.error
    return output


def computed(output: EngineOutput) -> dict[str, Any]:
    """계산 결과만 남긴다. 실행 시각과 이름표(``versions``: 규칙·엔진·워커 버전 문자열)는 뺀다.

    이름표를 빼는 이유: 골든이 지키는 것은 "이 명세로 계산하면 이 숫자"이고, 워커 버전을 올릴 때마다
    모든 골든이 바뀌면 정말 숫자가 바뀐 경우를 가려낼 수 없다.
    """
    payload: dict[str, Any] = json.loads(output.to_json())
    payload["run"].pop("startedAt", None)
    payload["run"].pop("finishedAt", None)
    payload.pop("versions", None)
    return payload


def digest(payload: dict[str, Any]) -> str:
    canonical = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


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


@pytest.mark.parametrize("snapshot_path", rule_snapshots(), ids=lambda path: path.stem)
def test_each_rules_version_reproduces_its_golden(snapshot_path: Path) -> None:
    snapshot = load_json(snapshot_path)
    output = run(engine_input_for(snapshot))
    actual = digest(computed(output))
    target = golden_path(snapshot_path)

    if not target.exists():
        if os.environ.get(WRITE_ENV) != "1":
            pytest.fail(
                f"{snapshot_path.name} 의 골든이 없다. 새 규칙 버전이면 "
                f"{WRITE_ENV}=1 pytest tests/test_rules_goldens.py 로 만든다"
            )
        GOLDEN_DIR.mkdir(exist_ok=True)
        golden = {
            "rules": snapshot_path.name,
            "engineInput": BASE_INPUTS[snapshot["strategyId"]],
            "sha256": actual,
            "summary": summary(output),
        }
        target.write_text(json.dumps(golden, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return

    golden = load_json(target)
    assert golden["rules"] == snapshot_path.name
    assert golden["engineInput"] == BASE_INPUTS[snapshot["strategyId"]]
    assert actual == golden["sha256"], (
        f"{snapshot_path.stem} 이 골든과 다른 결과를 냈다 — 같은 규칙 버전이 다른 숫자를 낸다.\n"
        f"  골든 요약: {json.dumps(golden['summary'], ensure_ascii=False)}\n"
        f"  지금 요약: {json.dumps(summary(output), ensure_ascii=False)}"
    )


def test_every_golden_belongs_to_a_registered_rules_version() -> None:
    """골든만 남고 스냅샷이 사라지면 그 버전의 재현을 아무도 지키지 않는다."""
    snapshots = {path.name for path in rule_snapshots()}
    goldens = {path.name for path in GOLDEN_DIR.glob("*.json")}
    assert goldens <= snapshots, f"스냅샷 없는 골든: {sorted(goldens - snapshots)}"


def test_the_rules_version_label_does_not_change_the_computation() -> None:
    """엔진은 버전 문자열로 분기하지 않는다 — 같은 명세면 버전 이름이 달라도 같은 숫자다."""
    snapshot = load_json(RULES_DIR / "INVERSE_VOLATILITY-1.json")

    as_v1 = run(engine_input_for(snapshot))
    as_v9 = run(engine_input_for(snapshot, rules_version="9"))

    assert digest(computed(as_v1)) == digest(computed(as_v9))
    assert as_v9.versions is not None and as_v9.versions.rules.version == "9"


def test_a_rule_key_is_what_changes_the_computation() -> None:
    """반대로 명세의 키가 다르면 버전 이름이 같아도 다른 숫자다 — 분기의 근거는 키다."""
    snapshot = load_json(RULES_DIR / "INVERSE_VOLATILITY-1.json")
    coarser = {**snapshot, "spec": {**snapshot["spec"], "weightUnit": "0.01"}}

    fine = run(engine_input_for(snapshot))
    coarse = run(engine_input_for(coarser))

    assert fine.weights_series is not None and coarse.weights_series is not None
    assert [point.target for point in fine.weights_series] != [
        point.target for point in coarse.weights_series
    ]
    assert all(
        (weight * 100) == (weight * 100).to_integral_value()
        for point in coarse.weights_series
        for weight in point.target.values()
    )
