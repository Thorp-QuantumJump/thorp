"""월별 변환 — 원값을 등록표가 정한 규칙 하나로 바꾼다 (spec 008 FR-006·FR-006a·FR-006b).

변환은 여섯이고 성격이 둘로 갈린다. 전년동월 대비·전월 대비·월 기말·월 평균은 **우리가 만들고**,
공식 금액과 원천 계절조정값은 **원천이 준 값을 그대로 쓴다**.

**만들 수 없으면 만들지 않는다.** 분모가 없거나 0인 증가율, 계열이 시작되기 전 열두 달, 아직
끝나지 않은 달의 기말, 거래일이 덜 모인 달의 평균 — 넷 다 값을 지어내지 않고 결측으로 두며 사유를
남긴다(FR-008, 원칙 VI). 특히 부분 표본으로 만든 평균은 다른 달의 평균과 같은 뜻이 아니다. 섞여
들어가면 그 달만 유독 튀는데, 원인이 시장이 아니라 우리 계산이라는 사실은 아무도 되짚지 못한다.

**결측에도 이유가 있다.** "값이 없다"만 남기면 원천이 안 준 것인지, 우리가 못 만든 것인지,
아직 때가 아닌 것인지 구분되지 않는다. 셋은 대응이 전혀 다르다 — 첫째는 원천에 묻고, 둘째는
우리 규칙을 보고, 셋째는 그냥 기다린다.

**계보를 남긴다**(T029). 월 기말은 고른 그 하루를, 월 평균은 쓴 거래일 전부를, 증가율은 분자와
분모 두 달을 적는다. 값이 이상할 때 어느 원값에서 왔는지 되짚을 수 있어야 한다.
"""

from __future__ import annotations

import calendar
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Final

from data_pipeline.macro.registry import (
    CYCLE_DAILY,
    CYCLE_MONTHLY,
    TRANSFORM_MOM,
    TRANSFORM_MONTH_AVERAGE,
    TRANSFORM_MONTH_END,
    TRANSFORM_OFFICIAL_AMOUNT,
    TRANSFORM_SEASONAL_RATE,
    TRANSFORM_YOY,
    IndicatorDefinition,
)
from data_pipeline.macro.sources.base import SourceObservation

#: 결측 사유 코드. 화면 문구가 아니라 판정 결과다 — 표시 문구는 표시 등록표가 따로 든다.
MISSING_SOURCE: Final[str] = "SOURCE_MISSING"
"""원천이 그 시점을 비워 보냈다. 우리가 할 수 있는 일이 없다."""

MISSING_NOT_TRANSFORMABLE: Final[str] = "NOT_TRANSFORMABLE"
"""분모가 없거나 0이라 증가율을 만들 수 없다 (FR-006)."""

MISSING_SERIES_START: Final[str] = "SERIES_START"
"""계열이 시작되기 전이라 비교할 달이 애초에 없다. 원천의 결함이 아니다."""

MISSING_INCOMPLETE_MONTH: Final[str] = "INCOMPLETE_MONTH"
"""그 달의 거래일이 다 모이지 않았다. 부분 표본으로 만든 값은 다른 달과 같은 뜻이 아니다."""

#: 증가율을 어디서 끊을지. 재현성(SC-004)을 위해 고정한다 — 자리를 정하지 않으면 같은 자료로
#: 다시 돌려도 끝자리가 흔들린다.
GROWTH_EXPONENT: Final[Decimal] = Decimal("0.000001")

_HUNDRED: Final[Decimal] = Decimal(100)


@dataclass(frozen=True)
class MonthlyValue:
    """변환을 마친 월 값 하나.

    ``value`` 와 ``missing_reason`` 중 정확히 하나만 채워진다. 둘 다 비면 판정하지 않은
    것이고, 둘 다 차면 값이 있는데 결측이라는 말이 되어 어느 쪽도 믿을 수 없다.

    ``lineage`` 는 이 값을 만든 원천 관측의 기간 표기다(T029).
    """

    indicator_id: str
    period: str
    value: Decimal | None
    missing_reason: str | None
    lineage: tuple[str, ...]

    def __post_init__(self) -> None:
        if (self.value is None) == (self.missing_reason is None):
            raise ValueError(
                f"{self.indicator_id} {self.period}: 값과 결측 사유 중 하나만 있어야 한다"
                f" (값={self.value!r}, 사유={self.missing_reason!r})"
            )


