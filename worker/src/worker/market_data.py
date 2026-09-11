"""Replaceable daily data sources. Fixtures are always identified as test data."""

import csv
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Protocol

import numpy as np
import pandas as pd

from worker.contracts.engine_input import EngineInput


class MarketDataError(ValueError):
    """Unavailable or invalid source data; never silently substitute another symbol."""


@dataclass
class MarketDataset:
    prices: pd.DataFrame
    instrument_id: str
    ticker: str
    source: str
    adjustment: str = "SPLIT_ADJUSTED"
    metadata: dict[str, Any] = field(default_factory=dict)


class DataProvider(Protocol):
    def load(self, request: EngineInput) -> MarketDataset: ...


def validate_prices(prices: pd.DataFrame) -> pd.DataFrame:
    """Reject ambiguous rows and malformed OHLCV instead of fabricating prices."""
    frame = prices.copy()
    if isinstance(frame.columns, pd.MultiIndex):
        raise MarketDataError("Expected single-ticker flat OHLCV columns, received MultiIndex")
    frame.columns = [str(column).lower() for column in frame.columns]
    required = ["open", "high", "low", "close", "volume"]
    if not set(required).issubset(frame.columns) or frame.columns.duplicated().any():
        raise MarketDataError("Missing or duplicate OHLCV columns")
    if frame.empty:
        raise MarketDataError("No market data returned for the requested ticker/period")
    if not isinstance(frame.index, pd.DatetimeIndex) or frame.index.isna().any():
        raise MarketDataError("Expected a valid DatetimeIndex")
    if frame.index.tz is not None:
        frame.index = frame.index.tz_convert("Asia/Seoul").tz_localize(None)
    frame.index = frame.index.normalize()
    if frame.index.has_duplicates or not frame.index.is_monotonic_increasing:
        raise MarketDataError("Trading dates must be unique and ascending")
    try:
        frame = frame[required].apply(pd.to_numeric, errors="raise")
    except (ValueError, TypeError) as exc:
        raise MarketDataError(f"Non-numeric OHLCV: {exc}") from exc
    if not np.isfinite(frame.to_numpy(dtype=float)).all():
        invalid = frame.index[~np.isfinite(frame.to_numpy(dtype=float)).all(axis=1)]
        raise MarketDataError(
            "Missing or non-finite OHLCV on " + ", ".join(day.date().isoformat() for day in invalid)
        )
    # Same precision as the native adjusted-price instrument; remove sub-micro-unit
    # floating error (e.g. High is 1e-11 below Close), never repair material bad prices.
    frame[["open", "high", "low", "close"]] = frame[["open", "high", "low", "close"]].round(6)
    if (frame[["open", "high", "low", "close"]] <= 0).any().any():
        raise MarketDataError("OHLC prices must be positive")
    if (frame.volume < 0).any() or (frame.volume % 1 != 0).any():
        raise MarketDataError("Volume must be a nonnegative integer")
    invalid_range = (frame.high < frame[["open", "low", "close"]].max(axis=1)) | (
        frame.low > frame[["open", "high", "close"]].min(axis=1)
    )
    if invalid_range.any():
        raise MarketDataError(
            "Inconsistent OHLC range on "
            + ", ".join(day.date().isoformat() for day in frame.index[invalid_range])
        )
    return frame


