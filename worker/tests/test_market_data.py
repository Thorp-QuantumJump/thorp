"""다종목 시장 자료 공급자(spec 004 T004, R-09).

contracts/market-data-input.md의 불변 조건을 픽스처로 고정한다. 특히 **결측과 거래정지를
구분**하는 부분이 핵심이다 — 정지일을 결측으로 보면 멀쩡한 실행이 실패하고, 결측을 정지로
보면 없는 가격을 있는 것처럼 쓴다.
"""

from __future__ import annotations

import csv
import shutil
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest

from worker.market_data import (
    MAX_INSTRUMENTS,
    CsvMarketDataProvider,
    DataInsufficientError,
    MarketDataError,
    PostgresMarketDataProvider,
)

FIXTURE = Path(__file__).resolve().parents[1] / "examples" / "three_etf_24m"
DATASET = "FIXTURE-three_etf_24m"

SEMICONDUCTOR = "091160.XKRX"
BATTERY = "305720.XKRX"
BIO = "244580.XKRX"
ALL_THREE = [SEMICONDUCTOR, BATTERY, BIO]

START = date(2023, 1, 2)
END = date(2024, 12, 31)

SPLIT_DAY = date(2024, 3, 4)
SUSPENDED_DAY = date(2024, 6, 11)
BIO_LISTED_ON = date(2023, 7, 3)


@pytest.fixture
def provider() -> CsvMarketDataProvider:
    return CsvMarketDataProvider(FIXTURE)


def rewrite(directory: Path, name: str, keep) -> None:
    """픽스처 사본에서 조건에 맞지 않는 행을 지운다(결측 상황 재현용)."""
    path = directory / name
    with path.open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames or []
        rows = [row for row in reader if keep(row)]
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)


def test_loads_three_instruments_with_fixture_dataset_id(
    provider: CsvMarketDataProvider,
) -> None:
    data = provider.load(ALL_THREE, START, END)

    assert data.dataset_id == DATASET, "결과 versions.data 가 픽스처임을 드러내야 한다"
    assert set(data.prices) == set(ALL_THREE)
    assert list(data.prices[SEMICONDUCTOR].columns) == [
        "open",
        "high",
        "low",
        "close",
        "volume",
        "trading_suspended",
        "adjusted_close",
    ]


def test_later_listing_shortens_only_that_instrument(provider: CsvMarketDataProvider) -> None:
    data = provider.load(ALL_THREE, START, END)

    bio = data.prices[BIO]
    assert bio.index[0].date() == BIO_LISTED_ON
    assert data.instruments[BIO].listed_on == BIO_LISTED_ON
    assert len(bio) < len(data.prices[SEMICONDUCTOR])
    # 달력은 합집합이므로 가장 긴 종목의 거래일 수와 같다.
    assert len(data.trading_days) == len(data.prices[SEMICONDUCTOR])


def test_suspended_day_is_a_row_not_a_gap(provider: CsvMarketDataProvider) -> None:
    data = provider.load([SEMICONDUCTOR], START, END)
    frame = data.prices[SEMICONDUCTOR]

    row = frame.loc[str(SUSPENDED_DAY)]
    assert bool(row.trading_suspended) is True
    assert row.volume == 0
    assert row.open == row.high == row.low == row.close
    assert int(frame.trading_suspended.sum()) == 1


def test_missing_trading_day_fails_instead_of_interpolating(
    provider: CsvMarketDataProvider, tmp_path: Path
) -> None:
    copy = tmp_path / "fixture"
    shutil.copytree(FIXTURE, copy)
    gap = "2024-02-07"
    rewrite(
        copy,
        "prices_raw.csv",
        lambda row: not (row["instrument_id"] == BATTERY and row["date"] == gap),
    )

    with pytest.raises(DataInsufficientError, match=gap):
        CsvMarketDataProvider(copy).load(ALL_THREE, START, END)


