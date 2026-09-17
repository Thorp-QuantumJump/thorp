"""픽스처가 열 가지 **모양**을 실제로 담고 있는가 (contracts/macro-indicator-port.md §4.4).

모양 하나가 빠지면 그 판정 경로가 조용히 검증 밖으로 나간다 — `SCALE_ZERO`도, 정확한 동률도,
±6개월 건너뛰기도 입력이 없으면 시험되지 않고 통과한다. 그래서 값이 아니라 모양을 고정한다.

**어느 지표가 그 모양을 지고 있는지는 여기서 정하지 않는다.** 지표 id를 적어 두면 생성기의
의도를 다시 적는 꼴이라 파일이 바뀌어도 함께 바뀌어 버린다. 대신 계열 전체를 훑어 그 모양이
**어딘가에 있는지**를 찾는다. 지표를 옮겨도 시험은 그대로 성립하고, 모양을 지우면 실패한다.

M2·M3의 판정 코드는 아직 없으므로 척도·거리는 여기서 numpy로 직접 잰다. 그 계산이 규칙의
정답이라는 뜻은 아니다 — 여기서 확인하는 것은 **입력이 그런 모양인가**뿐이다.
"""

from __future__ import annotations

from datetime import date

import numpy as np
import pytest

from data_pipeline.regime.macro import SOURCE_FIXTURE, create_source
from data_pipeline.regime.macro.types import MonthlyValue

#: spec FR-005 — 척도 구간은 t-122 ~ t-3의 최대 120개월이고 최소 60개월 연속이 필요하다.
SCALE_LOOKBACK = 3
SCALE_LENGTH = 120
SCALE_MINIMUM = 60

#: spec FR-001 — 비교 대상은 t-2·t-1·t 세 달이다.
COMPARISON_OFFSETS = (2, 1, 0)

#: spec FR-012 — 인접 후보 건너뛰기 폭.
SKIP_RADIUS = 6

Series = dict[date, MonthlyValue]


def _month_number(month: date) -> int:
    return month.year * 12 + (month.month - 1)


