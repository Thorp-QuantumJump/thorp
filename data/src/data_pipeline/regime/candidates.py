"""후보 제한과 순위 (spec FR-012·FR-013).

후보는 질의 종료월 t보다 **12개월 초과 이전**의 종료월만 허용한다. 아래쪽 경계는 두지 않는다 —
자료가 있는 전 구간이 후보이고 실제 범위는 자료 버전이 정한다.

**±6개월 건너뛰기는 하나를 담을 때마다 적용한다** — 거리순 1위를 담고 그 종료월 ±6개월을 남은
후보에서 제외한 뒤, 남은 것 중 다시 1위를 담는 식으로 표시 개수까지 채운다. 상위 10개를 먼저
뽑아 거르지 않는다(그러면 아래 순위의 관계없는 사례까지 함께 잃는다). **정확한 동률**은 더
오래된 종료월을 우선한다 — 저장 자릿수까지 반올림한 거리가 같을 때만 동률이다.

**전체 유효 후보 목록에는 건너뛰기를 적용하지 않는다** — 011의 가용성 필터 입력이다(FR-030).
후보가 부족하면 부족한 대로 둔다. 빈자리를 채우지 않는다(FR-013).
제외한 후보는 입력 결측 / 12개월 이내 / 인접 월 건너뜀 셋으로 구분해 집계한다.

**자격 조건은 비교 시점 입력뿐이다.** 후보 직전 60개월 이력도, 시대별 보조 척도도 자격에 넣지
않는다(FR-012·FR-017) — 넣으면 자료 시작 구간이 통째로 후보에서 사라지고, 보조 설명 하나 때문에
순위가 바뀐다. 그래서 이 모듈은 `scale`·`era`를 부르지 않는다.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date
from typing import Final

from data_pipeline.regime.distance import CandidateDistance, DistanceModel, months_between
from data_pipeline.regime.macro.types import MonthlyValue, require_month

#: 표시 상한 (spec FR-013, data-model §4). 화면이 처음 3개만 펼치는 것은 화면 규칙이다.
DISPLAY_LIMIT: Final[int] = 10

#: 질의 종료월과 이만큼 **넘게** 떨어진 달만 후보다 (spec FR-012).
MINIMUM_GAP_MONTHS: Final[int] = 12

#: 담은 사례의 종료월 앞뒤 이만큼은 남은 후보에서 뺀다 (spec FR-012, D09).
NEIGHBOR_MONTHS: Final[int] = 6

#: 제외 사유 (data-model.md §2의 `candidate_counts`).
MISSING_INPUT: Final[str] = "MISSING_INPUT"
WITHIN_12_MONTHS: Final[str] = "WITHIN_12_MONTHS"
NEIGHBOR_SKIPPED: Final[str] = "NEIGHBOR_SKIPPED"

EXCLUSION_REASONS: Final[tuple[str, ...]] = (MISSING_INPUT, WITHIN_12_MONTHS, NEIGHBOR_SKIPPED)


@dataclass(frozen=True)
class RankedCandidate:
    """전체 유효 후보 목록의 한 줄 (data-model §2.3의 `{endMonth, distance, rank}`)."""

    end_month: date
    distance: float
    rank: int


@dataclass(frozen=True)
class CandidateCounts:
    """후보 집계 (data-model §2의 `candidate_counts`).

    `NEIGHBOR_SKIPPED`는 **전체 유효 후보 목록에서 빠지지 않는다** — 건너뛰기는 표시할 사례를
    고를 때만 적용하고, 목록에는 그대로 남는다(FR-030). 그래서 이 수는 `evaluated`에서 빼는
    값이 아니라 "표시에서 접힌 수"다.
    """

    evaluated: int
    excluded: Mapping[str, int]


@dataclass(frozen=True)
class CandidateSelection:
    """후보 선정 결과.

    `display_limited`는 상한에 걸렸다는 **사실만** 말한다. 남은 후보 수를 여기 두지 않는 이유는
    그 수가 "그만큼 비슷했다"로 읽히기 때문이다(spec FR-013) — 011이 쓰는 `ranked`에는 전부
    들어 있고, 화면에 나가는 쪽에는 수가 없다.
    """

    ranked: tuple[RankedCandidate, ...]
    displayed: tuple[CandidateDistance, ...]
    counts: CandidateCounts
    display_limited: bool


def observable_months(
    series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
) -> tuple[date, ...]:
    """자료에 나타난 모든 달. 후보로 **검토할** 범위이지 후보 자격이 아니다.

    합집합인 이유는 자격 판정을 여기서 미리 하지 않기 위해서다. 교집합으로 좁히면 어느 지표
    하나의 구멍이 그 달을 검토 대상에서 지워, 제외 집계에도 잡히지 않는다.
    """
    months: set[date] = set()
    for series in series_by_indicator.values():
        months.update(series)
    return tuple(sorted(months))


def is_far_enough(baseline_month: date, end_month: date) -> bool:
    """질의 종료월보다 12개월 **초과** 이전인가 (spec FR-012).

    아래쪽 경계는 두지 않는다 — 오래될수록 걸러지는 규칙은 없다.
    """
    return months_between(end_month, baseline_month) > MINIMUM_GAP_MONTHS


def select_candidates(
    model: DistanceModel, months: Iterable[date] | None = None
) -> CandidateSelection:
    """후보를 거르고 줄 세우고 표시할 것을 고른다 (spec FR-012·FR-013).

    검토 범위를 주지 않으면 자료에 있는 모든 달을 본다.
    """
    considered = tuple(sorted(set(observable_months(model.series) if months is None else months)))
    excluded = dict.fromkeys(EXCLUSION_REASONS, 0)

    valid: list[CandidateDistance] = []
    for month in considered:
        require_month(month, "후보 종료월")
        if not is_far_enough(model.baseline_month, month):
            # 12개월 제한을 입력 검사보다 먼저 본다. 그 달은 애초에 후보 범위 밖이라
            # 입력이 있는지 없는지는 물을 일이 아니다.
            excluded[WITHIN_12_MONTHS] += 1
            continue
        if not model.has_inputs_for(month):
            excluded[MISSING_INPUT] += 1
            continue
        valid.append(model.distance_for(month))

    ordered = sorted(valid, key=lambda item: (item.value, item.end_month))
    ranked = tuple(
        RankedCandidate(end_month=item.end_month, distance=item.value, rank=index)
        for index, item in enumerate(ordered, start=1)
    )

    displayed, skipped, leftover = _apply_neighbor_skip(ordered)
    excluded[NEIGHBOR_SKIPPED] = skipped
    return CandidateSelection(
        ranked=ranked,
        displayed=tuple(displayed),
        counts=CandidateCounts(evaluated=len(considered), excluded=excluded),
        display_limited=leftover,
    )


def _apply_neighbor_skip(
    ordered: list[CandidateDistance],
) -> tuple[list[CandidateDistance], int, bool]:
    """±6개월 건너뛰기를 **순차로** 적용한다 (spec FR-012·FR-014).

    하나를 담을 때마다 그 종료월 ±6개월을 남은 후보에서 빼고 다시 1위를 담는다. 상위 10개를
    먼저 뽑아 거르면, 같은 국면을 묶는 목적은 같으면서 그 아래 순위의 관계없는 사례까지 잃는다.

    빈자리를 먼 후보로 채우지 않는다 — 여기서 채워지는 것은 **걸러진 만큼 올라온 다음 순위**이지
    모자란 수를 맞추려고 끌어온 후보가 아니다(FR-013).
    """
    remaining = list(ordered)
    displayed: list[CandidateDistance] = []
    skipped = 0
    while remaining and len(displayed) < DISPLAY_LIMIT:
        head = remaining.pop(0)
        displayed.append(head)
        kept = [
            item
            for item in remaining
            if abs(months_between(item.end_month, head.end_month)) > NEIGHBOR_MONTHS
        ]
        skipped += len(remaining) - len(kept)
        remaining = kept
    # 상한에서 멈춘 뒤 남은 후보는 **제외가 아니다.** 전체 목록에 그대로 있고, 화면에는 상한이
    # 있다는 사실만 알린다(FR-013).
    return displayed, skipped, bool(remaining)
