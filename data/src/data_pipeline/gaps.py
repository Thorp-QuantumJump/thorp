"""자료 부족 구간과 거래정지 구간 (spec 001 FR-005·FR-006, contracts/market-dataset.md).

## 거래일 하나를 어떻게 처리하는가

거래일 달력은 ``daily_index``의 날짜 집합이다(별도 표를 두지 않는다). 그 달력의 하루에 대해
그 종목의 자료가 어떤 상태인지에 따라 셋으로 갈린다.

| 상태 | ``daily_price`` 행 | 표시 |
|---|---|---|
| 체결이 있었다 | 만든다 | 그대로 |
| 체결 가격이 없었다 (거래정지 등) | 만든다 | ``trading_suspended``와
  ``price_carried_forward`` 둘 다 참 |
| 자료를 아예 얻지 못했다 (진짜 결측) | **만들지 않는다** | ``data_gap``(``AMBIGUOUS_PRICE``) |

정지일에 행을 만드는 이유는 004 계약이 "결측은 행 부재로 표현하고 워커는 결측 거래일을 만나면
``DATA_INSUFFICIENT``로 실패한다"고 정했기 때문이다. 정지일에 행이 없으면 워커가 001의 자료를
읽자마자 실패한다. **정지는 결측이 아니라 별도 상태다.**

정지일의 모양은 확정되어 있다(2026-09-11). OHLC 네 값은 모두 직전 거래일 종가라 서로 같고,
``volume``·``value``는 0이며 ``price_carried_forward``도 참이다. 마이그레이션의
``ck_daily_price_suspended_shape``가 같은 조건을 강제한다.

**판정 기준은 ``trading_suspended``이며 ``volume = 0``이 아니다.** 정지가 아니면서 거래량이
0인 날이 있을 수 있고, 그날은 호가가 있어 체결이 가능하다. 두 조건을 같은 것으로 다루면
정지가 아닌 날의 주문을 잘못 막는다.

## 원천이 주는 것과 이 모듈이 정하는 것

KRX OPEN API의 ETF 일별매매정보에는 **거래정지 여부를 알리는 열이 없다.** 체결이 없던 날은
시가·고가·저가가 ``"0"``이고 종가 자리에 거래소 기준가가 온다(:mod:`data_pipeline.krx` 문서
3번). 그래서 이 모듈은 "거래일인데 체결 가격이 없다"를 정지로 본다 — FR-006이 정한 발동
조건("거래일인데 체결 가격이 없으면(거래정지 등)")과 같은 조건이다. 원천이 낸 기준가는 쓰지
않고 **직전 종가로 덮는다.** 확정된 규칙이 그렇고, 기준가와 직전 종가가 다른 사례를 실제
응답에서 확인했다(T015 입력으로 보고에 남긴다).

## ``SUSPENDED_TOO_LONG``을 쓰지 않는다

``data_gap.reason``으로 쓰는 값은 넷뿐이다: ``AMBIGUOUS_PRICE``·``MISSING_CORPORATE_ACTION``·
``RECONCILIATION_UNKNOWN``·``INDEX_HISTORY_SHORT``. ``SUSPENDED_TOO_LONG``은 PD-19·PD-20이
확정되기 전에는 쓰지 않는다(:data:`SUSPENSION_RULE_CONFIRMED` 가 그 스위치다). 확정 전에
정지를 자료 부족으로 빼면 T015의 결정 근거가 될 정지 기록이 사라지고, 다른 기능의 개발도
막힌다. 대신 정지 구간을 ``suspension_period``에 시작일·종료일·거래일 수로 남긴다.
"""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Any, Final

from data_pipeline.corporate_actions import (
    AdjustmentSeries,
    CorporateAction,
    build_adjustment_series,
    quantize_adjusted_close,
    quantize_adjustment_factor,
)
from data_pipeline.krx import DailyQuote, ListingWindow

INSTRUMENT_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9]{6}\.XKRX\Z")

#: ``data_gap.reason``으로 지금 쓸 수 있는 값. ``SUSPENDED_TOO_LONG``은 여기 없다 (FR-006).
REASON_AMBIGUOUS_PRICE: Final[str] = "AMBIGUOUS_PRICE"
REASON_MISSING_CORPORATE_ACTION: Final[str] = "MISSING_CORPORATE_ACTION"
REASON_RECONCILIATION_UNKNOWN: Final[str] = "RECONCILIATION_UNKNOWN"
REASON_INDEX_HISTORY_SHORT: Final[str] = "INDEX_HISTORY_SHORT"

ALLOWED_GAP_REASONS: Final[frozenset[str]] = frozenset(
    {
        REASON_AMBIGUOUS_PRICE,
        REASON_MISSING_CORPORATE_ACTION,
        REASON_RECONCILIATION_UNKNOWN,
        REASON_INDEX_HISTORY_SHORT,
    }
)