def _shift(month: date, back: int) -> date:
    total = _month_number(month) - back
    return date(total // 12, total % 12 + 1, 1)


def _scale_run(series: Series, baseline: date) -> list[float]:
    """t-3에서 과거로 거슬러 처음 결측을 만날 때까지 (spec FR-005). 최대 120개월."""
    values: list[float] = []
    month = _shift(baseline, SCALE_LOOKBACK)
    while len(values) < SCALE_LENGTH and month in series:
        values.append(series[month].value)
        month = _shift(month, 1)
    return values


def _median_mad_iqr(values: list[float]) -> tuple[float, float, float]:
    array = np.array(values, dtype=float)
    median = float(np.median(array))
    mad = float(np.median(np.abs(array - median)))
    q1, q3 = np.percentile(array, [25, 75])  # 선형 보간 (research R5)
    return median, mad, float(q3 - q1)


def _comparison_block(series: Series, end: date) -> tuple[float, ...] | None:
    """t-2·t-1·t 세 값. 하나라도 없으면 ``None``."""
    months = [_shift(end, back) for back in COMPARISON_OFFSETS]
    if any(month not in series for month in months):
        return None
    return tuple(series[month].value for month in months)


@pytest.fixture(scope="module")
def source():
    return create_source(SOURCE_FIXTURE)


@pytest.fixture(scope="module")
def series_by_indicator(source) -> dict[str, Series]:
    return {
        indicator.id: source.monthly_series(indicator.id)
        for indicator in source.registry().indicators
    }


@pytest.fixture(scope="module")
def months_by_indicator(series_by_indicator) -> dict[str, list[date]]:
    return {
        indicator_id: sorted(series) for indicator_id, series in series_by_indicator.items()
    }


class TestShapesOneToFive:
    def test_shape_1_계열_하나는_구멍이_없다(self, months_by_indicator) -> None:
        """모양 1 — 기본 경로. 결측이 하나도 없는 계열이 있어야 정상 흐름이 시험된다."""
        intact = [
            indicator_id
            for indicator_id, months in months_by_indicator.items()
            if len(months) == _month_number(months[-1]) - _month_number(months[0]) + 1
        ]
        assert intact, "결측 없는 계열이 하나도 없다 — 기본 경로를 시험할 입력이 없다"

    def test_shape_2_척도_구간_한가운데_구멍이_있다(
        self, series_by_indicator, months_by_indicator
    ) -> None:
        """모양 2 — 구멍 때문에 연속 구간이 60개월 미만이 되는 기준월이 있어야 한다.

        계열 **시작**이라서 짧은 것(모양 10)과 구분한다. 구멍 건너편에 더 오래된 자료가
        남아 있어야 "구멍을 건너뛰어 끌어오지 않는다"(FR-005)가 시험된다.
        """
        found = []
        for indicator_id, series in series_by_indicator.items():
            months = months_by_indicator[indicator_id]
            for baseline in months:
                run = _scale_run(series, baseline)
                if not 0 < len(run) < SCALE_MINIMUM:
                    continue
                oldest_used = _shift(baseline, SCALE_LOOKBACK + len(run) - 1)
                older = [month for month in months if month < oldest_used]
                if len(older) >= SCALE_MINIMUM:
                    found.append((indicator_id, baseline, len(run)))
        assert found, "구멍 때문에 연속 구간이 60개월 미만이 되는 기준월이 없다"

    def test_shape_3_MAD가_0이고_IQR은_0이_아닌_창이_있다(
        self, series_by_indicator, months_by_indicator
    ) -> None:
        """모양 3 — IQR 대체 경로 (spec FR-006)."""
        assert self._windows_with(series_by_indicator, months_by_indicator, iqr_zero=False), (
            "MAD가 0인데 IQR이 0이 아닌 창이 없다 — IQR 대체 경로를 시험할 입력이 없다"
        )

    def test_shape_4_MAD도_IQR도_0인_창이_있다(
        self, series_by_indicator, months_by_indicator
    ) -> None:
        """모양 4 — `SCALE_ZERO` 경로 (spec FR-006)."""
        assert self._windows_with(series_by_indicator, months_by_indicator, iqr_zero=True), (
            "MAD와 IQR이 모두 0인 창이 없다 — SCALE_ZERO 경로를 시험할 입력이 없다"
        )

    @staticmethod
    def _windows_with(
        series_by_indicator: dict[str, Series],
        months_by_indicator: dict[str, list[date]],
        *,
        iqr_zero: bool,
    ) -> list[tuple[str, date]]:
        found = []
        for indicator_id, series in series_by_indicator.items():
            for baseline in months_by_indicator[indicator_id]:
                run = _scale_run(series, baseline)
                if len(run) < SCALE_MINIMUM:
                    continue
                _, mad, iqr = _median_mad_iqr(run)
                if mad == 0.0 and (iqr == 0.0) is iqr_zero:
                    found.append((indicator_id, baseline))
        return found

    def test_shape_5_시작이_늦고_최신월도_이른_계열이_있다(self, months_by_indicator) -> None:
        """모양 5 — 그 지표 때문에 T_all이 분야 T_g보다 이르고 더 최신인 개별 발표가 생긴다."""
        starts = {indicator_id: months[0] for indicator_id, months in months_by_indicator.items()}
        ends = {indicator_id: months[-1] for indicator_id, months in months_by_indicator.items()}
        late = [
            indicator_id
            for indicator_id in starts
            if starts[indicator_id] > min(starts.values())
            and ends[indicator_id] < max(ends.values())
        ]
        assert late, "시작이 늦고 최신월도 이른 계열이 없다 — T_all이 T_g보다 이른 상황이 안 생긴다"

    def test_shape_5_T_all이_분야_T_g보다_이르다(self, source) -> None:
        baseline = source.baseline_months()
        assert baseline.composite is not None
        assert baseline.composite < max(baseline.by_field.values()), (
            "T_all이 모든 분야 T_g와 같다 — 기준월이 바뀐다는 알림(US3)을 시험할 입력이 없다"
        )


class TestShapesSixAndSeven:
    """후보 제한 규칙(FR-012)의 입력. 분야 단위로 본다 — 거리는 분야 안에서 합쳐진다."""

    @staticmethod
    def _field_blocks(source, series_by_indicator, field_id: str) -> dict[date, tuple[float, ...]]:
        """그 분야의 지표 값을 종료월마다 한 줄로 이어 붙인다. 하나라도 비면 그 달은 뺀다."""
        indicators = source.registry().indicators_of(field_id)
        months = sorted(set().union(*(set(series_by_indicator[i.id]) for i in indicators)))
        blocks: dict[date, tuple[float, ...]] = {}
        for end in months:
            row: list[float] = []
            for indicator in indicators:
                block = _comparison_block(series_by_indicator[indicator.id], end)
                if block is None:
                    row = []
                    break
                row.extend(block)
            if row:
                blocks[end] = tuple(row)
        return blocks

    def test_shape_6_인접한_후보가_뭉쳐_있다(self, source, series_by_indicator) -> None:
        """모양 6 — 가장 가까운 후보들이 ±6개월 안에 몰려 있어야 건너뛰기가 실제로 작동한다.

        여기서 재는 거리는 **모델 거리가 아니라 대용값**이다(척도로 나누지 않는다). 확인하는
        것은 순위 규칙이 아니라 "뭉친 구간이 입력에 있는가"다.
        """
        clustered = []
        for field in source.registry().fields:
            blocks = self._field_blocks(source, series_by_indicator, field.id)
            if not blocks:
                continue
            query_end = max(blocks)
            query = np.array(blocks[query_end])
            candidates = [
                (float(np.mean(np.abs(np.array(values) - query))), end)
                for end, values in blocks.items()
                if _month_number(query_end) - _month_number(end) > 12
            ]
            nearest = [end for _, end in sorted(candidates)[:10]]
            for anchor in nearest:
                near = [
                    end
                    for end in nearest
                    if abs(_month_number(end) - _month_number(anchor)) <= SKIP_RADIUS
                ]
                if len(near) >= 3:
                    clustered.append((field.id, anchor, len(near)))
                    break
        assert clustered, "상위 후보가 ±6개월 안에 뭉친 분야가 없다 — 건너뛰기를 시험할 입력이 없다"

    def test_shape_7_값이_정확히_같은_두_후보가_있다(self, source, series_by_indicator) -> None:
        """모양 7 — 동률은 **정확한 동률**이어야 한다. 근사 동률은 동률이 아니다(FR-012)."""
        ties = []
        for field in source.registry().fields:
            blocks = self._field_blocks(source, series_by_indicator, field.id)
            seen: dict[tuple[float, ...], date] = {}
            for end in sorted(blocks):
                values = blocks[end]
                earlier = seen.get(values)
                gap = _month_number(end) - _month_number(earlier or end)
                if earlier is not None and gap > SKIP_RADIUS:
                    ties.append((field.id, earlier, end))
                seen.setdefault(values, end)
        assert ties, "비교 시점 값이 완전히 같은 두 후보가 없다 — 동률 규칙을 시험할 입력이 없다"


class TestShapesEightToTen:
    def test_shape_8_질의_세_달_중_한_달만_비는_지표가_있다(
        self, source, series_by_indicator
    ) -> None:
        """모양 8 — 그 지표만 비교 시점이 두 달로 줄고 빠진 지표·월이 표기된다(FR-001·FR-031)."""
        baseline = source.baseline_months()
        partial = []
        for indicator in source.registry().indicators:
            end = baseline.by_field.get(indicator.field_id)
            if end is None:
                continue
            series = series_by_indicator[indicator.id]
            present = [
                back for back in COMPARISON_OFFSETS if _shift(end, back) in series
            ]
            if 0 < len(present) < len(COMPARISON_OFFSETS):
                partial.append((indicator.id, end, len(present)))
        assert partial, (
            "분야 기준월에서 질의 세 달 중 일부만 비는 지표가 없다 — 지표별 비교 시점을 "
            "기본 경로에서 시험할 입력이 없다"
        )

    def test_shape_9_질의_세_달이_모두_비는_지표가_있다(
        self, series_by_indicator, months_by_indicator
    ) -> None:
        """모양 9 — 그 분야가 `QUERY_INPUT_MISSING`이다. 지표를 빼고 계산하지 않는다(FR-007)."""
        found = []
        for indicator_id, series in series_by_indicator.items():
            months = months_by_indicator[indicator_id]
            # **있는 달이 아니라 계열 전 구간을 훑는다.** 세 달이 모두 빈 기준월은 그 자신도
            # 빠져 있으므로, 있는 달만 돌면 찾으려는 모양이 애초에 걸리지 않는다.
            span = range(_month_number(months[0]), _month_number(months[-1]) + 1)
            for number in span:
                end = date(number // 12, number % 12 + 1, 1)
                if any(_shift(end, back) in series for back in COMPARISON_OFFSETS):
                    continue
                # 계열 끝이 아니라 가운데 구멍이어야 '질의 입력 없음'으로 읽힌다.
                if any(month > end for month in months):
                    found.append((indicator_id, end))
        assert found, "질의 세 달이 모두 비는 지표·기준월이 없다 — QUERY_INPUT_MISSING 입력이 없다"

    def test_shape_10_자료_시작_직후_후보가_있다(
        self, series_by_indicator, months_by_indicator
    ) -> None:
        """모양 10 — 비교 시점 입력은 있는데 그 직전 60개월이 없는 후보.

        **후보 자격은 유지되고 시대별 보조 설명만 제한된다**(FR-012·FR-017). 자격까지 잃으면
        자료 시작 구간이 통째로 후보에서 사라진다.
        """
        found = []
        for indicator_id, series in series_by_indicator.items():
            for end in months_by_indicator[indicator_id]:
                if _comparison_block(series, end) is None:
                    continue
                if len(_scale_run(series, end)) < SCALE_MINIMUM:
                    found.append((indicator_id, end))
        assert found, "비교 시점은 있으나 직전 이력이 60개월 미만인 후보가 없다"
