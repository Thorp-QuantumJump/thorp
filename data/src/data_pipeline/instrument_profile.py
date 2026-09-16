"""대표 상품 정보 산출 (spec 001 FR-011·FR-015, T012).

사용자가 결과의 근거와 한계를 확인하는 묶음이며, 화면에 그대로 보이는 값이다
(contracts/sector-api.md ``GET /sectors/{code}/instrument-profile``). 저장 대상은
``instrument_profile`` 표이고 열 구성은 data-model.md 「instrument_profile」이 원본이다.

## 값이 어디서 오는가

| 값 | 출처 |
|---|---|
| ``avg_daily_value_3m`` | ``daily_price.value`` (거래대금). **이 모듈이 계산한다** |
| ``index_methodology_summary`` | 파일 입력 |
| ``holdings_overlap``·``holdings_as_of_current`` | 파일 입력 |
| ``source_attribution``·``survivorship_note`` | 상수 (FR-011·FR-015) |
| 기준 지수·상장일·운용사 | 이 표에 없다. ``representative_etf``가 갖고 조회 API가 함께 읽는다 |

**지수 방법론 요약과 구성종목 중복을 파일로 받는 이유**: KRX OPEN API에 그 둘을 주는
서비스가 없다. 2026-09-13에 인증키로 직접 확인했다 — ``etp/etf_bydd_trd``는 200이지만 기초지수
이름(``IDX_IND_NM``)과 순자산총액까지이고 구성종목이 없으며, 구성종목·지수 산출 방법을 줄 만한
경로(``etp/etf_pdf``·``etp/etf_comp_isu``·``idx/idx_comp_isu``·``idx/idx_base_info``)는 모두
``404 API referenced by the path does not exist``다. 상장일·기업행위를 파일로 받는 것과 같은
사정이며(:mod:`data_pipeline.krx` 문서 5번, ``scripts/ingest_krx.py``의 ``--listing``·
``--corporate-actions``), 같은 선례를 따른다. 원천이 나중에 제공하면 파일 입력을 수집으로
바꾸되 열 구성은 그대로다.

## 유동성은 거래대금이지 거래량이 아니다

FR-015의 유동성은 **최근 3개월 일평균 거래대금(원)**이고 근거 열은 ``daily_price.value``다.
거래량(``volume``)에 종가를 곱해 추정하지 않는다 — 그날의 가격이 오르내린 만큼 실제
거래대금과 어긋난다(research.md 결정 6). 그래서 이 모듈은 ``close``를 인자로도 받지 않는다.

**"최근 3개월"의 범위**: 자료 마감일을 포함하는 직전 3개월이다. 마감일에서 달을 셋 뺀 날의
**다음 날**부터 마감일까지이며, 그 사이에 있는 ``daily_price`` 행만 쓴다. 달 단위로 빼는 값이
없는 날짜(5월 31일의 석 달 전)는 그 달의 마지막 날로 맞춘다. 거래일 수로 세지 않는 이유는
FR-015가 기간을 달로 말하고, 거래일 환산값은 해마다 달라져 같은 말이 다른 구간을 가리키기
때문이다.

**거래정지일도 분모에 넣는다.** 그날은 장이 열렸는데 이 종목이 거래되지 않은 날이라
거래대금 0원이 사실이고, 빼면 유동성이 실제보다 좋아 보인다. 결측(행 자체가 없는 날)은
애초에 세지 않는다.

## 추적 차이를 만들지 않는다

기획서 §3.3이 항목으로 들었으나 계산 정의가 확정되지 않아 이번 범위에서 뺐다(PD-21,
spec.md Out of Scope). 그래서 이 모듈에는 추적 차이 필드가 없고, 파일 입력에 그런 키가 들어오면
조용히 버리지 않고 거부한다. 정의가 확정되면 필드를 더하는 방향으로 넓힌다.
"""

from __future__ import annotations