#: PD-19·PD-20 확정 전에는 쓰지 않는 값. 확정되면 T015가 이 스위치를 켠다.
REASON_SUSPENDED_TOO_LONG: Final[str] = "SUSPENDED_TOO_LONG"

#: PD-19·PD-20이 확정되었는가. **확정 전에는 false다.** 이 값이 false인 동안
#: ``SUSPENDED_TOO_LONG``을 자료 부족 구간에 쓰면 :func:`validate_gap_rows` 가 거부한다.
#: T015가 규칙을 확정하면 이 한 줄을 켜고 그때부터 기록이 시작된다.
SUSPENSION_RULE_CONFIRMED: Final[bool] = False


class GapError(ValueError):
    """자료 부족·정지 구간 규칙 위반."""


# ---------------------------------------------------------------------------
# 일별 가격 행 만들기
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class DailyPriceBuild:
    """한 종목의 ``daily_price`` 행과 그에서 파생된 기록.

    ``rows``는 적재할 일별 가격 행이고, ``suspension_periods``는 정지 표시가 연속한 구간을
    묶은 파생 표다. 일별 표시가 원본이고 둘이 어긋나면 일별 표시를 기준으로 다시 만든다
    (data-model.md ``suspension_period``).
    """

    instrument_id: str
    rows: tuple[dict[str, Any], ...]
    suspension_periods: tuple[dict[str, Any], ...]
    gaps: tuple[dict[str, Any], ...]
    carried_forward_dates: tuple[date, ...]

    @property
    def suspended_dates(self) -> tuple[date, ...]:
        return tuple(row["date"] for row in self.rows if row["trading_suspended"])


def build_daily_prices(
    quotes: Sequence[DailyQuote],
    calendar: Sequence[date],
    window: ListingWindow,
    adjustment: AdjustmentSeries,
) -> DailyPriceBuild:
    """거래일 달력을 기준으로 ``daily_price`` 행을 만든다.

    ``adjustment``의 조정 계열은 **여기서 만든 원시 종가 계열** 위에서 계산된 것이어야 한다.
    정지일의 원시 종가가 직전 종가로 정해지므로 순서가 뒤바뀌면 조정가가 다른 계열을 가리킨다.
    :func:`build_instrument_series` 가 그 순서를 묶어 준다.

    정지일의 ``adjusted_close``는 직전 종가에 **그날의** 누적 계수를 곱한 값이다. 사이에
    기업행위가 없으면 직전 거래일의 조정가와 같아지고, 있으면 계수가 달라지는 것이 맞다.
    """
    if INSTRUMENT_ID_PATTERN.match(window.instrument_id) is None:
        raise GapError(f"종목 식별자가 {{6자리}}.XKRX 형식이 아니다: {window.instrument_id!r}")
    by_date = {quote.date: quote for quote in quotes}
    if len(by_date) != len(quotes):
        raise GapError(f"같은 날짜의 시세가 두 번 들어 있다: {window.instrument_id}")
    for quote in quotes:
        if quote.instrument_id != window.instrument_id:
            raise GapError(
                f"다른 종목의 시세가 섞여 있다: {window.instrument_id} ≠ {quote.instrument_id}"
            )

    trading_days = [day for day in sorted(set(calendar)) if window.contains(day)]
    rows: list[dict[str, Any]] = []
    missing_days: list[date] = []
    carried: list[date] = []
    previous_close: Decimal | None = None

    for day in trading_days:
        quote = by_date.get(day)
        if quote is None:
            # 진짜 결측이다. 행을 만들지 않는다 (004 계약, 원칙 VI).
            missing_days.append(day)
            continue
        if quote.traded:
            assert quote.open is not None and quote.high is not None and quote.low is not None
            rows.append(
                _price_row(
                    window.instrument_id,
                    day,
                    opened=quote.open,
                    high=quote.high,
                    low=quote.low,
                    close=quote.close,
                    volume=quote.volume,
                    value=quote.value,
                    suspended=False,
                    adjustment=adjustment,
                )
            )
            previous_close = quote.close
            continue
        if previous_close is None:
            # 상장 첫날에 체결이 없으면 직전 종가가 없다. 채울 값이 없으므로 임의 값을
            # 만들지 않고 자료 부족으로 남긴다 (data-model.md 「거래정지일 표현」).
            missing_days.append(day)
            continue
        rows.append(
            _price_row(
                window.instrument_id,
                day,
                opened=previous_close,
                high=previous_close,
                low=previous_close,
                close=previous_close,
                volume=0,
                value=0,
                suspended=True,
                adjustment=adjustment,
            )
        )
        carried.append(day)

    gaps = [
        {
            "instrument_id": window.instrument_id,
            "start_date": start,
            "end_date": end,
            "reason": REASON_AMBIGUOUS_PRICE,
        }
        for start, end in merge_consecutive(missing_days, calendar)
    ]
    gaps.extend(dict(gap) for gap in adjustment.gaps)
    return DailyPriceBuild(
        instrument_id=window.instrument_id,
        rows=tuple(rows),
        suspension_periods=tuple(suspension_periods(rows, calendar)),
        gaps=tuple(validate_gap_rows(gaps)),
        carried_forward_dates=tuple(carried),
    )