class CsvDataProvider:
    """Explicit offline fixture source; never advertised as historical market prices."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    def load(self, request: EngineInput) -> MarketDataset:
        prices = pd.read_csv(self.path, index_col="date", parse_dates=["date"])
        instrument_id = request.universe.instrument_ids[0]
        return MarketDataset(
            prices=validate_prices(prices),
            instrument_id=instrument_id,
            ticker=instrument_id.split(".")[0] + ".KS",
            source="FIXED_TEST_DATA",
            metadata={"fixture": self.path.name, "notRealMarketData": True},
        )


# ---------------------------------------------------------------------------
# 다종목 시장 자료 (spec 004 R-09, contracts/market-data-input.md)
#
# 위쪽 ``DataProvider``/``MarketDataset`` 은 DEMO 단일 종목 모멘텀 경로다. 아래는 001이
# 적재하는 표를 읽는 경로이며 종목 여러 개·조정 종가·기업행위를 함께 돌려준다. 둘을 한
# 타입으로 합치지 않은 이유: DEMO 경로는 T006 이 다종목 엔진으로 갈아끼울 때 통째로
# 사라진다. 그때까지 두 경로가 같은 ``validate_prices()`` 로 같은 fail-closed 검증을 받는다.
# ---------------------------------------------------------------------------


class DataInsufficientError(MarketDataError):
    """요청 기간에 거래일 행이 빠졌다.

    보간하거나 0을 넣지 않는다(원칙 VI). 워커는 이 예외를 계약 오류 코드
    ``DATA_INSUFFICIENT`` 로 바꿔 실행을 실패시킨다.
    """


#: 거래정지일도 행이 있어야 한다 — 결측(행 부재)과 정지는 다른 사건이다. 정지 판정의 기준은
#: ``trading_suspended`` 이며 ``volume = 0`` 이 아니다. 정지가 아닌데 거래량이 0인 날은 호가가
#: 있어 체결이 가능하므로, 둘을 같게 다루면 그날의 주문을 잘못 막는다(계약 「거래정지 처리」).
PRICE_COLUMNS = ("open", "high", "low", "close", "volume")


@dataclass(frozen=True)
class Instrument:
    """``representative_etf`` 한 행. 상장 전 자료 요구를 막는 근거."""

    instrument_id: str
    name: str
    listed_on: date
    delisted_on: date | None = None
    benchmark_index: str | None = None

    def available(self, day: date) -> bool:
        return self.listed_on <= day and (self.delisted_on is None or day <= self.delisted_on)


@dataclass(frozen=True)
class Distribution:
    """분배금. ``pay_date`` 에 현금으로 한 번만 반영한다."""

    instrument_id: str
    ex_date: date
    pay_date: date
    amount: Decimal


@dataclass(frozen=True)
class Split:
    """액면분할. ``amount`` 가 비율이고 지급일이 없다(계약 표 참고)."""

    instrument_id: str
    ex_date: date
    ratio: Decimal


@dataclass(frozen=True)
class SectorMapping:
    """섹터-ETF 대응표 한 항목. 유효 기간은 버전 단위다."""

    version: str
    sector_id: str
    instrument_id: str
    effective_from: date
    effective_to: date | None = None


@dataclass(frozen=True)
class MarketData:
    """한 실행이 읽는 자료 전부. 같은 ``dataset_id`` 로 일관된다.

    ``metadata`` 는 001 [contracts/market-dataset.md] 「metadata」 표의 값을 담는다. 결과에
    붙어 자료 버전·마감 시각·직전 종가를 쓴 날·자료 부족 구간·대응표 버전·거래정지 임시 처리
    표시를 사용자에게 전달한다. 공급자마다 채울 수 있는 항목이 다르므로(픽스처에는 자료 마감
    시각이 없다) 없는 값을 지어내지 않고 키를 넣지 않는다.
    """

    dataset_id: str
    prices: dict[str, pd.DataFrame]
    instruments: dict[str, Instrument]
    distributions: tuple[Distribution, ...] = ()
    splits: tuple[Split, ...] = ()
    mappings: tuple[SectorMapping, ...] = ()
    metadata: dict[str, Any] = field(default_factory=dict)

    @property
    def trading_days(self) -> list[date]:
        """읽어 온 모든 종목의 거래일 합집합(오름차순).

        상장일이 다른 종목이 섞이면 종목별 거래일 수가 다르므로 달력은 합집합이다.
        """
        days: set[date] = set()
        for frame in self.prices.values():
            days.update(day.date() for day in frame.index)
        return sorted(days)

    def distributions_for(self, instrument_id: str) -> tuple[Distribution, ...]:
        return tuple(d for d in self.distributions if d.instrument_id == instrument_id)

    def splits_for(self, instrument_id: str) -> tuple[Split, ...]:
        return tuple(s for s in self.splits if s.instrument_id == instrument_id)


class MarketDataProvider(Protocol):
    """다종목 자료 공급자. 구현은 읽기 전용이다.

    ``warmup_trading_days`` 는 요청 구간 **앞에서** 더 읽을 거래일 수다. 달력 일수가 아니라
    거래일 수인 이유는 연휴가 긴 해에 달력 일수로 잡으면 워밍업이 모자라기 때문이다
    (001 contracts/market-dataset.md 「읽기 규칙」).
    """

    def load(
        self,
        instrument_ids: Sequence[str],
        start: date,
        end: date,
        dataset_id: str | None = None,
        *,
        warmup_trading_days: int = 0,
    ) -> MarketData: ...


#: 실행 한 건이 읽는 종목 상한(계약 「조회 패턴」). 넘으면 조회를 쪼개는 것이 아니라 거절한다.
MAX_INSTRUMENTS = 50

#: 거래정지 처리 규칙(PD-19·PD-20)이 확정되기 전의 임시 동작이라는 표시. 확정 작업은
#: S15P21E205-187이며, 그때 이 상수와 계약 「거래정지 처리」를 함께 고친다. 긴 정지 동안
#: 평가액이 굳은 채 계산된 결과를 표시 없이 내보내지 않기 위한 값이다(원칙 VI).
PROVISIONAL_SUSPENSION_HANDLING = True

#: KRX 현지 시간대. 1988년 이후 서머타임이 없어 고정 오프셋으로 충분하고, tzdata 패키지가
#: 없는 환경에서도 같은 값을 준다.
KST = timezone(timedelta(hours=9), "KST")


def _as_date(value: Any) -> date:
    """DATE 열·날짜 문자열·시각 값을 날짜로 바꾼다.

    시간대가 붙은 값은 KRX 현지(KST)로 옮긴 뒤 날짜를 뗀다. ``sector_etf_map_version`` 의
    유효 기간은 ``timestamptz`` 이고 드라이버는 세션 시간대(대개 UTC)로 돌려주므로, 그대로
    ``.date()`` 를 부르면 한국 자정 기준 날짜가 하루 밀린다.
    """
    if isinstance(value, datetime):
        if value.tzinfo is not None:
            return value.astimezone(KST).date()
        return value.date()
    if isinstance(value, date):
        return value
    text = str(value).strip()
    if not text:
        raise MarketDataError("Empty date value")
    return date.fromisoformat(text[:10])


def _optional_date(value: Any) -> date | None:
    if value is None or (isinstance(value, str) and not value.strip()):
        return None
    return _as_date(value)


def _as_bool(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"true", "t", "1", "yes"}


def _check_instruments(instrument_ids: Sequence[str]) -> list[str]:
    if not instrument_ids:
        raise MarketDataError("At least one instrument is required")
    unique = list(dict.fromkeys(instrument_ids))
    if len(unique) != len(instrument_ids):
        raise MarketDataError("Duplicate instrument ids in request")
    if len(unique) > MAX_INSTRUMENTS:
        raise MarketDataError(f"At most {MAX_INSTRUMENTS} instruments per run, got {len(unique)}")
    return unique


def _price_frame(rows: list[dict[str, Any]], instrument_id: str) -> pd.DataFrame:
    """원시 OHLCV·정지 표시·조정 종가를 한 프레임으로 만들고 fail-closed 검증한다."""
    if not rows:
        raise DataInsufficientError(f"No price rows for {instrument_id}")
    frame = pd.DataFrame(rows).set_index("date")
    frame.index = pd.to_datetime(frame.index)
    frame = frame.sort_index()
    # 원시 OHLCV는 기존 검증을 그대로 통과해야 한다(중복일·역전 범위·음수 거래량 등).
    checked = validate_prices(frame[list(PRICE_COLUMNS)])
    checked["trading_suspended"] = frame["trading_suspended"].astype(bool)
    adjusted = pd.to_numeric(frame["adjusted_close"], errors="coerce")
    if adjusted.isna().any() or (adjusted <= 0).any():
        raise MarketDataError(f"Missing or non-positive adjusted close for {instrument_id}")
    checked["adjusted_close"] = adjusted.astype(float)
    suspended = checked.loc[checked.trading_suspended]
    if (suspended.volume != 0).any():
        raise MarketDataError(f"Suspended day with nonzero volume for {instrument_id}")
    return checked


def _warmup_start_from_days(
    earlier_days: Sequence[date], start: date, warmup_trading_days: int
) -> date:
    """워밍업 거래일 수만큼 앞선 거래일을 고른다.

    ``earlier_days`` 는 요청 시작일보다 앞선 거래일을 오름차순으로 담은 달력이다. 모자라면
    조용히 짧게 읽지 않고 실패한다 — 워밍업이 모자란 신호는 계산은 되지만 뜻이 다르다.
    """
    if warmup_trading_days < 0:
        raise MarketDataError(f"warmup_trading_days must be nonnegative, got {warmup_trading_days}")
    if warmup_trading_days == 0:
        return start
    if len(earlier_days) < warmup_trading_days:
        raise DataInsufficientError(
            f"Trading calendar holds only {len(earlier_days)} day(s) before {start.isoformat()}, "
            f"need {warmup_trading_days} warmup trading day(s)"
        )
    return earlier_days[-warmup_trading_days]


def _check_window(
    instruments: dict[str, Instrument],
    instrument_ids: Sequence[str],
    start: date,
    end: date,
) -> None:
    """요청 구간 자체가 성립하는지 본다.

    상장 전 구간을 빈 값으로 채우지 않고 오류를 낸다(FR-005, SC-003). 다만 **겹치는 구간이
    하나도 없을 때만** 거절한다. 요청 구간 안에서 상장한 종목은 그 종목만 짧게 돌려주는 것이
    004 계약(불변 조건 2)이자 공통 평가 기간 산식(evaluation-window.md)의 전제라, 일부만
    겹친다고 거절하면 상장일이 다른 종목을 섞은 실행이 아예 불가능해진다.
    """
    if end < start:
        raise MarketDataError(f"Requested window ends before it starts: {start} > {end}")
    for instrument_id in instrument_ids:
        info = instruments[instrument_id]
        if end < info.listed_on:
            raise MarketDataError(
                f"{instrument_id} requested window {start}..{end} is entirely before its "
                f"listing date {info.listed_on.isoformat()}; no prices exist there"
            )
        if info.delisted_on is not None and info.delisted_on < start:
            raise MarketDataError(
                f"{instrument_id} requested window {start}..{end} is entirely after its "
                f"delisting date {info.delisted_on.isoformat()}; no prices exist there"
            )


def _check_complete(
    prices: dict[str, pd.DataFrame],
    instruments: dict[str, Instrument],
    start: date,
    end: date,
    calendar: Sequence[date] | None = None,
) -> None:
    """거래일 달력 대비 빠진 행을 찾는다.

    ``calendar`` 는 지수 자료(``daily_index``)의 날짜 집합이다(계약 ※1). 그것을 받으면 모든
    종목에서 같은 날이 함께 빠진 경우까지 잡는다.

    ``calendar`` 가 없으면 읽어 온 가격의 날짜 합집합으로 대신한다. 이때는 **모든** 종목에서
    같은 날이 함께 빠지면 보이지 않으므로, 지수 자료를 갖지 못하는 픽스처 공급자만 이 경로를
    쓴다. 계약이 합집합을 달력으로 쓰지 말라고 한 이유가 이 구멍이다.
    """
    if calendar is None:
        calendar = sorted({day.date() for frame in prices.values() for day in frame.index})
    for instrument_id, frame in prices.items():
        info = instruments[instrument_id]
        present = {day.date() for day in frame.index}
        early = sorted(day for day in present if day < info.listed_on)
        if early:
            raise MarketDataError(
                f"{instrument_id} has rows before its listing date {info.listed_on.isoformat()}: "
                + ", ".join(day.isoformat() for day in early[:3])
            )
        expected = [day for day in calendar if start <= day <= end and info.available(day)]
        missing = [day for day in expected if day not in present]
        if missing:
            shown = ", ".join(day.isoformat() for day in missing[:5])
            more = "" if len(missing) <= 5 else f" (+{len(missing) - 5} more)"
            raise DataInsufficientError(
                f"{instrument_id} is missing {len(missing)} trading day(s): {shown}{more}"
            )


def _build(
    dataset_id: str,
    price_rows: dict[str, list[dict[str, Any]]],
    instruments: dict[str, Instrument],
    distributions: Sequence[Distribution],
    splits: Sequence[Split],
    mappings: Sequence[SectorMapping],
    instrument_ids: Sequence[str],
    start: date,
    end: date,
    *,
    fetch_start: date | None = None,
    calendar: Sequence[date] | None = None,
    metadata: dict[str, Any] | None = None,
) -> MarketData:
    """``start``·``end`` 는 요청 구간, ``fetch_start`` 는 워밍업까지 포함해 실제로 읽은 시작일이다.

    상장 전 구간 판정은 요청 구간으로 하고(워밍업은 사용자가 요청한 구간이 아니다), 빠진 행
    검사는 워밍업까지 포함해 한다 — 워밍업 구간에 구멍이 있으면 신호가 조용히 덜 익는다.
    """
    missing_info = [i for i in instrument_ids if i not in instruments]
    if missing_info:
        raise MarketDataError("Unknown instrument(s): " + ", ".join(missing_info))
    _check_window(instruments, instrument_ids, start, end)
    prices = {i: _price_frame(price_rows.get(i, []), i) for i in instrument_ids}
    _check_complete(prices, instruments, fetch_start or start, end, calendar)
    return MarketData(
        dataset_id=dataset_id,
        prices=prices,
        instruments={i: instruments[i] for i in instrument_ids},
        distributions=tuple(distributions),
        splits=tuple(splits),
        mappings=tuple(mappings),
        metadata=dict(metadata or {}),
    )


class CsvMarketDataProvider:
    """001 적재 전까지 쓰는 픽스처 공급자. 결과는 항상 픽스처로 식별된다.

    디렉터리 안의 파일 이름과 열은 PostgreSQL 표와 같다(계약 「001 미완성 시」). 그래서
    001이 자료를 넣으면 공급자만 바꾸고 위쪽 코드는 그대로 둔다.
    """

    FILES = (
        "prices_raw.csv",
        "prices_adjusted.csv",
        "distributions.csv",
        "splits.csv",
        "instruments.csv",
        "mapping.csv",
    )

    def __init__(self, directory: str | Path) -> None:
        self.directory = Path(directory)
        missing = [name for name in self.FILES if not (self.directory / name).exists()]
        if missing:
            raise MarketDataError(f"Fixture {self.directory} is missing: " + ", ".join(missing))

    def _read(self, name: str) -> list[dict[str, str]]:
        with (self.directory / name).open(encoding="utf-8", newline="") as handle:
            return list(csv.DictReader(handle))

    def load(
        self,
        instrument_ids: Sequence[str],
        start: date,
        end: date,
        dataset_id: str | None = None,
        *,
        warmup_trading_days: int = 0,
    ) -> MarketData:
        wanted = _check_instruments(instrument_ids)
        raw = self._read("prices_raw.csv")
        versions = {row["dataset_id"] for row in raw}
        if dataset_id is None:
            if len(versions) != 1:
                raise MarketDataError(
                    f"Fixture holds {len(versions)} dataset ids; pass one explicitly"
                )
            dataset_id = versions.pop()
        elif dataset_id not in versions:
            raise MarketDataError(f"Fixture has no dataset {dataset_id}")

        adjusted = {
            (row["instrument_id"], row["date"]): row["adjusted_close"]
            for row in self._read("prices_adjusted.csv")
            if row["dataset_id"] == dataset_id
        }
        fetch_start = _warmup_start_from_days(
            sorted(
                {
                    _as_date(row["date"])
                    for row in raw
                    if row["dataset_id"] == dataset_id and _as_date(row["date"]) < start
                }
            ),
            start,
            warmup_trading_days,
        )
        price_rows: dict[str, list[dict[str, Any]]] = {i: [] for i in wanted}
        for row in raw:
            if row["dataset_id"] != dataset_id or row["instrument_id"] not in price_rows:
                continue
            day = _as_date(row["date"])
            if not (fetch_start <= day <= end):
                continue
            key = (row["instrument_id"], row["date"])
            if key not in adjusted:
                raise MarketDataError(f"No adjusted close for {key[0]} on {key[1]}")
            price_rows[row["instrument_id"]].append(
                {
                    "date": day,
                    "open": row["open"],
                    "high": row["high"],
                    "low": row["low"],
                    "close": row["close"],
                    "volume": row["volume"],
                    "trading_suspended": _as_bool(row["trading_suspended"]),
                    "adjusted_close": adjusted[key],
                }
            )

        instruments = {
            row["instrument_id"]: Instrument(
                instrument_id=row["instrument_id"],
                name=row["name"],
                listed_on=_as_date(row["listed_on"]),
                delisted_on=_optional_date(row["delisted_on"]),
                benchmark_index=row["benchmark_index"] or None,
            )
            for row in self._read("instruments.csv")
        }
        distributions = [
            Distribution(
                instrument_id=row["instrument_id"],
                ex_date=_as_date(row["ex_date"]),
                pay_date=_as_date(row["pay_date"]),
                amount=Decimal(row["amount"]),
            )
            for row in self._read("distributions.csv")
            if row["dataset_id"] == dataset_id
            and row["instrument_id"] in price_rows
            and start <= _as_date(row["ex_date"]) <= end
        ]
        splits = [
            Split(
                instrument_id=row["instrument_id"],
                ex_date=_as_date(row["ex_date"]),
                ratio=Decimal(row["amount"]),
            )
            for row in self._read("splits.csv")
            if row["dataset_id"] == dataset_id
            and row["instrument_id"] in price_rows
            and start <= _as_date(row["ex_date"]) <= end
        ]
        mappings = [
            SectorMapping(
                version=row["version"],
                sector_id=row["sector_id"],
                instrument_id=row["instrument_id"],
                effective_from=_as_date(row["effective_from"]),
                effective_to=_optional_date(row["effective_to"]),
            )
            for row in self._read("mapping.csv")
            if row["instrument_id"] in price_rows
        ]
        return _build(
            dataset_id,
            price_rows,
            instruments,
            distributions,
            splits,
            mappings,
            wanted,
            start,
            end,
            fetch_start=fetch_start,
            metadata={
                # 픽스처에는 자료 버전 표가 없어 마감 시각·대응표 버전·자료 부족 구간을 채울 수
                # 없다. 없는 값을 지어내면 결과가 실제 자료처럼 보이므로 키를 넣지 않는다.
                "datasetId": dataset_id,
                "notRealMarketData": True,
                "provisionalSuspensionHandling": PROVISIONAL_SUSPENSION_HANDLING,
            },
        )


@dataclass(frozen=True)
class _DatasetVersion:
    """``dataset_version`` 한 행에서 이 공급자가 쓰는 값만."""

    dataset_id: str
    data_deadline: Any
    is_sample: bool
    map_version_id: int
    map_version: str


class PostgresMarketDataProvider:
    """001이 적재한 표를 읽는 운영 공급자(R-09). 읽기 전용 접속만 쓴다.

    표·열 이름의 원본은 마이그레이션
    ``backend/src/main/resources/db/migration/V20260911114500__etf_market_data.sql`` 이고,
    읽는 쪽의 근거는 [004 contracts/market-data-input.md]와 [001 contracts/market-dataset.md]다.
    2026-09-11에 실제 스키마와 대조하며 고친 것(001 T011):

    - 쓸 수 있는 자료 묶음 판정을 ``failure_reason IS NULL`` 에서 ``complete`` 로 바꿨다.
      판정 열은 ``complete`` (BOOLEAN NOT NULL)이고 ``failure_reason`` 은 사유를 담는 칸이다.
      제약이 ``complete OR failure_reason IS NOT NULL`` 이라 옛 조건이 부분 갱신을 고르지는
      않았지만, 사유를 적어 둔 **완전한** 묶음을 건너뛰고 조용히 더 오래된 묶음을 골랐다.
    - ``daily_price.price_carried_forward`` 를 읽는다. ``metadata.carriedForwardDates`` 의
      근거가 이 열이다. ``value`` (거래대금)와 ``adjustment_factor`` 는 읽지 않는다 — 전자는
      대표 상품 정보(FR-015, 파이프라인)가 쓰고 후자는 ``adjusted_close`` 에 이미 반영됐다.
    - 대응표 항목의 ``sector_id`` 는 ``sector.id`` (BIGINT FK)다. 밖으로 내보내는 섹터 식별자는
      ``sector.code`` 이므로(ADR-0002 D2) ``sector`` 를 조인해 코드를 쓴다.
    - 대응표를 자료 묶음의 ``map_version_id`` 로 좁힌다. 모든 버전을 읽으면 나중에 대응표가
      한 번 더 발행될 때 같은 ``dataset_id`` 조회 결과가 달라져 SC-004가 깨진다.
    - 거래일 달력을 ``daily_index`` 에서 읽는다(계약 ※1). 워밍업을 거래일로 세고, 모든 종목이
      함께 빠진 날도 결측으로 잡는다.
    """

    def __init__(self, url: str, *, connect: Any = None) -> None:
        self.url = url
        # psycopg 를 생성자에서 import 하지 않는다(테스트가 가짜 접속을 주입한다).
        self._connect = connect

    def _open(self) -> Any:
        if self._connect is not None:
            return self._connect(self.url)
        import psycopg

        connection = psycopg.connect(self.url)
        connection.read_only = True
        return connection

    # -- 조회 하나마다 함수 하나. 가짜 커서로 검증할 때 어느 질의가 무엇을 읽는지 드러난다. --

    def _dataset_version(self, cursor: Any, dataset_id: str | None) -> _DatasetVersion:
        """쓸 수 있는 자료 묶음 한 건을 고른다.

        ``dataset_id`` 를 주면 그 행만 본다. 없으면 오류다 — 다른 버전으로 조용히 대체하지
        않는다(계약 「읽기 규칙」). 주지 않으면 마감 시각이 가장 늦은 **완전한** 묶음을 고른다.
        같은 마감 시각이 둘이면 식별자로 갈라 같은 요청이 늘 같은 묶음을 고르게 한다(SC-004).
        """
        columns = (
            "SELECT v.dataset_id, v.data_deadline, v.is_sample, v.map_version_id, m.version,"
            " v.complete, v.failure_reason FROM dataset_version v"
            " JOIN sector_etf_map_version m ON m.id = v.map_version_id"
        )
        if dataset_id is None:
            cursor.execute(
                columns + " WHERE v.complete"
                " ORDER BY v.data_deadline DESC, v.dataset_id DESC LIMIT 1"
            )
        else:
            cursor.execute(columns + " WHERE v.dataset_id = %s", (dataset_id,))
        row = cursor.fetchone()
        if row is None:
            raise MarketDataError(
                f"No dataset_version row for {dataset_id}"
                if dataset_id is not None
                else "No complete dataset_version row to read"
            )
        if not _as_bool(row[5]):
            # FR-014. 부분 갱신된 묶음으로 계산한 결과를 완전한 자료의 결과처럼 내보내지 않는다.
            reason = row[6] or "no reason recorded"
            raise MarketDataError(f"dataset_version {row[0]} is incomplete: {reason}")
        return _DatasetVersion(
            dataset_id=row[0],
            data_deadline=row[1],
            is_sample=_as_bool(row[2]),
            map_version_id=row[3],
            map_version=row[4],
        )

    def _warmup_start(
        self, cursor: Any, dataset_id: str, start: date, warmup_trading_days: int
    ) -> date:
        """요청 시작일보다 ``warmup_trading_days`` 거래일 앞선 날짜.

        달력 일수로 빼지 않는다 — 설·추석이 긴 해에는 같은 달력 일수가 더 적은 거래일을 뜻해
        워밍업이 모자라진다(계약 「읽기 규칙」).
        """
        if warmup_trading_days < 0:
            raise MarketDataError(
                f"warmup_trading_days must be nonnegative, got {warmup_trading_days}"
            )
        if warmup_trading_days == 0:
            return start
        cursor.execute(
            "SELECT DISTINCT date FROM daily_index"
            " WHERE dataset_id = %s AND date < %s"
            " ORDER BY date DESC LIMIT %s",
            (dataset_id, start, warmup_trading_days),
        )
        days = sorted(_as_date(record[0]) for record in cursor.fetchall())
        return _warmup_start_from_days(days, start, warmup_trading_days)

    def _calendar(self, cursor: Any, dataset_id: str, first: date, last: date) -> list[date]:
        """거래일 달력. 지수 자료의 날짜 집합이다(계약 ※1).

        개별 ETF는 정지될 수 있지만 지수는 장이 열리면 언제나 산출되므로, 지수 행이 있다는
        사실이 그날 장이 열렸다는 증거다. 가격 행의 합집합으로 대신하면 모든 종목이 함께 빠진
        날을 휴일로 오인한다. 달력이 비면 결측 검사가 조용히 꺼지므로 그때는 실패한다.
        """
        cursor.execute(
            "SELECT DISTINCT date FROM daily_index"
            " WHERE dataset_id = %s AND date BETWEEN %s AND %s"
            " ORDER BY date",
            (dataset_id, first, last),
        )
        days = [_as_date(record[0]) for record in cursor.fetchall()]
        if not days:
            raise DataInsufficientError(
                f"No daily_index rows for {dataset_id} between {first.isoformat()} and "
                f"{last.isoformat()}; the trading calendar is unknown for that window"
            )
        return days

    def _prices(
        self,
        cursor: Any,
        dataset_id: str,
        wanted: Sequence[str],
        first: date,
        last: date,
        start: date,
    ) -> tuple[dict[str, list[dict[str, Any]]], set[date]]:
        """일별 가격과 직전 종가를 쓴 날짜.

        ``carriedForwardDates`` 는 요청 구간 안의 날짜만 담는다. 결과가 알리는 기간이 요청
        구간이고, 워밍업 길이가 달라져도 같은 구간의 표시가 달라지지 않아야 한다(SC-004).
        """
        cursor.execute(
            "SELECT instrument_id, date, open, high, low, close, volume,"
            " trading_suspended, adjusted_close, price_carried_forward FROM daily_price"
            " WHERE dataset_id = %s AND instrument_id = ANY(%s)"
            " AND date BETWEEN %s AND %s"
            " ORDER BY instrument_id, date",
            (dataset_id, list(wanted), first, last),
        )
        price_rows: dict[str, list[dict[str, Any]]] = {i: [] for i in wanted}
        carried: set[date] = set()
        for record in cursor.fetchall():
            day = _as_date(record[1])
            price_rows[record[0]].append(
                {
                    "date": day,
                    "open": record[2],
                    "high": record[3],
                    "low": record[4],
                    "close": record[5],
                    "volume": record[6],
                    "trading_suspended": _as_bool(record[7]),
                    "adjusted_close": record[8],
                }
            )
            if _as_bool(record[9]) and start <= day <= last:
                carried.add(day)
        return price_rows, carried

    def _corporate_actions(
        self, cursor: Any, dataset_id: str, wanted: Sequence[str], start: date, end: date
    ) -> tuple[list[Distribution], list[Split]]:
        cursor.execute(
            "SELECT instrument_id, event_type, ex_date, pay_date, amount"
            " FROM corporate_action"
            " WHERE dataset_id = %s AND instrument_id = ANY(%s)"
            " AND ex_date BETWEEN %s AND %s"
            " ORDER BY ex_date, instrument_id",
            (dataset_id, list(wanted), start, end),
        )
        distributions: list[Distribution] = []
        splits: list[Split] = []
        for instrument_id, event_type, ex_date, pay_date, amount in cursor.fetchall():
            if event_type == "DISTRIBUTION":
                if pay_date is None:
                    raise MarketDataError(
                        f"Distribution without pay date for {instrument_id} on {ex_date}"
                    )
                distributions.append(
                    Distribution(
                        instrument_id, _as_date(ex_date), _as_date(pay_date), Decimal(amount)
                    )
                )
            elif event_type == "SPLIT":
                splits.append(Split(instrument_id, _as_date(ex_date), Decimal(amount)))
            else:
                raise MarketDataError(f"Unknown corporate action type {event_type}")
        return distributions, splits

    def _instruments(self, cursor: Any, wanted: Sequence[str]) -> dict[str, Instrument]:
        cursor.execute(
            "SELECT instrument_id, listed_on, delisted_on, name, benchmark_index"
            " FROM representative_etf WHERE instrument_id = ANY(%s)",
            (list(wanted),),
        )
        return {
            record[0]: Instrument(
                instrument_id=record[0],
                name=record[3],
                listed_on=_as_date(record[1]),
                delisted_on=_optional_date(record[2]),
                benchmark_index=record[4],
            )
            for record in cursor.fetchall()
        }

    def _mappings(
        self, cursor: Any, map_version_id: int, wanted: Sequence[str]
    ) -> list[SectorMapping]:
        """자료 묶음이 대상으로 삼은 대응표 한 버전만 읽는다.

        항목 표의 ``sector_id``·``etf_id`` 는 둘 다 내부 PK라 밖으로 내보내는 식별자
        (``sector.code``·``representative_etf.instrument_id``)로 바꿔 돌려준다.
        유효 기간은 항목이 아니라 버전에 있고 ``timestamptz`` 라 ``_as_date`` 가 KST로 옮긴다.
        """
        cursor.execute(
            "SELECT v.version, v.effective_from, v.effective_to, s.code, r.instrument_id"
            " FROM sector_etf_map_entry e"
            " JOIN sector_etf_map_version v ON v.id = e.map_version_id"
            " JOIN sector s ON s.id = e.sector_id"
            " JOIN representative_etf r ON r.id = e.etf_id"
            " WHERE e.map_version_id = %s AND r.instrument_id = ANY(%s)"
            " ORDER BY s.code",
            (map_version_id, list(wanted)),
        )
        return [
            SectorMapping(
                version=record[0],
                sector_id=record[3],
                instrument_id=record[4],
                effective_from=_as_date(record[1]),
                effective_to=_optional_date(record[2]),
            )
            for record in cursor.fetchall()
        ]

    def _data_gaps(
        self, cursor: Any, dataset_id: str, wanted: Sequence[str], start: date, end: date
    ) -> list[dict[str, str]]:
        """요청 구간과 **겹치는** 자료 부족 구간(FR-006).

        구간 대 구간 비교라 시작일만 보면 요청 구간 앞에서 시작해 안까지 이어지는 구간을 놓친다.
        """
        cursor.execute(
            "SELECT instrument_id, start_date, end_date, reason FROM data_gap"
            " WHERE dataset_id = %s AND instrument_id = ANY(%s)"
            " AND start_date <= %s AND end_date >= %s"
            " ORDER BY instrument_id, start_date",
            (dataset_id, list(wanted), end, start),
        )
        return [
            {
                "instrumentId": record[0],
                "startDate": _as_date(record[1]).isoformat(),
                "endDate": _as_date(record[2]).isoformat(),
                "reason": record[3],
            }
            for record in cursor.fetchall()
        ]

    def load(
        self,
        instrument_ids: Sequence[str],
        start: date,
        end: date,
        dataset_id: str | None = None,
        *,
        warmup_trading_days: int = 0,
    ) -> MarketData:
        wanted = _check_instruments(instrument_ids)
        with self._open() as connection, connection.cursor() as cursor:
            version = self._dataset_version(cursor, dataset_id)
            fetch_start = self._warmup_start(cursor, version.dataset_id, start, warmup_trading_days)
            calendar = self._calendar(cursor, version.dataset_id, fetch_start, end)
            price_rows, carried = self._prices(
                cursor, version.dataset_id, wanted, fetch_start, end, start
            )
            distributions, splits = self._corporate_actions(
                cursor, version.dataset_id, wanted, start, end
            )
            instruments = self._instruments(cursor, wanted)
            mappings = self._mappings(cursor, version.map_version_id, wanted)
            gaps = self._data_gaps(cursor, version.dataset_id, wanted, start, end)

        deadline = version.data_deadline
        return _build(
            version.dataset_id,
            price_rows,
            instruments,
            distributions,
            splits,
            mappings,
            wanted,
            start,
            end,
            fetch_start=fetch_start,
            calendar=calendar,
            metadata={
                "datasetId": version.dataset_id,
                "dataDeadline": (
                    deadline.isoformat() if hasattr(deadline, "isoformat") else str(deadline)
                ),
                "carriedForwardDates": [day.isoformat() for day in sorted(carried)],
                "dataGaps": gaps,
                "mapVersion": version.map_version,
                # 확정 전 임시 처리라는 표시. 계산해 내는 값이 아니라 규칙이 미확정인 동안 늘
                # 참이다(S15P21E205-187에서 PD-19·PD-20과 함께 정리한다).
                "provisionalSuspensionHandling": PROVISIONAL_SUSPENSION_HANDLING,
                "notRealMarketData": version.is_sample,
            },
        )