import calendar as _calendar
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation
from typing import Any, Final

#: FR-011이 요구하는 출처 표시. 한국거래소 자료를 쓰는 화면에 그대로 실린다.
SOURCE_ATTRIBUTION_KRX: Final[str] = "한국거래소 통계정보"

#: FR-015가 요구하는 생존 편향 한계 한 문장. contracts/sector-api.md의 예시와 같은 값이다.
SURVIVORSHIP_NOTE: Final[str] = (
    "현재 상장되어 있는 상품만 다루므로 중간에 상장폐지된 상품은 포함되지 않습니다."
)

#: 유동성 집계 구간의 길이(달). FR-015 "최근 3개월".
LIQUIDITY_WINDOW_MONTHS: Final[int] = 3

#: 저장 자리수. ``avg_daily_value_3m``은 원 단위 금액이라 정수로 반올림해 담는다. 원보다 작은
#: 자리는 거래대금에 뜻이 없고, 계약이 금액을 plain 문자열로 규정하므로 지수 표기도 쓰지 않는다.
_WON: Final[Decimal] = Decimal(1)

#: 비중 자리수. 계약 예시(``"0.2431"``)와 같은 소수 넷째 자리다.
_WEIGHT_EXPONENT: Final[Decimal] = Decimal("0.0001")

INSTRUMENT_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9A-Z]{6}\.XKRX\Z")

#: 파일 입력이 받는 키. 여기 없는 키는 거부한다 — 특히 추적 차이(PD-21)가 조용히 섞이지 않게 한다.
#: ``source_attribution``을 받지 않는 이유는 그 값이 종목마다 다른 자료가 아니라 원천이 정해 주는
#: 한 문장이기 때문이다. 파일이 덮어쓸 수 있으면 FR-011이 요구한 출처 표시가 파일을 쓰는 사람의
#: 손에 달리고, SC-005의 "100%"를 코드가 보장하지 못한다.
_PROFILE_KEYS: Final[frozenset[str]] = frozenset(
    {"instrument_id", "index_methodology_summary", "holdings_overlap"}
)
_OVERLAP_KEYS: Final[frozenset[str]] = frozenset({"as_of_current_composition", "top_holdings"})
_HOLDING_KEYS: Final[frozenset[str]] = frozenset({"name", "weight"})

#: 상위 종목 개수 상한. 화면이 펼쳐 보는 층에 싣는 목록이고, 전체 구성종목을 옮겨 담는 표가
#: 아니다(data-model.md는 "상위 종목과 비중"이라고 적었다).
MAX_TOP_HOLDINGS: Final[int] = 20


class InstrumentProfileError(ValueError):
    """대표 상품 정보의 값 규칙 위반. 값을 지어내지 않고 알린다."""


def plain(value: Decimal) -> str:
    """지수 표기 없는 문자열. ``str(Decimal("1E+9"))``은 ``"1E+9"``라 그대로 쓸 수 없다.

    계약이 금액·비율을 plain 문자열로 규정하고(contracts/sector-api.md 「공통 규칙」), 저장
    스키마의 ``ck_instrument_profile_avg_daily_value_plain``도 그 모양만 받는다. 백엔드가
    같은 일을 ``PlainBigDecimalSerializer``로 하고 있으므로 양쪽 표기가 같아진다.
    """
    return format(value, "f")


# ---------------------------------------------------------------------------
# 유동성
# ---------------------------------------------------------------------------


def months_before(day: date, months: int) -> date:
    """``day``에서 달을 ``months``만큼 뺀 날. 그 달에 없는 일자는 말일로 맞춘다."""
    if months < 0:
        raise InstrumentProfileError(f"months는 0 이상이어야 한다: {months}")
    total = day.year * 12 + (day.month - 1) - months
    year, month = divmod(total, 12)
    last_day = _calendar.monthrange(year, month + 1)[1]
    return date(year, month + 1, min(day.day, last_day))