def _price_row(
    instrument_id: str,
    day: date,
    *,
    opened: Decimal,
    high: Decimal,
    low: Decimal,
    close: Decimal,
    volume: int,
    value: int,
    suspended: bool,
    adjustment: AdjustmentSeries,
) -> dict[str, Any]:
    """``daily_price`` 한 행. 열 이름은 마이그레이션과 같고 ``dataset_id``는 넣지 않는다."""
    return {
        "instrument_id": instrument_id,
        "date": day,
        "open": opened,
        "high": high,
        "low": low,
        "close": close,
        "volume": int(volume),
        "value": int(value),
        "adjusted_close": quantize_adjusted_close(adjustment.adjusted_on(day)),
        "adjustment_factor": quantize_adjustment_factor(adjustment.factor_on(day)),
        "trading_suspended": suspended,
        # 정지일은 직전 종가를 썼으므로 함께 참이다. 두 열의 뜻은 다르다 — 앞은 원인,
        # 뒤는 처리다. 정지가 아닌 사유로 직전 종가를 쓰는 경우가 생기면 구분할 수 있어야 한다.
        "price_carried_forward": suspended,
    }


def raw_close_series(
    quotes: Sequence[DailyQuote], calendar: Sequence[date], window: ListingWindow
) -> dict[date, Decimal]:
    """조정 계산에 넣을 **원시 종가 계열**을 만든다.

    정지일에는 직전 종가가 들어간다. 조정 계열을 이 계열 위에서 계산해야
    :func:`build_daily_prices` 가 만드는 행의 ``close``와 ``adjusted_close``가 같은 계열을
    가리킨다. 순서를 뒤집으면 정지일의 조정가가 원천 기준가 기준이 되어 어긋난다.
    """
    by_date = {quote.date: quote for quote in quotes}
    closes: dict[date, Decimal] = {}
    previous_close: Decimal | None = None
    for day in sorted(set(calendar)):
        if not window.contains(day):
            continue
        quote = by_date.get(day)
        if quote is None:
            continue
        if quote.traded:
            closes[day] = quote.close
            previous_close = quote.close
            continue
        if previous_close is None:
            continue
        closes[day] = previous_close
    return closes


# ---------------------------------------------------------------------------
# 구간 묶기
# ---------------------------------------------------------------------------


def merge_consecutive(days: Iterable[date], calendar: Sequence[date]) -> list[tuple[date, date]]:
    """거래일 달력에서 **잇따른** 날짜를 한 구간으로 묶는다.

    달력 기준으로 묶는 이유는 주말·휴일을 사이에 끼고 이어진 정지를 둘로 쪼개지 않기
    위함이다. 달력 날짜로 금요일과 다음 월요일은 잇따른 거래일이다.
    """
    ordered_calendar = sorted(set(calendar))
    position = {day: index for index, day in enumerate(ordered_calendar)}
    ordered = sorted(set(days))
    unknown = [day for day in ordered if day not in position]
    if unknown:
        raise GapError(f"거래일 달력에 없는 날짜다: {unknown[:5]}")

    ranges: list[tuple[date, date]] = []
    for day in ordered:
        index = position[day]
        if ranges and position[ranges[-1][1]] + 1 == index:
            ranges[-1] = (ranges[-1][0], day)
            continue
        ranges.append((day, day))
    return ranges


def suspension_periods(
    rows: Iterable[Mapping[str, Any]], calendar: Sequence[date]
) -> list[dict[str, Any]]:
    """정지 표시가 잇따른 구간을 ``suspension_period`` 행으로 묶는다.

    ``trading_days``는 그 구간에 든 거래일 수이며 달력 일수가 아니다. 이 기록이 T015의
    입력이 되므로 길이는 거래일로 세야 한다 — 달력 일수로 세면 연휴가 낀 정지가 더 길게
    보인다.
    """
    ordered_calendar = sorted(set(calendar))
    suspended = sorted(row["date"] for row in rows if bool(row.get("trading_suspended")))
    if not suspended:
        return []
    instruments = {row["instrument_id"] for row in rows}
    if len(instruments) != 1:
        raise GapError(f"한 종목의 행만 묶을 수 있다: {sorted(instruments)}")
    instrument_id = next(iter(instruments))
    calendar_set = set(ordered_calendar)
    periods: list[dict[str, Any]] = []
    for start, end in merge_consecutive(suspended, ordered_calendar):
        trading_days = sum(1 for day in calendar_set if start <= day <= end)
        periods.append(
            {
                "instrument_id": instrument_id,
                "start_date": start,
                "end_date": end,
                "trading_days": int(trading_days),
            }
        )
    return periods


