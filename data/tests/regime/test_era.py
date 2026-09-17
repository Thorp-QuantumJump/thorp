"""시대별 평소 대비 위치 (spec FR-017, SC-011, research R8, tasks.md T042).

여기서 고정하는 것은 넷이다 — 판정과 문장 틀, 이력이 부족하거나 척도가 0일 때 **그 지표만**
빠지는가, 같은 후보월의 보조 위치가 기준월과 무관한가, 그리고 이 값이 순위·거리에 닿지 않는가.

**z가 밖으로 나가지 않는 것**을 값이 아니라 자료구조로 확인한다. 어떤 문장에 숫자가 없는지를
세는 시험은 문장이 하나 늘 때마다 뒤처지지만, 담을 자리가 없으면 늘어날 수 없다.
"""

from __future__ import annotations

import inspect
from dataclasses import fields as dataclass_fields
from datetime import date
from pathlib import Path

import pytest
from conftest import make_series

from data_pipeline.regime import distance as distance_module
from data_pipeline.regime.era import (
    HIGH,
    LOW,
    SIMILAR,
    SIMILAR_THRESHOLD,
    EraPosition,
    EraPositionCache,
    era_position_for,
    era_positions_for,
)
from data_pipeline.regime.explain import format_value
from data_pipeline.regime.macro.types import IndicatorEntry
from data_pipeline.regime.scale import NORMAL_QUARTILE

START = (2005, 1)
MONTHS = 200
END_MONTH = date(2015, 6, 1)
END_INDEX = (2015 - 2005) * 12 + (6 - 1)  # 후보 종료월 c 의 자리

#: 척도 구간(c-122~c-3)의 값 패턴. 중앙값 12.0, MAD 1.0 이라 산포는 1/Φ⁻¹(0.75)다.
WINDOW_MEDIAN = 12.0
WINDOW_SPREAD = 1.0 / NORMAL_QUARTILE


def indicator(unit: str = "%", name: str = "실업률") -> IndicatorEntry:
    return IndicatorEntry(
        id="UNEMPLOYMENT_RATE",
        name=name,
        field_id="FIELD_A",
        unit=unit,
        transform="LEVEL",
        display_order=1,
    )


def series_with(case_value: float | None, *, months: int = MONTHS) -> dict:
    """후보월 값만 갈아 끼운 계열. 척도 구간은 손대지 않는다."""
    values: list[float | None] = [10.0 + (index % 5) for index in range(months)]
    values[END_INDEX] = case_value
    return make_series(START, values)


class TestVerdict:
    """판정은 셋뿐이고 경계값은 모델 버전 항목이다 (research R8)."""

    def test_평소보다_높으면_높은이다(self) -> None:
        position = era_position_for(indicator(), END_MONTH, series_with(20.0))
        assert position is not None
        assert HIGH in position.sentence

    def test_평소보다_낮으면_낮은이다(self) -> None:
        position = era_position_for(indicator(), END_MONTH, series_with(4.0))
        assert position is not None
        assert LOW in position.sentence

    def test_중앙값이면_비슷한이다(self) -> None:
        position = era_position_for(indicator(), END_MONTH, series_with(WINDOW_MEDIAN))
        assert position is not None
        assert SIMILAR in position.sentence

    def test_경계_안쪽은_비슷한이다(self) -> None:
        value = WINDOW_MEDIAN + 0.4 * WINDOW_SPREAD
        position = era_position_for(indicator(), END_MONTH, series_with(value))
        assert position is not None
        assert SIMILAR in position.sentence

    def test_경계_바깥은_높은이다(self) -> None:
        value = WINDOW_MEDIAN + 0.6 * WINDOW_SPREAD
        position = era_position_for(indicator(), END_MONTH, series_with(value))
        assert position is not None
        assert HIGH in position.sentence

    def test_경계값은_0_5다(self) -> None:
        assert SIMILAR_THRESHOLD == 0.5


class TestSentence:
    """문장은 템플릿 조립이며 값과 단위를 그대로 쓴다 (research R8, spec FR-015)."""

    def test_지표명과_값과_단위가_들어간다(self) -> None:
        position = era_position_for(indicator(), END_MONTH, series_with(20.0))
        assert position is not None
        assert position.sentence == "실업률 20%는 당시로서는 높은 편이었습니다."

    def test_받침_있는_단위에는_은이_붙는다(self) -> None:
        position = era_position_for(
            indicator(unit="명", name="취업자 수"), END_MONTH, series_with(20.0)
        )
        assert position is not None
        assert "20명은 당시로서는" in position.sentence

    def test_소수_자릿수를_지어내지_않는다(self) -> None:
        assert format_value(3.2) == "3.2"
        assert format_value(112.0) == "112"
        assert format_value(-0.25) == "-0.25"

    def test_지수_표기가_문장에_실리지_않는다(self) -> None:
        position = era_position_for(indicator(), END_MONTH, series_with(0.00005))
        assert position is not None
        assert "e-" not in position.sentence
        assert "0.00005%" in position.sentence

    def test_좋다_나쁘다를_쓰지_않는다(self) -> None:
        for value in (4.0, WINDOW_MEDIAN, 20.0):
            position = era_position_for(indicator(), END_MONTH, series_with(value))
            assert position is not None
            assert "좋" not in position.sentence
            assert "나쁘" not in position.sentence


