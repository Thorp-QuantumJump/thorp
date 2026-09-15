"""픽스처 적재 스크립트의 행 만들기(T013). DB 는 quickstart 에서 사람이 돈다 — 모양만 본다."""

from decimal import Decimal

from load_three_etf_fixture import (
    DATASET_ID,
    INDEX_CODE,
    build_corporate_action_rows,
    build_daily_index_rows,
    build_daily_price_rows,
    read_csv,
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
