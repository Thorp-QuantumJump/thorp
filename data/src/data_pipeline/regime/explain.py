"""비슷한 점·다른 점 문장 조립 (spec FR-015, research R8).

지표 거리가 작은 쪽에서 최대 2개, 큰 쪽에서 최대 2개를 골라 **템플릿으로 조립한다.**
자유 생성하지 않는다 — 생성 문장은 같은 입력에 같은 결과를 보장하지 못해 SC-002가 깨진다.

서술은 **변환 후 값과 단위**로 하고 거리도 같은 값으로 계산한다(spec FR-015, D21 적용 세부).
증가율 지표면 증가율이지 지수 원수준이 아니다 — 설명만 원수준으로 말하면 순위와 설명이 서로
다른 값을 가리킨다. 확률·추천·'대표 유사국면' 같은 표현을 만들지 않는다(FR-022).

**같은 지표가 비슷한 점과 다른 점에 함께 들어가지 않는다.** 들어가면 한 화면에서 "비슷합니다"와
"다릅니다"를 같은 지표에 대고 말하게 된다. 그래서 위·아래에서 뽑되 겹치지 않는 선까지만 뽑고,
지표가 적으면 문장도 적게 나온다 — 모자란 자리를 채우려고 같은 지표를 두 번 쓰지 않는다.

**다른 점은 인용한 두 값이 실제로 다를 때만 만든다.** 지표 거리가 0보다 커도 인용하는 달에서는
두 값이 같을 수 있는데, 그때 "…보다 높습니다"는 그냥 틀린 문장이다. 그런 지표는 건너뛰고 다음
지표로 넘어간다.

문장이 인용하는 달은 **비교 시점 중 가장 최근 달**이다. 문장이 값 하나씩을 말하므로 고를 수밖에
없고, 그중 "지금"에 가장 가까운 달을 쓴다. 관측 구간 전체는 문장이 아니라 사례 자체가 보인다
(FR-015).
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Final

from data_pipeline.regime.distance import CandidateDistance, IndicatorDistance
from data_pipeline.regime.macro.types import IndicatorEntry, MonthlyValue
from data_pipeline.regime.rounding import STORAGE_DECIMALS, equal_at_storage, to_storage_text

#: 한쪽에 담는 문장 수 상한 (research R8).
MAX_SENTENCES: Final[int] = 2

SIMILAR_TEMPLATE: Final[str] = "{name}{particle} {query}{unit}로 당시 {case}{unit}와 비슷합니다."
DIFFERENT_TEMPLATE: Final[str] = (
    "{name}{particle} 지금이 {query}{unit}로 당시 {case}{unit}보다 {verdict}."
)
HIGHER: Final[str] = "높습니다"
LOWER: Final[str] = "낮습니다"


def topic_particle(text: str) -> str:
    """앞 글자에 받침이 있으면 ``은``, 없으면 ``는``.

    R8의 틀은 ``은``·``는`` 한쪽으로 적혀 있지만 지표명과 단위는 둘 다 올 수 있다. 틀을 바꾸는
    것이 아니라 같은 틀을 한국어로 옳게 적는 일이다 — "취업자 수은"이나 "명는"을 내보내지 않는다.
    """
    if not text:
        return "는"
    last = text[-1]
    if "가" <= last <= "힣":
        return "는" if (ord(last) - 0xAC00) % 28 == 0 else "은"
    return "는"


#: 문장에 싣는 기본 소수 자릿수 (S15P21E205-447). 모델 버전의 일부다 — 바꾸면 버전을 올린다.
SENTENCE_DECIMALS: Final[int] = 1


def format_value(value: float, decimals: int = SENTENCE_DECIMALS) -> str:
    """문장에 넣을 값 표기 — 소수 ``decimals``자리에서 반올림하고 꼬리 0을 덜어 낸다.

    저장 자릿수(12)를 그대로 쓰면 증가율 같은 파생 값이 `4.346167`처럼 실려 읽을 수 없다. 표시
    자릿수는 **문장에만** 적용하고 저장 값·거리 계산은 12자리 그대로다. 반올림은 사사오입
    (`ROUND_HALF_UP`)이고, `-0`은 `0`으로 쓴다. ``repr``을 쓰지 않는 이유는 지수 표기(`5e-05`)
    때문이다.
    """
    quantum = Decimal(1).scaleb(-decimals)
    rounded = Decimal(to_storage_text(value)).quantize(quantum, rounding=ROUND_HALF_UP)
    if rounded == 0:
        return "0"
    return f"{rounded.normalize():f}"


def format_pair(query: float, case: float) -> tuple[str, str]:
    """지금·당시 두 값을 함께 표기한다. 기본 한 자리에서 둘이 같게 보이면 다르게 보일 때까지 늘린다.

    "지금이 2.3%로 당시 2.3%보다 높습니다"처럼 판정과 숫자가 어긋나는 문장을 내지 않기 위해서다.
    저장 자릿수까지 같으면 같은 값이므로 기본 자릿수로 둔다.
    """
    for decimals in range(SENTENCE_DECIMALS, STORAGE_DECIMALS + 1):
        pair = format_value(query, decimals), format_value(case, decimals)
        if pair[0] != pair[1]:
            return pair
    return format_value(query), format_value(case)


@dataclass(frozen=True)
class Explanation:
    """한 사례의 설명 문장 (data-model §4의 ``explanation``)."""

    similar: tuple[str, ...]
    different: tuple[str, ...]


def _quoted_months(item: IndicatorDistance) -> tuple[date, date]:
    """인용할 질의 달과 그에 대응하는 후보 달 — 비교 시점 중 가장 최근이다."""
    return item.months[-1], item.case_months[-1]


def _values(
    item: IndicatorDistance, series: Mapping[date, MonthlyValue]
) -> tuple[float, float]:
    month, case_month = _quoted_months(item)
    return series[month].value, series[case_month].value


def similar_sentence(
    entry: IndicatorEntry, item: IndicatorDistance, series: Mapping[date, MonthlyValue]
) -> str:
    query, case = format_pair(*_values(item, series))
    return SIMILAR_TEMPLATE.format(
        name=entry.name,
        particle=topic_particle(entry.name),
        query=query,
        case=case,
        unit=entry.unit,
    )


def different_sentence(
    entry: IndicatorEntry, item: IndicatorDistance, series: Mapping[date, MonthlyValue]
) -> str:
    query, case = _values(item, series)
    query_text, case_text = format_pair(query, case)
    return DIFFERENT_TEMPLATE.format(
        name=entry.name,
        particle=topic_particle(entry.name),
        query=query_text,
        case=case_text,
        unit=entry.unit,
        verdict=HIGHER if query > case else LOWER,
    )


def explanation_for(
    distance: CandidateDistance,
    entries: Mapping[str, IndicatorEntry],
    series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
) -> Explanation:
    """사례 하나의 비슷한 점·다른 점 (spec FR-015, research R8).

    고르는 기준은 **공통 척도로 잰 지표 거리**다(FR-009). 시대별 위치는 여기에 섞지 않는다 —
    그쪽은 후보월 척도로 잰 다른 자이고, 기본 검색 이유에 들어가면 순위와 설명이 서로 다른 것을
    가리킨다(FR-017).
    """
    ordered = sorted(
        (item for field in distance.fields for item in field.indicators),
        key=lambda item: (-item.e, item.indicator_id),
    )
    if not ordered:
        return Explanation(similar=(), different=())

    def sentence_values(item: IndicatorDistance) -> tuple[float, float]:
        return _values(item, series_by_indicator[item.indicator_id])

    if ordered[0].e == 0.0:
        # 가장 먼 지표까지 저장 자릿수로 0이다 — 다른 점이 없다. 이때 "보다 높습니다"를 만들면
        # 없는 차이를 말하게 된다.
        return Explanation(
            similar=tuple(
                similar_sentence(
                    entries[item.indicator_id], item, series_by_indicator[item.indicator_id]
                )
                for item in ordered[:MAX_SENTENCES]
            ),
            different=(),
        )

    limit = min(MAX_SENTENCES, max(1, len(ordered) // 2))
    different: list[str] = []
    used: set[str] = set()
    for item in ordered:
        if len(different) == limit:
            break
        query, case = sentence_values(item)
        if equal_at_storage(query, case):
            continue
        different.append(
            different_sentence(
                entries[item.indicator_id], item, series_by_indicator[item.indicator_id]
            )
        )
        used.add(item.indicator_id)

    similar: list[str] = []
    for item in reversed(ordered):
        if len(similar) == limit:
            break
        if item.indicator_id in used:
            continue
        similar.append(
            similar_sentence(
                entries[item.indicator_id], item, series_by_indicator[item.indicator_id]
            )
        )

    return Explanation(similar=tuple(similar), different=tuple(different))
