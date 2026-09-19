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

**해시는 수를 유효숫자 12자리로 맞춘 뒤에 잰다.** 샤프 비율·변동성 같은 지표는 float 표기 그대로
결과에 실리고, 같은 라이브러리 버전이라도 OS·CPU가 다르면 마지막 자리가 흔들릴 수 있다. 의존성
버전은 ``uv.lock`` 이 고정하지만(004 T027) 그것만으로 환경 간 끝자리 차이까지 막지는 못한다. 규칙이
바뀌어서 생기는 차이는 그보다 훨씬 크므로 12자리에서 잘라도 놓치지 않는다. 환경 간 재현도 이
해시로 판정한다(004 T030, research R-21). 해시 정의는 ``worker.reproducibility``, 기준 입력과 실행은
``golden_inputs`` 에 있고 환경 간 비교 스크립트(``scripts/reproducibility_report.py``)도 같은
것을 쓴다.
"""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest
from golden_inputs import (
    BASE_INPUTS,
    GOLDEN_DIR,
    RULES_DIR,
    engine_input_for,
    golden_path,
    load_json,
    rule_snapshots,
    run,
    summary,
)

from worker.reproducibility import computed, digest, input_digest, rounded

WRITE_ENV = "THORP_WRITE_RULES_GOLDENS"


@pytest.mark.parametrize("snapshot_path", rule_snapshots(), ids=lambda path: path.stem)
def test_each_rules_version_reproduces_its_golden(snapshot_path: Path) -> None:
    snapshot = load_json(snapshot_path)
    body = engine_input_for(snapshot)
    output = run(body)
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
            "inputSha256": input_digest(body),
            "sha256": actual,
            "summary": summary(output),
        }
        text = json.dumps(golden, ensure_ascii=False, indent=2) + "\n"
        target.write_text(text, encoding="utf-8", newline="\n")
        return

    golden = load_json(target)
    assert golden["rules"] == snapshot_path.name
    assert golden["engineInput"] == BASE_INPUTS[snapshot["strategyId"]]
    # 입력부터 본다. 기준 입력 파일(결정성 테스트와 함께 쓴다)을 고쳐서 결과가 달라진 것을
    # 엔진 회귀로 읽으면 엉뚱한 곳을 찾게 된다.
    assert input_digest(body) == golden["inputSha256"], (
        f"{snapshot_path.stem} 골든을 만든 기준 입력({golden['engineInput']})이나"
        " 규칙 스냅샷이 바뀌었다. 규칙이 아니라 입력을 바꾼 것이면 이 골든 파일을 지우고"
        " 다시 만든다(모듈 주석)"
    )
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


def test_rounding_absorbs_last_digit_noise_but_keeps_identifiers() -> None:
    """해시 전 정규화는 float 마지막 자리 차이만 흡수한다.

    식별자·날짜와 의미 있는 차이는 그대로 남는다.
    """
    assert rounded("0.6603170681642053") == rounded("0.6603170681642054")
    assert rounded(0.1 + 0.2) == rounded("0.3")
    assert rounded("0.660317") != rounded("0.660318")
    assert rounded({"id": "091160.XKRX", "date": "2024-06-28", "n": 16}) == {
        "id": "091160.XKRX",
        "date": "2024-06-28",
        "n": 16,
    }
