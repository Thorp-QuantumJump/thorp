"""KOSPI 시리즈 지수 수집과 거래일 달력 (spec 001 FR-009, contracts/market-dataset.md ※1).

이 자료는 두 가지로 쓰인다.

1. **거래일 달력.** ``daily_index``의 날짜 집합이 거래일 달력이며 별도 표를 두지 않는다.
   개별 ETF는 거래정지될 수 있지만 지수는 장이 열리면 언제나 산출되므로, 지수에 그 날짜가
   있다는 사실이 그날 장이 열렸다는 근거가 된다. ``daily_price``에 행이 있는 날짜의 합집합으로
   달력을 대신하지 않는다 — 모든 종목이 같은 날 정지·결측이면 그 날을 휴일로 오인한다.
2. **결과 보조 보기와 경제환경 사례의 참고 정보.** 검색 입력에는 쓰지 않는다(FR-009).
   그 제한은 이 자료를 읽는 쪽이 지킨다.

**원천의 성질** — 2026-09-11에 실제 응답을 받아 확인했다.

- ``idx/kospi_dd_trd``는 하루치 KOSPI 시리즈 지수 51종을 함께 준다. 종목 선택은 ``IDX_NM``으로
  하며 ``코스피``·``코스피 200``이 정확히 그 문자열로 온다.
- **휴장일에는 빈 배열이 온다**(2026-09-06 일요일 확인). ETF 일별매매정보는 휴장일에도 행을
  주므로(:mod:`data_pipeline.krx` 문서 2번) 거래일 판정을 이 자료에 맡기는 근거가 이것이다.
- 같은 날 응답 안에 값이 비어 있는 행이 섞여 있다(``코스피 (외국주포함)``은 거래량만 있고
  종가·시가가 빈 문자열이다). 그런 행은 값이 없는 것이므로 담지 않는다.
- 2010-01-04까지 거슬러 올라간다(같은 날 확인, 41종).
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date, timedelta
from decimal import Decimal
from typing import Final

from data_pipeline.krx import (
    INSTRUMENT_ID_PATTERN,
    DailyRowSource,
    KrxError,
    KrxQuotaExhaustedError,
    KrxResponseError,
    ListingWindow,
    parse_optional_decimal,
    parse_optional_int,
)

#: KOSPI 시리즈 일별 시세. (서비스 묶음, 엔드포인트) 쌍이다.
KOSPI_DAILY_SERVICE: Final[tuple[str, str]] = ("idx", "kospi_dd_trd")

#: ``daily_index.index_code``에 넣을 코드와 원천의 ``IDX_NM`` 대응.
#: 코드를 한글 이름 그대로 쓰지 않는 이유는 원천의 표기가 바뀌면 키가 따라 바뀌어 옛 자료
#: 버전과 이어지지 않기 때문이다. 이름은 여기 한 곳에서만 원천과 맞춘다.
INDEX_SERIES: Final[dict[str, str]] = {
    "KOSPI": "코스피",
    "KOSPI200": "코스피 200",
}

#: 거래일 달력의 근거가 되는 지수. 광범위 국내 주가지수여야 한다(FR-009).
CALENDAR_INDEX_CODE: Final[str] = "KOSPI"

#: ``data_gap.reason``. 지수 확보 기간이 대표 ETF의 상장 이후를 덮지 못한 구간이다.
REASON_INDEX_HISTORY_SHORT: Final[str] = "INDEX_HISTORY_SHORT"


class IndexDataError(KrxError):
    """지수 수집 오류."""


@dataclass(frozen=True)
class IndexQuote:
    """지수 하루치 한 건. ``daily_index`` 한 행에 대응한다."""

    index_code: str
    date: date
    open: Decimal
    high: Decimal
    low: Decimal
    close: Decimal
    volume: int
    value: int

    def to_row(self) -> dict[str, object]:
        """``daily_index`` 적재용 값 묶음. ``dataset_id``는 적재 인자로만 받는다."""
        return {
            "index_code": self.index_code,
            "date": self.date,
            "open": self.open,
            "high": self.high,
            "low": self.low,
            "close": self.close,
            "volume": self.volume,
            "value": self.value,
        }


def normalize_index_row(
    row: Mapping[str, str], *, expected_date: date, index_code: str
) -> IndexQuote | None:
    """KOSPI 시리즈 한 행을 :class:`IndexQuote` 로 바꾼다.

    네 가격 가운데 하나라도 없으면 ``None``이다. 값이 없는 행을 0으로 채우면 없던 지수를
    만들어 내고, 그 날짜가 거래일 달력에 들어가면 결측을 휴일로 오인하는 판정까지 어긋난다.
    """
    where = f"kospi_dd_trd {expected_date:%Y%m%d} {index_code}"
    basis = str(row.get("BAS_DD") or "").strip()
    if basis and basis != f"{expected_date:%Y%m%d}":
        raise KrxResponseError(f"{where}: 응답의 BAS_DD가 요청한 날짜와 다르다 ({basis})")

    close = parse_optional_decimal(row.get("CLSPRC_IDX"), "CLSPRC_IDX", where)
    opened = parse_optional_decimal(row.get("OPNPRC_IDX"), "OPNPRC_IDX", where)
    high = parse_optional_decimal(row.get("HGPRC_IDX"), "HGPRC_IDX", where)
    low = parse_optional_decimal(row.get("LWPRC_IDX"), "LWPRC_IDX", where)
    if close is None or opened is None or high is None or low is None:
        return None
    if high < max(opened, low, close) or low > min(opened, high, close):
        raise KrxResponseError(
            f"{where}: 고가·저가가 OHLC 상하한을 벗어난다 (o={opened} h={high} l={low} c={close})"
        )
    volume = parse_optional_int(row.get("ACC_TRDVOL"), "ACC_TRDVOL", where) or 0
    value = parse_optional_int(row.get("ACC_TRDVAL"), "ACC_TRDVAL", where) or 0
    return IndexQuote(
        index_code=index_code,
        date=expected_date,
        open=opened,
        high=high,
        low=low,
        close=close,
        volume=volume,
        value=value,
    )


@dataclass
class IndexCollection:
    """지수 수집 결과. 중단 사유까지 함께 담는다 (FR-014).

    ``called_dates``는 **호출을 마친 달력 날짜**이고 값이 왔는지와 무관하다. 지수 수집은
    거래일 달력을 만드는 단계라 후보가 달력 날짜여서(휴장일이 섞여 있다) 이 목록의 마지막
    날짜는 휴장일일 수 있다. 그래서 **자료 마감일로 쓸 수 있는 것은 이 목록이 아니라
    :meth:`last_trading_day` 다.**

    ``KrxCollection``에는 ``last_collected``가 있고 그 값은 거래일이다. ETF 수집은 이미 만들어진
    달력을 입력으로 받기 때문이다. 여기에 같은 이름을 두면 뜻이 다른 두 값이 같은 이름으로
    불려 마감일이 휴장일로 발행된다. 발행본은 트리거가 UPDATE·DELETE를 막아 되돌릴 수
    없으므로(FR-017) 이름을 갈라 둔다.
    """

    quotes: list[IndexQuote] = field(default_factory=list)
    called_dates: list[date] = field(default_factory=list)
    requested_dates: list[date] = field(default_factory=list)
    complete: bool = True
    failure_reason: str | None = None

    @property
    def uncalled_count(self) -> int:
        """호출하지 못한 날짜 수. 이용 한도로 중단된 뒤에 몇 날이 남았는지 센다."""
        return len(self.requested_dates) - len(self.called_dates)

    def last_trading_day(self, index_code: str = CALENDAR_INDEX_CODE) -> date | None:
        """값을 실제로 얻은 마지막 거래일. **자료 마감일은 이 값으로 정한다.**"""
        calendar = self.trading_calendar(index_code)
        return calendar[-1] if calendar else None

    def for_index(self, index_code: str) -> list[IndexQuote]:
        picked = [quote for quote in self.quotes if quote.index_code == index_code]
        picked.sort(key=lambda quote: quote.date)
        return picked

    def to_rows(self) -> list[dict[str, object]]:
        ordered = sorted(self.quotes, key=lambda quote: (quote.index_code, quote.date))
        return [quote.to_row() for quote in ordered]

    def trading_calendar(self, index_code: str = CALENDAR_INDEX_CODE) -> list[date]:
        """거래일 달력. 그 지수에 값이 있는 날짜 집합이다."""
        return build_trading_calendar(self.for_index(index_code))


def build_trading_calendar(quotes: Iterable[IndexQuote]) -> list[date]:
    """지수 시세에서 거래일 달력을 만든다. 오름차순이고 중복이 없다."""
    return sorted({quote.date for quote in quotes})


def collect_index_quotes(
    source: DailyRowSource,
    days: Sequence[date],
    *,
    index_codes: Iterable[str] = (CALENDAR_INDEX_CODE,),
) -> IndexCollection:
    """달력 후보 날짜를 하루씩 물어 지수 시세를 모은다.

    지수 수집은 거래일 달력을 **만드는** 단계라 달력을 입력으로 받을 수 없다. 그래서 후보
    날짜(달력일)를 훑고, 값이 없는 날은 행을 만들지 않는 방식으로 휴장일을 걸러낸다.
    휴장일에는 빈 배열이 오므로 값을 지어낼 일이 없다.

    이용 한도에 걸리면 중단하고 그때까지 모은 것을 돌려준다 (FR-014).
    """
    group, endpoint = KOSPI_DAILY_SERVICE
    wanted = list(dict.fromkeys(index_codes))
    unknown = [code for code in wanted if code not in INDEX_SERIES]
    if unknown:
        raise IndexDataError(f"모르는 지수 코드다: {unknown}. INDEX_SERIES에 이름을 등록해야 한다")
    names = {INDEX_SERIES[code]: code for code in wanted}

    ordered = sorted(set(days))
    collection = IndexCollection(requested_dates=list(ordered))
    for day in ordered:
        try:
            rows = source.rows(group, endpoint, day)
        except KrxQuotaExhaustedError as error:
            collection.complete = False
            collection.failure_reason = (
                f"KRX OPEN API 이용 한도로 {day:%Y-%m-%d} 지수 수집을 중단했다: {error}"
            )
            break
        for row in rows:
            name = str(row.get("IDX_NM") or "").strip()
            code = names.get(name)
            if code is None:
                continue
            quote = normalize_index_row(row, expected_date=day, index_code=code)
            if quote is not None:
                collection.quotes.append(quote)
        collection.called_dates.append(day)
    return collection


def calendar_day_range(start: date, end: date) -> list[date]:
    """``start``부터 ``end``까지 달력 날짜를 모두 만든다. 지수 수집의 후보 날짜다."""
    if end < start:
        raise IndexDataError(f"end가 start보다 앞이다: {start} → {end}")
    span = (end - start).days
    return [start + timedelta(days=offset) for offset in range(span + 1)]


# ---------------------------------------------------------------------------
# FR-009 확보 기간 검사
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class IndexCoverage:
    """한 대표 ETF의 상장 이후 전체가 지수 자료로 덮이는지에 대한 판정 (FR-009)."""

    instrument_id: str
    required_start: date
    required_end: date
    covers: bool
    missing_ranges: tuple[tuple[date, date], ...] = ()

    def to_gap_rows(self) -> list[dict[str, object]]:
        """못 덮은 구간을 ``data_gap`` 행으로 바꾼다. ``reason``은 ``INDEX_HISTORY_SHORT``다."""
        return [
            {
                "instrument_id": self.instrument_id,
                "start_date": start,
                "end_date": end,
                "reason": REASON_INDEX_HISTORY_SHORT,
            }
            for start, end in self.missing_ranges
        ]


def verify_index_coverage(
    windows: Iterable[ListingWindow],
    calendar: Sequence[date],
    *,
    data_deadline: date,
) -> list[IndexCoverage]:
    """지수 확보 기간이 각 대표 ETF의 상장 이후 전체를 포함하는지 검사한다 (FR-009).

    판정은 달력의 양 끝만 본다. 달력 안쪽에 빠진 날짜가 있는지는 이 검사의 대상이 아니다 —
    안쪽의 빠진 날짜는 그 자체로 휴장일과 구분되지 않고, 구분할 근거가 이 달력뿐이기 때문이다.
    앞뒤가 모자란 것은 달력 밖이라 판정할 수 있다.

    ``data_deadline``은 자료 마감일이다. 상장 유지 중인 종목의 요구 종료일이 그날이며,
    상장폐지된 종목은 상장폐지일이 더 앞이면 그쪽을 쓴다.
    """
    ordered = sorted(set(calendar))
    results: list[IndexCoverage] = []
    for window in windows:
        required_end = data_deadline
        if window.delisted_on is not None:
            required_end = min(required_end, window.delisted_on)
        required_start = window.listed_on
        if required_end < required_start:
            # 자료 마감일이 상장일보다 앞이다. 이 자료 버전이 그 종목을 다루지 않는 것이라
            # 모자란 구간도 없다.
            results.append(
                IndexCoverage(
                    instrument_id=window.instrument_id,
                    required_start=required_start,
                    required_end=required_end,
                    covers=True,
                )
            )
            continue
        missing: list[tuple[date, date]] = []
        if not ordered:
            missing.append((required_start, required_end))
        else:
            first, last = ordered[0], ordered[-1]
            if required_start < first:
                missing.append((required_start, min(first - timedelta(days=1), required_end)))
            if required_end > last:
                missing.append((max(last + timedelta(days=1), required_start), required_end))
        results.append(
            IndexCoverage(
                instrument_id=window.instrument_id,
                required_start=required_start,
                required_end=required_end,
                covers=not missing,
                missing_ranges=tuple(missing),
            )
        )
    return results


def index_history_gap_rows(coverages: Iterable[IndexCoverage]) -> list[dict[str, object]]:
    """판정 묶음에서 ``data_gap`` 행을 모은다."""
    rows: list[dict[str, object]] = []
    for coverage in coverages:
        if INSTRUMENT_ID_PATTERN.match(coverage.instrument_id) is None:
            raise IndexDataError(
                f"종목 식별자가 {{6글자}}.XKRX 형식이 아니다: {coverage.instrument_id!r}"
            )
        rows.extend(coverage.to_gap_rows())
    return rows
