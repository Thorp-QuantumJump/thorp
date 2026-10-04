"""발행 게이트와 기준월 테스트 (T035·T031) — FR-011, FR-013, FR-016a.

핵심은 하나다. **17개 중 하나라도 유효 변환값이 0건이면 발행이 보류된다.**
"""

from __future__ import annotations

from dataclasses import replace
from decimal import Decimal

import pytest

from data_pipeline.macro import publish
from data_pipeline.macro.normalize import NormalizedObservation
from data_pipeline.macro.registry import (
    FIELD_IDS,
    INDICATORS,
    REVIEW_PENDING_RIGHTS,
    REVIEW_UNVERIFIED,
    REVIEW_VERIFIED,
)
from data_pipeline.macro.transform import monthly

BY_ID = {indicator.indicator_id: indicator for indicator in INDICATORS}
ALL_IDS = tuple(indicator.indicator_id for indicator in INDICATORS)


def value(indicator_id: str, period: str) -> NormalizedObservation:
    indicator = BY_ID[indicator_id]
    return NormalizedObservation(
        indicator_id=indicator_id,
        period=period,
        transformed_value=Decimal("1.0"),
        missing_reason=None,
        raw_values=((period, Decimal("1.0")),),
        transform_rule=indicator.transform_rule,
        raw_unit=indicator.raw_unit,
        transformed_unit=indicator.transformed_unit,
        source_updated_at=None,
    )


def missing(
    indicator_id: str, period: str, reason: str = monthly.MISSING_SOURCE
) -> NormalizedObservation:
    indicator = BY_ID[indicator_id]
    return NormalizedObservation(
        indicator_id=indicator_id,
        period=period,
        transformed_value=None,
        missing_reason=reason,
        raw_values=((period, Decimal("1.0")),),
        transform_rule=indicator.transform_rule,
        raw_unit=indicator.raw_unit,
        transformed_unit=indicator.transformed_unit,
        source_updated_at=None,
    )


def all_filled(*periods: str) -> dict[str, list[NormalizedObservation]]:
    """17개 전부가 같은 달들을 채운 상태."""
    return {i: [value(i, p) for p in periods] for i in ALL_IDS}


class TestGate:
    """발행 게이트 — 두 관문을 모두 지나야 한다 (FR-011, FR-016a)."""

    def test_17개가_다_차면_발행할_수_있다(self) -> None:
        decision = publish.plan_publication(INDICATORS, all_filled("2026-07"))

        assert decision.can_publish
        assert decision.blockers == ()

    @pytest.mark.parametrize("starved", ["all_industry_production", "g20_cli", "trade_balance"])
    def test_하나라도_유효_변환값이_0건이면_보류한다(self, starved: str) -> None:
        observations = all_filled("2026-07")
        observations[starved] = [missing(starved, "2026-07")]

        decision = publish.plan_publication(INDICATORS, observations)

        assert not decision.can_publish
        assert (
            publish.PublicationBlocker(starved, publish.BLOCK_NO_VALID_VALUE) in decision.blockers
        )

    def test_보류되면_기준월을_달지_않는다(self) -> None:
        """보류인데 기준월이 채워져 있으면 그 값이 공개된 것처럼 읽힌다."""
        observations = all_filled("2026-07")
        observations["consumer_price_index"] = [missing("consumer_price_index", "2026-07")]

        decision = publish.plan_publication(INDICATORS, observations)

        assert decision.baselines == ()

    def test_원값만_있는_달은_유효_건수에_넣지_않는다(self) -> None:
        """원값 유무로 세면 17개가 찼다고 판정되면서 화면은 빈다 (SC-008)."""
        observations = all_filled("2026-07")
        raw_only = missing("employed_persons", "2026-07", monthly.MISSING_NOT_TRANSFORMABLE)
        assert raw_only.raw_values[0][1] is not None
        observations["employed_persons"] = [raw_only]

        decision = publish.plan_publication(INDICATORS, observations)

        assert not decision.can_publish
        assert decision.valid_month_counts["employed_persons"] == 0

    def test_수집이_아예_안_된_것과_변환_실패를_구분한다(self) -> None:
        """전자는 수집이 안 돌았다는 뜻이고 후자는 받았으나 못 만들었다는 뜻이다."""
        observations = all_filled("2026-07")
        del observations["service_production"]
        observations["retail_sales_index"] = [missing("retail_sales_index", "2026-07")]

        reasons = {b.indicator_id: b.reason for b in publish.gate(INDICATORS, observations)}

        assert reasons["service_production"] == publish.BLOCK_NOT_COLLECTED
        assert reasons["retail_sales_index"] == publish.BLOCK_NO_VALID_VALUE

    def test_보류_사유를_전부_모은다(self) -> None:
        """하나만 알려 주면 고치고 다시 돌렸을 때 또 나오고 몇 번을 돌릴지 모른다."""
        observations = all_filled("2026-07")
        for starved in ("exports", "imports", "unemployment_rate"):
            observations[starved] = [missing(starved, "2026-07")]

        decision = publish.plan_publication(INDICATORS, observations)

        assert {b.indicator_id for b in decision.blockers} == {
            "exports",
            "imports",
            "unemployment_rate",
        }

    def test_등록표_전부를_받지_않으면_판정하지_않는다(self) -> None:
        with pytest.raises(publish.PublishError, match="17개 전부를 받아야"):
            publish.gate(INDICATORS[:5], all_filled("2026-07"))

    def test_17행이어도_지표_id가_중복되면_판정하지_않는다(self) -> None:
        indicators = (INDICATORS[0], INDICATORS[0], *INDICATORS[2:])

        with pytest.raises(publish.PublishError, match="지표 id 가 겹친다"):
            publish.plan_publication(indicators, all_filled("2026-07"))


