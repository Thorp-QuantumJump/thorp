"""재계산 범위 판정 (spec FR-030, research R3).

`changed_since(data_version)`이 돌려준 가장 오래된 대상월 M에 대해 **t ≥ M인 기준월만** 다시
계산한다. 008이 답할 수 없어 `None`이 오면 계열 시작월을 M으로 삼아 전 구간을 계산한다 —
모르는 것을 '바뀐 것 없음'으로 해석하지 않는다.

**규칙이 `t ≥ M` 한 줄인 이유**는 M이 계산에 들어가는 경로가 넷인데 넷 다 같은 답을 주기
때문이다(research R3).

| 경로 | M이 닿는 t |
|---|---|
| 질의 비교 시점 `t-2 ~ t` | t ≥ M |
| 공통 척도 구간 `t-122 ~ t-3` | t ≥ M+3 |
| 후보 관측 구간 (후보는 t-12 이전) | t ≥ M+13 |
| 시대별 보조 척도 구간 `c-122 ~ c-3` | t ≥ M+16 |

가장 넓은 것이 첫 줄이라 나머지를 덮는다. 그래서 **의존 그래프가 아니라 워터마크 하나**다.
경로마다 다른 경계를 두면 셋은 맞고 하나는 틀린 상태가 만들어지는데, 그 틀림은 "설명 문장만
낡음"처럼 눈에 띄지 않는 모양으로 남는다.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date
from typing import Final

from data_pipeline.regime.macro.source import MacroIndicatorSource
from data_pipeline.regime.macro.types import (
    MacroSourceError,
    MonthlyValue,
    format_month,
    require_month,
)

#: 008이 바뀐 달을 알려 줬다.
ORIGIN_CHANGED_SINCE: Final[str] = "CHANGED_SINCE"

#: 008이 답하지 못해 계열 시작월로 물러났다. 첫 적재도 여기다.
ORIGIN_SERIES_START: Final[str] = "SERIES_START"

#: 사람이 `--all`·`--since`로 범위를 지정했다.
ORIGIN_REQUESTED: Final[str] = "REQUESTED"

ORIGINS: Final[tuple[str, ...]] = (
    ORIGIN_CHANGED_SINCE,
    ORIGIN_SERIES_START,
    ORIGIN_REQUESTED,
)


@dataclass(frozen=True)
class Watermark:
    """재계산을 시작할 달 M과 그 달을 어디서 얻었는지.

    ``origin``을 함께 들고 다니는 이유는 배치 기록에 남기기 위해서다. 전 구간이 다시 돌았을 때
    그것이 **008이 개편을 알렸기 때문인지 답하지 못했기 때문인지**가 기록에 없으면, 배치가 매번
    전 구간을 도는 상태를 정상으로 착각한다.
    """

    month: date
    origin: str

    def __post_init__(self) -> None:
        require_month(self.month, "워터마크 월")
        if self.origin not in ORIGINS:
            raise MacroSourceError(f"모르는 워터마크 출처다: {self.origin!r}")

    def covers(self, baseline_month: date) -> bool:
        """그 기준월이 다시 계산할 대상인가 — ``t ≥ M``."""
        require_month(baseline_month, "기준월")
        return baseline_month >= self.month

    def describe(self) -> str:
        return f"{format_month(self.month)}({self.origin})"


def series_start(series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]]) -> date:
    """모든 계열에 나타난 가장 오래된 달.

    **합집합의 최솟값이다.** 교집합으로 잡으면 시작이 늦은 지표 하나가 그보다 이른 기준월을
    재계산 대상에서 통째로 빼는데, 그 달들에도 분야 검색 결과가 있다(시작이 늦은 지표가 없는
    분야다). 전 구간으로 물러나는 자리에서 구간을 좁히면 물러난 뜻이 사라진다.
    """
    months = [month for series in series_by_indicator.values() for month in series]
    if not months:
        raise MacroSourceError("계열에 달이 하나도 없어 재계산 시작월을 정할 수 없다")
    return min(months)


def watermark_for(
    source: MacroIndicatorSource,
    series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
    *,
    loaded_data_version: str | None,
    requested: date | None = None,
) -> Watermark:
    """이번 실행이 다시 계산할 시작월 M을 정한다 (spec FR-030).

    셋을 순서대로 본다.

    1. 사람이 지정한 범위(``--all``·``--since``)가 있으면 그것이 이긴다.
    2. 적재된 자료 버전이 있고 `changed_since()`가 달을 주면 그 달이다.
    3. 그 밖에는 계열 시작월이다 — **첫 적재와 "008이 모른다" 둘 다 여기로 온다.**

    3번이 전 구간인 것이 핵심이다. `changed_since()`가 ``None``일 때 "바뀐 것 없음"으로 읽고
    아무것도 하지 않으면, 개편으로 값이 통째로 달라진 뒤에도 낡은 결과가 최신인 채로 남는다.
    """
    if requested is not None:
        return Watermark(month=require_month(requested, "지정한 시작월"), origin=ORIGIN_REQUESTED)
    if loaded_data_version:
        changed = source.changed_since(loaded_data_version)
        if changed is not None:
            return Watermark(
                month=require_month(changed, "바뀐 대상월"), origin=ORIGIN_CHANGED_SINCE
            )
    return Watermark(month=series_start(series_by_indicator), origin=ORIGIN_SERIES_START)


def recomputed_months(months: Iterable[date], watermark: Watermark) -> tuple[date, ...]:
    """다시 계산할 기준월만 오름차순으로 추린다 (``t ≥ M``)."""
    return tuple(sorted(month for month in months if watermark.covers(month)))
