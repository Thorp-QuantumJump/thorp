"""변환값이 원값에서 손으로 낸 값과 같은가 (T072, spec 008 SC-007).

**여기서는 변환 코드를 쓰지 않는다.** `transform.monthly` 가 낸 값을 같은 모듈로 다시 계산하면
그 모듈이 틀렸을 때 둘이 똑같이 틀린다. 그래서 원천 응답에서 읽은 숫자로 **이 파일 안에서 다시
계산**해 견준다 — 손계산을 코드로 옮긴 것이고, 옮긴 식은 아래에 그대로 적어 둔다.

    전년동월 대비 = (t 값 / t-12 값 - 1) × 100
    전월 대비     = (t 값 / t-1 값 - 1) × 100
    월 기말       = 그 달 마지막 거래일의 값 그대로
    월 평균       = 그 달 거래일 값의 산술평균
    공식 금액     = 원천 값 그대로
    계절조정 비율 = 원천 값 그대로

허용 오차는 **0**이다. PD-12 가 오차 기준을 정하기 전까지 정확 일치를 요구한다(SC-007). 그래서
비교는 `float` 가 아니라 `Decimal` 로 하고, 반올림 자리는 변환기가 쓰는 것과 같은 자리를 쓴다 —
자리를 다르게 잡으면 끝자리에서만 갈리는 차이를 "계산이 틀렸다"로 읽게 된다.

표본은 2026-09-17 에 17개를 실조회해 저장한 응답이다(`tests/fixtures/macro/`).
"""

from __future__ import annotations

import json
import pathlib
from datetime import date
from decimal import Decimal
from typing import Final

import pytest

from data_pipeline.macro.registry import (
    INDICATORS,
    TRANSFORM_MOM,
    TRANSFORM_MONTH_AVERAGE,
    TRANSFORM_MONTH_END,
    TRANSFORM_OFFICIAL_AMOUNT,
    TRANSFORM_SEASONAL_RATE,
    TRANSFORM_YOY,
    IndicatorDefinition,
)
from data_pipeline.macro.sources import customs, ecos, housing, kosis, oecd
from data_pipeline.macro.sources.base import SourceObservation
from data_pipeline.macro.transform import monthly

FIXTURES: Final[pathlib.Path] = pathlib.Path(__file__).parent / "fixtures" / "macro"

#: 변환기가 증가율을 끊는 자리. 손계산도 같은 자리에서 끊어야 끝자리만 다른 것을 오류로 읽지 않는다.
EXPONENT: Final[Decimal] = monthly.GROWTH_EXPONENT

BY_ID: Final[dict[str, IndicatorDefinition]] = {i.indicator_id: i for i in INDICATORS}

_KOSIS_FILES: Final[dict[str, str]] = {
    "consumer_price_index": "cpi.json",
    "housing_sale_price": "housing_sale_price_current.json",
}

_ECOS_FILES: Final[dict[str, str]] = {
    "dubai_crude": "dubai_crude_monthly.json",
    "treasury_bond_3y": "treasury_bond_3y_daily.json",
    "usd_krw_rate": "usd_krw_monthly.json",
}

#: 저장된 표본만으로는 대조할 수 없는 지표와 그 이유.
#:
#: **지금은 비어 있고, 비어 있는 것이 올바른 상태다.** 여기 지표를 넣으면 그 지표는 SC-007 의
#: 대조를 받지 못한다 — 넣어야 할 상황이면 이유를 함께 적고, 표본이 갖춰지는 대로 지운다.
#: 아래 시험이 이 목록이 비었음을 고정해, 건너뛰기가 조용히 늘지 않게 한다.
SAMPLE_TOO_SHORT: Final[dict[str, str]] = {}


def _linked_housing(indicator: IndicatorDefinition) -> list[SourceObservation]:
    """개편으로 나뉜 표 셋을 이어 하나의 계열로 만든다 (FR-010).

    현행 표 하나만 읽으면 2021-06 부터가 되어 등록표가 말하는 2000-01 과 어긋난다. 이음매를 넘는
    전월 대비를 대조하려면 이어 붙인 뒤여야 한다(T072).
    """
    values_by_base: dict[str, dict[str, object]] = {}
    for table in indicator.link_chain:
        name = f"base_{table.base_month.replace('-', '_')}_{table.table_code}.json"
        path = FIXTURES / "housing" / name
        values_by_base[table.base_month] = housing.parse_table(
            table, json.loads(path.read_text(encoding="utf-8"))
        )
    linked = housing.link(housing.segments_from(values_by_base, indicator.link_chain))
    return [
        SourceObservation(
            indicator_id=indicator.indicator_id,
            period=point.period,
            value=point.value,
            unit=indicator.raw_unit,
        )
        for point in linked.points
    ]