def _missing(
    indicator_id: str, period: str, reason: str, lineage: Iterable[str] = ()
) -> MonthlyValue:
    return MonthlyValue(
        indicator_id=indicator_id,
        period=period,
        value=None,
        missing_reason=reason,
        lineage=tuple(lineage),
    )


def month_of(period: str) -> str:
    """``YYYY-MM-DD`` 든 ``YYYY-MM`` 이든 그 달을 돌려준다."""
    return period[:7]


def shift_months(period: str, months: int) -> str:
    """``YYYY-MM`` 을 ``months`` 만큼 옮긴다. 음수면 과거로 간다."""
    year, month = int(period[:4]), int(period[5:7])
    index = year * 12 + (month - 1) + months
    return f"{index // 12:04d}-{index % 12 + 1:02d}"


def _last_day(period: str) -> date:
    year, month = int(period[:4]), int(period[5:7])
    return date(year, month, calendar.monthrange(year, month)[1])


# --------------------------------------------------------------------------
# 일별 → 월별 접기
# --------------------------------------------------------------------------
def fold_month_end(
    observations: Sequence[SourceObservation], *, as_of: date
) -> list[MonthlyValue]:
    """일별 계열에서 **그 달 마지막 거래일** 값을 고른다 (FR-006a).

    ``as_of`` 보다 뒤에 끝나는 달은 아직 마지막 거래일이 오지 않았으므로 값을 내지 않는다.
    그러지 않으면 달이 끝날 때까지 "이 달의 기말"이 날마다 바뀌고, 어제 본 값과 오늘 본 값이
    다른데 둘 다 같은 이름을 달게 된다.

    **휴장일을 채우지 않는다.** 원천은 장이 선 날만 행을 주므로 그 달에 남은 마지막 행이 곧
    마지막 거래일이다. 거래일 달력 없이도 이 규칙은 성립한다.
    """
    by_month: dict[str, list[SourceObservation]] = {}
    for observation in observations:
        by_month.setdefault(month_of(observation.period), []).append(observation)

    result: list[MonthlyValue] = []
    for period in sorted(by_month):
        if _last_day(period) >= as_of:
            result.append(_missing(_id(by_month[period]), period, MISSING_INCOMPLETE_MONTH))
            continue
        days = sorted(by_month[period], key=lambda o: o.period)
        last = days[-1]
        if last.value is None:
            result.append(_missing(last.indicator_id, period, MISSING_SOURCE, [last.period]))
            continue
        result.append(
            MonthlyValue(
                indicator_id=last.indicator_id,
                period=period,
                value=last.value,
                missing_reason=None,
                # 고른 그 하루만 적는다. 나머지 거래일은 이 값에 쓰이지 않았다.
                lineage=(last.period,),
            )
        )
    return result


def fold_month_average(
    observations: Sequence[SourceObservation],
    *,
    trading_days: Mapping[str, Sequence[str]],
) -> list[MonthlyValue]:
    """일별 계열에서 **그 달 평균**을 낸다 (FR-006b).

    ``trading_days`` 는 달마다 장이 선 날의 목록이다. **거래일이 하나라도 빠지면 값을 내지
    않는다** — 스무 날 중 열다섯 날로 낸 평균은 스무 날로 낸 평균과 같은 이름을 달 수 없다.

    달력이 없는 달은 완전한지 판정할 방법이 없으므로 역시 값을 내지 않는다. "아마 다 왔을
    것"으로 넘기면 판정 불가와 판정 통과가 같은 모양이 된다.
    """
    by_month: dict[str, list[SourceObservation]] = {}
    for observation in observations:
        by_month.setdefault(month_of(observation.period), []).append(observation)

    result: list[MonthlyValue] = []
    for period in sorted(by_month):
        days = sorted(by_month[period], key=lambda o: o.period)
        indicator_id = days[0].indicator_id
        expected = trading_days.get(period)
        if expected is None:
            result.append(_missing(indicator_id, period, MISSING_INCOMPLETE_MONTH))
            continue
        present = {o.period for o in days if o.value is not None}
        if not set(expected) <= present:
            result.append(
                _missing(indicator_id, period, MISSING_INCOMPLETE_MONTH, sorted(present))
            )
            continue
        used = [o for o in days if o.period in set(expected)]
        total = sum((o.value for o in used if o.value is not None), start=Decimal(0))
        result.append(
            MonthlyValue(
                indicator_id=indicator_id,
                period=period,
                value=(total / Decimal(len(used))).quantize(GROWTH_EXPONENT),
                missing_reason=None,
                # 평균에 쓴 거래일을 전부 적는다. 하나만 적으면 되짚을 수 없다.
                lineage=tuple(o.period for o in used),
            )
        )
    return result