class TestLicenseGate:
    """이용 조건 — 미확인만 막는다 (FR-016a)."""

    def test_권리_미확인_지표가_있으면_보류한다(self) -> None:
        indicators = tuple(
            replace(i, license_review_status=REVIEW_UNVERIFIED)
            if i.indicator_id == "dubai_crude"
            else i
            for i in INDICATORS
        )

        decision = publish.plan_publication(indicators, all_filled("2026-07"))

        assert not decision.can_publish
        assert (
            publish.PublicationBlocker("dubai_crude", publish.BLOCK_LICENSE_UNVERIFIED)
            in decision.blockers
        )

    def test_권리_확인_대기는_발행을_막지_않는다(self) -> None:
        """두바이유가 그 상태다. 미확인과 묶으면 하나 때문에 나머지 16개가 멈춘다."""
        assert BY_ID["dubai_crude"].license_review_status == REVIEW_PENDING_RIGHTS

        decision = publish.plan_publication(INDICATORS, all_filled("2026-07"))

        assert decision.can_publish

    @pytest.mark.parametrize("invalid_status", ["UNVERIFED", "", "SUSPENDED"])
    def test_모르는_권리_상태는_발행을_보류한다(self, invalid_status: str) -> None:
        indicators = tuple(
            replace(i, license_review_status=invalid_status)
            if i.indicator_id == "dubai_crude"
            else i
            for i in INDICATORS
        )

        decision = publish.plan_publication(indicators, all_filled("2026-07"))

        assert not decision.can_publish
        assert decision.baselines == ()
        assert (
            publish.PublicationBlocker("dubai_crude", publish.BLOCK_LICENSE_UNVERIFIED)
            in decision.blockers
        )

    def test_등록표의_17개가_미확인이_아니다(self) -> None:
        statuses = {i.license_review_status for i in INDICATORS}

        assert REVIEW_UNVERIFIED not in statuses
        assert statuses <= {REVIEW_VERIFIED, REVIEW_PENDING_RIGHTS}


