"""`MacroIndicatorSource`가 돌려주는 값 타입 (contracts/macro-indicator-port.md §1).

008이 완성되면 구현체가 바뀌고 이 타입은 그대로 남는다. 그래서 008이 지키기로 한 규칙을
**타입 쪽에서 검사한다** — 어느 구현체가 와도 같은 자리에서 같은 오류가 난다.

검사하는 규칙은 계약 §3의 셋이다.

- **결측은 결측으로 온다.** 빠진 달은 매핑에 **키가 없다**. 0이나 직전 값으로 채워 오지 않고
  NaN으로도 오지 않는다 — NaN이 섞이면 척도 연속 구간 판정(spec FR-005)이 구멍을 못 보고
  지나가고, 그때 만들어진 순위는 아무도 틀렸다고 말해 주지 않는다.
- **원값과 변환값을 모두 받는다.** 설명 문장은 원값과 단위로 쓰고 거리는 변환값으로 계산한다
  (spec FR-015·FR-009). 하나만 받으면 다른 한쪽을 만들어 내야 한다.
- **버전 셋이 함께 온다.** 자료 버전·등록표 버전이 재계산 대상을 정한다(spec FR-030).

월은 ``date``의 그 달 1일로 나타낸다. 저장 스키마가 DATE이고(data-model.md §3) pandas·psycopg가
그대로 받는다. 1일이 아닌 값은 "그 달의 어느 날"이 뜻을 갖는 것처럼 보이게 하므로 막는다.
"""

from __future__ import annotations

import math
import re
from dataclasses import dataclass
from datetime import date
from typing import Final

_MONTH_PATTERN: Final[re.Pattern[str]] = re.compile(r"^(\d{4})-(\d{2})$")


class MacroSourceError(ValueError):
    """소스가 계약을 어긴 값을 돌려줬다."""


def parse_month(text: str) -> date:
    """``YYYY-MM``을 그 달 1일로 바꾼다."""
    if not isinstance(text, str):
        raise MacroSourceError(f"월은 문자열이어야 한다: {type(text).__name__}")
    matched = _MONTH_PATTERN.match(text)
    if matched is None:
        raise MacroSourceError(f"월은 YYYY-MM 형식이어야 한다: {text!r}")
    year, month = int(matched.group(1)), int(matched.group(2))
    if not 1 <= month <= 12:
        raise MacroSourceError(f"월이 1~12가 아니다: {text!r}")
    return date(year, month, 1)


def format_month(month: date) -> str:
    """그 달을 ``YYYY-MM``으로 적는다."""
    return f"{month:%Y-%m}"


def require_month(month: object, field: str) -> date:
    """월 값이 ``date``의 1일인지 검사하고 그대로 돌려준다."""
    if not isinstance(month, date):
        raise MacroSourceError(f"{field}은(는) date여야 한다: {type(month).__name__}")
    if month.day != 1:
        raise MacroSourceError(f"{field}은(는) 그 달 1일이어야 한다: {month.isoformat()}")
    return month