def liquidity_window(
    data_deadline: date, *, months: int = LIQUIDITY_WINDOW_MONTHS
) -> tuple[date, date]:
    """유동성 집계 구간 ``(시작일, 종료일)``. 양끝을 모두 포함한다."""
    if months < 1:
        raise InstrumentProfileError(f"집계 구간은 한 달 이상이어야 한다: {months}")
    start = months_before(data_deadline, months)
    return (date.fromordinal(start.toordinal() + 1), data_deadline)


def _as_date(value: object, field: str) -> date:
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, date):
        return value
    raise InstrumentProfileError(f"{field}은(는) 날짜여야 한다: {value!r}")


def average_daily_trading_value(
    price_rows: Iterable[Mapping[str, Any]],
    instrument_id: str,
    *,
    data_deadline: date,
    months: int = LIQUIDITY_WINDOW_MONTHS,
) -> Decimal:
    """최근 ``months``개월 일평균 거래대금(원)을 낸다 (FR-015).

    ``price_rows``는 ``daily_price``에 적재할 행이며 ``value``(거래대금)를 그대로 쓴다.
    한 행도 없으면 값을 지어내지 않고 :class:`InstrumentProfileError` 다 — 0원으로 내보내면
    "거래가 없었다"와 "자료가 없다"가 같은 숫자가 된다(원칙 VI).
    """
    require_instrument_id(instrument_id)
    start, end = liquidity_window(data_deadline, months=months)
    total = Decimal(0)
    days = 0
    for position, row in enumerate(price_rows):
        if not isinstance(row, Mapping):
            raise InstrumentProfileError(
                f"{position}번째 가격 행이 매핑이 아니다: {type(row).__name__}"
            )
        if row.get("instrument_id") != instrument_id:
            continue
        day = _as_date(row.get("date"), "date")
        if not (start <= day <= end):
            continue
        if "value" not in row:
            raise InstrumentProfileError(
                f"{instrument_id} {day} 행에 value(거래대금)가 없다. 유동성은 거래대금 열로만 낸다 "
                "— 거래량과 종가를 곱한 추정은 그날의 가격 변동만큼 실제와 어긋난다"
            )
        total += _require_nonnegative_integer(row["value"], f"{instrument_id} {day}의 value")
        days += 1
    if days == 0:
        raise InstrumentProfileError(
            f"{instrument_id}의 {start}~{end} 구간에 가격 행이 없다. 일평균 거래대금을 낼 근거가 "
            "없으므로 0원으로 만들어 내지 않는다 (원칙 VI)"
        )
    return (total / Decimal(days)).quantize(_WON, rounding=ROUND_HALF_UP)


def _require_nonnegative_integer(value: object, field: str) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, int):
        raise InstrumentProfileError(f"{field}은(는) 정수여야 한다: {value!r}")
    if value < 0:
        raise InstrumentProfileError(f"{field}은(는) 0 이상이어야 한다: {value!r}")
    return Decimal(value)


# ---------------------------------------------------------------------------
# 값
# ---------------------------------------------------------------------------


def require_instrument_id(value: object) -> str:
    if not isinstance(value, str) or not INSTRUMENT_ID_PATTERN.match(value):
        raise InstrumentProfileError(f"instrument_id는 {{6글자}}.XKRX 형식이어야 한다: {value!r}")
    return value


