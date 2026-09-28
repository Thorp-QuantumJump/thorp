"""운영 적재 가드 (contracts/macro-indicator-port.md §4.1, tasks.md T008).

설정 실수 하나로 지어낸 값이 운영 DB에 들어가는 경로를 막는다. **가장 오래 남는 것은 코드가
아니라 적재된 행이다** — 코드를 되돌려도 행은 남고, 그 행은 식별 키·기록·화면을 타고 계속
돌아다닌다. 그래서 계산을 시작하기 전에 끝낸다.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from conftest import make_registry, make_series, write_phase_config

import precompute_regime
from data_pipeline.regime.macro.types import BaselineMonths
from precompute_regime import (
    build_parser,
    main,
)


class TestEntryPoint:
    def test_아무것도_계산하지_않은_실행이_성공으로_끝나지_않는다(
        self, monkeypatch: pytest.MonkeyPatch,
        tmp_path: Path,
    ) -> None:
        """대상이 0개인 실행이 0으로 끝나면 배치가 돈 줄 알고 넘어간다 (spec FR-033).

        지표 소스를 갈아 끼우는 이유는 008 구현체가 DB 를 읽기 때문이다. 여기서 보는 것은
        종료 코드이지 자료가 아니다.
        """
        monkeypatch.setattr(precompute_regime, "create_source", lambda name: _StubSource())
        monkeypatch.setattr(
            precompute_regime,
            "PHASE_CONFIG",
            write_phase_config(tmp_path, _StubSource._registry),
        )
        assert main(["--source", "macro", "--baseline-month", "1900-01", "--dry-run"]) != 0

    def test_잘못된_월_표기는_거부한다(self) -> None:
        assert main(["--source", "macro", "--since", "2024/01"]) != 0


class TestFlags:
    def test_소스_기본값은_008_구현체다(self) -> None:
        """플래그를 빠뜨린 실행이 임시 대역으로 돌면 가드가 있어도 새어 나간다."""
        assert build_parser().parse_args([]).source == "macro"

    def test_범위_플래그는_함께_쓸_수_없다(self) -> None:
        with pytest.raises(SystemExit):
            build_parser().parse_args(["--all", "--since", "2024-01"])

    def test_계약이_정한_플래그가_모두_있다(self) -> None:
        args = build_parser().parse_args(
            ["--source", "macro", "--all", "--baseline-month", "2018-06", "--dry-run"]
        )
        assert args.source == "macro"
        assert args.all is True
        assert args.baseline_month == "2018-06"
        assert args.dry_run is True


class _StubSource:
    """종료 코드를 보는 시험이 쓰는 최소 소스. 값이 아니라 흐름만 필요하다."""

    _registry = make_registry({"A": ["A1"]})
    _series = make_series((2020, 1), [1.0] * 12)

    def registry(self):
        return self._registry

    def monthly_series(self, indicator_id: str):
        return dict(self._series)

    def raw_series(self, indicator_id: str):
        return {month: value.raw for month, value in self._series.items()}

    def baseline_months(self):
        return BaselineMonths(by_field={"A": max(self._series)}, composite=max(self._series))

    def publication(self, indicator_id: str, month):
        return None

    def data_version(self) -> str:
        return "STUB-1"

    def changed_since(self, data_version: str):
        return None

    def is_sample_data(self) -> bool:
        return False