def test_rows_before_listing_date_are_rejected(
    provider: CsvMarketDataProvider, tmp_path: Path
) -> None:
    copy = tmp_path / "fixture"
    shutil.copytree(FIXTURE, copy)
    rewrite(
        copy,
        "instruments.csv",
        lambda row: row["instrument_id"] != BIO,
    )
    with (copy / "instruments.csv").open("a", encoding="utf-8", newline="") as handle:
        handle.write(f"{BIO},2024-01-02,,바이오,BIO\n")

    with pytest.raises(MarketDataError, match="before its listing date"):
        CsvMarketDataProvider(copy).load([BIO], START, END)


def test_split_keeps_adjusted_close_continuous(provider: CsvMarketDataProvider) -> None:
    data = provider.load([BIO], START, END)
    frame = data.prices[BIO]

    before = frame.loc[frame.index < str(SPLIT_DAY)].iloc[-1]
    on_day = frame.loc[str(SPLIT_DAY)]
    # 원시 가격은 반토막이지만 조정 종가는 이어진다 — 신호·성과가 가짜 하락을 보지 않는다.
    assert on_day.close < before.close / 1.5
    assert before.adjusted_close == pytest.approx(before.close / 2)
    assert on_day.adjusted_close == pytest.approx(on_day.close)

    (split,) = data.splits_for(BIO)
    assert split.ex_date == SPLIT_DAY
    assert split.ratio == Decimal("2")


def test_distributions_carry_pay_date_and_amount(provider: CsvMarketDataProvider) -> None:
    data = provider.load(ALL_THREE, START, END)

    assert len(data.distributions) == 2
    first = data.distributions_for(SEMICONDUCTOR)[0]
    assert first.ex_date == date(2023, 4, 27)
    assert first.pay_date == date(2023, 5, 2)
    assert first.amount == Decimal("120")
    assert data.distributions_for(BIO) == ()


def test_period_filter_excludes_events_outside_the_window(
    provider: CsvMarketDataProvider,
) -> None:
    data = provider.load(ALL_THREE, date(2024, 1, 2), END)

    assert [d.instrument_id for d in data.distributions] == [BATTERY]
    assert data.prices[SEMICONDUCTOR].index[0].date() >= date(2024, 1, 2)


def test_sector_mapping_is_loaded(provider: CsvMarketDataProvider) -> None:
    data = provider.load(ALL_THREE, START, END)

    assert {m.sector_id for m in data.mappings} == {"SEMICONDUCTOR", "BATTERY", "BIO"}
    assert all(m.version == "SECTOR-ETF-2026-09" for m in data.mappings)
    assert all(m.effective_to is None for m in data.mappings)


@pytest.mark.parametrize(
    ("instruments", "message"),
    [
        ([], "At least one instrument"),
        ([SEMICONDUCTOR, SEMICONDUCTOR], "Duplicate instrument"),
        ([f"{i:06d}.XKRX" for i in range(MAX_INSTRUMENTS + 1)], "At most 50 instruments"),
    ],
)
def test_request_shape_is_rejected_before_any_read(
    provider: CsvMarketDataProvider, instruments: list[str], message: str
) -> None:
    with pytest.raises(MarketDataError, match=message):
        provider.load(instruments, START, END)


def test_unknown_dataset_and_instrument_are_rejected(provider: CsvMarketDataProvider) -> None:
    with pytest.raises(MarketDataError, match="no dataset"):
        provider.load([SEMICONDUCTOR], START, END, dataset_id="KRX_DAILY_20260911")
    with pytest.raises(MarketDataError, match="Unknown instrument"):
        provider.load(["000000.XKRX"], START, END)


def test_missing_fixture_file_is_reported_on_construction(tmp_path: Path) -> None:
    with pytest.raises(MarketDataError, match="prices_raw.csv"):
        CsvMarketDataProvider(tmp_path)


# ---------------------------------------------------------------------------
# PostgresMarketDataProvider — 001이 표를 만들기 전이라 가짜 커서로 매핑만 고정한다.
# 실제 스키마 대조는 001 적재 후 통합 테스트로 해야 한다.
# ---------------------------------------------------------------------------


