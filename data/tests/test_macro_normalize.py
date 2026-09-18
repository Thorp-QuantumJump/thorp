"""정규화 테스트 (T029) — 원값·변환값 동시 보존과 계보 기록 (FR-006a, FR-007).

월 기말은 마지막 거래일 행을, 월 평균은 그 달 거래일 전부를 계보로 남긴다.
"""

from __future__ import annotations

import json
import pathlib
from dataclasses import replace
from datetime import UTC, date, datetime
from decimal import Decimal

from data_pipeline.macro.normalize import normalize
from data_pipeline.macro.registry import CYCLE_DAILY, INDICATORS
from data_pipeline.macro.sources import ecos, kosis
from data_pipeline.macro.sources.base import SourceObservation
from data_pipeline.macro.transform import monthly

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro"

BY_ID = {indicator.indicator_id: indicator for indicator in INDICATORS}

#: 일별 원천에서 월 평균을 내는 지표. **등록표에는 없다** — 두바이유는 변환이 월 평균이지만
#: 원천이 월값을 직접 주므로 접는 일이 없다(pd11 §1). 접기 경로를 검증하려면 주기를 일별로
#: 선언한 지표가 필요하고, 그것을 여기서 만든다. 새 일별 원천이 들어오면 등록표 쪽이 생긴다.
DAILY_AVERAGE = replace(BY_ID["dubai_crude"], source_cycle=CYCLE_DAILY)


def obs(indicator_id: str, period: str, value: str | None) -> SourceObservation:
    return SourceObservation(
        indicator_id=indicator_id,
        period=period,
        value=None if value is None else Decimal(value),
    )


class TestMonthEndLineage:
    """월 기말은 **고른 그 하루**를 계보로 남긴다."""

    def test_마지막_거래일_행만_계보에_든다(self) -> None:
        daily = [
            obs("treasury_bond_3y", "2026-01-28", "3.0"),
            obs("treasury_bond_3y", "2026-01-29", "3.1"),
            obs("treasury_bond_3y", "2026-01-30", "3.2"),
        ]

        result = normalize(BY_ID["treasury_bond_3y"], daily, as_of=date(2026, 3, 1))

        january = next(r for r in result if r.period == "2026-01")
        assert january.transformed_value == Decimal("3.2")
        assert january.lineage_periods == ("2026-01-30",)
        assert january.raw_values == (("2026-01-30", Decimal("3.2")),)

    def test_원값과_변환값이_함께_남는다(self) -> None:
        daily = [obs("treasury_bond_3y", "2026-01-30", "3.2")]

        result = normalize(BY_ID["treasury_bond_3y"], daily, as_of=date(2026, 3, 1))

        assert result[0].raw_values[0][1] == Decimal("3.2")
        assert result[0].transformed_value == Decimal("3.2")
        assert result[0].raw_unit == "연%"
        assert result[0].transformed_unit == "연%"

    def test_실제_국고채_표본의_계보가_하루씩이다(self) -> None:
        payload = json.loads(
            (FIXTURES / "ecos" / "treasury_bond_3y_daily.json").read_text(encoding="utf-8")
        )
        daily = ecos.parse(BY_ID["treasury_bond_3y"], payload)

        result = normalize(BY_ID["treasury_bond_3y"], daily, as_of=date(2026, 9, 17))

        filled = [r for r in result if not r.is_missing]
        assert filled
        assert all(len(r.lineage_periods) == 1 for r in filled)
        assert all(r.lineage_periods[0].startswith(r.period) for r in filled)


class TestMonthAverageLineage:
    """월 평균은 **그 달 거래일 전부**를 계보로 남긴다."""

    def test_쓴_거래일을_모두_적는다(self) -> None:
        daily = [
            obs("dubai_crude", "2026-01-02", "80"),
            obs("dubai_crude", "2026-01-05", "82"),
            obs("dubai_crude", "2026-01-06", "84"),
        ]
        calendar_days = {"2026-01": ["2026-01-02", "2026-01-05", "2026-01-06"]}

        result = normalize(DAILY_AVERAGE, daily, trading_days=calendar_days)

        assert result[0].transformed_value == Decimal("82.000000")
        assert result[0].lineage_periods == ("2026-01-02", "2026-01-05", "2026-01-06")
        assert [v for _, v in result[0].raw_values] == [
            Decimal("80"),
            Decimal("82"),
            Decimal("84"),
        ]