def _id(observations: Sequence[SourceObservation]) -> str:
    return observations[0].indicator_id


# --------------------------------------------------------------------------
# 증가율
# --------------------------------------------------------------------------
def _growth(
    monthly: Sequence[MonthlyValue], *, lag: int, series_start: str
) -> list[MonthlyValue]:
    """``lag`` 달 전과 견준 증가율. 비교월은 고정이며 가장 가까운 값으로 대신하지 않는다."""
    by_period = {value.period: value for value in monthly}

    result: list[MonthlyValue] = []
    for current in monthly:
        base_period = shift_months(current.period, -lag)
        if current.value is None:
            result.append(_missing(current.indicator_id, current.period, MISSING_SOURCE))
            continue
        if base_period < series_start[:7]:
            # 계열이 시작되기 전이다. 원천의 결함이 아니라 자료가 거기서 시작할 뿐이다.
            result.append(
                _missing(current.indicator_id, current.period, MISSING_SERIES_START)
            )
            continue
        base = by_period.get(base_period)
        if base is None or base.value is None or base.value == 0:
            result.append(
                _missing(current.indicator_id, current.period, MISSING_NOT_TRANSFORMABLE)
            )
            continue
        ratio = (current.value - base.value) / base.value * _HUNDRED
        result.append(
            MonthlyValue(
                indicator_id=current.indicator_id,
                period=current.period,
                value=ratio.quantize(GROWTH_EXPONENT),
                missing_reason=None,
                # 분자와 분모 두 달을 모두 적는다.
                lineage=(base_period, current.period),
            )
        )
    return result


def year_over_year(monthly: Sequence[MonthlyValue], *, series_start: str) -> list[MonthlyValue]:
    """전년동월 대비. **비교월은 t-12 로 고정한다** (FR-006).

    t-12 가 비었다고 t-11 이나 t-13 으로 물러서지 않는다. 그렇게 만든 값은 다른 달의 전년동월
    대비와 같은 뜻이 아닌데 표에서는 같은 열에 나란히 놓인다.
    """
    return _growth(monthly, lag=12, series_start=series_start)


def month_over_month(monthly: Sequence[MonthlyValue], *, series_start: str) -> list[MonthlyValue]:
    """전월 대비. 주택가격이 쓴다."""
    return _growth(monthly, lag=1, series_start=series_start)


# --------------------------------------------------------------------------
# 원천 값을 그대로 쓰는 변환
# --------------------------------------------------------------------------
def passthrough(observations: Sequence[SourceObservation]) -> list[MonthlyValue]:
    """원천이 준 월값을 그대로 쓴다 — 공식 금액과 원천 계절조정값, 그리고 원천이 직접 주는 월값.

    **계산하지 않는다는 것이 이 함수의 요점이다.** 무역수지는 수출에서 수입을 뺀 값과 지금
    일치하지만 그렇게 만들지 않고(FR-006), 실업률은 우리가 계절조정하지 않으며, 두바이유는
    원천이 월 평균을 이미 내 준다(pd11 §1).
    """
    result: list[MonthlyValue] = []
    for observation in observations:
        period = month_of(observation.period)
        if observation.value is None:
            result.append(
                _missing(observation.indicator_id, period, MISSING_SOURCE, [observation.period])
            )
            continue
        result.append(
            MonthlyValue(
                indicator_id=observation.indicator_id,
                period=period,
                value=observation.value,
                missing_reason=None,
                lineage=(observation.period,),
            )
        )
    return result