def _require_finite(value: object, field: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise MacroSourceError(f"{field}은(는) 수여야 한다: {value!r}")
    number = float(value)
    if math.isnan(number) or math.isinf(number):
        # 결측은 키 없음으로 온다. NaN을 값으로 받으면 결측이 값인 척하고 계산에 들어간다.
        raise MacroSourceError(
            f"{field}에 NaN·무한대가 올 수 없다: {value!r}. 결측 월은 키를 두지 않는다"
        )
    return number


def _require_identifier(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise MacroSourceError(f"{field}은(는) 비어 있을 수 없다: {value!r}")
    return value


@dataclass(frozen=True)
class FieldEntry:
    """분야 하나 — id·이름·표시 순서 (008 FR-002·FR-003).

    이름과 순서는 **화면 분류**다. 계산 가중은 등록표가 따로 정하며 이름·배치를 바꿨다는
    이유로 따라 바뀌지 않는다(008 FR-003).
    """

    id: str
    name: str
    display_order: int

    def __post_init__(self) -> None:
        _require_identifier(self.id, "분야 id")
        _require_identifier(self.name, "분야 이름")
        if isinstance(self.display_order, bool) or not isinstance(self.display_order, int):
            raise MacroSourceError(f"분야 표시 순서는 정수여야 한다: {self.display_order!r}")


@dataclass(frozen=True)
class IndicatorEntry:
    """지표 하나 — id·이름·단위·변환과 소속 분야 (008 FR-001·FR-002)."""

    id: str
    name: str
    field_id: str
    unit: str
    transform: str
    display_order: int

    def __post_init__(self) -> None:
        _require_identifier(self.id, "지표 id")
        _require_identifier(self.name, "지표 이름")
        _require_identifier(self.field_id, "지표의 분야 id")
        _require_identifier(self.unit, "지표 단위")
        _require_identifier(self.transform, "지표 변환")
        if isinstance(self.display_order, bool) or not isinstance(self.display_order, int):
            raise MacroSourceError(f"지표 표시 순서는 정수여야 한다: {self.display_order!r}")


@dataclass(frozen=True)
class IndicatorRegistry:
    """지표 등록표 — 지표 목록, 지표 → 분야 매핑, 분야 이름·순서, 등록표 버전.

    **010은 이 등록표가 돌려주는 것 외에 지표·분야에 대해 아무것도 알지 못한다**
    (spec FR-004). 지표 목록 상수·분야 이름 문자열·분야 id 분기를 코드에 두지 않으므로
    008에 지표가 늘어도 계산 쪽은 고치지 않는다.
    """

    version: str
    fields: tuple[FieldEntry, ...]
    indicators: tuple[IndicatorEntry, ...]

    def __post_init__(self) -> None:
        _require_identifier(self.version, "등록표 버전")
        if not self.fields:
            raise MacroSourceError("등록표에 분야가 하나도 없다")
        if not self.indicators:
            raise MacroSourceError("등록표에 지표가 하나도 없다")
        self._require_unique(tuple(field.id for field in self.fields), "분야 id")
        self._require_unique(tuple(field.display_order for field in self.fields), "분야 표시 순서")
        self._require_unique(tuple(indicator.id for indicator in self.indicators), "지표 id")
        known = {field.id for field in self.fields}
        unknown = sorted(
            {indicator.field_id for indicator in self.indicators if indicator.field_id not in known}
        )
        if unknown:
            raise MacroSourceError(f"등록표에 없는 분야를 가리키는 지표가 있다: {unknown}")
        # 분야가 있는데 지표가 하나도 없으면 그 분야의 거리는 빈 평균이 된다. 계산이 0으로
        # 나누기 전에 등록표 쪽에서 막는다.
        empty = sorted(known - {indicator.field_id for indicator in self.indicators})
        if empty:
            raise MacroSourceError(f"지표가 하나도 없는 분야가 있다: {empty}")

    @staticmethod
    def _require_unique(values: tuple[object, ...], field: str) -> None:
        if len(set(values)) != len(values):
            listed = list(values)
            duplicated = sorted({str(value) for value in listed if listed.count(value) > 1})
            raise MacroSourceError(f"{field}이(가) 겹친다: {duplicated}")

    def ordered_fields(self) -> tuple[FieldEntry, ...]:
        """표시 순서대로 정렬한 분야."""
        return tuple(sorted(self.fields, key=lambda field: field.display_order))

    def indicators_of(self, field_id: str) -> tuple[IndicatorEntry, ...]:
        """그 분야의 지표를 표시 순서대로 돌려준다."""
        return tuple(
            sorted(
                (entry for entry in self.indicators if entry.field_id == field_id),
                key=lambda entry: entry.display_order,
            )
        )


@dataclass(frozen=True)
class MonthlyValue:
    """지표 한 달의 값.

    ``value``는 변환 후 값(거리 계산 입력, spec FR-009), ``raw``는 변환 전 원값
    (설명 문장 서술용, spec FR-015)이다. 둘 다 있어야 하고 결측 월은 이 값이 아니라
    **매핑의 키 없음**으로 온다.
    """

    month: date
    value: float
    raw: float

    def __post_init__(self) -> None:
        require_month(self.month, "대상월")
        object.__setattr__(self, "value", _require_finite(self.value, "변환값"))
        object.__setattr__(self, "raw", _require_finite(self.raw, "원값"))


@dataclass(frozen=True)
class BaselineMonths:
    """분야별 T_g와 공통 T_all (008 FR-013).

    T_g는 그 분야 지표들이 함께 확보된 최신월, T_all은 전체 지표가 함께 확보된 최신월이다.
    **``composite``는 ``None``일 수 있다** — 지표 교체·개편으로 전 지표가 함께 있는 달이
    아예 없을 수 있고, 그때 종합만 계산 불가이며 분야 검색은 그대로 제공한다(spec FR-039).

    이 값은 계산에 쓰고 ``regime_baseline_month``에 기록한다. 조회 쪽은 그 표를 읽고 008을
    실시간으로 읽지 않는다 — 배치가 아직 돌지 않은 달을 화면이 고르면 결과가 없다
    (contracts/macro-indicator-port.md §0.2).
    """

    by_field: dict[str, date]
    composite: date | None

    def __post_init__(self) -> None:
        if not self.by_field:
            raise MacroSourceError("분야별 기준월이 비어 있다")
        for field_id, month in self.by_field.items():
            _require_identifier(field_id, "기준월의 분야 id")
            require_month(month, f"분야 {field_id}의 기준월")
        if self.composite is not None:
            require_month(self.composite, "종합 기준월")


@dataclass(frozen=True)
class Publication:
    """그 값의 대상월·발표일·개정 여부 (008 FR-007, spec FR-031).

    발표일은 ``data_asof``로 **복사해 둔다** — 화면이 "이 결과가 언제 자료인가"를 말하려면
    계산 당시의 발표일이어야 하고, 나중에 008을 다시 읽으면 그 뒤 개정된 값이 나온다
    (data-model.md §9).
    """

    indicator_id: str
    month: date
    published_on: date
    revised: bool

    def __post_init__(self) -> None:
        _require_identifier(self.indicator_id, "지표 id")
        require_month(self.month, "대상월")
        if not isinstance(self.published_on, date) or isinstance(self.published_on, bool):
            raise MacroSourceError(f"발표일은 date여야 한다: {self.published_on!r}")
        if self.published_on < self.month:
            raise MacroSourceError(
                f"발표일이 대상월보다 앞선다: {self.indicator_id} "
                f"{format_month(self.month)} → {self.published_on.isoformat()}"
            )
        if not isinstance(self.revised, bool):
            raise MacroSourceError(f"개정 여부는 bool이어야 한다: {self.revised!r}")
