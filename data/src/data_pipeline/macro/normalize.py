"""정규화 — 원값과 변환값을 함께 남기고 계보를 적는다 (spec 008 FR-006·FR-007·FR-009).

수집과 변환 사이가 아니라 **뒤**에 있는 단계다. 어댑터가 원천의 말을 우리 말로 옮기고, 변환기가
등록표의 규칙을 걸고 나면, 여기서 그 둘을 한 줄에 묶어 저장소로 보낼 모양을 만든다.

**원값을 버리지 않는다.** 변환값만 남기면 원천이 개정했을 때 무엇이 달라졌는지 알 수 없고
(FR-009), 변환 규칙을 고쳤을 때 과거 구간을 다시 만들 수도 없다. 그러려면 원천이 준 값을 다시
받아야 하는데, 원천은 옛 값을 돌려주지 않는다.

**계보는 대상월이 아니라 원천 관측을 가리킨다.** 월 기말은 고른 그 하루, 월 평균은 쓴 거래일
전부, 증가율은 분자와 분모 두 달이다. "2026년 1월 값"이라고만 적으면 그 값이 1월 30일에서
왔는지 31일에서 왔는지 되짚지 못하고, 휴장일 처리가 바뀌었을 때 무엇이 영향을 받는지도
가려내지 못한다.

**세 시각을 섞지 않는다**(FR-007). 대상월은 그 값이 말하는 달, 발표일은 원천이 그 값을 낸 날,
수신 시각은 우리가 받은 때다. 발표일을 주지 않는 원천은 그 자리를 비워 두고 수신 시각으로
대신하지 않는다 — 대신 채우면 "언제 알 수 있었는가"를 묻는 질문에 우리 수집 일정이 답하게 된다.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal

from data_pipeline.macro.registry import IndicatorDefinition
from data_pipeline.macro.sources.base import SourceObservation
from data_pipeline.macro.transform import monthly
from data_pipeline.macro.transform.monthly import MonthlyValue


@dataclass(frozen=True)
class NormalizedObservation:
    """저장소에 들어갈 관측 하나. `macro_observation` 과 `macro_observation_lineage` 를 채운다.

    원값이 여럿일 수 있다 — 월 평균은 그 달 거래일 전부에서 왔다. 그래서 ``raw_values`` 는
    기간 표기에서 값으로 가는 매핑이고, 변환값은 하나다.
    """

    indicator_id: str
    #: 값이 말하는 달 (``YYYY-MM``).
    period: str
    #: 변환을 마친 값. 결측이면 ``None`` 이고 사유가 채워진다.
    transformed_value: Decimal | None
    missing_reason: str | None
    #: 이 값을 만든 원천 관측. 계보이자 원값 보존이다.
    raw_values: tuple[tuple[str, Decimal | None], ...]
    #: 등록표가 정한 변환 규칙. 값과 함께 남겨야 나중에 규칙이 바뀐 것을 알 수 있다.
    transform_rule: str
    raw_unit: str
    transformed_unit: str
    #: 원천이 밝힌 통계표 갱신일. 관측치별 발표일이 아니다 (FR-007).
    source_updated_at: str | None
    #: 원천이 관측치별로 밝힌 발표 시각. 수신 시각이나 통계표 갱신일로 대신하지 않는다.
    released_at: datetime | None = None
    #: 우리가 받은 때. 발표일 자리에 넣지 않는다.
    received_at: datetime | None = None
    #: 이 달의 값을 준 구간. 개편으로 나뉜 계열만 채워지고 나머지는 ``None`` 이다 (FR-010).
    #:
    #: 이 칸이 자료에 남아야 "2012년 값은 어느 표에서 왔나"를 되짚을 수 있다. 이어 붙인 값은
    #: 계수를 곱한 것이라 원천이 그 달에 대해 직접 말한 값과 다르고, 둘을 구분하지 못하면
    #: 개정 대조에서 우리가 곱한 계수를 원천의 개정으로 읽는다.
    break_segment: str | None = None

    @property
    def is_missing(self) -> bool:
        return self.transformed_value is None

    @property
    def lineage_periods(self) -> tuple[str, ...]:
        return tuple(period for period, _ in self.raw_values)


def normalize(
    indicator: IndicatorDefinition,
    observations: Sequence[SourceObservation],
    *,
    as_of: date | None = None,
    trading_days: Mapping[str, Sequence[str]] | None = None,
    received_at: datetime | None = None,
    break_segments: Mapping[str, str] | None = None,
    linked_segments: frozenset[str] = frozenset(),
) -> list[NormalizedObservation]:
    """원천 관측을 변환하고, 그 결과를 원값·계보와 한 줄로 묶는다.

    변환 자체는 :mod:`~data_pipeline.macro.transform.monthly` 가 한다. 여기서 다시 계산하지
    않는 이유는 규칙이 두 곳에 있으면 언젠가 갈라지기 때문이다.
    """
    raw_by_period = {observation.period: observation for observation in observations}
    values = monthly.apply(
        indicator,
        observations,
        as_of=as_of,
        trading_days=trading_days,
        break_segments=break_segments,
        linked_segments=linked_segments,
    )

    result: list[NormalizedObservation] = []
    for value in values:
        result.append(
            NormalizedObservation(
                indicator_id=indicator.indicator_id,
                period=value.period,
                transformed_value=value.value,
                missing_reason=value.missing_reason,
                raw_values=_raw_for(value, raw_by_period),
                transform_rule=indicator.transform_rule,
                raw_unit=indicator.raw_unit,
                transformed_unit=indicator.transformed_unit,
                released_at=_released_at(value, raw_by_period),
                source_updated_at=_updated_at(value, raw_by_period),
                received_at=received_at,
                break_segment=(
                    None if break_segments is None else break_segments.get(value.period)
                ),
            )
        )
    return result


def _raw_for(
    value: MonthlyValue, raw_by_period: Mapping[str, SourceObservation]
) -> tuple[tuple[str, Decimal | None], ...]:
    """계보에 적힌 기간마다 원값을 짝지어 둔다.

    계보에 있는데 원천 관측이 없는 경우가 있다 — 증가율의 분모가 그렇다. 그 달의 원값은 이미
    다른 행이 들고 있으므로 여기서는 기간만 남기고 값은 비운다. 값을 다시 적으면 같은 원값이
    여러 행에 흩어져, 나중에 개정이 들어왔을 때 어느 쪽이 최신인지 알 수 없게 된다.
    """
    return tuple(
        (period, raw.value if (raw := raw_by_period.get(period)) else None)
        for period in value.lineage
    )


def _updated_at(
    value: MonthlyValue, raw_by_period: Mapping[str, SourceObservation]
) -> str | None:
    """계보에 든 원천 관측이 밝힌 갱신일 중 가장 늦은 것.

    여러 원값에서 온 값은 그중 가장 늦게 갱신된 때가 곧 이 값이 바뀔 수 있었던 마지막 시점이다.
    """
    stamps = [
        raw.source_updated_at
        for period in value.lineage
        if (raw := raw_by_period.get(period)) and raw.source_updated_at
    ]
    return max(stamps) if stamps else None


def _released_at(
    value: MonthlyValue, raw_by_period: Mapping[str, SourceObservation]
) -> datetime | None:
    """계보 원천이 밝힌 발표 시각. 미제공이면 비워 두며 수신 시각으로 대체하지 않는다."""
    stamps = [
        raw.released_at
        for period in value.lineage
        if (raw := raw_by_period.get(period)) and raw.released_at is not None
    ]
    return max(stamps) if stamps else None
