"""월별 변환 테스트 (T021) — 네 변환 유형과 경계 (FR-006, FR-006a, FR-006b).

다루는 경계는 넷이다. 분모 결측·0, 계열 시작 구간, 휴장일, 부분 표본.
"""

from __future__ import annotations

import json
import pathlib
from datetime import date
from decimal import Decimal

import pytest

from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.sources import ecos
from data_pipeline.macro.sources.base import SourceObservation
from data_pipeline.macro.transform import monthly

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro"

BY_ID = {indicator.indicator_id: indicator for indicator in INDICATORS}


def obs(indicator_id: str, period: str, value: str | None) -> SourceObservation:
    return SourceObservation(
        indicator_id=indicator_id,
        period=period,
        value=None if value is None else Decimal(value),
    )


def months(indicator_id: str, start: str, values: list[str | None]) -> list[monthly.MonthlyValue]:
    """``start`` 부터 한 달씩 늘어놓은 월 값 목록을 만든다."""
    return monthly.passthrough(
        [
            obs(indicator_id, monthly.shift_months(start, offset), value)
            for offset, value in enumerate(values)
        ]
    )


class TestMonthlyValue:
    def test_값과_사유가_둘_다_있으면_만들_수_없다(self) -> None:
        with pytest.raises(ValueError, match="하나만 있어야 한다"):
            monthly.MonthlyValue("x", "2026-01", Decimal(1), monthly.MISSING_SOURCE, ())

    def test_값도_사유도_없으면_만들_수_없다(self) -> None:
        with pytest.raises(ValueError, match="하나만 있어야 한다"):
            monthly.MonthlyValue("x", "2026-01", None, None, ())


class TestYearOverYear:
    """전년동월 대비 — 비교월은 t-12 로 고정한다 (FR-006)."""

    def test_열두_달_전과_견준다(self) -> None:
        series = months("x", "2025-01", ["100"] * 12 + ["110"])

        result = {v.period: v for v in monthly.year_over_year(series, series_start="2025-01")}

        assert result["2026-01"].value == Decimal("10.000000")
        assert result["2026-01"].lineage == ("2025-01", "2026-01")

    def test_분모가_결측이면_변환_불가로_남긴다(self) -> None:
        series = months("x", "2025-01", [None] + ["100"] * 11 + ["110"])

        result = {v.period: v for v in monthly.year_over_year(series, series_start="2025-01")}

        assert result["2026-01"].value is None
        assert result["2026-01"].missing_reason == monthly.MISSING_NOT_TRANSFORMABLE

    def test_분모가_0이면_변환_불가로_남긴다(self) -> None:
        """0으로 나눈 값을 무한대나 0으로 대신 채우지 않는다."""
        series = months("x", "2025-01", ["0"] + ["100"] * 11 + ["110"])

        result = {v.period: v for v in monthly.year_over_year(series, series_start="2025-01")}

        assert result["2026-01"].value is None
        assert result["2026-01"].missing_reason == monthly.MISSING_NOT_TRANSFORMABLE

    def test_계열_시작_전_열두_달은_시작_구간으로_남긴다(self) -> None:
        """원천의 결함이 아니라 자료가 거기서 시작할 뿐이다. 변환 불가와 구분한다."""
        series = months("x", "2025-01", ["100"] * 13)

        result = monthly.year_over_year(series, series_start="2025-01")

        early = [v for v in result if v.period < "2026-01"]
        assert len(early) == 12
        assert {v.missing_reason for v in early} == {monthly.MISSING_SERIES_START}

    def test_비교월이_비어도_가까운_달로_물러서지_않는다(self) -> None:
        """t-12 가 없다고 t-11 을 쓰면 다른 달의 증가율과 같은 뜻이 아니게 된다."""
        series = months("x", "2025-01", ["100"] * 13)
        series = [v for v in series if v.period != "2025-01"]

        result = {v.period: v for v in monthly.year_over_year(series, series_start="2025-01")}

        assert result["2026-01"].missing_reason == monthly.MISSING_NOT_TRANSFORMABLE

    def test_분자가_결측이면_원천_결측으로_남긴다(self) -> None:
        series = months("x", "2025-01", ["100"] * 12 + [None])

        result = {v.period: v for v in monthly.year_over_year(series, series_start="2025-01")}

        assert result["2026-01"].missing_reason == monthly.MISSING_SOURCE

    def test_음의_증가율도_그대로_낸다(self) -> None:
        series = months("x", "2025-01", ["200"] * 12 + ["150"])

        result = {v.period: v for v in monthly.year_over_year(series, series_start="2025-01")}

        assert result["2026-01"].value == Decimal("-25.000000")


