"""경제협력개발기구 어댑터 테스트 (T025).

T018~T020이 원천별 계약 테스트를 셋만 세웠지만 어댑터는 넷이다. 나머지 하나를 검증 없이 두면
묶음 B가 "표본으로 전부 검증된다"는 전제를 채우지 못한다.

표본은 2026-09-18에 실제로 받아 `tests/fixtures/macro/oecd/`에 저장한 응답이다.
"""

from __future__ import annotations

import csv
import io
import json
import pathlib
from decimal import Decimal

import pytest

from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.sources import oecd
from data_pipeline.macro.sources.base import SourceContractError

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro" / "oecd"

G20_CLI = next(i for i in INDICATORS if i.indicator_id == "g20_cli")

#: 표본을 받을 때 응답 헤더가 준 데이터셋 갱신일.
LAST_MODIFIED = "2024-03-07"


def load() -> dict:
    return json.loads((FIXTURES / "g20_cli_aa_ix.json").read_text(encoding="utf-8"))


class TestSeriesKey:
    def test_계열_키가_아홉_차원이다(self) -> None:
        """다섯 자리만 주면 403 Not enough key values in query 가 온다 (pd11 §3)."""
        assert oecd.split_key(G20_CLI) == ("G20", "M", "LI", "", "", "AA", "IX", "", "H")

    def test_진폭조정_지수를_고른다(self) -> None:
        parts = dict(zip(oecd.KEY_DIMENSIONS, oecd.split_key(G20_CLI), strict=True))

        assert parts["ADJUSTMENT"] == "AA"
        assert parts["TRANSFORMATION"] == "IX"

    def test_조회_경로에_인증키_자리가_없다(self) -> None:
        assert oecd.build_path(G20_CLI) == "OECD.SDD.STES,DSD_STES@DF_CLI,/G20.M.LI...AA.IX..H"


class TestParse:
    def test_관측_자리_번호를_대상월로_짝짓는다(self) -> None:
        """자리 번호는 대상월 순서가 아니다. 표본에서 0번이 2018-05, 103번이 2018-06 이다."""
        observations = oecd.parse(G20_CLI, load())

        assert len(observations) == 104
        by_period = {o.period: o.value for o in observations}
        assert by_period["2018-05"] == Decimal("100.5832")
        assert by_period["2018-06"] == Decimal("100.4628")

    def test_대상월_순서로_돌려준다(self) -> None:
        observations = oecd.parse(G20_CLI, load())

        periods = [o.period for o in observations]
        assert periods == sorted(periods)
        assert periods[0] == "2018-01"
        assert periods[-1] == "2026-08"

    def test_최근_값이_원천_확인값과_같다(self) -> None:
        observations = oecd.parse(G20_CLI, load())

        assert observations[-1].value == Decimal("100.2968")

    def test_갱신일은_넘겨받은_헤더값을_쓴다(self) -> None:
        """본문의 prepared 는 응답 생성 시각이라 개정 식별자로 쓰지 않는다 (pd11 §3)."""
        prepared = load()["meta"]["prepared"]

        observations = oecd.parse(G20_CLI, load(), last_modified=LAST_MODIFIED)

        assert {o.source_updated_at for o in observations} == {LAST_MODIFIED}
        assert prepared not in {o.source_updated_at for o in observations}

    def test_갱신일을_넘기지_않으면_비워_둔다(self) -> None:
        observations = oecd.parse(G20_CLI, load())

        assert all(o.source_updated_at is None for o in observations)


class TestGrowthIsOurs:
    """증가율은 지수에서 직접 계산한다. 원천의 TR/GY 계열을 쓰지 않는다 (FR-019)."""

    def test_등록표가_증가율_계열을_고르지_않았다(self) -> None:
        parts = dict(zip(oecd.KEY_DIMENSIONS, oecd.split_key(G20_CLI), strict=True))

        assert parts["TRANSFORMATION"] != "GY"

    def test_원천_증가율_계열은_지수보다_늦다(self) -> None:
        """대조용으로만 둔 표본이다. 이 계열을 쓰면 해외 경기의 기준월이 여섯 달 물린다."""
        text = (FIXTURES / "g20_cli_tr_gy_reference.csv").read_text(encoding="utf-8")
        rows = list(csv.DictReader(io.StringIO(text)))
        reference_last = max(row["TIME_PERIOD"] for row in rows)

        index_last = oecd.parse(G20_CLI, load())[-1].period

        assert reference_last < index_last


class TestContractViolations:
    def test_다른_계열이_오면_멈춘다(self) -> None:
        payload = load()
        dimensions = payload["data"]["structures"][0]["dimensions"]["series"]
        adjustment = next(d for d in dimensions if d["id"] == "ADJUSTMENT")
        adjustment["values"][0]["id"] = "TR"

        with pytest.raises(SourceContractError, match="다른 계열이 섞였는지"):
            oecd.parse(G20_CLI, payload)

    def test_차원_구성이_바뀌면_멈춘다(self) -> None:
        payload = load()
        payload["data"]["structures"][0]["dimensions"]["series"].pop()

        with pytest.raises(SourceContractError, match="아는 차원은"):
            oecd.parse(G20_CLI, payload)

    def test_관측_차원이_대상월이_아니면_멈춘다(self) -> None:
        payload = load()
        payload["data"]["structures"][0]["dimensions"]["observation"][0]["id"] = "OBS_INDEX"

        with pytest.raises(SourceContractError, match="TIME_PERIOD"):
            oecd.parse(G20_CLI, payload)

    def test_대상월보다_많은_관측은_멈춘다(self) -> None:
        payload = load()
        series = payload["data"]["dataSets"][0]["series"]
        next(iter(series.values()))["observations"]["999"] = [1.0, 0]

        with pytest.raises(SourceContractError, match="맞는 대상월이 없다"):
            oecd.parse(G20_CLI, payload)

    def test_자료가_없으면_빈_목록이지_실패가_아니다(self) -> None:
        payload = load()
        payload["data"]["dataSets"][0]["series"] = {}

        assert oecd.parse(G20_CLI, payload) == []

    def test_봉투가_바뀌면_멈춘다(self) -> None:
        with pytest.raises(SourceContractError, match="structures 가 없다"):
            oecd.parse(G20_CLI, {"data": {"dataSets": []}})