#: 관세청 표본이 담은 해들. 조회 기간이 1년으로 제한돼 해마다 따로 받는다.
_CUSTOMS_YEARS: Final[tuple[str, ...]] = ("newtrade_2025.xml", "newtrade_2026.xml")


def _customs_two_years(indicator: IndicatorDefinition) -> list[SourceObservation]:
    """관세청 표본 두 해를 이어 하나의 계열로 만든다 (T072).

    수출·수입은 전년동월 대비라 한 해만 읽으면 t-12 가 없어 대조할 달이 하나도 없다. 원천이
    조회 기간을 1년으로 제한하므로(FR-017a) 표본도 해마다 따로 받아 여기서 잇는다.
    """
    observations: list[SourceObservation] = []
    for name in _CUSTOMS_YEARS:
        payload = (FIXTURES / "customs" / name).read_text(encoding="utf-8")
        observations.extend(customs.parse(indicator, payload))
    periods = [o.period for o in observations]
    assert len(periods) == len(set(periods)), "표본 두 해가 같은 달을 겹쳐 담았다"
    return sorted(observations, key=lambda o: o.period)


def _observations(indicator: IndicatorDefinition):
    """등록표가 고른 원천에서 그 지표의 관측을 읽는다. 어댑터는 파싱만 하고 변환하지 않는다."""
    name = indicator.indicator_id
    if indicator.link_chain:
        return _linked_housing(indicator)
    if indicator.primary_source == "customs":
        return _customs_two_years(indicator)
    if indicator.primary_source == "ecos":
        payload = json.loads(
            (FIXTURES / "ecos" / _ECOS_FILES[name]).read_text(encoding="utf-8")
        )
        return ecos.parse(indicator, payload)
    if indicator.primary_source == "oecd":
        payload = json.loads(
            (FIXTURES / "oecd" / "g20_cli_aa_ix.json").read_text(encoding="utf-8")
        )
        return oecd.parse(indicator, payload)
    payload = json.loads(
        (FIXTURES / "kosis" / _KOSIS_FILES.get(name, f"{name}.json")).read_text(encoding="utf-8")
    )
    return kosis.parse(indicator, payload)


def _by_period(observations) -> dict[str, Decimal | None]:
    return {o.period: o.value for o in observations}


def _shift(period: str, months: int) -> str:
    index = int(period[:4]) * 12 + int(period[5:7]) - 1 + months
    return f"{index // 12:04d}-{index % 12 + 1:02d}"


def _hand_growth(values: dict[str, Decimal | None], period: str, lag: int) -> Decimal | None:
    """손으로 낸 증가율. 분모가 없거나 0이면 만들지 않는다."""
    current = values.get(period)
    base = values.get(_shift(period, -lag))
    if current is None or base is None or base == 0:
        return None
    return ((current - base) / base * Decimal(100)).quantize(EXPONENT)


def _produced(indicator: IndicatorDefinition, observations) -> dict[str, monthly.MonthlyValue]:
    """파이프라인이 낸 값. 일별 계열은 as_of 를 표본의 마지막 날 다음으로 둔다."""
    as_of = date(2026, 12, 31)
    return {value.period: value for value in monthly.apply(indicator, observations, as_of=as_of)}