class TestMonthOverMonth:
    def test_한_달_전과_견준다(self) -> None:
        series = months("x", "2026-01", ["100", "101"])

        result = {v.period: v for v in monthly.month_over_month(series, series_start="2026-01")}

        assert result["2026-02"].value == Decimal("1.000000")
        assert result["2026-01"].missing_reason == monthly.MISSING_SERIES_START


class TestMonthEnd:
    """월 기말 — 그 달 마지막 거래일 (FR-006a)."""

    def test_그_달_마지막_거래일을_고른다(self) -> None:
        daily = [
            obs("x", "2026-01-29", "3.1"),
            obs("x", "2026-01-30", "3.2"),
            obs("x", "2026-02-02", "3.3"),
        ]

        result = {v.period: v for v in monthly.fold_month_end(daily, as_of=date(2026, 3, 1))}

        assert result["2026-01"].value == Decimal("3.2")
        assert result["2026-01"].lineage == ("2026-01-30",)

    def test_휴장일을_채워_넣지_않는다(self) -> None:
        """1월 31일이 휴장이면 30일이 마지막 거래일이다. 31일 행을 만들지 않는다."""
        daily = [obs("x", "2026-01-30", "3.2")]

        result = monthly.fold_month_end(daily, as_of=date(2026, 3, 1))

        assert result[0].lineage == ("2026-01-30",)
        assert "2026-01-31" not in result[0].lineage

    def test_아직_끝나지_않은_달은_기말을_내지_않는다(self) -> None:
        """달이 끝날 때까지 기말이 날마다 바뀌면 어제 본 값과 오늘 본 값이 달라진다."""
        daily = [obs("x", "2026-03-02", "3.5"), obs("x", "2026-03-03", "3.6")]

        result = monthly.fold_month_end(daily, as_of=date(2026, 3, 4))

        assert result[0].value is None
        assert result[0].missing_reason == monthly.MISSING_INCOMPLETE_MONTH

    def test_달이_끝난_당일에는_아직_내지_않는다(self) -> None:
        daily = [obs("x", "2026-01-30", "3.2")]

        assert monthly.fold_month_end(daily, as_of=date(2026, 1, 31))[0].value is None
        assert monthly.fold_month_end(daily, as_of=date(2026, 2, 1))[0].value == Decimal("3.2")

    def test_실제_국고채_표본에서_월_기말을_고른다(self) -> None:
        payload = json.loads(
            (FIXTURES / "ecos" / "treasury_bond_3y_daily.json").read_text(encoding="utf-8")
        )
        daily = ecos.parse(BY_ID["treasury_bond_3y"], payload)

        result = {
            v.period: v for v in monthly.fold_month_end(daily, as_of=date(2026, 9, 17))
        }

        # 1월 31일은 토요일이라 30일이 마지막 거래일이다.
        assert result["2026-01"].lineage == ("2026-01-30",)
        # 9월은 아직 끝나지 않았다.
        assert result["2026-09"].missing_reason == monthly.MISSING_INCOMPLETE_MONTH
        assert all(v.value is not None for p, v in result.items() if p < "2026-09")


class TestMonthAverage:
    """월 평균 — 그 달 거래일이 모두 모였을 때만 (FR-006b)."""

    def test_거래일이_다_모이면_평균을_낸다(self) -> None:
        daily = [obs("x", "2026-01-02", "10"), obs("x", "2026-01-05", "20")]
        calendar_days = {"2026-01": ["2026-01-02", "2026-01-05"]}

        result = monthly.fold_month_average(daily, trading_days=calendar_days)

        assert result[0].value == Decimal("15.000000")
        assert result[0].lineage == ("2026-01-02", "2026-01-05")

    def test_거래일이_하나라도_빠지면_내지_않는다(self) -> None:
        """부분 표본으로 만든 평균은 다른 달의 평균과 같은 뜻이 아니다."""
        daily = [obs("x", "2026-01-02", "10")]
        calendar_days = {"2026-01": ["2026-01-02", "2026-01-05"]}

        result = monthly.fold_month_average(daily, trading_days=calendar_days)

        assert result[0].value is None
        assert result[0].missing_reason == monthly.MISSING_INCOMPLETE_MONTH
        assert result[0].lineage == ("2026-01-02",)

    def test_값이_빈_거래일은_모인_것으로_세지_않는다(self) -> None:
        daily = [obs("x", "2026-01-02", "10"), obs("x", "2026-01-05", None)]
        calendar_days = {"2026-01": ["2026-01-02", "2026-01-05"]}

        result = monthly.fold_month_average(daily, trading_days=calendar_days)

        assert result[0].missing_reason == monthly.MISSING_INCOMPLETE_MONTH

    def test_달력이_없는_달은_완전한지_판정할_수_없다(self) -> None:
        """판정 불가를 판정 통과와 같은 모양으로 두지 않는다."""
        daily = [obs("x", "2026-01-02", "10")]

        result = monthly.fold_month_average(daily, trading_days={})

        assert result[0].missing_reason == monthly.MISSING_INCOMPLETE_MONTH


