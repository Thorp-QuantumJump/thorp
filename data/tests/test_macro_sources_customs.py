"""관세청 어댑터 테스트 (T020) — 연 단위 분할과 총계 행 제외 (FR-017a).

표본은 2026-09-18에 실제로 받아 `tests/fixtures/macro/customs/`에 저장한 응답이다.
"""

from __future__ import annotations

import pathlib
from datetime import date
from decimal import Decimal

import pytest

from data_pipeline.macro.config import SOURCE_CUSTOMS
from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.sources import customs
from data_pipeline.macro.sources.base import (
    PermanentSourceError,
    SourceContractError,
    split_months,
)

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro" / "customs"

BY_ID = {indicator.indicator_id: indicator for indicator in INDICATORS}
TRADE_INDICATORS = tuple(i for i in INDICATORS if i.primary_source == SOURCE_CUSTOMS)


def load(name: str) -> str:
    return (FIXTURES / f"{name}.xml").read_text(encoding="utf-8")


def test_세_지표가_한_응답에서_나온다() -> None:
    parsed = customs.parse_all(load("newtrade_2026"), TRADE_INDICATORS)

    assert set(parsed.observations) == {"exports", "imports", "trade_balance"}
    assert {len(v) for v in parsed.observations.values()} == {9}


class TestTotalRow:
    """``<year>총계</year>`` 는 그 해 열두 달의 합이라 관측값이 아니다 (FR-017a)."""

    def test_총계_행을_대상월로_세지_않는다(self) -> None:
        observations = customs.parse(BY_ID["exports"], load("newtrade_2026"))

        periods = [o.period for o in observations]
        assert periods == [f"2026-{m:02d}" for m in range(1, 10)]
        assert customs.TOTAL_ROW_MARKER not in periods

    def test_걸러낸_행_수를_세어_돌려준다(self) -> None:
        """조용히 건너뛰면 원천이 합계 행 모양을 바꿔 걸러지지 않게 됐을 때 드러나지 않는다."""
        parsed = customs.parse_all(load("newtrade_2026"), TRADE_INDICATORS)

        assert parsed.excluded_total_rows == 1

    def test_합계_행이_없으면_걸러낸_수가_0이다(self) -> None:
        parsed = customs.parse_all(load("newtrade_1988_empty"), TRADE_INDICATORS)

        assert parsed.excluded_total_rows == 0

    def test_총계_금액이_관측값에_섞이지_않는다(self) -> None:
        """총계는 월값의 열 배 규모라 섞이면 증가율이 통째로 틀어진다."""
        observations = customs.parse(BY_ID["exports"], load("newtrade_2026"))

        total = Decimal("756149650687")
        assert all(o.value != total for o in observations)
        assert sum(o.value for o in observations if o.value) == total


class TestValues:
    def test_수출과_수입을_각자_필드에서_읽는다(self) -> None:
        parsed = customs.parse_all(load("newtrade_2026"), TRADE_INDICATORS)

        exports = {o.period: o.value for o in parsed["exports"]}
        imports = {o.period: o.value for o in parsed["imports"]}
        assert exports["2026-08"] == Decimal("98282430140")
        assert imports["2026-08"] == Decimal("63496947555")

    def test_무역수지는_원천_값이지_수출빼기수입이_아니다(self) -> None:
        """값이 같아도 출처가 다르다. 원천이 정의를 바꾸면 파생식은 조용히 어긋난다 (FR-006)."""
        parsed = customs.parse_all(load("newtrade_2026"), TRADE_INDICATORS)
        balance = {o.period: o.value for o in parsed["trade_balance"]}

        assert balance["2026-08"] == Decimal("34785482585")
        assert BY_ID["trade_balance"].item_code == "balPayments"

    def test_대상월_표기를_YYYY_MM_으로_바꾼다(self) -> None:
        observations = customs.parse(BY_ID["imports"], load("newtrade_2026"))

        assert observations[0].period == "2026-01"

    def test_발표일을_수신_시각으로_채우지_않는다(self) -> None:
        observations = customs.parse(BY_ID["exports"], load("newtrade_2026"))

        assert all(o.source_updated_at is None for o in observations)