def _sample_months(produced: dict[str, monthly.MonthlyValue]) -> list[str]:
    """값이 있는 달에서 셋을 고른다 — 처음·가운데·마지막."""
    filled = sorted(period for period, value in produced.items() if value.value is not None)
    if len(filled) < 3:
        return filled
    return [filled[0], filled[len(filled) // 2], filled[-1]]


@pytest.mark.parametrize("indicator", INDICATORS, ids=lambda i: i.indicator_id)
def test_표본_세_달이_손계산과_정확히_일치한다(indicator: IndicatorDefinition) -> None:
    """지표마다 세 달을 골라 원값에서 다시 계산한 값과 견준다 (SC-007, 허용 오차 0)."""
    if indicator.indicator_id in SAMPLE_TOO_SHORT:
        pytest.skip(SAMPLE_TOO_SHORT[indicator.indicator_id])
    observations = _observations(indicator)
    produced = _produced(indicator, observations)
    values = _by_period(observations)
    months = _sample_months(produced)
    assert len(months) == 3, f"{indicator.indicator_id}: 값이 있는 달이 셋보다 적다"

    for period in months:
        got = produced[period].value
        rule = indicator.transform_rule
        if rule == TRANSFORM_YOY:
            expected = _hand_growth(values, period, 12)
        elif rule == TRANSFORM_MOM:
            expected = _hand_growth(values, period, 1)
        elif rule in (TRANSFORM_OFFICIAL_AMOUNT, TRANSFORM_SEASONAL_RATE):
            expected = values[period]
        elif rule == TRANSFORM_MONTH_END:
            if indicator.source_cycle == "M":
                expected = values[period]
            else:
                days = sorted(p for p in values if p.startswith(period) and values[p] is not None)
                expected = values[days[-1]]
        elif rule == TRANSFORM_MONTH_AVERAGE:
            if indicator.source_cycle == "M":
                expected = values[period]
            else:
                days = [values[p] for p in sorted(values) if p.startswith(period)]
                expected = (sum(days, start=Decimal(0)) / Decimal(len(days))).quantize(EXPONENT)
        else:
            pytest.fail(f"{indicator.indicator_id}: 모르는 변환 {rule}")

        assert got == expected, (
            f"{indicator.indicator_id} {period}: 파이프라인 {got} / 손계산 {expected}"
            f" ({rule}). 허용 오차는 0이다(SC-007)"
        )


@pytest.mark.parametrize(
    "rule",
    [
        TRANSFORM_YOY,
        TRANSFORM_MOM,
        TRANSFORM_MONTH_END,
        TRANSFORM_MONTH_AVERAGE,
        TRANSFORM_OFFICIAL_AMOUNT,
        TRANSFORM_SEASONAL_RATE,
    ],
)
def test_여섯_변환_유형이_모두_대조에_든다(rule: str) -> None:
    """SC-007 은 여섯 유형을 모두 표본에 넣으라고 한다. 하나라도 빠지면 그 경로가 대조 밖이다."""
    assert [i.indicator_id for i in INDICATORS if i.transform_rule == rule]


def test_실업률은_원천_계절조정값을_그대로_둔다() -> None:
    """우리가 다시 계절조정하지 않는다 (FR-001, T072)."""
    indicator = BY_ID["unemployment_rate"]
    observations = _observations(indicator)
    produced = _produced(indicator, observations)
    values = _by_period(observations)
    for period in _sample_months(produced):
        assert produced[period].value == values[period]


def test_결측_직후_달도_비교_대상을_옮기지_않는다() -> None:
    """결측 뒤의 달이 가까운 달로 물러나 계산되지 않는다 (FR-006, T072).

    표본에 결측이 없으면 이 검사가 의미를 잃으므로, 분모를 비워 그 상황을 만든다.
    """
    indicator = BY_ID["consumer_price_index"]
    observations = list(_observations(indicator))
    target = observations[-1].period
    base = _shift(target, -12)
    blanked = [
        o if o.period != base else type(o)(o.indicator_id, o.period, None, o.unit)
        for o in observations
    ]
    produced = _produced(indicator, blanked)
    assert produced[target].value is None
    assert produced[target].missing_reason == monthly.MISSING_NOT_TRANSFORMABLE


def test_대조하지_못한_지표가_늘지_않는다() -> None:
    """표본 한계로 건너뛴 지표를 목록에 고정한다.

    건너뛰기를 조용히 늘리면 SC-007 의 대조 범위가 줄어드는데 스위트는 초록으로 남는다. 줄어든
    것을 보이게 하려고 목록 자체를 시험한다 — 지표가 늘면 여기서 걸린다.
    """
    assert SAMPLE_TOO_SHORT == {}, "표본 한계로 대조를 건너뛴 지표가 생겼다"
    # 건너뛴 목록이 비었다는 것만으로는 부족하다 — 이 시험 파일이 지표마다 표본을 실제로
    # 찾아 주는지도 본다. 표본이 없는 지표는 여기서 걸리고, 대조 시험이 빠뜨린 채 초록으로
    # 남지 않는다.
    for indicator in INDICATORS:
        observations = _observations(indicator)
        assert observations, f"{indicator.indicator_id}: 표본에서 관측을 읽지 못했다"
