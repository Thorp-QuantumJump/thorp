"""주택가격 연결기 — 기준월별 표 5개를 이어 2000-01까지 만든다 (spec 008 FR-010, pd11 §2).

한국부동산원은 기준월을 바꿀 때마다 통계표를 새로 만들고 지수를 재기준화한다. 그래서 현행 표
하나만으로는 2021-06 까지밖에 닿지 못하고, 경제환경 검색이 볼 수 있는 과거가 5년으로 줄어든다.
옛 표들을 이어 붙이면 2000-01 까지 닿는다.

**이어 붙이는 방식은 한 지점에서 비율을 재는 것이다**(연쇄 연결). 겹치는 구간 전체의 평균 비율을
쓰지 않고 연결 지점 한 달의 비율을 쓰는 이유는, 그래야 각 표가 자기 안에서 가지고 있던 **달마다의
변화율이 그대로 보존**되기 때문이다. 평균 비율로 통째로 늘리면 표 안의 모든 달이 조금씩 어긋나고,
그 어긋남은 어느 달에 얼마나 들어갔는지 되짚을 수 없다.

**두 연결 지점의 성격이 다르다** (2026-09-18 표본 실측):

===================  ========  ==========================================
연결                 겹침      겹침 구간 비율
===================  ========  ==========================================
현행(2026.6)×2021.6  46개월    편차 0.0000% — 순수 재기준화다
2021.6×2011.6        110개월   편차 5.6788% — 같은 계열을 다시 잰 것이 아니다
===================  ========  ==========================================

앞은 지수를 새 기준월로 나눠 다시 적은 것뿐이라 어느 달에서 이어도 같은 값이 나온다. 뒤는
그렇지 않다 — 두 표가 같은 달에 대해 서로 다른 변화를 말하고 있어서, 어느 달에서 잇느냐에 따라
2012년 이전 수준이 최대 5.7% 달라진다.

**그래서 비율의 편차를 재서 함께 남긴다**(:attr:`Link.overlap_spread_percent`). 연결한 사실만
남기고 편차를 감추면, 2012년 이전 값이 왜 그 수준인지 나중에 아무도 설명하지 못한다. 편차가
:data:`RATIO_SPREAD_TOLERANCE_PERCENT` 를 넘는 연결은 :attr:`Link.ratio_is_stable` 이 거짓이
되고, 발행과 화면이 그 사실을 실어 나른다(원칙 VI).

**연결한 구간임을 값에 남긴다.** 연결된 값은 현행 표가 직접 준 값과 같은 자리에 놓이지만 같은
것이 아니다. 어디까지가 원천이 준 값이고 어디부터가 우리가 이은 값인지 구분되지 않으면, 과거
구간의 오차를 원천 탓으로 돌리게 된다.

전 구간을 덮는 데 필요한 표는 셋(현행·2021.6·2011.6)이다. 나머지 둘(2026.1·2025.3)은 현행
표와 구간이 겹치기만 해 연결에 쓰이지 않지만, 같은 구간을 다른 기준월로 적은 판본이라 개정
대조에 쓴다(T052).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Final

from data_pipeline.macro.registry import HOUSING_LINK_CHAIN, LinkedTable
from data_pipeline.macro.sources import kosis

#: 연결 지점을 바꿔도 결과가 같다고 볼 수 있는 편차의 한계(%). 넘으면 두 표가 같은 계열을
#: 다시 잰 것이 아니라는 뜻이고, 그 사실을 감추지 않는다.
RATIO_SPREAD_TOLERANCE_PERCENT: Final[Decimal] = Decimal("0.01")

#: 연결 계수를 어디서 끊을지. 재현성(SC-004)을 위해 고정한다.
COEFFICIENT_EXPONENT: Final[Decimal] = Decimal("0.000000001")

#: 이어 붙인 값을 어디서 끊을지. 원천이 소수 일곱째 자리까지 주므로 그보다 넉넉히 둔다.
VALUE_EXPONENT: Final[Decimal] = Decimal("0.0000001")

_HUNDRED: Final[Decimal] = Decimal(100)


class HousingLinkError(ValueError):
    """표를 이을 수 없다."""


@dataclass(frozen=True)
class Segment:
    """기준월 하나의 통계표. ``values`` 는 그 표가 준 원지수다."""

    base_month: str
    table_code: str
    values: Mapping[str, Decimal]

    @property
    def periods(self) -> tuple[str, ...]:
        return tuple(sorted(self.values))


@dataclass(frozen=True)
class Link:
    """연결 하나의 기록. 등록표와 자료에 그대로 남는다 (FR-010)."""

    #: 이어 붙인 쪽(더 옛 표)의 기준월.
    from_base_month: str
    #: 이어 붙일 대상(더 새 표 또는 이미 이어진 계열)의 기준월.
    to_base_month: str
    #: 비율을 잰 달. 겹침 구간에서 가장 최근 달이다.
    splice_period: str
    #: 옛 표의 값에 곱해 새 기준으로 옮기는 계수(누적).
    coefficient: Decimal
    #: 두 표가 함께 값을 가진 개월 수.
    overlap_months: int
    #: 겹침 구간에서 잰 비율의 최대 상대 편차(%). 0이면 순수 재기준화다.
    overlap_spread_percent: Decimal

    @property
    def ratio_is_stable(self) -> bool:
        """연결 지점을 바꿔도 결과가 사실상 같은가."""
        return self.overlap_spread_percent <= RATIO_SPREAD_TOLERANCE_PERCENT


@dataclass(frozen=True)
class LinkedPoint:
    """이어 붙인 계열의 한 달."""

    period: str
    value: Decimal
    #: 이 값을 준 표의 기준월.
    source_base_month: str
    #: 우리가 계수를 곱해 만든 값인가. 거짓이면 현행 표가 직접 준 값이다.
    is_linked: bool


@dataclass(frozen=True)
class LinkedSeries:
    points: tuple[LinkedPoint, ...]
    links: tuple[Link, ...]

    @property
    def values(self) -> dict[str, Decimal]:
        return {point.period: point.value for point in self.points}

    @property
    def start(self) -> str:
        return self.points[0].period

    @property
    def end(self) -> str:
        return self.points[-1].period

    @property
    def all_links_stable(self) -> bool:
        return all(link.ratio_is_stable for link in self.links)


#: 사슬의 표들이 함께 만드는 지표.
INDICATOR_ID: Final[str] = "housing_sale_price"


def build_params(table: LinkedTable, start: str, end: str) -> dict[str, str]:
    """연결 사슬의 표 하나를 받을 조회 인자. **인증키는 넣지 않는다.**

    각 표는 국가통계포털의 보통 통계표라 조회 방식이 같다. 다만 기관·항목·분류가 표마다
    달라 지표가 아니라 등록표의 사슬에서 읽는다.
    """
    return kosis.build_series_params(
        table.org_id, table.table_code, table.item_code, table.classification, start, end
    )


def parse_table(table: LinkedTable, payload: object) -> dict[str, Decimal]:
    """사슬의 표 하나를 읽어 대상월→값으로 만든다.

    보통 지표와 같은 구조 검증을 거친다 — 표마다 기관·항목·분류가 다르므로 그 표의 것으로
    확인한다. 결측월은 이어 붙일 때 쓸 수 없으므로 빼고, 값이 있는 달만 남긴다.

    **단위는 읽지 않는다.** 표마다 자기 기준월을 단위로 말하는데(2021.6 표는
    ``2021.6＝100.0``, 2011.6 표는 아예 주지 않는다) 이어 붙이고 나면 모든 달이 현행
    기준월로 옮겨지므로 계열의 단위는 등록표의 ``raw_unit`` 하나다.
    """
    observations = kosis.parse_series(
        payload,
        indicator_id=INDICATOR_ID,
        org_id=table.org_id,
        table_code=table.table_code,
        item_code=table.item_code,
        classification=table.classification,
        require_unit=False,
    )
    return {o.period: o.value for o in observations if o.value is not None}


def segments_from(
    values_by_base_month: Mapping[str, Mapping[str, Decimal]],
    chain: Sequence[LinkedTable] = HOUSING_LINK_CHAIN,
) -> list[Segment]:
    """등록표의 연결 사슬 순서대로 :class:`Segment` 를 만든다.

    **순서를 부르는 쪽이 정하지 않는다.** 어느 표를 어떤 차례로 잇느냐가 곧 2000-01 부터의
    값을 정하므로, 그 결정은 등록표에 있어야 하고 여기서는 따를 뿐이다(FR-005).
    """
    missing = [table.base_month for table in chain if table.base_month not in values_by_base_month]
    if missing:
        raise HousingLinkError(
            f"연결 사슬의 표가 덜 왔다: 기준월 {', '.join(missing)}."
            " 빠진 표를 건너뛰고 이으면 그 구간만큼 과거가 조용히 짧아진다"
        )
    return [
        Segment(
            base_month=table.base_month,
            table_code=table.table_code,
            values=values_by_base_month[table.base_month],
        )
        for table in chain
    ]


def _overlap_ratios(
    anchor: Mapping[str, Decimal], older: Segment
) -> dict[str, Decimal]:
    """겹치는 달마다 ``anchor / older`` 를 잰다. 분모가 0인 달은 뺀다."""
    return {
        period: anchor[period] / older.values[period]
        for period in sorted(set(anchor) & set(older.values))
        if older.values[period] != 0
    }


def _spread_percent(ratios: Mapping[str, Decimal]) -> Decimal:
    if len(ratios) < 2:
        return Decimal(0)
    lowest, highest = min(ratios.values()), max(ratios.values())
    if lowest == 0:
        return Decimal(0)
    return ((highest - lowest) / lowest * _HUNDRED).quantize(Decimal("0.0001"))


def link(segments: Sequence[Segment]) -> LinkedSeries:
    """기준월이 새 것부터 늘어선 표들을 이어 하나의 계열로 만든다.

    첫 표가 기준이 되고(계수 1), 뒤로 갈수록 계수가 누적된다. 이미 덮인 달은 새 표가 준 값을
    그대로 두고 옛 표로 덮어쓰지 않는다 — 새 기준월의 표가 그 달에 대해 더 최근의 개정을 담고
    있기 때문이다.
    """
    if not segments:
        raise HousingLinkError("이을 표가 없다")
    if not segments[0].values:
        raise HousingLinkError(f"{segments[0].table_code}: 기준이 될 표가 비어 있다")

    anchor = segments[0]
    points: dict[str, LinkedPoint] = {
        period: LinkedPoint(period, value, anchor.base_month, is_linked=False)
        for period, value in anchor.values.items()
    }
    linked_values: dict[str, Decimal] = dict(anchor.values)

    links: list[Link] = []
    for older in segments[1:]:
        ratios = _overlap_ratios(linked_values, older)
        if not ratios:
            raise HousingLinkError(
                f"{older.table_code}(기준 {older.base_month})는 앞선 계열과 겹치는 달이 없다."
                " 겹침 없이 이으면 두 구간의 수준이 이어지지 않는다"
            )
        # 겹침 구간에서 가장 최근 달에서 잇는다. 그 달이 두 표 모두에서 가장 덜 오래된
        # 개정을 담고 있다.
        splice_period = max(ratios)
        coefficient = ratios[splice_period].quantize(COEFFICIENT_EXPONENT)
        links.append(
            Link(
                from_base_month=older.base_month,
                to_base_month=anchor.base_month,
                splice_period=splice_period,
                coefficient=coefficient,
                overlap_months=len(ratios),
                overlap_spread_percent=_spread_percent(ratios),
            )
        )
        for period, value in older.values.items():
            if period in points:
                continue
            points[period] = LinkedPoint(
                period=period,
                value=(value * coefficient).quantize(VALUE_EXPONENT),
                source_base_month=older.base_month,
                is_linked=True,
            )
            linked_values[period] = points[period].value

    return LinkedSeries(
        points=tuple(points[period] for period in sorted(points)),
        links=tuple(links),
    )