def _require_text(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InstrumentProfileError(f"{field}이(가) 비어 있다: {value!r}")
    return value.strip()


def _check_keys(payload: Mapping[str, Any], allowed: frozenset[str], where: str) -> None:
    """모르는 키를 거부한다. 추적 차이(PD-21)가 조용히 흘러드는 것을 여기서 막는다."""
    unknown = sorted(key for key in payload if key not in allowed)
    if unknown:
        raise InstrumentProfileError(
            f"{where}에 모르는 키가 있다: {unknown}. 받는 키는 {sorted(allowed)}뿐이다. "
            "추적 차이는 계산 정의가 확정되지 않아 이번 범위에서 뺐다 (PD-21)"
        )


@dataclass(frozen=True)
class Holding:
    """구성종목 한 건. 비중은 1을 100%로 보는 비율이다."""

    name: str
    weight: Decimal

    def __post_init__(self) -> None:
        object.__setattr__(self, "name", _require_text(self.name, "구성종목 name"))
        weight = self.weight
        if isinstance(weight, str | int):
            try:
                weight = Decimal(weight)
            except InvalidOperation as error:
                raise InstrumentProfileError(f"weight가 숫자가 아니다: {self.weight!r}") from error
        if not isinstance(weight, Decimal):
            raise InstrumentProfileError(f"weight는 Decimal이어야 한다: {self.weight!r}")
        if not weight.is_finite() or weight <= 0 or weight > 1:
            raise InstrumentProfileError(f"weight는 0 초과 1 이하여야 한다: {weight}")
        object.__setattr__(self, "weight", weight)

    def to_json_value(self) -> dict[str, str]:
        """JSONB에 담을 모양. 비중은 문자열이다 — jsonb가 숫자를 정규화해 자리수를 바꾼다."""
        return {"name": self.name, "weight": plain(self.weight)}


@dataclass(frozen=True)
class HoldingsOverlap:
    """구성종목 중복 표시 (FR-015).

    ``as_of_current``가 true면 화면은 "당시 구성을 재현했다"고 표현하지 않는다. 과거 구성
    이력을 복원하는 일은 범위 밖이므로(spec.md Out of Scope) 이번 범위에서 만드는 값은 모두
    현재 구성 기준이다. 그래도 열을 두는 까닭은, 나중에 과거 이력이 생겼을 때 어느 행이 현재
    구성으로 만든 값인지 사후에 가릴 수 있어야 하기 때문이다.
    """

    holdings: tuple[Holding, ...]
    as_of_current: bool

    def __post_init__(self) -> None:
        if not isinstance(self.as_of_current, bool):
            raise InstrumentProfileError(
                f"as_of_current_composition은 참·거짓이어야 한다: {self.as_of_current!r}"
            )
        holdings = tuple(self.holdings)
        if not holdings:
            raise InstrumentProfileError(
                "구성종목이 비어 있다. 빈 목록을 내보내면 '중복이 없다'로 읽히므로, "
                "자료가 없으면 대표 상품 정보를 만들지 않는다 (원칙 VI)"
            )
        if len(holdings) > MAX_TOP_HOLDINGS:
            raise InstrumentProfileError(
                f"상위 종목은 {MAX_TOP_HOLDINGS}개까지다: {len(holdings)}개. "
                "전체 구성종목을 옮겨 담는 표가 아니다"
            )
        names = [holding.name for holding in holdings]
        duplicated = sorted({name for name in names if names.count(name) > 1})
        if duplicated:
            raise InstrumentProfileError(f"같은 종목이 두 번 들어 있다: {duplicated}")
        total = sum((holding.weight for holding in holdings), Decimal(0))
        if total > 1:
            raise InstrumentProfileError(f"비중의 합이 1을 넘는다: {total}")
        # 큰 비중부터 보여야 "상위 종목"이라는 말과 화면이 맞는다. 파일이 어떤 차례로 적어 왔든
        # 여기서 한 번 세워 두면 읽는 쪽이 다시 정렬하지 않는다.
        object.__setattr__(
            self, "holdings", tuple(sorted(holdings, key=lambda item: (-item.weight, item.name)))
        )

    def to_json_value(self) -> dict[str, Any]:
        """``instrument_profile.holdings_overlap``에 담을 JSONB 값."""
        return {"topHoldings": [holding.to_json_value() for holding in self.holdings]}


@dataclass(frozen=True)
class InstrumentProfile:
    """``instrument_profile`` 한 행. ``etf_id``·``dataset_version_id``는 적재가 찾아 넣는다."""

    instrument_id: str
    index_methodology_summary: str
    holdings: HoldingsOverlap
    avg_daily_value_3m: Decimal
    source_attribution: str = SOURCE_ATTRIBUTION_KRX
    survivorship_note: str = SURVIVORSHIP_NOTE

    def __post_init__(self) -> None:
        object.__setattr__(self, "instrument_id", require_instrument_id(self.instrument_id))
        object.__setattr__(
            self,
            "index_methodology_summary",
            _require_text(self.index_methodology_summary, "index_methodology_summary"),
        )
        if not isinstance(self.holdings, HoldingsOverlap):
            raise InstrumentProfileError(
                f"holdings는 HoldingsOverlap이어야 한다: {type(self.holdings).__name__}"
            )
        value = self.avg_daily_value_3m
        if not isinstance(value, Decimal):
            raise InstrumentProfileError(f"avg_daily_value_3m은 Decimal이어야 한다: {value!r}")
        if not value.is_finite() or value < 0:
            raise InstrumentProfileError(f"avg_daily_value_3m은 0 이상이어야 한다: {value}")
        object.__setattr__(
            self, "source_attribution", _require_text(self.source_attribution, "source_attribution")
        )
        object.__setattr__(
            self, "survivorship_note", _require_text(self.survivorship_note, "survivorship_note")
        )

    def to_row(self) -> dict[str, Any]:
        """적재용 행. ``instrument_id``는 적재가 ``etf_id``로 바꿔 넣고 행에서 뺀다."""
        return {
            "instrument_id": self.instrument_id,
            "index_methodology_summary": self.index_methodology_summary,
            "holdings_overlap": self.holdings.to_json_value(),
            "holdings_as_of_current": self.holdings.as_of_current,
            # 금액은 문자열로 담는다. NUMERIC은 scale을 임의로 정해야 하고 넘는 값을 조용히
            # 반올림한다(backend/CLAUDE.md, data-model.md instrument_profile 불변식).
            "avg_daily_value_3m": plain(self.avg_daily_value_3m),
            "source_attribution": self.source_attribution,
            "survivorship_note": self.survivorship_note,
        }


# ---------------------------------------------------------------------------
# 파일 입력
# ---------------------------------------------------------------------------


def holdings_overlap_from_payload(payload: object, where: str) -> HoldingsOverlap:
    """``holdings_overlap`` 객체를 :class:`HoldingsOverlap` 으로 바꾼다 (snake_case 파일 형식)."""
    if not isinstance(payload, Mapping):
        raise InstrumentProfileError(f"{where}의 holdings_overlap이 객체가 아니다: {payload!r}")
    _check_keys(payload, _OVERLAP_KEYS, f"{where}의 holdings_overlap")
    missing = sorted(_OVERLAP_KEYS - set(payload))
    if missing:
        raise InstrumentProfileError(f"{where}의 holdings_overlap에 없는 키: {missing}")
    raw_holdings = payload["top_holdings"]
    if not isinstance(raw_holdings, Sequence) or isinstance(raw_holdings, str | bytes):
        raise InstrumentProfileError(f"{where}의 top_holdings가 배열이 아니다: {raw_holdings!r}")
    holdings: list[Holding] = []
    for position, item in enumerate(raw_holdings):
        spot = f"{where}의 top_holdings {position}번째 항목"
        if not isinstance(item, Mapping):
            raise InstrumentProfileError(f"{spot}이 객체가 아니다: {item!r}")
        _check_keys(item, _HOLDING_KEYS, spot)
        missing_keys = sorted(_HOLDING_KEYS - set(item))
        if missing_keys:
            raise InstrumentProfileError(f"{spot}에 없는 키: {missing_keys}")
        weight = item["weight"]
        if not isinstance(weight, str):
            # 소수를 JSON 숫자로 받으면 부동소수로 풀려 자리수가 흔들린다. 계약도 비중을
            # 문자열로 규정한다(contracts/sector-api.md 예시).
            raise InstrumentProfileError(f"{spot}의 weight는 문자열이어야 한다: {weight!r}")
        holdings.append(Holding(name=item["name"], weight=_parse_weight(weight, spot)))
    as_of_current = _require_bool(payload["as_of_current_composition"])
    if not as_of_current:
        # 과거 구성 이력을 주는 원천이 없다(2026-09-13 확인). 거짓으로 두면 "당시 구성을
        # 재현했다"는 뜻이 되어 FR-015가 금지한 표현이 화면에 실린다. 나중에 과거 구성을
        # 확보하면 이 검사를 푸는 자리가 여기다.
        raise InstrumentProfileError(
            f"{where}의 as_of_current_composition은 참이어야 한다. 과거 구성 이력을 주는 "
            "원천이 없어 당시 구성을 재현했다고 표현할 수 없다 (FR-015)"
        )
    return HoldingsOverlap(holdings=tuple(holdings), as_of_current=as_of_current)


def _parse_weight(value: str, where: str) -> Decimal:
    try:
        weight = Decimal(value)
    except InvalidOperation as error:
        raise InstrumentProfileError(f"{where}의 weight가 숫자가 아니다: {value!r}") from error
    return weight.quantize(_WEIGHT_EXPONENT, rounding=ROUND_HALF_UP)


def _require_bool(value: object) -> bool:
    if not isinstance(value, bool):
        raise InstrumentProfileError(f"as_of_current_composition은 참·거짓이어야 한다: {value!r}")
    return value


def profiles_from_records(
    records: Iterable[Mapping[str, Any]],
    price_rows: Sequence[Mapping[str, Any]],
    *,
    data_deadline: date,
    months: int = LIQUIDITY_WINDOW_MONTHS,
) -> list[InstrumentProfile]:
    """파일에서 읽은 항목과 가격 행으로 대표 상품 정보를 만든다.

    ``avg_daily_value_3m``만 이 자리에서 계산하고 나머지는 파일이 준 값이다. 파일이 계산값을
    함께 들고 오지 못하게 막는 이유는, 두 곳에서 만든 숫자가 갈라져도 아무도 알아채지 못하기
    때문이다 — 받는 키 목록(:data:`_PROFILE_KEYS`)에 ``avg_daily_value_3m``이 없다.
    """
    profiles: list[InstrumentProfile] = []
    seen: dict[str, int] = {}
    for position, record in enumerate(records):
        where = f"대표 상품 정보 {position}번째 항목"
        if not isinstance(record, Mapping):
            raise InstrumentProfileError(f"{where}이 객체가 아니다: {type(record).__name__}")
        _check_keys(record, _PROFILE_KEYS, where)
        required = {"instrument_id", "index_methodology_summary", "holdings_overlap"}
        missing = sorted(required - set(record))
        if missing:
            raise InstrumentProfileError(f"{where}에 없는 키: {missing}")
        instrument_id = require_instrument_id(record["instrument_id"])
        if instrument_id in seen:
            raise InstrumentProfileError(
                f"{instrument_id}의 대표 상품 정보가 두 번 들어 있다 "
                f"({seen[instrument_id]}번째와 {position}번째)"
            )
        seen[instrument_id] = position
        profiles.append(
            InstrumentProfile(
                instrument_id=instrument_id,
                index_methodology_summary=record["index_methodology_summary"],
                holdings=holdings_overlap_from_payload(record["holdings_overlap"], where),
                avg_daily_value_3m=average_daily_trading_value(
                    price_rows, instrument_id, data_deadline=data_deadline, months=months
                ),
                source_attribution=SOURCE_ATTRIBUTION_KRX,
            )
        )
    return profiles