class TestBaselines:
    """기준월 — 교집합의 최대월 (FR-013)."""

    def test_분야_일곱과_전체_하나를_낸다(self) -> None:
        result = publish.baselines(INDICATORS, all_filled("2026-07"))

        assert len(result) == len(FIELD_IDS) + 1
        assert [b.scope_id for b in result] == [*FIELD_IDS, publish.SCOPE_ALL]

    def test_모두_같은_달을_가지면_그_달이_기준월이다(self) -> None:
        result = publish.baselines(INDICATORS, all_filled("2026-05", "2026-06", "2026-07"))

        assert all(b.reference_month == "2026-07" for b in result)
        assert all(b.held_back_by == () for b in result)

    def test_교집합의_최대월을_고른다(self) -> None:
        """한 지표가 6월까지만 있으면 전체 기준월은 6월이다 — 7월로 당기지 않는다."""
        observations = all_filled("2026-06", "2026-07")
        observations["g20_cli"] = [value("g20_cli", "2026-06")]

        overall = publish.baselines(INDICATORS, observations)[-1]

        assert overall.scope_id == publish.SCOPE_ALL
        assert overall.reference_month == "2026-06"

    def test_기준월을_물린_지표를_남긴다(self) -> None:
        """왜 6월까지만 보이는지 밝히지 않으면 아무도 답하지 못한다 (FR-023)."""
        observations = all_filled("2026-06", "2026-07")
        observations["g20_cli"] = [value("g20_cli", "2026-06")]

        overall = publish.baselines(INDICATORS, observations)[-1]

        assert overall.held_back_by == ("g20_cli",)

    def test_물리지_않은_분야는_빈_배열이다(self) -> None:
        observations = all_filled("2026-06", "2026-07")
        observations["g20_cli"] = [value("g20_cli", "2026-06")]

        by_scope = {b.scope_id: b for b in publish.baselines(INDICATORS, observations)}

        assert by_scope["production"].reference_month == "2026-07"
        assert by_scope["production"].held_back_by == ()
        assert by_scope["overseas"].held_back_by == ()

    def test_공통_대상월이_없으면_자료_없음이다(self) -> None:
        """가장 가까운 달로 대신하지 않는다. 그 달 값이 없는 지표가 비교에 들어간다."""
        observations = all_filled("2026-07")
        observations["g20_cli"] = [value("g20_cli", "2026-06")]

        overall = publish.baselines(INDICATORS, observations)[-1]

        assert overall.reference_month is None
        assert overall.has_data is False

    def test_교집합이_비어도_물린_지표는_남긴다(self) -> None:
        """T_g 가 없어도 후보 월이 있으면 부족 지표를 기록한다 (data-model §3.3)."""
        observations = all_filled("2026-07")
        observations["g20_cli"] = [value("g20_cli", "2026-06")]

        overall = publish.baselines(INDICATORS, observations)[-1]

        assert overall.reference_month is None
        assert overall.held_back_by == ("g20_cli",)

    def test_분야마다_따로_센다(self) -> None:
        """한 분야가 물려도 다른 분야의 기준월은 그대로다."""
        observations = all_filled("2026-06", "2026-07")
        observations["exports"] = [value("exports", "2026-06")]

        by_scope = {b.scope_id: b for b in publish.baselines(INDICATORS, observations)}

        assert by_scope["external_trade"].reference_month == "2026-06"
        assert by_scope["external_trade"].held_back_by == ("exports",)
        assert by_scope["labor"].reference_month == "2026-07"
        assert by_scope["production"].reference_month == "2026-07"


class TestValidMonths:
    def test_결측월을_세지_않는다(self) -> None:
        months = publish.valid_months(
            [
                value("consumer_price_index", "2026-06"),
                missing("consumer_price_index", "2026-07"),
            ]
        )

        assert months == frozenset({"2026-06"})

    def test_지표별_유효_건수를_돌려준다(self) -> None:
        observations = all_filled("2026-05", "2026-06", "2026-07")

        decision = publish.plan_publication(INDICATORS, observations)

        assert set(decision.valid_month_counts) == set(ALL_IDS)
        assert all(count == 3 for count in decision.valid_month_counts.values())


class TestDecisionLookup:
    def test_범위로_기준월을_찾는다(self) -> None:
        decision = publish.plan_publication(INDICATORS, all_filled("2026-07"))

        assert decision.baseline_for(publish.SCOPE_ALL) is not None
        assert decision.baseline_for("production") is not None
        assert decision.baseline_for("없는_분야") is None