class TestEmptyAndRejected:
    def test_1988년은_빈_응답이지_실패가_아니다(self) -> None:
        """1989-01 이 실제 시작월이다 (pd11 §4)."""
        assert customs.parse(BY_ID["exports"], load("newtrade_1988_empty")) == []

    def test_1년_초과_조회는_원천_문구를_그대로_올린다(self) -> None:
        with pytest.raises(PermanentSourceError, match="1년이내"):
            customs.parse(BY_ID["exports"], load("newtrade_over_one_year_error"))

    def test_폐기된_주소_오류를_인증키_문제로_단정하지_않는다(self) -> None:
        """키가 멀쩡해도 옛 주소로 부르면 같은 오류가 온다 (pd11 §4, T047)."""
        payload = (
            '<response><header><resultCode>30</resultCode>'
            "<resultMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</resultMsg>"
            "</header><body/></response>"
        )

        with pytest.raises(PermanentSourceError, match="기준 주소가"):
            customs.parse(BY_ID["exports"], payload)


class TestSplitMonths:
    """전 구간은 연 단위로 나눠야 받아진다 (FR-017a)."""

    def test_상한이_열두_달이다(self) -> None:
        assert customs.MAX_MONTHS == 12

    def test_전_구간을_서른일곱_토막_넘게_나눈다(self) -> None:
        windows = split_months(date(1989, 1, 1), date(2026, 9, 1), customs.MAX_MONTHS)

        assert len(windows) >= 37
        assert windows[0].start == date(1989, 1, 1)
        assert windows[0].end == date(1989, 12, 1)
        assert windows[-1].end == date(2026, 9, 1)

    def test_토막이_겹치지도_빠지지도_않는다(self) -> None:
        windows = split_months(date(1989, 1, 1), date(2026, 9, 1), customs.MAX_MONTHS)

        months = [(w.start.year, w.start.month) for w in windows]
        assert len(months) == len(set(months))
        for earlier, later in zip(windows, windows[1:], strict=False):
            assert earlier.end < later.start

    def test_한_토막이_열두_달을_넘지_않는다(self) -> None:
        windows = split_months(date(1989, 1, 1), date(2026, 9, 1), customs.MAX_MONTHS)

        for window in windows:
            span = (window.end.year - window.start.year) * 12 + (
                window.end.month - window.start.month
            )
            assert span < customs.MAX_MONTHS


class TestContractViolations:
    def test_등록표_항목이_응답_필드가_아니면_멈춘다(self) -> None:
        wrong = BY_ID["exports"].__class__(**{**BY_ID["exports"].__dict__, "item_code": "expCnt"})

        with pytest.raises(SourceContractError, match="응답 필드가 아니다"):
            customs.parse(wrong, load("newtrade_2026"))

    def test_금액_필드가_사라지면_멈춘다(self) -> None:
        payload = (
            load("newtrade_2026")
            .replace("<expDlr>", "<exportDollar>")
            .replace("</expDlr>", "</exportDollar>")
        )

        with pytest.raises(SourceContractError, match="expDlr 가 없다"):
            customs.parse(BY_ID["exports"], payload)

    def test_XML이_아니면_멈춘다(self) -> None:
        with pytest.raises(SourceContractError, match="XML 로 읽지 못했다"):
            customs.parse(BY_ID["exports"], "{}")

    def test_결과_코드가_없으면_멈춘다(self) -> None:
        with pytest.raises(SourceContractError, match="resultCode 가 없다"):
            customs.parse(BY_ID["exports"], "<response><body/></response>")

    def test_인증키를_인자에_넣지_않는다(self) -> None:
        params = customs.build_params("202601", "202612")

        assert params == {"strtYymm": "202601", "endYymm": "202612"}
