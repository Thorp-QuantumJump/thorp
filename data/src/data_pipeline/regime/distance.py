"""거리와 기여 (spec FR-009·FR-010).

같은 공통 척도로 표준화한 뒤 지표 거리 E_i는 비교 시점들의 |z_질의 − z_후보| **평균**이다.
합이 아니라 평균이라 지표마다 비교 시점 수가 달라도 같은 크기로 결합된다. 분야 거리 D_g는
분야 안 E_i의 산술평균, 종합 거리는 분야 D_g의 산술평균이다. **같은 기준월·분야의 D_g는
어느 모드에서 계산하든 같은 값이다**(FR-001).

기여는 두 가지를 나란히 남긴다 — 명목 비중(분야 안 1/n, 분야 간 1/분야 수)과 실제 거리 기여
(분야 안 E_i ÷ ΣE_i, 분야 간 D_g ÷ ΣD_g). 둘의 차이가 U02 검증의 관측 대상이다.
**전체 거리가 0이면 기여도 키를 만들지 않는다** — 0으로 나눈 값을 지어내지 않는다.
분야 검색에서는 분야 기여도를 만들지 않는다(분야가 하나라 늘 1이다).

**척도를 후보마다 다시 만들지 않는다.** `build_model`이 기준월 t로 척도를 한 번 만들고 모든
후보가 그 `DistanceModel` 하나를 쓴다(FR-005, research R3). 후보를 받을 때마다 척도를 구하는
모양이면 언젠가 후보월이 인자로 새어 들어가고, 그 순간 후보마다 다른 자로 잰 값이 한 줄에 선다.

**모드를 인자로 받지 않는다.** 분야 검색은 분야 하나로, 종합 검색은 등록표 전체로 같은 함수를
부르며 계산 경로가 갈리지 않는다. 그래서 같은 분야의 D_g가 두 모드에서 같다는 것이 시험이 아니라
구조로 보장된다(SC-002). 분야 기여도를 만들지 않는 조건도 모드가 아니라 **분야가 하나인가**로
판정한다 — spec이 든 이유가 "분야가 하나라 값이 늘 1"이기 때문이다.

**반올림은 내보내는 값에만 한 번 적용한다.** E_i에서 D_g로, D_g에서 종합으로 올라가는 중간값은
double 그대로 둔다. 단계마다 반올림하면 오차가 경계에서 누적되고, 어느 단계의 값이 저장값인지도
흐려진다(research R4). 정렬은 이 모듈이 돌려준 반올림된 값으로 한다(FR-012).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from typing import Final

from data_pipeline.regime.comparison import (
    QUERY_INPUT_MISSING,
    ScopeComparison,
    comparison_for_scope,
)
from data_pipeline.regime.macro.types import (
    FieldEntry,
    IndicatorEntry,
    IndicatorRegistry,
    MonthlyValue,
    format_month,
    require_month,
)
from data_pipeline.regime.rounding import round_for_storage
from data_pipeline.regime.scale import Scale, scale_for, shift_month

#: 척도 사유가 여럿일 때 고르는 순서 (data-model.md §2.1 표 순서).
SCALE_REASON_ORDER: Final[tuple[str, ...]] = ("SCALE_WINDOW_TOO_SHORT", "SCALE_ZERO")


class CandidateInputMissingError(LookupError):
    """후보에게 질의 비교 시점에 대응하는 입력이 없다.

    **질의보다 적은 시점으로 재지 않는다.** 없는 달을 빼고 평균을 내면 값은 나오지만 그 값은
    다른 후보와 다른 개수의 시점으로 잰 것이고, 같은 조건 비교(원칙 IV)가 조용히 깨진다.
    걸러내는 일은 `candidates.py`의 자격 판정 몫이며, 여기서는 걸러지지 않은 후보가 들어온
    사실을 드러낸다.
    """


def months_between(earlier: date, later: date) -> int:
    """두 달 사이의 개월 수. 월은 그 달 1일로 나타낸다."""
    return (later.year - earlier.year) * 12 + (later.month - earlier.month)


@dataclass(frozen=True)
class IndicatorDistance:
    """지표 하나의 거리와 기여 (spec FR-009·FR-010).

    ``query_z``·``case_z``는 상세 화면과 수기 대조가 쓰는 값이다(FR-028). 시대별 보조 위치의
    z와 헷갈리기 쉬운 자리인데, 이쪽은 **질의 기준월 t의 공통 척도**로 잰 값이고 저쪽은 후보월
    척도로 잰 값이라 서로 다른 자다. 저쪽은 애초에 숫자로 내보내지 않는다(FR-017).

    ``distance_share``가 ``None``이면 분야 안 ΣE_i가 0이라는 뜻이고, 저장·응답에서 **키를
    생략한다**(data-model §4). 0을 넣으면 "기여가 없다"로 읽히지만 실제로는 "말할 수 없다"다.
    """

    indicator_id: str
    field_id: str
    months: tuple[date, ...]
    case_months: tuple[date, ...]
    query_z: tuple[float, ...]
    case_z: tuple[float, ...]
    e: float
    nominal_weight: float
    distance_share: float | None


@dataclass(frozen=True)
class FieldDistance:
    """분야 하나의 거리와 기여."""

    field_id: str
    indicators: tuple[IndicatorDistance, ...]
    d: float
    contribution: float | None


@dataclass(frozen=True)
class CandidateDistance:
    """후보 하나의 거리 — 종합이면 분야 D_g의 평균, 분야 검색이면 그 분야의 D_g다."""

    end_month: date
    value: float
    fields: tuple[FieldDistance, ...]

    @property
    def by_field(self) -> dict[str, float]:
        return {field.field_id: field.d for field in self.fields}

    @property
    def contribution(self) -> dict[str, float] | None:
        """분야 기여도. 만들지 않는 자리에서는 ``None``이며 **키 자체가 없다**.

        만들지 않는 경우는 둘이다 — 분야가 하나뿐일 때(값이 늘 1이라 뜻이 없다)와 ΣD_g가
        0일 때(0으로 나눈 값을 지어내지 않는다). 둘 다 spec FR-009·Edge Cases가 정한 바다.
        """
        shares = {
            field.field_id: field.contribution
            for field in self.fields
            if field.contribution is not None
        }
        return shares if len(shares) == len(self.fields) else None


@dataclass(frozen=True)
class ModelUnavailable:
    """기준월 t로 계산을 시작할 수 없다. ``reason``은 data-model §2.1의 사유다."""

    baseline_month: date
    reason: str
    indicator_ids: tuple[str, ...]

    @property
    def available(self) -> bool:
        return False


@dataclass(frozen=True)
class DistanceModel:
    """기준월 t 하나에 고정된 계산 준비물. **모든 후보가 이것 하나를 쓴다**(FR-005).

    ``scales``는 t로 만든 공통 척도다. 후보월로 만든 척도는 이 자료구조에 들어올 자리가 없고,
    그래서 "후보월 척도가 거리 계산에 들어간 자리 0건"이 검사가 아니라 구조로 지켜진다.
    """

    baseline_month: date
    fields: tuple[FieldEntry, ...]
    indicators: tuple[IndicatorEntry, ...]
    comparison: ScopeComparison
    scales: Mapping[str, Scale]
    series: Mapping[str, Mapping[date, MonthlyValue]]

    @property
    def available(self) -> bool:
        return True

    def indicators_of(self, field_id: str) -> tuple[IndicatorEntry, ...]:
        return tuple(entry for entry in self.indicators if entry.field_id == field_id)

    def case_months_for(self, indicator_id: str, end_month: date) -> tuple[date, ...]:
        """후보에서 읽을 달 — 질의 비교 시점과 **같은 간격**의 달이다 (spec FR-012).

        질의가 t-2·t를 봤으면 후보도 c-2·c만 본다. 후보에게 질의보다 많은 시점을 요구하지
        않는다는 규칙은 여기서 간격을 그대로 옮기는 것으로 지켜진다.
        """
        months = self.comparison.by_indicator[indicator_id].months
        return tuple(
            shift_month(end_month, months_between(month, self.baseline_month)) for month in months
        )

    def has_inputs_for(self, end_month: date) -> bool:
        """후보가 모든 지표의 대응 시점 입력을 갖췄는가.

        자격 판정 전체(12개월 초과 이전, ±6개월 건너뛰기)는 `candidates.py` 몫이고, 여기서는
        **거리를 잴 수 있는가**만 답한다. 판정을 부르는 쪽에 맡기면 없는 달을 건너뛴 평균이
        언젠가 만들어진다.
        """
        require_month(end_month, "후보 종료월")
        return all(
            case_month in self.series[entry.id]
            for entry in self.indicators
            for case_month in self.case_months_for(entry.id, end_month)
        )

    def distance_for(self, end_month: date) -> CandidateDistance:
        """후보 하나의 거리와 기여 (spec FR-009·FR-010)."""
        require_month(end_month, "후보 종료월")
        measured_fields = [self._measure_field(field, end_month) for field in self.fields]
        raw_d = [field_distance for _, _, field_distance in measured_fields]
        total_d = sum(raw_d)
        single_field = len(measured_fields) == 1

        fields = tuple(
            FieldDistance(
                field_id=field.id,
                indicators=items,
                d=round_for_storage(field_distance),
                contribution=(
                    None
                    if single_field or total_d == 0.0
                    else round_for_storage(field_distance / total_d)
                ),
            )
            for (field, items, field_distance) in measured_fields
        )
        return CandidateDistance(
            end_month=end_month, value=round_for_storage(_mean(raw_d)), fields=fields
        )

    def _measure_field(
        self, field: FieldEntry, end_month: date
    ) -> tuple[FieldEntry, tuple[IndicatorDistance, ...], float]:
        """분야 하나의 지표 거리와 **반올림 전** D_g."""
        entries = self.indicators_of(field.id)
        raw_e = [self._raw_indicator_distance(entry, end_month) for entry in entries]
        total_e = sum(value for _, value in raw_e)
        indicators = tuple(
            _with_shares(item, value, total_e, len(raw_e)) for item, value in raw_e
        )
        return field, indicators, _mean([value for _, value in raw_e])

    def _raw_indicator_distance(
        self, entry: IndicatorEntry, end_month: date
    ) -> tuple[IndicatorDistance, float]:
        """지표 하나의 z와 E_i. 두 번째 값은 **반올림 전** E_i로, 결합에 쓰인다."""
        scale = self.scales[entry.id]
        series = self.series[entry.id]
        months = self.comparison.by_indicator[entry.id].months
        case_months = self.case_months_for(entry.id, end_month)

        query_z: list[float] = []
        case_z: list[float] = []
        gaps: list[float] = []
        for month, case_month in zip(months, case_months, strict=True):
            case_value = series.get(case_month)
            if case_value is None:
                raise CandidateInputMissingError(
                    f"{entry.id}의 {format_month(case_month)} 입력이 없다 — "
                    f"질의 비교 시점 {format_month(month)}에 대응하는 달이다 (spec FR-012)"
                )
            # 질의와 후보에 같은 m·s를 쓴다. 중앙값이 상쇄되어 차이는 |x_질의 − x_후보|/s_t이지만
            # (spec FR-009), 화면과 수기 대조가 z 자체를 쓰므로 둘 다 실제로 구한다(FR-028).
            standardized_query = scale.standardize(series[month].value)
            standardized_case = scale.standardize(case_value.value)
            query_z.append(standardized_query)
            case_z.append(standardized_case)
            gaps.append(abs(standardized_query - standardized_case))

        raw_e = _mean(gaps)
        return (
            IndicatorDistance(
                indicator_id=entry.id,
                field_id=entry.field_id,
                months=tuple(months),
                case_months=case_months,
                query_z=tuple(round_for_storage(value) for value in query_z),
                case_z=tuple(round_for_storage(value) for value in case_z),
                e=round_for_storage(raw_e),
                nominal_weight=0.0,
                distance_share=None,
            ),
            raw_e,
        )


def _mean(values: Sequence[float]) -> float:
    return sum(values) / len(values)


def _with_shares(
    item: IndicatorDistance, raw_e: float, field_total: float, count: int
) -> IndicatorDistance:
    """명목 비중과 실제 거리 기여를 채운다 (spec FR-010).

    **분모는 반올림 전 E_i의 합이다.** 반올림된 값으로 나누면 몫의 합이 1에서 미세하게 어긋나고,
    그 어긋남은 12자리 안에 보이는 자리에서 생긴다.
    """
    return IndicatorDistance(
        indicator_id=item.indicator_id,
        field_id=item.field_id,
        months=item.months,
        case_months=item.case_months,
        query_z=item.query_z,
        case_z=item.case_z,
        e=item.e,
        nominal_weight=round_for_storage(1 / count),
        distance_share=(None if field_total == 0.0 else round_for_storage(raw_e / field_total)),
    )


def build_model(
    registry: IndicatorRegistry,
    baseline_month: date,
    series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
    field_ids: Sequence[str] | None = None,
) -> DistanceModel | ModelUnavailable:
    """기준월 t의 공통 척도와 비교 시점을 한 번 만들어 모든 후보가 쓸 준비물을 돌려준다.

    ``field_ids``가 ``None``이면 등록표의 모든 분야다(종합 검색). 하나만 주면 분야 검색이며,
    **분야 개수를 상수로 갖지 않는다** — 등록표가 준 수를 쓴다(spec FR-010).

    척도나 비교 시점을 만들 수 없으면 `ModelUnavailable`을 돌려준다. 그 분야(종합이면 종합)가
    계산 불가라는 뜻이며, **문제가 있는 지표를 빼고 나머지로 계산하지 않는다**(FR-007·FR-008).
    """
    require_month(baseline_month, "기준월")
    wanted = None if field_ids is None else set(field_ids)
    fields = tuple(
        field for field in registry.ordered_fields() if wanted is None or field.id in wanted
    )
    if not fields:
        raise LookupError(f"등록표에 없는 분야다: {sorted(wanted or ())}")
    indicators = tuple(entry for field in fields for entry in registry.indicators_of(field.id))

    comparison = comparison_for_scope(
        (entry.id for entry in indicators), baseline_month, series_by_indicator
    )
    # 질의 입력이 없는 것을 척도 사유보다 먼저 알린다. 둘 다 어긋났을 때 척도를 말하면 화면이
    # "척도가 60개월이 안 된다"고 잘못 말하게 되는데, data-model §2.1이 사유를 따로 둔 이유가
    # 바로 그것이다.
    if not comparison.computable:
        return ModelUnavailable(
            baseline_month=baseline_month,
            reason=QUERY_INPUT_MISSING,
            indicator_ids=comparison.missing_indicators,
        )

    scales: dict[str, Scale] = {}
    failures: dict[str, list[str]] = {}
    for entry in indicators:
        result = scale_for(entry.id, baseline_month, series_by_indicator[entry.id])
        if isinstance(result, Scale):
            scales[entry.id] = result
        else:
            failures.setdefault(result.reason, []).append(entry.id)
    if failures:
        # 사유가 섞이면 data-model §2.1 순서대로 하나를 고른다. 둘을 합친 새 사유를 만들지
        # 않는다 — 어휘에 없는 값이 화면과 기록으로 새어 나간다.
        reason = next(name for name in SCALE_REASON_ORDER if name in failures)
        return ModelUnavailable(
            baseline_month=baseline_month,
            reason=reason,
            indicator_ids=tuple(sorted(failures[reason])),
        )

    return DistanceModel(
        baseline_month=baseline_month,
        fields=fields,
        indicators=indicators,
        comparison=comparison,
        scales=scales,
        series=series_by_indicator,
    )
