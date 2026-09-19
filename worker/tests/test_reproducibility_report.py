"""환경 간 비교 스크립트가 골든과 같은 판정을 내는지(spec 004 T030).

스크립트는 CI 에서 따로 돌지 않으므로, 골든 테스트와 입력·해시 정의가 어긋나도 아무도 모른다.
이 환경에서 스크립트의 판정이 골든 테스트와 같은지 붙박는다.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
from types import ModuleType

from golden_inputs import golden_path, load_json, rule_snapshots

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "reproducibility_report.py"


def load_script() -> ModuleType:
    spec = importlib.util.spec_from_file_location("reproducibility_report", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_the_report_covers_every_rules_snapshot_and_matches_its_golden() -> None:
    report = load_script().report(dump_dir=None)

    results = report["results"]
    assert set(results) == {path.stem for path in rule_snapshots()}
    for snapshot_path in rule_snapshots():
        entry = results[snapshot_path.stem]
        golden = load_json(golden_path(snapshot_path))
        assert entry["roundedSha256"] == golden["sha256"], snapshot_path.stem
        assert entry["inputSha256"] == golden["inputSha256"], snapshot_path.stem
        assert entry["goldenMatch"] is True
        assert len(entry["exactSha256"]) == 64
    assert report["environment"]["packages"]["numpy"]
