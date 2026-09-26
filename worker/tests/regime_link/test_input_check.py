"""004 저장 결과로 011 구간 입력을 다시 세울 수 있는지의 회귀 시험.

spec 011 T000·T102, DEP-004-011.

``scripts/regime_link_input_check.py``가 T000 근거를 만든 절차다. 그 절차를 이 시험이 매번 돌린다 —
004 결과 모양(스냅샷·체결·분배금)이 바뀌어 011 입력을 세울 수 없게 되면 여기서 먼저 깨진다.
구간 계산 자체의 정답 대조는 ``test_slicer.py``(T014 독립 계산)가 따로 한다.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
import regime_link_input_check


def run_check(monkeypatch: pytest.MonkeyPatch, tmp_path: Path, *flags: str) -> dict:
    out = tmp_path / "check.json"
    monkeypatch.setattr(sys, "argv", ["regime_link_input_check", *flags, "--out", str(out)])
    regime_link_input_check.main()
    return json.loads(out.read_text(encoding="utf-8"))


def test_every_window_is_reconstructed_from_004_outputs(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    report = run_check(monkeypatch, tmp_path)
    assert report["windowCount"] > 0
    assert report["failedCount"] == 0


def test_dropping_the_split_snapshot_is_caught(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """대조군 — 기업행위 스냅샷을 빼면 어긋남이 드러나야 이 대조가 무언가를 잡는다(T100)."""
    report = run_check(monkeypatch, tmp_path, "--ignore-split-snapshot")
    assert report["failedCount"] > 0
