"""시장 자료 공급자(주입). 픽스처는 항상 테스트 자료로 식별된다."""

import csv
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Protocol

import numpy as np
import pandas as pd


class MarketDataError(ValueError):
    """Unavailable or invalid source data; never silently substitute another symbol."""


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


# ---------------------------------------------------------------------------
# 다종목 시장 자료 (spec 004 R-09, contracts/market-data-input.md)
#
# 001이 적재하는 표를 읽어 종목 여러 개·조정 종가·기업행위를 함께 돌려준다. 원시 OHLCV 는
# 위 ``validate_prices()`` 로 fail-closed 검증을 받는다.
# ---------------------------------------------------------------------------


class DataInsufficientError(MarketDataError):
    """요청 기간에 거래일 행이 빠졌다.

    보간하거나 0을 넣지 않는다(원칙 VI). 워커는 이 예외를 계약 오류 코드
    ``DATA_INSUFFICIENT`` 로 바꿔 실행을 실패시킨다.
    """


#: 거래정지일도 행이 있어야 한다 — 결측(행 부재)과 정지(volume 0)는 다른 사건이다.
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
    """한 실행이 읽는 자료 전부. 같은 ``dataset_id`` 로 일관된다."""

    dataset_id: str
    prices: dict[str, pd.DataFrame]
    instruments: dict[str, Instrument]
    distributions: tuple[Distribution, ...] = ()
    splits: tuple[Split, ...] = ()
    mappings: tuple[SectorMapping, ...] = ()

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
    """다종목 자료 공급자. 구현은 읽기 전용이다."""

    def load(
        self,
        instrument_ids: Sequence[str],
        start: date,
        end: date,
        dataset_id: str | None = None,
    ) -> MarketData: ...


#: 실행 한 건이 읽는 종목 상한(계약 「조회 패턴」). 넘으면 조회를 쪼개는 것이 아니라 거절한다.
MAX_INSTRUMENTS = 50


def _as_date(value: Any) -> date:
    if isinstance(value, datetime):
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


def _check_complete(
    prices: dict[str, pd.DataFrame],
    instruments: dict[str, Instrument],
    start: date,
    end: date,
) -> None:
    """거래일 합집합 대비 빠진 행을 찾는다.

    달력은 읽어 온 자료에서 만든다 — 워커는 KRX 달력을 따로 갖고 있지 않다. 따라서 **모든**
    종목에서 같은 날이 함께 빠지면 이 검사로는 보이지 않는다. 그 경우는 자료를 넣는 001이
    ``dataset_version`` 으로 막는 몫이고, 여기서는 종목 사이의 불일치를 잡는다.
    """
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
) -> MarketData:
    missing_info = [i for i in instrument_ids if i not in instruments]
    if missing_info:
        raise MarketDataError("Unknown instrument(s): " + ", ".join(missing_info))
    prices = {i: _price_frame(price_rows.get(i, []), i) for i in instrument_ids}
    _check_complete(prices, instruments, start, end)
    return MarketData(
        dataset_id=dataset_id,
        prices=prices,
        instruments={i: instruments[i] for i in instrument_ids},
        distributions=tuple(distributions),
        splits=tuple(splits),
        mappings=tuple(mappings),
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
        price_rows: dict[str, list[dict[str, Any]]] = {i: [] for i in wanted}
        for row in raw:
            if row["dataset_id"] != dataset_id or row["instrument_id"] not in price_rows:
                continue
            day = _as_date(row["date"])
            if not (start <= day <= end):
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
        )


class PostgresMarketDataProvider:
    """001이 적재한 표를 읽는 운영 공급자(R-09). 읽기 전용 접속만 쓴다.

    표·열 이름은 [contracts/market-data-input.md] 기준이고 원본 정의는 001 data-model.md다.
    001이 아직 표를 만들지 않아 이 SQL은 통합 테스트로 확인하지 못했다 — 001 T00x 적재 후
    실제 스키마와 대조해야 한다(MR 리뷰 포인트).
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

    def load(
        self,
        instrument_ids: Sequence[str],
        start: date,
        end: date,
        dataset_id: str | None = None,
    ) -> MarketData:
        wanted = _check_instruments(instrument_ids)
        with self._open() as connection, connection.cursor() as cursor:
            if dataset_id is None:
                cursor.execute(
                    "SELECT dataset_id FROM dataset_version"
                    " WHERE failure_reason IS NULL"
                    " ORDER BY data_deadline DESC LIMIT 1"
                )
                row = cursor.fetchone()
                if row is None:
                    raise MarketDataError("No usable dataset_version row")
                dataset_id = row[0]

            cursor.execute(
                "SELECT instrument_id, date, open, high, low, close, volume,"
                " trading_suspended, adjusted_close FROM daily_price"
                " WHERE dataset_id = %s AND instrument_id = ANY(%s)"
                " AND date BETWEEN %s AND %s"
                " ORDER BY instrument_id, date",
                (dataset_id, list(wanted), start, end),
            )
            price_rows: dict[str, list[dict[str, Any]]] = {i: [] for i in wanted}
            for record in cursor.fetchall():
                price_rows[record[0]].append(
                    {
                        "date": _as_date(record[1]),
                        "open": record[2],
                        "high": record[3],
                        "low": record[4],
                        "close": record[5],
                        "volume": record[6],
                        "trading_suspended": _as_bool(record[7]),
                        "adjusted_close": record[8],
                    }
                )

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

            cursor.execute(
                "SELECT instrument_id, listed_on, delisted_on, name, benchmark_index"
                " FROM representative_etf WHERE instrument_id = ANY(%s)",
                (list(wanted),),
            )
            instruments = {
                record[0]: Instrument(
                    instrument_id=record[0],
                    name=record[3],
                    listed_on=_as_date(record[1]),
                    delisted_on=_optional_date(record[2]),
                    benchmark_index=record[4],
                )
                for record in cursor.fetchall()
            }

            # 항목 표는 instrument_id 가 아니라 representative_etf.id 를 가리킨다(계약 참고).
            cursor.execute(
                "SELECT v.version, v.effective_from, v.effective_to, e.sector_id,"
                " r.instrument_id FROM sector_etf_map_entry e"
                " JOIN sector_etf_map_version v ON v.id = e.map_version_id"
                " JOIN representative_etf r ON r.id = e.etf_id"
                " WHERE r.instrument_id = ANY(%s)"
                " ORDER BY v.effective_from, e.sector_id",
                (list(wanted),),
            )
            mappings = [
                SectorMapping(
                    version=record[0],
                    sector_id=record[3],
                    instrument_id=record[4],
                    effective_from=_as_date(record[1]),
                    effective_to=_optional_date(record[2]),
                )
                for record in cursor.fetchall()
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
        )