class TestGrowthLineage:
    def test_증가율은_분자와_분모_두_달을_적는다(self) -> None:
        series = [
            obs("all_industry_production", monthly.shift_months("2025-01", offset), value)
            for offset, value in enumerate(["100"] * 12 + ["110"])
        ]

        result = normalize(BY_ID["all_industry_production"], series)

        january = next(r for r in result if r.period == "2026-01")
        assert january.transformed_value == Decimal("10.000000")
        assert january.lineage_periods == ("2025-01", "2026-01")

    def test_분모의_원값은_그_달_행이_들고_있다(self) -> None:
        """같은 원값을 여러 행에 흩뿌리면 개정이 들어왔을 때 어느 쪽이 최신인지 모른다."""
        series = [
            obs("all_industry_production", monthly.shift_months("2025-01", offset), value)
            for offset, value in enumerate(["100"] * 12 + ["110"])
        ]

        result = normalize(BY_ID["all_industry_production"], series)
        by_period = {r.period: r for r in result}

        january = by_period["2026-01"]
        assert dict(january.raw_values)["2025-01"] == Decimal("100")
        assert dict(january.raw_values)["2026-01"] == Decimal("110")


class TestMissing:
    def test_결측이면_사유가_남고_값이_비어_있다(self) -> None:
        series = [
            obs("all_industry_production", monthly.shift_months("2025-01", offset), value)
            for offset, value in enumerate(["0"] + ["100"] * 11 + ["110"])
        ]

        result = normalize(BY_ID["all_industry_production"], series)
        january = next(r for r in result if r.period == "2026-01")

        assert january.is_missing
        assert january.transformed_value is None
        assert january.missing_reason == monthly.MISSING_NOT_TRANSFORMABLE


class TestTimestamps:
    """대상월·발표일·수신 시각을 섞지 않는다 (FR-007)."""

    def test_발표일을_주지_않는_원천은_비워_둔다(self) -> None:
        received = datetime(2026, 9, 18, 9, 0, tzinfo=UTC)
        daily = [obs("treasury_bond_3y", "2026-01-30", "3.2")]

        result = normalize(
            BY_ID["treasury_bond_3y"], daily, as_of=date(2026, 3, 1), received_at=received
        )

        assert result[0].source_updated_at is None
        assert result[0].received_at == received

    def test_수신_시각을_갱신일로_대신하지_않는다(self) -> None:
        received = datetime(2026, 9, 18, 9, 0, tzinfo=UTC)
        daily = [obs("treasury_bond_3y", "2026-01-30", "3.2")]

        result = normalize(
            BY_ID["treasury_bond_3y"], daily, as_of=date(2026, 3, 1), received_at=received
        )

        assert result[0].source_updated_at != received.isoformat()

    def test_통계표_갱신일을_그대로_싣는다(self) -> None:
        payload = json.loads((FIXTURES / "kosis" / "cpi.json").read_text(encoding="utf-8"))
        series = kosis.parse(BY_ID["consumer_price_index"], payload)

        result = normalize(BY_ID["consumer_price_index"], series)

        latest = next(r for r in result if r.period == "2026-08")
        assert latest.source_updated_at == "2026-08-31"

    def test_여러_원값에서_온_값은_가장_늦은_갱신일을_쓴다(self) -> None:
        daily = [
            SourceObservation("x", "2026-01-02", Decimal("80"), source_updated_at="2026-02-01"),
            SourceObservation("x", "2026-01-05", Decimal("82"), source_updated_at="2026-03-01"),
        ]
        calendar_days = {"2026-01": ["2026-01-02", "2026-01-05"]}

        result = normalize(DAILY_AVERAGE, daily, trading_days=calendar_days)

        assert result[0].source_updated_at == "2026-03-01"


class TestTransformRuleIsRecorded:
    def test_변환_규칙을_값과_함께_남긴다(self) -> None:
        """규칙이 바뀌었을 때 어느 값이 옛 규칙으로 만들어졌는지 알아야 한다."""
        result = normalize(BY_ID["trade_balance"], [obs("trade_balance", "2026-08", "1")])

        assert result[0].transform_rule == BY_ID["trade_balance"].transform_rule
