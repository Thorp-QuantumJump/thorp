"""v3 종합 비교의 저장 본문 — 블록 거리·블록 설명·잔차 안내.

spec 010 v3 개정안 §3.5·§5, S15P21E205-442.

`snapshot.SnapshotBuilder`가 종합 범위에서만 부른다. 분야 검색은 v1 본문 그대로다.

**문장은 템플릿 조립만 쓴다**(FR-022 ②). 블록 이름은 설정이 준 Thorp 분류 이름이고, 지표 이름·단위는
등록표와 설정이 준 값이다. 이 모듈은 어느 지표도 이름으로 알지 않는다(FR-004).

**설명이 인용하는 값은 순위에 들어간 국면 입력 그대로다**(개정안 §5.1). 순위는 1년 변화로 재는데
설명만 수준으로 말하면 FR-015가 막으려던 모순이 생긴다.
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from typing import Any, Final

from data_pipeline.regime.explain import format_pair, topic_particle
from data_pipeline.regime.macro.types import IndicatorEntry, format_month
from data_pipeline.regime.phase import Block, PhaseDistance, PhaseModel
from data_pipeline.regime.rounding import round_for_storage, to_storage_text
from data_pipeline.regime.scale import WINDOW_MAX_MONTHS, shift_month

#: 한쪽에 담는 블록 문장 수 상한 — v1의 R8과 같다.
MAX_SENTENCES: Final[int] = 2

#: 잔차 안내 문턱 — 척도 구간 잔차 크기의 90번째 백분위 (PD-F3 출발안).
RESIDUAL_PERCENTILE: Final[float] = 0.90

SIMILAR_TEMPLATE: Final[str] = (
    "{block} 흐름은 비슷했습니다. {subject} 지금 {query}{unit}, 당시 {case}{unit}입니다."
)
DIFFERENT_TEMPLATE: Final[str] = (
    "{block} 흐름은 달랐습니다. {subject} 지금 {query}{unit}, 당시 {case}{unit}입니다."
)
RESIDUAL_TEMPLATE: Final[str] = (
    "{block} 안에서 {subject} 지금 {query}{unit}, 당시 {case}{unit}로 "
    "같은 흐름의 다른 지표와 따로 움직였습니다."
)
CASE_UNUSUAL_SENTENCE: Final[str] = (
    "이 기간에는 같은 흐름에 속한 지표들이 평소보다 엇갈려 움직였습니다."
)
QUERY_UNUSUAL_SENTENCE: Final[str] = (
    "지금은 같은 흐름에 속한 지표들이 평소보다 엇갈려 움직이고 있어, "
    "비슷했던 때의 흐름과 다를 수 있습니다."
)

#: 국면 입력 규칙마다 지표 이름 뒤에 붙이는 말. AS_IS는 008 변환값(전년동월 대비 증가율)이다.
KIND_LABELS: Final[dict[str, str]] = {
    "AS_IS": "",
    "YOY_DIFF": " 1년 변화",
    "YOY_PCT": " 1년 변화율",
    "CUM12_FROM_MOM": " 12개월 누적 변화율",
}


def subject_of(name: str, label: str) -> str:
    """문장의 주어 — 지표 이름·국면 입력 이름표에 은/는을 붙인다.

    이름이 괄호로 끝나면 괄호 앞 글자로 조사를 고른다. "건설기성액(불변)는"을 내보내지 않는다.
    """
    text = f"{name}{label}"
    bare = text
    while bare.endswith(")") and "(" in bare:
        bare = bare[: bare.rindex("(")].rstrip()
    return f"{text}{topic_particle(bare)}"


@dataclass(frozen=True)
class PhaseExplanation:
    similar: tuple[str, ...]
    different: tuple[str, ...]
    unusual: bool


def residual_threshold(model: PhaseModel) -> float | None:
    """척도 구간 잔차 크기의 90번째 백분위.

    구간은 t-3에서 거슬러 모든 지표가 있는 연속 달, 최대 120개월이다(블록 척도와 같은 규칙).
    """
    norms: list[float] = []
    month = shift_month(model.baseline_month, 3)
    ids = model.config.indicator_ids
    while len(norms) < WINDOW_MAX_MONTHS and all(month in model.series[i] for i in ids):
        norms.append(model.residual_norm(month))
        month = shift_month(month, 1)
    if not norms:
        return None
    norms.sort()
    position = RESIDUAL_PERCENTILE * (len(norms) - 1)
    low, high = math.floor(position), math.ceil(position)
    return norms[low] + (norms[high] - norms[low]) * (position - low)


def query_note(model: PhaseModel, threshold: float | None) -> str | None:
    """지금 시점 안내. 비교 시점 중 가장 최근 달의 잔차 크기가 문턱을 넘으면 한 문장이다."""
    if threshold is None:
        return None
    return QUERY_UNUSUAL_SENTENCE if model.residual_norm(model.months[-1]) > threshold else None


def explanation_for(
    model: PhaseModel,
    distance: PhaseDistance,
    entries: Mapping[str, IndicatorEntry],
    threshold: float | None,
) -> PhaseExplanation:
    """블록 단위 비슷한 점·다른 점과 잔차 안내 (개정안 §3.5·§5.1).

    - 블록 거리가 작은 쪽이 비슷한 점, 큰 쪽이 다른 점이다. 한쪽 최대 두 블록이며 겹치지 않는다.
    - 블록 문장이 인용하는 지표는 **그 블록 판단을 대표하는 지표**다 — 비슷한 블록은 z 차이가 가장
      작은 지표, 다른 블록은 가장 큰 지표다. 첫 지표를 고정으로 쓰면 "달랐습니다" 뒤에 거의 같은 두
      값이 붙는 문장이 나온다.
    - 블록 요인이 버린 엇갈림은 잔차 차이가 가장 큰 지표(블록 문장이 인용하지 않은 것) 한 문장으로
      다른 점 끝에 붙인다.
    - 사례 기간의 잔차 크기가 문턱을 넘으면 ``unusual``이고 고정 문장 하나를 더한다.
    """
    blocks = {block.id: block for block in model.config.blocks}
    ordered = sorted(distance.blocks, key=lambda b: (b.gap, b.block_id))
    limit = min(MAX_SENTENCES, max(1, len(ordered) // 2))
    similar_ids = [b.block_id for b in ordered[:limit]]
    different_ids = [b.block_id for b in reversed(ordered) if b.block_id not in similar_ids][:limit]

    query_month = model.months[-1]
    case_month = model.case_months_for(distance.end_month)[-1]

    def z_gap(indicator_id: str) -> float:
        scale = model.scales[indicator_id]
        series = model.series[indicator_id]
        return abs(
            scale.standardize(series[query_month].value)
            - scale.standardize(series[case_month].value)
        )

    def sentence(template: str, block: Block, indicator_id: str) -> str:
        kind = model.config.input_kinds[indicator_id]
        series = model.series[indicator_id]
        query_value, unit = model.config.display_of(indicator_id, series[query_month].value)
        case_value, _ = model.config.display_of(indicator_id, series[case_month].value)
        query, case = format_pair(query_value, case_value)
        return template.format(
            block=block.name,
            subject=subject_of(entries[indicator_id].name, KIND_LABELS[kind]),
            query=query,
            case=case,
            unit=unit or entries[indicator_id].unit,
        )

    def representative(block: Block, *, largest: bool) -> str:
        return sorted(block.indicator_ids, key=lambda i: (-z_gap(i) if largest else z_gap(i), i))[0]

    picks = [(i, representative(blocks[i], largest=False)) for i in similar_ids]
    picks_different = [(i, representative(blocks[i], largest=True)) for i in different_ids]
    similar = [sentence(SIMILAR_TEMPLATE, blocks[b], ind) for b, ind in picks]
    different = [sentence(DIFFERENT_TEMPLATE, blocks[b], ind) for b, ind in picks_different]
    cited = {ind for _, ind in picks + picks_different}

    # 이미 블록 문장이 인용한 지표는 건너뛴다 — 같은 두 값을 두 번 읽게 하지 않는다.
    query_r = model.residuals(query_month)
    case_r = model.residuals(case_month)
    gaps = sorted(
        ((abs(query_r[i] - case_r[i]), i) for i in model.config.indicator_ids if i not in cited),
        key=lambda item: (-item[0], item[1]),
    )
    if gaps and round_for_storage(gaps[0][0]) > 0:
        indicator_id = gaps[0][1]
        block = next(b for b in model.config.blocks if indicator_id in b.indicator_ids)
        different.append(sentence(RESIDUAL_TEMPLATE, block, indicator_id))

    unusual = threshold is not None and distance.residual_norm > threshold
    if unusual:
        different.append(CASE_UNUSUAL_SENTENCE)
    return PhaseExplanation(similar=tuple(similar), different=tuple(different), unusual=unusual)


def distance_body(model: PhaseModel, distance: PhaseDistance) -> dict[str, Any]:
    """v3 사례의 거리 본문.

    ``byField``는 비워 둔다 — v3 종합은 분야 거리로 재지 않는다(개정안 §5.3).
    """
    names = {block.id: block.name for block in model.config.blocks}
    total = sum(block.gap for block in distance.blocks)
    body: dict[str, Any] = {
        "value": to_storage_text(distance.value),
        "byField": {},
        "blocks": [
            {
                "id": block.block_id,
                "name": names[block.block_id],
                "gap": to_storage_text(block.gap),
                "spread": to_storage_text(round_for_storage(model.block_spread[block.block_id])),
            }
            for block in distance.blocks
        ],
    }
    threshold = model.config.threshold
    if threshold is not None:
        # 교정된 기준 충족 여부(FR-021). 순위는 바꾸지 않고 사례를 나눌 표시만 남긴다.
        body["meetsThreshold"] = threshold.meets(distance, len(model.months))
    if round_for_storage(total) != 0.0:
        body["blockContribution"] = {
            block.block_id: to_storage_text(block.gap / total) for block in distance.blocks
        }
    return body


def indicator_values_body(
    model: PhaseModel, distance: PhaseDistance, entries: Mapping[str, IndicatorEntry]
) -> dict[str, Any]:
    """지표별 국면 입력 값과 z (FR-028의 수기 대조용). 값은 표준화 전 국면 입력이다."""
    case_months = model.case_months_for(distance.end_month)
    block_size = {
        member.indicator_id: len(block.members)
        for block in model.config.blocks
        for member in block.members
    }
    count = len(model.config.blocks)
    body: dict[str, Any] = {}
    for indicator_id in model.config.indicator_ids:
        series = model.series[indicator_id]
        scale = model.scales[indicator_id]
        query_z = [scale.standardize(series[m].value) for m in model.months]
        case_z = [scale.standardize(series[m].value) for m in case_months]
        e = sum(abs(q - c) for q, c in zip(query_z, case_z, strict=True)) / len(query_z)
        body[indicator_id] = {
            "unit": model.config.input_units.get(indicator_id, entries[indicator_id].unit),
            "comparisonMonths": [format_month(m) for m in model.months],
            "caseMonths": [format_month(m) for m in case_months],
            "queryRaw": [to_storage_text(series[m].value) for m in model.months],
            "caseRaw": [to_storage_text(series[m].value) for m in case_months],
            "queryZ": [to_storage_text(round_for_storage(z)) for z in query_z],
            "caseZ": [to_storage_text(round_for_storage(z)) for z in case_z],
            "e": to_storage_text(round_for_storage(e)),
            "nominalWeight": to_storage_text(
                round_for_storage(1 / block_size[indicator_id] / count)
            ),
        }
    return body


def comparison_body(model: PhaseModel, offsets: Sequence[date]) -> dict[str, Any]:
    """지표별 비교 시점. v3는 달 단위라 모든 지표가 같은 달을 쓴다(개정안 §3.4)."""
    omitted = [format_month(m) for m in offsets if m not in model.months]
    return {
        indicator_id: {"months": [format_month(m) for m in model.months], "omitted": omitted}
        for indicator_id in sorted(model.config.indicator_ids)
    }
