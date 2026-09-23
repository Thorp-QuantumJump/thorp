"""지표별 비교 시점 판정 (spec FR-001).

기준월 t의 비교 대상은 t-2·t-1·t 세 달이고, **자료가 없는 달은 비교에서 뺀다** — 평균이나
직전 값으로 채우지 않는다(FR-007). 이렇게 남은 시점 묶음이 그 지표의 비교 시점이다.

**비교 시점은 지표마다 따로 정한다.** 같은 분야 안에서도 결측 월이 다를 수 있고, 멀쩡한
지표의 자료를 다른 지표의 구멍 때문에 버리지 않는다. **검색 모드가 이 규칙을 바꾸지 않는다** —
종합 검색도 17개 공통 달로 좁히지 않는다. 좁히면 지표 하나의 구멍이 전체의 그 달을 날리고,
같은 분야의 D_g가 모드에 따라 두 값이 되어 분야 검색과 종합 화면의 숫자가 어긋난다(SC-002).

어느 지표의 비교 시점이 하나도 남지 않으면 그 지표가 속한 분야(종합이면 종합)를
`QUERY_INPUT_MISSING`으로 처리한다. **그 지표를 빼고 나머지로 계산하지 않는다**(FR-007·FR-008) —
빼고 계산하면 답은 나오지만 그 답이 무엇에 대한 것인지 아무도 말할 수 없다.

빠진 달은 버리지 않고 `omitted`에 남긴다. 화면이 지표·월 단위로 표기해야 하기 때문이다
(spec FR-031, SC-007).
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date
from typing import Final

from data_pipeline.regime.macro.types import MonthlyValue, require_month
from data_pipeline.regime.scale import shift_month

#: 비교 대상은 t-2·t-1·t다 (spec FR-001). 오래된 달이 앞이다.
COMPARISON_OFFSETS: Final[tuple[int, ...]] = (2, 1, 0)

#: 질의 입력이 없다. 값은 data-model.md §2.1의 `status_reason` 어휘를 그대로 쓴다.
#:
#: 척도 사유와 따로 두는 이유는 **원인이 다르기 때문**이다. 질의 입력이 없는 것은 척도 문제도
#: 후보 문제도 아니라서, 다른 사유에 접어 넣으면 화면이 "척도가 60개월이 안 된다"고 잘못 말한다.
QUERY_INPUT_MISSING: Final[str] = "QUERY_INPUT_MISSING"


@dataclass(frozen=True)
class ComparisonMonths:
    """지표 하나의 비교 시점.

    ``months``가 비면 그 지표는 질의 입력이 없다 — 부르는 쪽이 그 분야를 계산 불가로 만든다.
    """

    indicator_id: str
    months: tuple[date, ...]
    omitted: tuple[date, ...]

    @property
    def is_empty(self) -> bool:
        return not self.months

    @property
    def is_partial(self) -> bool:
        """일부만 남았는가 — 화면이 빠진 지표·월을 표기할 자리다 (spec FR-031)."""
        return bool(self.months) and bool(self.omitted)


@dataclass(frozen=True)
class ScopeComparison:
    """한 분야(또는 종합)의 지표별 비교 시점을 모은 것.

    **계산 가능 여부를 여기서 판정한다.** 판정을 부르는 쪽마다 하면 어딘가 한 곳이 비어 있는
    지표를 빼고 계산하게 되고, 그 자리는 통과하는 테스트만 남긴다.
    """

    by_indicator: dict[str, ComparisonMonths]
    baseline_month: date

    @property
    def missing_indicators(self) -> tuple[str, ...]:
        """비교 시점이 하나도 없는 지표. 하나라도 있으면 이 범위는 계산 불가다."""
        return tuple(
            indicator_id
            for indicator_id, months in sorted(self.by_indicator.items())
            if months.is_empty
        )

    @property
    def computable(self) -> bool:
        return not self.missing_indicators

    @property
    def reason(self) -> str | None:
        """계산 불가 사유. 계산할 수 있으면 ``None``."""
        return None if self.computable else QUERY_INPUT_MISSING

    def require_computable(self) -> None:
        """계산할 수 없는데 계산하려 하면 여기서 멈춘다 (spec FR-007).

        지표를 빼고 나머지로 계산하는 경로를 **코드 수준에서 막는다.** 규칙을 주석으로만 적어
        두면, 급할 때 한 줄 건너뛰는 것으로 어겨지고 결과는 멀쩡해 보인다.
        """
        if not self.computable:
            raise QueryInputMissingError(
                f"비교 시점이 하나도 없는 지표가 있다: {', '.join(self.missing_indicators)}. "
                "그 지표를 빼고 나머지로 계산하지 않는다 (spec FR-007)"
            )


class QueryInputMissingError(RuntimeError):
    """비교 시점이 없는 지표가 있는데 계산을 시도했다."""


def comparison_months_for(
    indicator_id: str,
    baseline_month: date,
    series: Mapping[date, MonthlyValue],
) -> ComparisonMonths:
    """지표 하나의 비교 시점 — t-2·t-1·t 중 자료가 있는 달만 (spec FR-001)."""
    require_month(baseline_month, "기준월")
    months: list[date] = []
    omitted: list[date] = []
    for offset in COMPARISON_OFFSETS:
        month = shift_month(baseline_month, offset)
        (months if month in series else omitted).append(month)
    return ComparisonMonths(
        indicator_id=indicator_id, months=tuple(months), omitted=tuple(omitted)
    )


def comparison_for_scope(
    indicator_ids: Iterable[str],
    baseline_month: date,
    series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
) -> ScopeComparison:
    """분야(또는 종합) 하나의 지표별 비교 시점.

    **모드를 인자로 받지 않는다.** 규칙이 모드에 따라 달라지지 않기 때문이다(spec FR-001) —
    받으면 언젠가 분기가 생기고, 그때 같은 분야의 D_g가 두 값이 된다.
    """
    by_indicator = {
        indicator_id: comparison_months_for(
            indicator_id, baseline_month, series_by_indicator[indicator_id]
        )
        for indicator_id in indicator_ids
    }
    return ScopeComparison(by_indicator=by_indicator, baseline_month=baseline_month)