class FakeCursor:
    def __init__(self, results: list[Any]) -> None:
        self.results = results
        self.executed: list[tuple[str, Any]] = []

    def __enter__(self) -> FakeCursor:
        return self

    def __exit__(self, *_: Any) -> None:
        return None

    def execute(self, sql: str, params: Any = None) -> None:
        self.executed.append((" ".join(sql.split()), params))

    def fetchone(self) -> Any:
        return self.results.pop(0)

    def fetchall(self) -> Any:
        return self.results.pop(0)


class FakeConnection:
    def __init__(self, cursor: FakeCursor) -> None:
        self._cursor = cursor
        self.read_only = False

    def __enter__(self) -> FakeConnection:
        return self

    def __exit__(self, *_: Any) -> None:
        return None

    def cursor(self) -> FakeCursor:
        return self._cursor


def postgres_rows() -> list[Any]:
    """(dataset 조회, 가격, 기업행위, 상품, 대응표) 순서의 응답."""
    return [
        ("KRX_DAILY_20260910",),
        [
            (SEMICONDUCTOR, date(2024, 1, 2), 100, 110, 95, 105, 1000, False, 105.0),
            (SEMICONDUCTOR, date(2024, 1, 3), 105, 108, 105, 105, 0, True, 105.0),
        ],
        [
            (SEMICONDUCTOR, "DISTRIBUTION", date(2024, 1, 3), date(2024, 1, 5), Decimal("7")),
            (SEMICONDUCTOR, "SPLIT", date(2024, 1, 3), None, Decimal("2")),
        ],
        [(SEMICONDUCTOR, date(2015, 1, 2), None, "반도체", "IDX")],
        [("SECTOR-ETF-2026-09", date(2023, 1, 1), None, "SEMICONDUCTOR", SEMICONDUCTOR)],
    ]


def test_postgres_provider_maps_rows_and_picks_latest_dataset() -> None:
    cursor = FakeCursor(postgres_rows())
    connect = lambda _: FakeConnection(cursor)  # noqa: E731
    provider = PostgresMarketDataProvider("postgresql://x", connect=connect)

    data = provider.load([SEMICONDUCTOR], date(2024, 1, 2), date(2024, 1, 3))

    assert data.dataset_id == "KRX_DAILY_20260910"
    assert len(data.prices[SEMICONDUCTOR]) == 2
    assert bool(data.prices[SEMICONDUCTOR].iloc[1].trading_suspended) is True
    assert data.distributions[0].pay_date == date(2024, 1, 5)
    assert data.splits[0].ratio == Decimal("2")
    assert data.mappings[0].sector_id == "SEMICONDUCTOR"
    # 가장 늦은 성공 자료 버전을 고르고, 이후 조회는 모두 그 버전으로 묶인다.
    assert "failure_reason IS NULL" in cursor.executed[0][0]
    assert cursor.executed[1][1][0] == "KRX_DAILY_20260910"


def test_postgres_provider_honours_explicit_dataset_id() -> None:
    cursor = FakeCursor(postgres_rows()[1:])
    connect = lambda _: FakeConnection(cursor)  # noqa: E731
    provider = PostgresMarketDataProvider("postgresql://x", connect=connect)

    data = provider.load([SEMICONDUCTOR], date(2024, 1, 2), date(2024, 1, 3), "KRX_DAILY_20250101")

    assert data.dataset_id == "KRX_DAILY_20250101"
    assert "dataset_version" not in cursor.executed[0][0]


def test_postgres_distribution_without_pay_date_is_rejected() -> None:
    rows = postgres_rows()
    rows[2] = [(SEMICONDUCTOR, "DISTRIBUTION", date(2024, 1, 3), None, Decimal("7"))]
    cursor = FakeCursor(rows)
    connect = lambda _: FakeConnection(cursor)  # noqa: E731
    provider = PostgresMarketDataProvider("postgresql://x", connect=connect)

    with pytest.raises(MarketDataError, match="without pay date"):
        provider.load([SEMICONDUCTOR], date(2024, 1, 2), date(2024, 1, 3))
