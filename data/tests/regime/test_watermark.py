"""재계산 범위 판정 (spec FR-030, research R3, tasks.md T058·T059).

확인하는 것은 **네 경로가 한 규칙으로 덮이는가**다. M이 계산에 들어가는 길은 질의 비교 시점·
공통 척도 구간·후보 관측 구간·시대별 보조 척도 구간 넷인데, 넷 중 가장 이른 것이 `t = M`이라
나머지가 그 안에 들어간다. 경로마다 다른 경계를 쓰면 셋은 맞고 하나는 틀린 상태가 만들어지고,
그 틀림은 "설명 문장만 낡음"처럼 눈에 띄지 않는 모양으로 남는다.

**경계값은 다른 모듈의 상수에서 끌어온다.** 여기에 숫자를 적어 두면 규칙이 바뀌어도 이 시험이
따라 바뀌지 않아, 낡은 경계를 지키는 시험만 남는다.
"""

from __future__ import annotations

from datetime import date

import pytest
from conftest import make_registry, make_series

from data_pipeline.regime.candidates import MINIMUM_GAP_MONTHS
from data_pipeline.regime.comparison import COMPARISON_OFFSETS
from data_pipeline.regime.macro.types import MacroSourceError
from data_pipeline.regime.scale import (
    WINDOW_END_OFFSET,
    WINDOW_START_OFFSET,
    shift_month,
)
from data_pipeline.regime.watermark import (
    ORIGIN_CHANGED_SINCE,
    ORIGIN_REQUESTED,
    ORIGIN_SERIES_START,
    Watermark,
    recomputed_months,
    series_start,
    watermark_for,
)

CHANGED = date(2010, 6, 1)


def months_after(month: date, count: int) -> date:
    return shift_month(month, -count)


class StubSource:
    """`changed_since()`만 흉내 내는 소스. 워터마크 판정에 쓰는 것이 그 하나다."""

    def __init__(self, changed: date | None) -> None:
        self._changed = changed
        self.asked: list[str] = []

    def changed_since(self, data_version: str) -> date | None:
        self.asked.append(data_version)
        return self._changed


@pytest.fixture
def series() -> dict[str, dict[date, float]]:
    registry = make_registry({"A": ["A1"]})
    return {entry.id: make_series((1994, 1), [1.0] * 400) for entry in registry.indicators}


class TestFourPaths:
    """M이 닿는 네 경로의 경계가 모두 `t ≥ M` 안에 있는가 (research R3)."""

    def test_질의_비교_시점_경계(self) -> None:
        """M이 `t-2 ~ t`에 들어가는 가장 이른 t는 M 자신이다 — 네 경로 중 가장 넓다."""
        watermark = Watermark(month=CHANGED, origin=ORIGIN_CHANGED_SINCE)
        earliest = CHANGED  # t = M 일 때 M 이 비교 시점의 마지막 달이다
        assert watermark.covers(earliest)
        assert max(COMPARISON_OFFSETS) == 2
        for offset in COMPARISON_OFFSETS:
            assert watermark.covers(months_after(CHANGED, offset))

    def test_공통_척도_구간_경계(self) -> None:
        """M이 `[t-122, t-3]`에 처음 들어가는 t는 M+3이고, 마지막은 M+122다."""
        watermark = Watermark(month=CHANGED, origin=ORIGIN_CHANGED_SINCE)
        first = months_after(CHANGED, WINDOW_END_OFFSET)
        last = months_after(CHANGED, WINDOW_START_OFFSET)
        assert watermark.covers(first)
        assert watermark.covers(last)

    def test_후보_관측_구간_경계(self) -> None:
        """후보는 t보다 12개월 **초과** 이전이므로 M이 후보에 닿는 가장 이른 t는 M+13이다."""
        watermark = Watermark(month=CHANGED, origin=ORIGIN_CHANGED_SINCE)
        assert watermark.covers(months_after(CHANGED, MINIMUM_GAP_MONTHS + 1))

    def test_시대별_보조_척도_구간_경계(self) -> None:
        """가장 늦게 닿는 경로다 — `c ≥ M+3`이고 `t > c+12`라 t는 M+16부터다.

        순위가 같아도 이 경로 하나 때문에 결과가 달라질 수 있다. 보조 설명이 바뀌면 화면에
        나가는 것이 달라지므로 새 결과다(FR-017·FR-030).
        """
        watermark = Watermark(month=CHANGED, origin=ORIGIN_CHANGED_SINCE)
        earliest = months_after(CHANGED, WINDOW_END_OFFSET + MINIMUM_GAP_MONTHS + 1)
        assert watermark.covers(earliest)

    def test_가장_넓은_경로가_나머지를_덮는다(self) -> None:
        """네 경계 중 가장 이른 것이 `t = M`이라 규칙이 한 줄이다 (research R3)."""
        boundaries = (
            CHANGED,
            months_after(CHANGED, WINDOW_END_OFFSET),
            months_after(CHANGED, MINIMUM_GAP_MONTHS + 1),
            months_after(CHANGED, WINDOW_END_OFFSET + MINIMUM_GAP_MONTHS + 1),
        )
        assert min(boundaries) == CHANGED

    def test_M_직전_달은_대상이_아니다(self) -> None:
        """`t < M`은 네 경로 어디로도 M이 들어가지 않는다 — 다시 계산하면 낭비다."""
        watermark = Watermark(month=CHANGED, origin=ORIGIN_CHANGED_SINCE)
        assert not watermark.covers(shift_month(CHANGED, 1))


