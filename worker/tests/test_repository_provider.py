"""저장소 공급자(``PostgresMarketDataProvider``) — spec 001 T011.

001이 적재한 표를 읽는 경로를 **실제 PostgreSQL 없이** 검증한다. 공급자가 ``connect`` 를
주입받으므로 가짜 접속을 넣고, 가짜 쪽은 질의의 WHERE·LIMIT 가운데 **결과가 달라지는 것**
(자료 묶음 고르기, 거래일 달력의 날짜 범위·개수, 가격의 날짜 범위)을 실제로 적용한다.
적용하지 않으면 공급자가 엉뚱한 구간을 물어봐도 픽스처가 그대로 답해 버려 테스트가 아무것도
지키지 못한다.

가짜가 흉내 내지 못하는 것(자료형 변환, 제약 위반, 실행 계획)은 마지막 절의 주석에 적었다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from typing import Any

import pandas as pd
import pytest

from worker.market_data import (
    DataInsufficientError,
    MarketDataError,
    PostgresMarketDataProvider,
)

SEMICONDUCTOR = "091160.XKRX"
BATTERY = "305720.XKRX"

DATASET = "KRX_DAILY_20260911"
OLDER_DATASET = "KRX_DAILY_20260910"
MAP_VERSION_ID = 7
MAP_VERSION = "SECTOR-ETF-2026-09"
DEADLINE = datetime(2026, 9, 11, 18, 0, tzinfo=UTC)

#: 연말연시 휴장을 넣은 거래일 달력. 2023-12-28 다음 거래일이 2024-01-02라 달력 일수와
#: 거래일 수가 어긋난다 — 워밍업을 달력 일수로 세는 구현이라면 여기서 드러난다.
CALENDAR: list[date] = [
    date(2023, 12, 26),
    date(2023, 12, 27),
    date(2023, 12, 28),
    date(2024, 1, 2),
    date(2024, 1, 3),
    date(2024, 1, 4),
    date(2024, 1, 5),
    date(2024, 1, 8),
    date(2024, 1, 9),
    date(2024, 1, 10),
    date(2024, 1, 11),
    date(2024, 1, 12),
]

START = date(2024, 1, 8)
END = date(2024, 1, 12)
SUSPENDED_DAY = date(2024, 1, 10)
LISTED_ON = date(2015, 1, 2)


# ---------------------------------------------------------------------------
# 가짜 접속
# ---------------------------------------------------------------------------


def price_row(
    instrument_id: str,
    day: date,
    close: str,
    *,
    suspended: bool = False,
    carried: bool = False,
) -> tuple[Any, ...]:
    """``daily_price`` 한 행. 자료형은 psycopg 가 돌려주는 것에 맞춘다.

    NUMERIC 은 ``Decimal``, BIGINT 는 ``int``, BOOLEAN 은 ``bool`` 이다. 정지일은 계약이 정한
    모양(OHLC 네 값이 직전 종가라 서로 같고 거래량 0)으로 만든다.
    """
    price = Decimal(close)
    if suspended:
        return (instrument_id, day, price, price, price, price, 0, True, price, True)
    return (
        instrument_id,
        day,
        price - Decimal("5"),
        price + Decimal("10"),
        price - Decimal("10"),
        price,
        1_000,
        False,
        price,
        carried,
    )


@dataclass
class FakeDatabase:
    """공급자가 읽는 표들. 열 순서는 마이그레이션의 SELECT 결과 순서다."""

    dataset_versions: list[tuple[Any, ...]] = field(default_factory=list)
    calendar: list[date] = field(default_factory=list)
    prices: list[tuple[Any, ...]] = field(default_factory=list)
    corporate_actions: list[tuple[Any, ...]] = field(default_factory=list)
    instruments: list[tuple[Any, ...]] = field(default_factory=list)
    mappings: list[tuple[Any, ...]] = field(default_factory=list)
    gaps: list[tuple[Any, ...]] = field(default_factory=list)


@dataclass(frozen=True)
class Query:
    sql: str
    params: Any


class FakeCursor:
    """표 이름으로 질의를 갈라 응답을 만든다.

    호출 **순서**에 기대지 않는다. 순서에 묶으면 조회가 하나 늘 때마다 관계없는 단언이 함께
    깨지고, 알아보지 못한 질의를 빈 결과로 돌려주는 쪽으로 흐르기 쉽다. 여기서는 알아보지
    못한 질의를 실패로 만든다 — 검사 장치가 조용히 꺼지지 않아야 한다.
    """

    TABLE = re.compile(r"\bFROM\s+([a-z_]+)")

    def __init__(self, database: FakeDatabase) -> None:
        self.database = database
        self.executed: list[Query] = []
        self._rows: list[tuple[Any, ...]] = []

    def __enter__(self) -> FakeCursor:
        return self

    def __exit__(self, *_: Any) -> None:
        return None

    def execute(self, sql: str, params: Any = None) -> None:
        flat = " ".join(sql.split())
        self.executed.append(Query(flat, params))
        matched = self.TABLE.search(flat)
        if matched is None:
            raise AssertionError(f"FROM 절을 찾지 못했다: {flat}")
        table = matched.group(1)
        if table == "daily_index":
            self._rows = self._index_rows(flat, params)
        elif table == "dataset_version":
            self._rows = self._dataset_rows(params)
        elif table == "daily_price":
            self._rows = self._price_rows(params)
        elif table == "corporate_action":
            self._rows = list(self.database.corporate_actions)
        elif table == "representative_etf":
            self._rows = list(self.database.instruments)
        elif table == "sector_etf_map_entry":
            # 저장한 행의 첫 칸은 대응표 버전 PK(조인 대상)이고 SELECT 결과에는 없다.
            self._rows = [row[1:] for row in self.database.mappings if row[0] == params[0]]
        elif table == "data_gap":
            self._rows = list(self.database.gaps)
        else:
            raise AssertionError(f"가짜 접속이 모르는 표를 읽었다: {table} ({flat})")

    def _dataset_rows(self, params: Any) -> list[tuple[Any, ...]]:
        rows = self.database.dataset_versions
        if params is not None:
            return [row for row in rows if row[0] == params[0]]
        complete = [row for row in rows if row[5]]
        return sorted(complete, key=lambda row: (row[1], row[0]), reverse=True)[:1]

    def _index_rows(self, flat: str, params: Any) -> list[tuple[date]]:
        """워밍업 질의와 달력 질의를 WHERE 절대로 갈라 실제로 걸러 준다."""
        if "date < %s" in flat:
            dataset_id, before, limit = params
            assert dataset_id, "자료 묶음을 지정하지 않고 달력을 읽었다"
            days = sorted((day for day in self.database.calendar if day < before), reverse=True)
            return [(day,) for day in days[:limit]]
        _, first, last = params
        return [(day,) for day in self.database.calendar if first <= day <= last]

    def _price_rows(self, params: Any) -> list[tuple[Any, ...]]:
        _, instruments, first, last = params
        return [
            row for row in self.database.prices if row[0] in instruments and first <= row[1] <= last
        ]

    def fetchone(self) -> Any:
        return self._rows[0] if self._rows else None

    def fetchall(self) -> list[tuple[Any, ...]]:
        return list(self._rows)


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


def build_database() -> FakeDatabase:
    """정상 경로 한 벌. 종목 하나가 요청 구간 안에 하루 거래정지된다."""
    prices = [
        price_row(SEMICONDUCTOR, day, "10000", suspended=day == SUSPENDED_DAY) for day in CALENDAR
    ]
    return FakeDatabase(
        dataset_versions=[
            (DATASET, DEADLINE, False, MAP_VERSION_ID, MAP_VERSION, True, None),
            (
                OLDER_DATASET,
                DEADLINE - timedelta(days=1),
                False,
                MAP_VERSION_ID,
                MAP_VERSION,
                True,
                "지수 1종을 못 받아 다시 받았다",
            ),
        ],
        calendar=list(CALENDAR),
        prices=prices,
        corporate_actions=[
            (SEMICONDUCTOR, "DISTRIBUTION", date(2024, 1, 9), date(2024, 1, 11), Decimal("120")),
            (SEMICONDUCTOR, "SPLIT", date(2024, 1, 11), None, Decimal("2")),
        ],
        instruments=[(SEMICONDUCTOR, LISTED_ON, None, "반도체", "KRX 반도체")],
        mappings=[
            (
                MAP_VERSION_ID,
                MAP_VERSION,
                datetime(2023, 1, 1, tzinfo=UTC) - timedelta(hours=9),
                None,
                "SEMICONDUCTOR",
                SEMICONDUCTOR,
            )
        ],
        gaps=[(SEMICONDUCTOR, date(2024, 1, 5), date(2024, 1, 9), "AMBIGUOUS_PRICE")],
    )


def make_provider(database: FakeDatabase) -> tuple[PostgresMarketDataProvider, FakeCursor]:
    cursor = FakeCursor(database)
    provider = PostgresMarketDataProvider(
        "postgresql://x", connect=lambda _: FakeConnection(cursor)
    )
    return provider, cursor


def sql_for(cursor: FakeCursor, table: str) -> str:
    matches = [query.sql for query in cursor.executed if f"FROM {table}" in query.sql]
    assert matches, f"{table} 를 읽는 질의가 없다: {[q.sql for q in cursor.executed]}"
    return matches[0]


def params_for(cursor: FakeCursor, table: str) -> Any:
    for query in cursor.executed:
        if f"FROM {table}" in query.sql:
            return query.params
    raise AssertionError(f"{table} 를 읽는 질의가 없다")


# ---------------------------------------------------------------------------
# T011 필수 테스트
# ---------------------------------------------------------------------------


def test_same_dataset_id_twice_returns_identical_values() -> None:
    """SC-004. 발행된 자료 버전은 고쳐지지 않으므로 두 번 읽으면 값이 같아야 한다."""
    provider, _ = make_provider(build_database())

    first = provider.load([SEMICONDUCTOR], START, END, DATASET)
    second = provider.load([SEMICONDUCTOR], START, END, DATASET)

    assert first.dataset_id == second.dataset_id == DATASET
    pd.testing.assert_frame_equal(first.prices[SEMICONDUCTOR], second.prices[SEMICONDUCTOR])
    assert first.metadata == second.metadata
    assert first.distributions == second.distributions
    assert first.splits == second.splits
    assert first.mappings == second.mappings
    assert first.instruments == second.instruments


def test_requested_dataset_id_that_is_not_loaded_is_rejected() -> None:
    """요청한 자료 묶음이 없으면 다른 버전으로 조용히 대체하지 않고 실패한다(계약 「읽기 규칙」)."""
    provider, _ = make_provider(build_database())

    with pytest.raises(MarketDataError, match="No dataset_version row for KRX_DAILY_19990101"):
        provider.load([SEMICONDUCTOR], START, END, "KRX_DAILY_19990101")


def test_incomplete_dataset_is_not_treated_as_complete() -> None:
    """FR-014. 부분 갱신된 묶음으로 계산한 결과를 완전한 자료의 결과처럼 내보내지 않는다."""
    database = build_database()
    database.dataset_versions = [
        (DATASET, DEADLINE, False, MAP_VERSION_ID, MAP_VERSION, False, "KRX 이용 한도 도달")
    ]
    provider, _ = make_provider(database)

    with pytest.raises(MarketDataError, match="incomplete: KRX 이용 한도 도달"):
        provider.load([SEMICONDUCTOR], START, END, DATASET)


def test_usable_dataset_is_chosen_by_the_complete_column() -> None:
    """판정 열은 ``complete`` 다.

    ``failure_reason IS NULL`` 은 사유를 적어 둔 **완전한** 묶음을 건너뛰고 조용히 더 오래된
    묶음을 고른다.
    """
    database = build_database()
    # 마감이 가장 늦은 묶음에 사유가 적혀 있지만 complete 라 쓸 수 있다.
    database.dataset_versions[0] = (
        DATASET,
        DEADLINE,
        False,
        MAP_VERSION_ID,
        MAP_VERSION,
        True,
        "종목 1종을 재수집했다",
    )
    provider, cursor = make_provider(database)

    data = provider.load([SEMICONDUCTOR], START, END)

    assert data.dataset_id == DATASET, "사유가 적힌 완전한 묶음을 건너뛰고 옛 묶음을 골랐다"
    chooser = sql_for(cursor, "dataset_version")
    assert "WHERE v.complete" in chooser
    assert "failure_reason IS NULL" not in chooser
    # 마감 시각이 같은 묶음이 둘일 때도 같은 것을 고르도록 식별자로 한 번 더 가른다.
    assert "ORDER BY v.data_deadline DESC, v.dataset_id DESC" in chooser


def test_window_entirely_before_listing_is_rejected() -> None:
    """FR-005·SC-003. 상장 전 구간을 빈 값으로 채우지 않는다."""
    database = build_database()
    database.instruments = [(SEMICONDUCTOR, date(2025, 1, 2), None, "반도체", "KRX 반도체")]
    provider, _ = make_provider(database)

    with pytest.raises(MarketDataError, match="entirely before its listing date 2025-01-02"):
        provider.load([SEMICONDUCTOR], START, END, DATASET)


def test_rows_before_listing_date_are_rejected() -> None:
    """적재 쪽이 상장 전 행을 넣었더라도 읽는 쪽이 받아들이지 않는다(SC-003)."""
    database = build_database()
    database.instruments = [(SEMICONDUCTOR, date(2024, 1, 9), None, "반도체", "KRX 반도체")]
    provider, _ = make_provider(database)

    with pytest.raises(MarketDataError, match="has rows before its listing date"):
        provider.load([SEMICONDUCTOR], START, END, DATASET)


def test_suspended_day_is_recorded_not_dropped() -> None:
    """거래정지일은 결측이 아니다 — 행이 남고 직전 종가를 쓴 날로 기록된다(FR-006)."""
    provider, _ = make_provider(build_database())

    data = provider.load([SEMICONDUCTOR], START, END, DATASET)
    frame = data.prices[SEMICONDUCTOR]
    row = frame.loc[str(SUSPENDED_DAY)]

    assert bool(row.trading_suspended) is True
    assert row.volume == 0
    assert row.open == row.high == row.low == row.close
    assert int(frame.trading_suspended.sum()) == 1
    assert data.metadata["carriedForwardDates"] == [SUSPENDED_DAY.isoformat()]
    assert data.metadata["provisionalSuspensionHandling"] is True


# ---------------------------------------------------------------------------
# metadata (contracts/market-dataset.md 「metadata」)
# ---------------------------------------------------------------------------


def test_metadata_carries_every_contract_key() -> None:
    provider, _ = make_provider(build_database())

    metadata = provider.load([SEMICONDUCTOR], START, END, DATASET).metadata

    assert metadata["datasetId"] == DATASET
    assert metadata["dataDeadline"] == DEADLINE.isoformat()
    assert metadata["carriedForwardDates"] == [SUSPENDED_DAY.isoformat()]
    assert metadata["mapVersion"] == MAP_VERSION
    assert metadata["provisionalSuspensionHandling"] is True
    assert metadata["notRealMarketData"] is False
    # 요청 구간(01-08~01-12) 앞에서 시작해 안까지 이어지는 구간도 겹침으로 잡는다.
    assert metadata["dataGaps"] == [
        {
            "instrumentId": SEMICONDUCTOR,
            "startDate": "2024-01-05",
            "endDate": "2024-01-09",
            "reason": "AMBIGUOUS_PRICE",
        }
    ]


def test_data_gap_query_overlaps_the_window() -> None:
    """구간 대 구간 비교다. 시작일만 보면 앞에서 시작해 안까지 이어지는 구간을 놓친다."""
    provider, cursor = make_provider(build_database())

    provider.load([SEMICONDUCTOR], START, END, DATASET)

    gap_sql = sql_for(cursor, "data_gap")
    assert "start_date <= %s AND end_date >= %s" in gap_sql
    assert params_for(cursor, "data_gap") == (DATASET, [SEMICONDUCTOR], END, START)


def test_sample_dataset_is_flagged_as_not_real_market_data() -> None:
    """FR-002·research.md 결정 5. 예시 자료 표시는 워커의 기존 장치를 그대로 쓴다."""
    database = build_database()
    database.dataset_versions[0] = (
        DATASET,
        DEADLINE,
        True,
        MAP_VERSION_ID,
        MAP_VERSION,
        True,
        None,
    )
    provider, _ = make_provider(database)

    data = provider.load([SEMICONDUCTOR], START, END, DATASET)

    assert data.metadata["notRealMarketData"] is True


def test_mapping_uses_the_sector_code_and_the_dataset_map_version() -> None:
    """항목 표의 ``sector_id`` 는 ``sector.id`` 다. 밖으로 내보내는 값은 ``sector.code`` 다."""
    provider, cursor = make_provider(build_database())

    data = provider.load([SEMICONDUCTOR], START, END, DATASET)

    (mapping,) = data.mappings
    assert mapping.sector_id == "SEMICONDUCTOR"
    assert mapping.version == MAP_VERSION
    # 자료 묶음이 대상으로 삼은 대응표 한 버전만 읽는다 — 다른 버전이 발행돼도 결과가 그대로다.
    assert params_for(cursor, "sector_etf_map_entry") == (MAP_VERSION_ID, [SEMICONDUCTOR])
    assert "JOIN sector s ON s.id = e.sector_id" in sql_for(cursor, "sector_etf_map_entry")


def test_map_version_validity_is_read_in_korean_local_time() -> None:
    """대응표의 유효 기간은 ``timestamptz`` 다. UTC 로 자르면 한국 자정 기준 날짜가 하루 밀린다."""
    provider, _ = make_provider(build_database())

    (mapping,) = provider.load([SEMICONDUCTOR], START, END, DATASET).mappings

    # 저장된 값은 2023-01-01 00:00 KST = 2022-12-31 15:00 UTC 다.
    assert mapping.effective_from == date(2023, 1, 1)
    assert mapping.effective_to is None


# ---------------------------------------------------------------------------
# 워밍업과 거래일 달력
# ---------------------------------------------------------------------------


def test_warmup_is_counted_in_trading_days_not_calendar_days() -> None:
    """연말 휴장을 건너뛰고 거래일로 센다(계약 「읽기 규칙」)."""
    provider, cursor = make_provider(build_database())

    data = provider.load([SEMICONDUCTOR], START, END, DATASET, warmup_trading_days=4)

    first_day = data.prices[SEMICONDUCTOR].index[0].date()
    assert first_day == date(2024, 1, 2), "휴장을 건너뛴 4거래일 앞이어야 한다"
    # 달력 일수로 세면 1월 4일이 되어 2거래일이 모자란다.
    assert first_day != START - timedelta(days=4)
    assert params_for(cursor, "daily_price")[2] == date(2024, 1, 2)
    # 워밍업 달력은 지수 자료에서 읽는다 — 가격 행의 합집합이 아니다(계약 ※1).
    assert params_for(cursor, "daily_index") == (DATASET, START, 4)


def test_warmup_shortfall_reads_as_far_back_as_the_calendar_goes() -> None:
    """앞이 모자라면 **있는 만큼** 읽는다 — 거절하지 않는다.

    **읽기는 관대하게, 평가 시작일 판정은 엄격하게.** 자료 맨 앞이나 상장일 근처를 요청하면
    워밍업을 채울 자료가 없는 것이 정상이고, 그것은 거절할 이유가 아니라 평가 시작일을 미룰
    이유다(001 contracts/evaluation-window.md 의 ``firstEvaluable``). 여기서 거절하면 백엔드가
    계약대로 시작일을 밀어 접수시킨 요청을 워커가 되돌려 두 계층이 모순된다.

    워밍업이 모자란 채로 계산이 시작되는 것은 ``engine_runner.evaluation_start()`` 가 막는다 —
    ``test_portfolio_execution.py`` 의 되밀기 검사들이 그 짝이다.
    """
    provider, cursor = make_provider(build_database())

    data = provider.load([SEMICONDUCTOR], START, END, DATASET, warmup_trading_days=99)

    # 달력에 START 앞선 거래일이 7개뿐이다. 그 맨 앞까지 읽고 멈춘다.
    assert data.prices[SEMICONDUCTOR].index[0].date() == date(2023, 12, 26)
    assert len([day for day in data.trading_days if day < START]) == 7
    assert params_for(cursor, "daily_price")[2] == date(2023, 12, 26)


def test_day_missing_from_every_instrument_is_still_a_gap() -> None:
    """달력이 지수 자료라 모든 종목이 함께 빠진 날도 결측으로 잡힌다(계약 ※1)."""
    database = build_database()
    database.prices = [row for row in database.prices if row[1] != date(2024, 1, 9)]
    provider, _ = make_provider(database)

    with pytest.raises(DataInsufficientError, match="2024-01-09"):
        provider.load([SEMICONDUCTOR], START, END, DATASET)


def test_empty_trading_calendar_fails_instead_of_skipping_the_check() -> None:
    """달력이 비면 결측 검사가 조용히 통과한다. 그 상태를 실패로 만든다."""
    database = build_database()
    database.calendar = []
    provider, _ = make_provider(database)

    with pytest.raises(DataInsufficientError, match="trading calendar is unknown"):
        provider.load([SEMICONDUCTOR], START, END, DATASET)


# ---------------------------------------------------------------------------
# 기업행위·요청 모양
# ---------------------------------------------------------------------------


def test_corporate_actions_are_split_by_event_type() -> None:
    provider, _ = make_provider(build_database())

    data = provider.load([SEMICONDUCTOR], START, END, DATASET)

    (distribution,) = data.distributions
    assert distribution.pay_date == date(2024, 1, 11)
    assert distribution.amount == Decimal("120")
    (split,) = data.splits
    assert split.ratio == Decimal("2")
    assert split.ex_date == date(2024, 1, 11)


def test_distribution_without_pay_date_is_rejected() -> None:
    database = build_database()
    database.corporate_actions = [
        (SEMICONDUCTOR, "DISTRIBUTION", date(2024, 1, 9), None, Decimal("120"))
    ]
    provider, _ = make_provider(database)

    with pytest.raises(MarketDataError, match="without pay date"):
        provider.load([SEMICONDUCTOR], START, END, DATASET)


def test_unknown_corporate_action_type_is_rejected() -> None:
    database = build_database()
    database.corporate_actions = [(SEMICONDUCTOR, "MERGER", date(2024, 1, 9), None, Decimal("1"))]
    provider, _ = make_provider(database)

    with pytest.raises(MarketDataError, match="Unknown corporate action type MERGER"):
        provider.load([SEMICONDUCTOR], START, END, DATASET)


def test_unknown_instrument_is_rejected_before_building_prices() -> None:
    provider, _ = make_provider(build_database())

    with pytest.raises(MarketDataError, match="Unknown instrument"):
        provider.load([BATTERY], START, END, DATASET)


def test_request_shape_is_rejected_before_any_read() -> None:
    provider, cursor = make_provider(build_database())

    with pytest.raises(MarketDataError, match="Duplicate instrument"):
        provider.load([SEMICONDUCTOR, SEMICONDUCTOR], START, END, DATASET)

    assert cursor.executed == [], "요청 모양이 틀렸는데 DB를 읽었다"


def test_negative_warmup_is_rejected() -> None:
    provider, _ = make_provider(build_database())

    with pytest.raises(MarketDataError, match="warmup_trading_days must be nonnegative"):
        provider.load([SEMICONDUCTOR], START, END, DATASET, warmup_trading_days=-1)


# ---------------------------------------------------------------------------
# 가짜 접속이 대신하지 못하는 것
# ---------------------------------------------------------------------------
# 아래는 실제 PostgreSQL 에 붙였을 때만 드러난다. 여기 테스트가 통과해도 남는 위험이다.
#
# 1. 자료형 변환. 여기서는 NUMERIC→Decimal, BIGINT→int, DATE→date, TIMESTAMPTZ→aware
#    datetime 이라고 가정하고 값을 넣었다. 드라이버 설정(예: psycopg 의 date/numeric 어댑터)이
#    다르면 ``_price_frame`` 의 숫자 변환이 다르게 동작할 수 있다.
# 2. ``ANY(%s)`` 와 파라미터 바인딩. 가짜는 파이썬 리스트로 걸러 보지만 실제로는 배열 어댑터가
#    관여한다.
# 3. 제약·트리거. 정지일 모양, 상장 전 행, 발행 후 UPDATE 거부는 DB 가 막는 몫이고 여기서는
#    거짓으로 만든 행을 그냥 넣을 수 있다.
# 4. 실행 계획. 종목 50개 × 16년 조회가 인덱스를 타는지는 EXPLAIN 으로만 확인된다.
# 5. 세션 시간대. ``_as_date`` 가 KST 로 옮기는 것은 aware datetime 일 때뿐이라, 드라이버가
#    naive 로 돌려주는 설정이면 날짜가 밀릴 수 있다.


def test_window_beyond_the_data_deadline_is_rejected() -> None:
    """마감일을 넘겨 요청하면 짧은 계열을 조용히 돌려주지 않는다.

    거래일 달력이 마감일까지만 있어 기대 거래일 집합도 그만큼만 만들어지고, 결측 검사가 그
    집합으로 도는 탓에 아무 신호 없이 통과했다. 6년치를 요청하고 닷새치를 받고도 6년을
    받았다고 믿게 된다.
    """
    provider, _ = make_provider(build_database())
    with pytest.raises(MarketDataError) as caught:
        provider.load([SEMICONDUCTOR], START, date(2030, 1, 1), DATASET)
    assert "data deadline" in str(caught.value) or "complete through" in str(caught.value)
