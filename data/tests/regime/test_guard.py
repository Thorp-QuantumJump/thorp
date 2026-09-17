"""운영 적재 가드 (contracts/macro-indicator-port.md §4.1, tasks.md T008).

설정 실수 하나로 지어낸 값이 운영 DB에 들어가는 경로를 막는다. **가장 오래 남는 것은 코드가
아니라 적재된 행이다** — 코드를 되돌려도 행은 남고, 그 행은 식별 키·기록·화면을 타고 계속
돌아다닌다. 그래서 계산을 시작하기 전에 끝낸다.
"""

from __future__ import annotations

import pytest

from precompute_regime import (
    ENV_VARIABLE,
    PROD_ENV,
    build_parser,
    main,
    reject_fixture_in_production,
)


class TestRejectRule:
    def test_운영에서_임시_대역은_거부한다(self) -> None:
        rejection = reject_fixture_in_production("fixture", PROD_ENV)
        assert rejection is not None
        assert "fixture" in rejection

    @pytest.mark.parametrize("environment", ["PROD", " prod ", "Prod"])
    def test_대소문자와_공백으로_피해_갈_수_없다(self, environment: str) -> None:
        assert reject_fixture_in_production("fixture", environment) is not None

    @pytest.mark.parametrize("environment", [None, "", "local", "dev", "staging"])
    def test_운영이_아니면_통과한다(self, environment: str | None) -> None:
        assert reject_fixture_in_production("fixture", environment) is None

    def test_008_구현체는_운영에서도_통과한다(self) -> None:
        assert reject_fixture_in_production("macro", PROD_ENV) is None


class TestEntryPoint:
    def test_운영_임시_대역_실행은_0이_아닌_코드로_끝난다(
        self, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
    ) -> None:
        monkeypatch.setenv(ENV_VARIABLE, PROD_ENV)
        exit_code = main(["--source", "fixture", "--all"])
        assert exit_code != 0
        assert "거부" in capsys.readouterr().err

    def test_거부는_계산한_것이_없는_실행과_다른_코드로_끝난다(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """둘이 같은 코드면 가드가 막은 것인지 대상이 없던 것인지 실행 결과로 구분할 수 없다."""
        monkeypatch.setenv(ENV_VARIABLE, PROD_ENV)
        rejected = main(["--source", "fixture", "--baseline-month", "1900-01", "--dry-run"])
        monkeypatch.delenv(ENV_VARIABLE)
        nothing_done = main(["--source", "fixture", "--baseline-month", "1900-01", "--dry-run"])
        assert rejected != nothing_done
        assert rejected != 0
        assert nothing_done != 0

    def test_아무것도_계산하지_않은_실행이_성공으로_끝나지_않는다(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """대상이 0개인 실행이 0으로 끝나면 배치가 돈 줄 알고 넘어간다 (spec FR-033)."""
        monkeypatch.delenv(ENV_VARIABLE, raising=False)
        assert main(["--source", "fixture", "--baseline-month", "1900-01", "--dry-run"]) != 0

    def test_잘못된_월_표기는_거부한다(self, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.delenv(ENV_VARIABLE, raising=False)
        assert main(["--source", "fixture", "--since", "2024/01"]) != 0


class TestFlags:
    def test_소스_기본값은_008_구현체다(self) -> None:
        """플래그를 빠뜨린 실행이 임시 대역으로 돌면 가드가 있어도 새어 나간다."""
        assert build_parser().parse_args([]).source == "macro"

    def test_범위_플래그는_함께_쓸_수_없다(self) -> None:
        with pytest.raises(SystemExit):
            build_parser().parse_args(["--all", "--since", "2024-01"])

    def test_계약이_정한_플래그가_모두_있다(self) -> None:
        args = build_parser().parse_args(
            ["--source", "fixture", "--all", "--baseline-month", "2018-06", "--dry-run"]
        )
        assert args.source == "fixture"
        assert args.all is True
        assert args.baseline_month == "2018-06"
        assert args.dry_run is True