class TestRecomputedMonths:
    def test_t_가_M_이상인_달만_오름차순으로_추린다(self) -> None:
        watermark = Watermark(month=CHANGED, origin=ORIGIN_CHANGED_SINCE)
        months = [date(2012, 1, 1), date(2009, 1, 1), CHANGED, shift_month(CHANGED, 1)]
        assert recomputed_months(months, watermark) == (CHANGED, date(2012, 1, 1))

    def test_대상이_없으면_빈_튜플이다(self) -> None:
        watermark = Watermark(month=date(2030, 1, 1), origin=ORIGIN_CHANGED_SINCE)
        assert recomputed_months([CHANGED], watermark) == ()


class TestWatermarkFor:
    def test_008이_준_달을_쓴다(self, series: dict[str, dict[date, float]]) -> None:
        source = StubSource(CHANGED)
        watermark = watermark_for(source, series, loaded_data_version="MACRO-1")
        assert watermark == Watermark(month=CHANGED, origin=ORIGIN_CHANGED_SINCE)
        assert source.asked == ["MACRO-1"]

    def test_008이_답하지_못하면_계열_시작월로_물러난다(
        self, series: dict[str, dict[date, float]]
    ) -> None:
        """모르는 것을 '바뀐 것 없음'으로 읽으면 개편 뒤에도 낡은 결과가 최신인 채 남는다."""
        watermark = watermark_for(StubSource(None), series, loaded_data_version="MACRO-1")
        assert watermark == Watermark(month=date(1994, 1, 1), origin=ORIGIN_SERIES_START)

    def test_적재된_자료_버전이_없으면_전_구간이다(
        self, series: dict[str, dict[date, float]]
    ) -> None:
        """첫 적재다. 008에게 '무엇이 바뀌었나'를 물을 기준이 없다."""
        source = StubSource(CHANGED)
        watermark = watermark_for(source, series, loaded_data_version=None)
        assert watermark.origin == ORIGIN_SERIES_START
        assert source.asked == []

    def test_사람이_지정한_범위가_이긴다(self, series: dict[str, dict[date, float]]) -> None:
        watermark = watermark_for(
            StubSource(CHANGED),
            series,
            loaded_data_version="MACRO-1",
            requested=date(2020, 1, 1),
        )
        assert watermark == Watermark(month=date(2020, 1, 1), origin=ORIGIN_REQUESTED)

    def test_계열_시작월은_합집합의_최솟값이다(self) -> None:
        """교집합으로 좁히면 시작이 늦은 지표 하나가 그보다 이른 기준월을 통째로 뺀다."""
        registry = make_registry({"A": ["A1", "A2"]})
        both = {
            registry.indicators[0].id: make_series((1994, 1), [1.0] * 100),
            registry.indicators[1].id: make_series((2005, 1), [1.0] * 100),
        }
        assert series_start(both) == date(1994, 1, 1)

    def test_계열이_비면_시작월을_정하지_않는다(self) -> None:
        with pytest.raises(MacroSourceError):
            series_start({})


class TestWatermarkValue:
    def test_모르는_출처는_거부한다(self) -> None:
        with pytest.raises(MacroSourceError):
            Watermark(month=CHANGED, origin="GUESSED")

    def test_달의_1일이_아니면_거부한다(self) -> None:
        with pytest.raises(MacroSourceError):
            Watermark(month=date(2010, 6, 15), origin=ORIGIN_REQUESTED)

    def test_기록에_남길_표기가_있다(self) -> None:
        """전 구간이 다시 돈 이유가 기록에 없으면 그 상태를 정상으로 착각한다."""
        assert Watermark(month=CHANGED, origin=ORIGIN_SERIES_START).describe() == (
            "2010-06(SERIES_START)"
        )