# --------------------------------------------------------------------------
# 등록표가 고른 규칙으로 보내기
# --------------------------------------------------------------------------
#: 주기별 기간 표기 길이. 월별은 ``YYYY-MM``, 일별은 ``YYYY-MM-DD`` 다.
_PERIOD_LENGTH: Final[Mapping[str, int]] = {CYCLE_MONTHLY: 7, CYCLE_DAILY: 10}


def _require_declared_cycle(
    indicator: IndicatorDefinition, observations: Sequence[SourceObservation]
) -> bool:
    """등록표가 선언한 주기와 관측의 기간 표기가 맞는지 본다. 월별이면 참이다.

    **관측을 훑어 주기를 추측하지 않는다.** 추측은 자료가 비었을 때 답을 내지 못하고,
    원천이 주기를 바꿨을 때도 조용히 따라가 버린다. 등록표가 선언한 것을 기준으로 삼고
    어긋나면 멈추면, 두 방향의 어긋남이 모두 드러난다 — 월별이라 적힌 계열에 일별이 온
    경우와 그 반대 모두.
    """
    expected = _PERIOD_LENGTH.get(indicator.source_cycle)
    if expected is None:
        raise ValueError(
            f"{indicator.indicator_id}: 등록표의 주기가 모르는 값이다({indicator.source_cycle})"
        )
    wrong = [o.period for o in observations if len(o.period) != expected]
    if wrong:
        raise ValueError(
            f"{indicator.indicator_id}: 등록표는 주기를 {indicator.source_cycle} 로"
            f" 선언했는데 기간 표기가 다른 관측이 {len(wrong)}건 있다({', '.join(wrong[:3])})."
            " 원천이 주기를 바꿨는지 본다"
        )
    return indicator.source_cycle == CYCLE_MONTHLY


def apply(
    indicator: IndicatorDefinition,
    observations: Sequence[SourceObservation],
    *,
    as_of: date | None = None,
    trading_days: Mapping[str, Sequence[str]] | None = None,
) -> list[MonthlyValue]:
    """등록표의 ``transform_rule`` 이 정한 변환을 건다. **여기서 규칙을 고르지 않는다**(FR-005).

    월 기말·월 평균은 원천이 일별로 줄 때만 접는 일이 필요하다. 원천이 이미 월값을 주면
    (환율의 말일자료, 두바이유의 월 평균) 그 값을 그대로 쓴다 — 등록표가 그 사실을 적어 둔다.
    """
    rule = indicator.transform_rule
    monthly_source = _require_declared_cycle(indicator, observations)

    if rule in (TRANSFORM_OFFICIAL_AMOUNT, TRANSFORM_SEASONAL_RATE):
        return passthrough(observations)

    if rule == TRANSFORM_MONTH_END:
        if monthly_source:
            return passthrough(observations)
        if as_of is None:
            raise ValueError(
                f"{indicator.indicator_id}: 월 기말을 접으려면 as_of 가 필요하다."
                " 달이 끝났는지 알 수 없으면 기말도 정해지지 않는다"
            )
        return fold_month_end(observations, as_of=as_of)

    if rule == TRANSFORM_MONTH_AVERAGE:
        if monthly_source:
            return passthrough(observations)
        return fold_month_average(observations, trading_days=trading_days or {})

    if rule in (TRANSFORM_YOY, TRANSFORM_MOM):
        if not monthly_source:
            # 증가율은 월 값끼리 견주는 것이다. 일별을 그대로 통과시키면 `month_of` 가 여러
            # 날을 같은 달로 접어 같은 대상월이 여러 번 나오고, 증가율은 그중 임의의 한 행을
            # 분모로 골라 값이 조용히 틀린다. 일별을 월로 접는 규칙은 등록표가 정할 일이라
            # 여기서 고르지 않는다(FR-005).
            raise ValueError(
                f"{indicator.indicator_id}: {rule} 는 월 값끼리 견주는 변환인데 등록표가"
                " 일별 계열이라고 선언했다. 일별을 월로 접는 규칙(월 기말·월 평균)을 먼저"
                " 정해야 한다 — 접지 않고 견주면 같은 달이 여러 번 나온다"
            )
        levels = passthrough(observations)
        growth = year_over_year if rule == TRANSFORM_YOY else month_over_month
        return growth(levels, series_start=indicator.series_start)

    raise ValueError(f"{indicator.indicator_id}: 모르는 변환 규칙이다({rule})")
