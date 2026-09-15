"""픽스처 적재 스크립트(T013) — 행 만들기와 공용 마스터 표 보호.

실제 PostgreSQL 은 quickstart 에서 사람이 돈다.
"""

from datetime import date
from decimal import Decimal

import pytest
from load_three_etf_fixture import (
    DATASET_ID,
    INDEX_CODE,
    MasterConflictError,
    _ensure_master_row,
    build_corporate_action_rows,
    build_daily_index_rows,
    build_daily_price_rows,
    etf_attributes,
    read_csv,
    sector_attributes,
)


def test_daily_price_rows_join_raw_and_adjusted_and_fill_required_columns() -> None:
    rows = build_daily_price_rows(read_csv("prices_raw.csv"), read_csv("prices_adjusted.csv"))

    assert len(rows) == 1_436  # 픽스처 README 의 행 수
    first = rows[0]
    assert first["dataset_id"] == DATASET_ID
    assert first["value"] == first["close"] * first["volume"]
    assert first["adjustment_factor"] == first["adjusted_close"] / first["close"]
    # 거래정지일(091160.XKRX 2024-06-11)은 정지·직전 종가 표시가 함께 참이다(표의 CHECK).
    suspended = [r for r in rows if r["trading_suspended"]]
    assert [(r["instrument_id"], r["date"]) for r in suspended] == [("091160.XKRX", "2024-06-11")]
    assert suspended[0]["price_carried_forward"] is True
    assert suspended[0]["volume"] == 0
    assert suspended[0]["open"] == suspended[0]["close"] == suspended[0]["high"]


def test_daily_index_rows_are_the_union_of_price_dates() -> None:
    prices = build_daily_price_rows(read_csv("prices_raw.csv"), read_csv("prices_adjusted.csv"))
    index = build_daily_index_rows(prices)

    dates = [row["date"] for row in index]
    assert dates == sorted(set(dates)), "날짜는 유일하고 오름차순이다"
    assert set(dates) == {row["date"] for row in prices}
    # 상장이 늦은 244580 이 없는 2023-01-02 도 달력에 있다 — 다른 종목이 거래된 개장일이다.
    assert "2023-01-02" in dates
    assert {row["index_code"] for row in index} == {INDEX_CODE}


def test_corporate_action_rows_follow_the_table_invariants() -> None:
    rows = build_corporate_action_rows(read_csv("distributions.csv"), read_csv("splits.csv"))

    by_type = {row["event_type"]: row for row in rows}
    assert by_type["DISTRIBUTION"]["pay_date"] is not None
    assert by_type["SPLIT"]["pay_date"] is None
    assert all(row["amount"] > Decimal(0) for row in rows)
    assert len(rows) == 3


# ---------------------------------------------------------------------------
# 공용 마스터 표(sector·representative_etf)는 고치지 않는다 — 리뷰 P1
# ---------------------------------------------------------------------------
# 실제 PostgreSQL 은 quickstart 에서 사람이 돈다. 여기서는 가짜 커서로 "어떤 SQL 을 보냈나"만 본다:
# 없으면 INSERT, 같으면 SELECT 만, 다르면 예외 — 어느 경우에도 UPDATE 는 없다.


class _FakeCursor:
    def __init__(self, existing: tuple | None) -> None:
        self.existing = existing
        self.statements: list[tuple[str, tuple]] = []
        self._pending: tuple | None = None

    def execute(self, sql: str, params: tuple = ()) -> None:
        self.statements.append((sql, params))
        if sql.startswith("SELECT"):
            self._pending = self.existing
        elif sql.startswith("INSERT"):
            self._pending = (99,)
        else:  # pragma: no cover - 여기 오면 테스트가 잡아야 하는 결함이다
            raise AssertionError(f"unexpected statement: {sql}")

    def fetchone(self) -> tuple | None:
        return self._pending


FIXTURE_SECTOR = sector_attributes("BIO")


def test_missing_master_row_is_inserted_and_its_id_returned() -> None:
    cursor = _FakeCursor(existing=None)

    got = _ensure_master_row(cursor, "sector", "code", "BIO", FIXTURE_SECTOR)

    assert got == 99
    kinds = [sql.split()[0] for sql, _ in cursor.statements]
    assert kinds == ["SELECT", "INSERT"]
    assert cursor.statements[1][1] == ("BIO", "BIO(픽스처)", "INDUSTRY", "spec 004 픽스처 섹터")


def test_identical_master_row_is_reused_without_writing() -> None:
    cursor = _FakeCursor(existing=(3, "BIO(픽스처)", "INDUSTRY", "spec 004 픽스처 섹터"))

    got = _ensure_master_row(cursor, "sector", "code", "BIO", FIXTURE_SECTOR)

    assert got == 3
    assert [sql.split()[0] for sql, _ in cursor.statements] == ["SELECT"]


def test_different_master_row_fails_instead_of_being_overwritten() -> None:
    # 001 이 진짜 이름으로 넣어 둔 섹터. 픽스처 이름으로 덮어쓰면 발행된 대응표가 가리키는
    # 내용이 바뀐다 — 001 의 SectorMasterConflictError 와 같은 이유로 멈춘다.
    cursor = _FakeCursor(existing=(3, "바이오", "INDUSTRY", "KRX 바이오 섹터"))

    with pytest.raises(MasterConflictError, match="sector BIO already exists"):
        _ensure_master_row(cursor, "sector", "code", "BIO", FIXTURE_SECTOR)
    assert [sql.split()[0] for sql, _ in cursor.statements] == ["SELECT"]


def test_etf_dates_compare_equal_between_db_date_and_csv_string() -> None:
    # DB 는 date 를, CSV 는 "2023-07-03" 을 돌려준다. 정규화 없이 비교하면 같은 행이 충돌로 보인다.
    instrument = {
        "instrument_id": "244580.XKRX",
        "name": "바이오 섹터 ETF(픽스처)",
        "listed_on": "2023-07-03",
        "delisted_on": "",
        "benchmark_index": "BIO_INDEX(픽스처)",
    }
    cursor = _FakeCursor(
        existing=(
            7,
            "바이오 섹터 ETF(픽스처)",
            date(2023, 7, 3),
            None,
            "BIO_INDEX(픽스처)",
            "픽스처",
        )
    )

    got = _ensure_master_row(
        cursor, "representative_etf", "instrument_id", "244580.XKRX", etf_attributes(instrument)
    )

    assert got == 7
    assert [sql.split()[0] for sql, _ in cursor.statements] == ["SELECT"]