class TestPassthrough:
    """공식 금액과 원천 계절조정값 — 계산하지 않는다."""

    def test_원천_값을_그대로_쓴다(self) -> None:
        result = monthly.passthrough([obs("trade_balance", "2026-08", "34785482585")])

        assert result[0].value == Decimal("34785482585")
        assert result[0].lineage == ("2026-08",)

    def test_원천_결측은_원천_결측으로_남긴다(self) -> None:
        result = monthly.passthrough([obs("x", "2026-08", None)])

        assert result[0].missing_reason == monthly.MISSING_SOURCE


class TestApply:
    """등록표가 고른 규칙으로 보낸다. 여기서 규칙을 고르지 않는다 (FR-005)."""

    def test_무역수지는_공식_금액을_그대로_쓴다(self) -> None:
        result = monthly.apply(
            BY_ID["trade_balance"], [obs("trade_balance", "2026-08", "34785482585")]
        )

        assert result[0].value == Decimal("34785482585")

    def test_실업률은_원천_계절조정값을_그대로_쓴다(self) -> None:
        result = monthly.apply(
            BY_ID["unemployment_rate"], [obs("unemployment_rate", "2026-08", "2.7")]
        )

        assert result[0].value == Decimal("2.7")

    def test_두바이유는_원천_월값을_쓰고_평균을_다시_내지_않는다(self) -> None:
        """원천이 월 평균을 이미 내 준다 (pd11 §1). 거래일 달력이 없어도 값이 나온다."""
        result = monthly.apply(BY_ID["dubai_crude"], [obs("dubai_crude", "2026-08", "87.5")])

        assert result[0].value == Decimal("87.5")

    def test_환율은_원천_말일자료를_쓰고_접지_않는다(self) -> None:
        result = monthly.apply(BY_ID["usd_krw_rate"], [obs("usd_krw_rate", "2026-08", "1376.5")])

        assert result[0].value == Decimal("1376.5")

    def test_국고채는_일별을_접으므로_as_of가_필요하다(self) -> None:
        daily = [obs("treasury_bond_3y", "2026-01-30", "3.2")]

        with pytest.raises(ValueError, match="as_of 가 필요하다"):
            monthly.apply(BY_ID["treasury_bond_3y"], daily)

    def test_국고채는_월_기말로_접는다(self) -> None:
        daily = [
            obs("treasury_bond_3y", "2026-01-29", "3.1"),
            obs("treasury_bond_3y", "2026-01-30", "3.2"),
        ]

        result = monthly.apply(BY_ID["treasury_bond_3y"], daily, as_of=date(2026, 3, 1))

        assert result[0].value == Decimal("3.2")

    def test_전산업생산은_전년동월_대비로_바꾼다(self) -> None:
        series = [
            obs("all_industry_production", monthly.shift_months("2025-01", offset), value)
            for offset, value in enumerate(["100"] * 12 + ["110"])
        ]

        result = {v.period: v for v in monthly.apply(BY_ID["all_industry_production"], series)}

        assert result["2026-01"].value == Decimal("10.000000")

    def test_주택가격은_전월_대비로_바꾼다(self) -> None:
        series = [
            obs("housing_sale_price", "2026-06", "100"),
            obs("housing_sale_price", "2026-07", "100.5"),
        ]

        result = {v.period: v for v in monthly.apply(BY_ID["housing_sale_price"], series)}

        assert result["2026-07"].value == Decimal("0.500000")

    def test_증가율_지표에_일별_원천이_오면_멈춘다(self) -> None:
        """접지 않고 견주면 month_of 가 여러 날을 같은 달로 접어 대상월이 중복된다."""
        daily = [
            obs("all_industry_production", "2025-01-29", "100"),
            obs("all_industry_production", "2025-01-30", "101"),
        ]

        with pytest.raises(ValueError, match="월 값끼리 견주는 변환"):
            monthly.apply(BY_ID["all_industry_production"], daily)

    def test_모르는_규칙은_조용히_넘기지_않는다(self) -> None:
        broken = BY_ID["exports"].__class__(
            **{**BY_ID["exports"].__dict__, "transform_rule": "MADE_UP"}
        )

        with pytest.raises(ValueError, match="모르는 변환 규칙"):
            monthly.apply(broken, [obs("exports", "2026-08", "1")])


class TestShiftMonths:
    @pytest.mark.parametrize(
        ("period", "months_", "expected"),
        [
            ("2026-01", -12, "2025-01"),
            ("2026-01", -1, "2025-12"),
            ("2026-12", 1, "2027-01"),
            ("2000-01", -1, "1999-12"),
        ],
    )
    def test_달을_옮긴다(self, period: str, months_: int, expected: str) -> None:
        assert monthly.shift_months(period, months_) == expected