class TestNoNumbersEscape:
    """z·분위수·백분율을 내보내지 않는다 (spec FR-017·FR-022 ① 11번, SC-011)."""

    def test_돌려주는_값에_z가_없다(self) -> None:
        names = {field.name for field in dataclass_fields(EraPosition)}
        assert names == {
            "indicator_id",
            "sentence",
            "method",
            "window_start",
            "window_end",
            "months",
        }

    def test_산출_근거는_구간과_방법이다(self) -> None:
        position = era_position_for(indicator(), END_MONTH, series_with(20.0))
        assert position is not None
        assert position.method == "MAD"
        assert position.months == 120
        assert position.window_end == date(2015, 3, 1)
        assert position.window_start == date(2005, 4, 1)

    def test_문장에_분위수_표현이_없다(self) -> None:
        position = era_position_for(indicator(unit="명"), END_MONTH, series_with(20.0))
        assert position is not None
        for banned in ("분위", "백분", "%", "유사", "z"):
            assert banned not in position.sentence


class TestLimitedOnly:
    """만들 수 없으면 **그 지표 설명만** 빠진다 (FR-017, SC-011)."""

    def test_이력이_부족하면_설명이_없다(self) -> None:
        values: list[float | None] = [10.0 + (index % 5) for index in range(MONTHS)]
        values[END_INDEX - 3 - 58] = None  # c-3 에서 거슬러 59개월째에 구멍
        values[END_INDEX] = 20.0
        assert era_position_for(indicator(), END_MONTH, make_series(START, values)) is None

    def test_척도가_0이면_설명이_없다(self) -> None:
        values: list[float | None] = [7.0] * MONTHS
        values[END_INDEX] = 20.0
        assert era_position_for(indicator(), END_MONTH, make_series(START, values)) is None

    def test_서술할_값이_없으면_설명이_없다(self) -> None:
        assert era_position_for(indicator(), END_MONTH, series_with(None)) is None

    def test_못_만든_지표는_키가_없다(self) -> None:
        good = indicator()
        bad = IndicatorEntry(
            id="OTHER",
            name="다른 지표",
            field_id="FIELD_A",
            unit="%",
            transform="LEVEL",
            display_order=2,
        )
        positions = era_positions_for(
            [good, bad],
            END_MONTH,
            {good.id: series_with(20.0), bad.id: series_with(None)},
        )
        assert set(positions) == {good.id}


class TestBaselineIndependent:
    """같은 후보월의 보조 위치는 기준월이 달라도 같다 (SC-011, research R3)."""

    def test_기준월을_인자로_받지_않는다(self) -> None:
        names = list(inspect.signature(era_position_for).parameters)
        assert names == ["indicator", "end_month", "series"]
        assert not any("baseline" in name for name in names)

    def test_같은_후보월이면_같은_값이다(self) -> None:
        series = series_with(20.0)
        assert era_position_for(indicator(), END_MONTH, series) == era_position_for(
            indicator(), END_MONTH, series
        )

    def test_후보월이_다르면_제_시대로_잰다(self) -> None:
        series = series_with(20.0)
        other = date(2016, 6, 1)
        first = era_position_for(indicator(), END_MONTH, series)
        second = era_position_for(indicator(), other, series)
        assert first is not None and second is not None
        assert first.window_end != second.window_end

    def test_같은_지표_같은_후보월은_한_번만_구한다(self) -> None:
        cache = EraPositionCache({indicator().id: series_with(20.0)})
        first = cache.position_for(indicator(), END_MONTH)
        second = cache.position_for(indicator(), END_MONTH)
        assert first is second, "후보월마다 한 번만 구해 나눠 쓴다(research R3)"
        assert cache.months == (END_MONTH,)

    def test_지표_집합이_달라도_각_지표를_다_돌려준다(self) -> None:
        """분야 검색이 먼저 채운 캐시를 종합 검색이 그대로 받으면 안 된다."""
        other = IndicatorEntry(
            id="OTHER",
            name="다른 지표",
            field_id="FIELD_A",
            unit="%",
            transform="LEVEL",
            display_order=2,
        )
        cache = EraPositionCache(
            {indicator().id: series_with(20.0), other.id: series_with(4.0)}
        )
        narrow = cache.positions_for([indicator()], END_MONTH)
        wide = cache.positions_for([indicator(), other], END_MONTH)
        assert set(narrow) == {indicator().id}
        assert set(wide) == {indicator().id, other.id}

    def test_돌려준_결과를_고쳐도_캐시가_상하지_않는다(self) -> None:
        cache = EraPositionCache({indicator().id: series_with(20.0)})
        first = cache.positions_for([indicator()], END_MONTH)
        first.clear()
        assert set(cache.positions_for([indicator()], END_MONTH)) == {indicator().id}

    def test_캐시_키에_기준월이_없다(self) -> None:
        names = list(inspect.signature(EraPositionCache.position_for).parameters)
        assert names == ["self", "indicator", "end_month"]


class TestDoesNotTouchRanking:
    """보조 설명이 순위·거리에 들어가지 않는다 (SC-011, 원칙 IV)."""

    def test_거리_계산이_era를_부르지_않는다(self) -> None:
        source = Path(distance_module.__file__).read_text(encoding="utf-8")
        assert "regime.era" not in source
        assert "import era" not in source

    def test_거리_모듈에_era_이름이_없다(self) -> None:
        assert not [name for name in vars(distance_module) if name.lower().startswith("era")]


@pytest.mark.parametrize("value", [20.0, 4.0, WINDOW_MEDIAN])
def test_판정은_셋_중_하나다(value: float) -> None:
    position = era_position_for(indicator(), END_MONTH, series_with(value))
    assert position is not None
    assert sum(word in position.sentence for word in (HIGH, LOW, SIMILAR)) == 1