def validate_gap_rows(rows: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """``data_gap`` 행의 ``reason``과 구간을 검사한다.

    ``SUSPENDED_TOO_LONG``은 :data:`SUSPENSION_RULE_CONFIRMED` 가 켜지기 전에는 거부한다.
    마이그레이션의 CHECK는 이 값을 미리 허용해 두었으므로(확정 뒤에 스키마를 고치지 않으려고)
    확정 전 금지를 지키는 곳은 여기뿐이다.
    """
    checked: list[dict[str, Any]] = []
    seen: set[tuple[str, date, str]] = set()
    for row in rows:
        reason = row["reason"]
        if reason == REASON_SUSPENDED_TOO_LONG and not SUSPENSION_RULE_CONFIRMED:
            raise GapError(
                "SUSPENDED_TOO_LONG은 PD-19·PD-20 확정 전까지 쓸 수 없다 (FR-006). "
                "확정 전에 정지를 자료 부족으로 빼면 T015의 결정 근거가 사라진다"
            )
        if reason not in ALLOWED_GAP_REASONS:
            raise GapError(f"reason은 {sorted(ALLOWED_GAP_REASONS)} 중 하나여야 한다: {reason!r}")
        if INSTRUMENT_ID_PATTERN.match(str(row["instrument_id"])) is None:
            raise GapError(f"종목 식별자가 {{6자리}}.XKRX 형식이 아니다: {row['instrument_id']!r}")
        if row["end_date"] < row["start_date"]:
            raise GapError(
                f"end_date는 start_date 이후여야 한다: {row['instrument_id']} "
                f"{row['start_date']} → {row['end_date']}"
            )
        # data_gap의 PK가 (dataset_id, instrument_id, start_date, reason)다. 같은 키가 두 번
        # 있으면 적재가 PK 위반으로 깨지는데, 그때는 절반만 들어간 표가 남는다.
        identity = (str(row["instrument_id"]), row["start_date"], str(reason))
        if identity in seen:
            raise GapError(f"같은 자료 부족 구간이 두 번 들어 있다: {identity}")
        seen.add(identity)
        checked.append(dict(row))
    return checked


# ---------------------------------------------------------------------------
# 한 종목 묶어 처리하기
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class InstrumentSeries:
    """한 종목의 적재 준비 결과. 조정과 정지 처리를 정해진 순서로 묶은 것이다."""

    instrument_id: str
    adjustment: AdjustmentSeries
    build: DailyPriceBuild

    @property
    def price_rows(self) -> tuple[dict[str, Any], ...]:
        return self.build.rows

    @property
    def gap_rows(self) -> tuple[dict[str, Any], ...]:
        return self.build.gaps

    @property
    def suspension_rows(self) -> tuple[dict[str, Any], ...]:
        return self.build.suspension_periods


def build_instrument_series(
    quotes: Sequence[DailyQuote],
    calendar: Sequence[date],
    window: ListingWindow,
    actions: Iterable[CorporateAction] = (),
    *,
    actions_known_from: date | None = None,
) -> InstrumentSeries:
    """원시 종가 계열 → 조정 계열 → 일별 가격 행 순서를 한 번에 묶는다.

    이 순서를 함수로 묶는 이유는 뒤바꾸면 조용히 틀린 값이 나오기 때문이다. 조정 계열을
    먼저 만들고 정지일을 나중에 채우면, 정지일의 조정가가 원천 기준가 기준이 되어 같은
    행의 ``close``와 다른 계열을 가리킨다.

    ``actions_known_from``은 기업행위 목록이 빠짐없다고 말할 수 있는 가장 이른 날이며
    :func:`data_pipeline.corporate_actions.build_adjustment_series` 로 그대로 넘긴다.
    **이 인자를 여기서 받지 않으면 FR-006의 ``MISSING_CORPORATE_ACTION`` 기록이 파이프라인
    경로에서 영원히 만들어지지 않는다.** 2020년부터만 담은 기업행위 파일로 2010년 상장
    종목을 처리하면 2010~2019년의 누적 계수가 틀린 채로, 그 사실을 알리는 자료 부족 구간도
    없이 발행된다.
    """
    closes = raw_close_series(quotes, calendar, window)
    adjustment = build_adjustment_series(
        window.instrument_id, closes, actions, actions_known_from=actions_known_from
    )
    build = build_daily_prices(quotes, calendar, window, adjustment)
    return InstrumentSeries(instrument_id=window.instrument_id, adjustment=adjustment, build=build)
