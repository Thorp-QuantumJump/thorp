"""후보 제한과 순위 (spec FR-012·FR-013, tasks.md T051~T055).

여기서 고정하는 것은 다섯이다 — 12개월 제한과 **순차** 건너뛰기, 동률을 푸는 방향, 부족하면
부족한 대로 두는가, 전체 목록에 건너뛰기가 **적용되지 않는가**, 그리고 후보 직전 이력이 없는
사례가 자격을 잃지 않는가.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

from conftest import make_registry, make_series

from data_pipeline.regime import candidates as candidates_module
from data_pipeline.regime.candidates import (
    DISPLAY_LIMIT,
    MISSING_INPUT,
    NEIGHBOR_MONTHS,
    NEIGHBOR_SKIPPED,
    WITHIN_12_MONTHS,
    observable_months,
    select_candidates,
)
from data_pipeline.regime.distance import DistanceModel, build_model, months_between
from data_pipeline.regime.era import era_position_for

START = (2015, 4)
MONTHS = 123
BASELINE = date(2025, 6, 1)


def ramp(period: int, step: float, base: float = 0.0, count: int = MONTHS) -> list[float]:
    return [base + (index % period) * step for index in range(count)]


def build(values: dict[str, list[float | None]], layout: dict[str, list[str]] | None = None):
    layout = layout or {"FIELD_A": ["A1"]}
    model = build_model(
        make_registry(layout),
        BASELINE,
        {key: make_series(START, series) for key, series in values.items()},
    )
    assert isinstance(model, DistanceModel)
    return model


def month_at(index: int) -> date:
    year, month = START[0], START[1] + index
    return date(year + (month - 1) // 12, (month - 1) % 12 + 1, 1)


class TestTwelveMonthLimit:
    """12개월 초과 이전만 후보다. **아래쪽 경계는 두지 않는다** (spec FR-012, T044)."""

    def selection(self):
        return select_candidates(build({"A1": ramp(7, 0.5, 1.0)}))

    def test_열두_달_이내는_후보가_아니다(self) -> None:
        selection = self.selection()
        months = {item.end_month for item in selection.ranked}
        assert all(months_between(month, BASELINE) > 12 for month in months)
        assert date(2024, 6, 1) not in months
        assert date(2024, 5, 1) in months

    def test_오래됐다고_걸러지지_않는다(self) -> None:
        selection = self.selection()
        oldest = min(item.end_month for item in selection.ranked)
        assert oldest == date(2015, 6, 1), "자료가 있는 전 구간이 후보다"

    def test_열두_달_이내는_사유로_집계된다(self) -> None:
        selection = self.selection()
        assert selection.counts.excluded[WITHIN_12_MONTHS] == 13  # t-12 ~ t

    def test_검토_수와_집계가_맞는다(self) -> None:
        selection = self.selection()
        excluded = selection.counts.excluded
        assert selection.counts.evaluated == len(observable_months(build(
            {"A1": ramp(7, 0.5, 1.0)}
        ).series))
        assert (
            len(selection.ranked) + excluded[MISSING_INPUT] + excluded[WITHIN_12_MONTHS]
            == selection.counts.evaluated
        )


class TestNeighborSkip:
    """±6개월 건너뛰기는 **하나를 담을 때마다** 적용한다 (spec FR-012·FR-014, T051)."""

    def selection(self):
        return select_candidates(build({"A1": ramp(7, 0.5, 1.0)}))

    def test_표시된_사례는_서로_여섯_달_넘게_떨어져_있다(self) -> None:
        shown = [item.end_month for item in self.selection().displayed]
        for earlier, later in zip(shown, shown[1:], strict=False):
            assert abs(months_between(earlier, later)) > NEIGHBOR_MONTHS

    def test_걸러진_만큼_아래_순위가_올라와_채운다(self) -> None:
        selection = self.selection()
        assert len(selection.displayed) == DISPLAY_LIMIT
        assert selection.counts.excluded[NEIGHBOR_SKIPPED] > 0

    def test_건너뛴_후보도_전체_목록에는_남는다(self) -> None:
        selection = self.selection()
        shown = {item.end_month for item in selection.displayed}
        listed = {item.end_month for item in selection.ranked}
        assert shown < listed
        skipped_neighbors = [
            month
            for month in listed - shown
            if any(abs(months_between(month, seen)) <= NEIGHBOR_MONTHS for seen in shown)
        ]
        assert skipped_neighbors, "건너뛴 인접 월이 목록에서도 사라지면 011이 다시 적용할 수 없다"

    def test_상한에_걸린_사실만_알린다(self) -> None:
        selection = self.selection()
        assert selection.display_limited is True
        assert not hasattr(selection, "remaining")


class TestTie:
    """정확한 동률은 더 오래된 종료월 우선 (spec FR-012, T052)."""

    def test_같은_거리면_오래된_달이_앞이다(self) -> None:
        #: 주기 12라 12개월 간격의 후보들이 값까지 똑같아 정확한 동률이 된다.
        selection = select_candidates(build({"A1": ramp(12, 0.5, 1.0)}))
        for earlier, later in zip(selection.ranked, selection.ranked[1:], strict=False):
            if earlier.distance == later.distance:
                assert earlier.end_month < later.end_month

    def test_순위는_1부터_연속이다(self) -> None:
        selection = select_candidates(build({"A1": ramp(12, 0.5, 1.0)}))
        assert [item.rank for item in selection.ranked] == list(
            range(1, len(selection.ranked) + 1)
        )


class TestNotPadded:
    """후보가 부족하면 **부족한 대로** 돌려준다 (spec FR-013, SC-004, T053)."""

    def test_두_개뿐이면_두_개다(self) -> None:
        model = build({"A1": ramp(7, 0.5, 1.0)})
        selection = select_candidates(model, [date(2016, 3, 1), date(2020, 9, 1)])
        assert len(selection.displayed) == 2
        assert len(selection.ranked) == 2
        assert selection.display_limited is False

    def test_빈자리를_먼_후보로_채우지_않는다(self) -> None:
        model = build({"A1": ramp(7, 0.5, 1.0)})
        #: 둘은 ±6개월 안이라 하나가 접힌다. 그래도 다른 데서 끌어오지 않는다.
        selection = select_candidates(model, [date(2016, 3, 1), date(2016, 5, 1)])
        assert len(selection.displayed) == 1
        assert selection.counts.excluded[NEIGHBOR_SKIPPED] == 1
        assert len(selection.ranked) == 2


class TestRankedList:
    """전체 유효 후보 목록에는 건너뛰기를 적용하지 않는다 (spec FR-030, T054)."""

    def test_표시보다_길고_순위가_연속이다(self) -> None:
        selection = select_candidates(build({"A1": ramp(7, 0.5, 1.0)}))
        assert len(selection.ranked) > len(selection.displayed)
        assert [item.rank for item in selection.ranked] == list(
            range(1, len(selection.ranked) + 1)
        )

    def test_거리가_오름차순이다(self) -> None:
        selection = select_candidates(build({"A1": ramp(7, 0.5, 1.0)}))
        distances = [item.distance for item in selection.ranked]
        assert distances == sorted(distances)

    def test_인접_월이_목록에_그대로_있다(self) -> None:
        selection = select_candidates(build({"A1": ramp(7, 0.5, 1.0)}))
        months = sorted(item.end_month for item in selection.ranked)
        gaps = [months_between(a, b) for a, b in zip(months, months[1:], strict=False)]
        assert 1 in gaps, "이웃한 달이 목록에서 빠지면 건너뛰기가 적용된 것이다"


class TestEligibilityIsInputsOnly:
    """자격은 비교 시점 입력뿐이다 — 이력도 보조 척도도 조건이 아니다 (FR-012·FR-017, T055)."""

    def test_직전_이력이_없어도_후보로_남는다(self) -> None:
        model = build({"A1": ramp(7, 0.5, 1.0)})
        early = date(2015, 6, 1)  # 자료 시작 직후라 직전 60개월이 없다
        entry = model.indicators[0]
        assert era_position_for(entry, early, model.series["A1"]) is None
        assert early in {item.end_month for item in select_candidates(model).ranked}

    def test_보조_설명이_없어도_거리가_그대로다(self) -> None:
        model = build({"A1": ramp(7, 0.5, 1.0)})
        early = date(2015, 6, 1)
        listed = next(
            item for item in select_candidates(model).ranked if item.end_month == early
        )
        assert listed.distance == model.distance_for(early).value

    def test_입력이_없는_달은_결측으로_집계된다(self) -> None:
        values = ramp(7, 0.5, 1.0)
        values[20] = None
        model = build({"A1": values})
        selection = select_candidates(model)
        assert selection.counts.excluded[MISSING_INPUT] > 0
        assert month_at(20) not in {item.end_month for item in selection.ranked}

    def test_자격_판정이_척도와_보조_설명을_부르지_않는다(self) -> None:
        source = Path(candidates_module.__file__).read_text(encoding="utf-8")
        assert "regime.era" not in source
        assert "regime.scale" not in source
