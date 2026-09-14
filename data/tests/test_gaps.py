"""자료 부족 구간과 거래정지 구간 (spec 001 FR-005·FR-006, contracts/market-dataset.md).

이 파일이 고정하는 것은 거래일 하나를 셋 가운데 어느 상태로 처리하는지다. 체결이 있었으면
그대로, 체결 가격이 없었으면 직전 종가로 채우고 표시를 남기고, 자료를 아예 얻지 못했으면
행을 만들지 않는다. 세 갈래를 섞으면 결측이 휴일처럼 또는 정지처럼 보여 그대로 계산된다.

픽스처는 2026-09-11에 KRX OPEN API에서 한 번 받아 저장한 실제 응답이다. 체결이 없던 날의
모양을 손으로 지어내지 않고 실제 응답으로 확인하기 위해 쓴다.
"""

from __future__ import annotations

import json
from collections.abc import Mapping
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import create_engine, func, select

from data_pipeline.corporate_actions import (
    EVENT_DISTRIBUTION,
    CorporateAction,
    CorporateActionError,
    build_adjustment_series,
)
from data_pipeline.dataset_version import SOURCE_KRX_OPEN_API, DatasetVersion
from data_pipeline.gaps import (
    ALLOWED_GAP_REASONS,
    REASON_AMBIGUOUS_PRICE,
    REASON_MISSING_CORPORATE_ACTION,
    REASON_SUSPENDED_TOO_LONG,
    SUSPENSION_RULE_CONFIRMED,
    GapError,
    build_daily_prices,
    build_instrument_series,
    merge_consecutive,
    raw_close_series,
    suspension_periods,
    validate_gap_rows,
)
from data_pipeline.index import collect_index_quotes
from data_pipeline.krx import DailyQuote, ListingWindow, collect_daily_quotes
from data_pipeline.repository import METADATA, MarketDataRepository

INSTRUMENT_ID = "091160.XKRX"
DATASET_ID = "KRX_DAILY_20260910"
DEADLINE = datetime(2026, 9, 10, 18, 0, tzinfo=UTC)

FIXTURE_ROOT = Path(__file__).resolve().parent / "fixtures" / "krx"

#: 픽스처에 담긴 날짜. 거래일 달력을 만들 후보로 넘긴다.
FIXTURE_DAYS: tuple[date, ...] = (
    date(2026, 9, 1),
    date(2026, 9, 2),
    date(2026, 9, 3),
    date(2026, 9, 4),
    date(2026, 9, 5),
    date(2026, 9, 6),
    date(2026, 9, 7),
    date(2026, 9, 8),
    date(2026, 9, 9),
    date(2026, 9, 10),
)

#: 픽스처에 체결 없는 날이 담겨 있는 종목. 2026-09-08·09-10에 시가·고가·저가가 ``"0"``이다.
FIXTURE_NO_TRADE_INSTRUMENT = "475270.XKRX"


class FixtureSource:
    """픽스처를 읽는 ``DailyRowSource``. 테스트가 네트워크에 기대지 않게 한다.

    파일이 없으면 **건너뛰지 않고 실패한다.** 없으면 빈 배열을 돌려주는 구현으로 두면
    픽스처가 사라진 순간 이 파일의 검사가 조용히 꺼진다.
    """

    def rows(self, group: str, endpoint: str, bas_dd: date) -> list[Mapping[str, str]]:
        path = FIXTURE_ROOT / f"{endpoint}_{bas_dd:%Y%m%d}.json"
        if not path.exists():
            # 주말은 애초에 받지 않았다. 그 두 날만 빈 응답으로 다루고 나머지는 알린다.
            if bas_dd.weekday() >= 5 and endpoint == "kospi_dd_trd":
                return []
            raise AssertionError(f"픽스처가 없다: {path}")
        body = json.loads(path.read_text(encoding="utf-8"))
        return list(body["OutBlock_1"])


@pytest.fixture
def fixture_calendar() -> list[date]:
    """픽스처의 지수 자료로 만든 거래일 달력. 별도 달력 표를 두지 않는다."""
    return collect_index_quotes(FixtureSource(), FIXTURE_DAYS).trading_calendar()


def quote(
    day: date,
    close: str,
    *,
    traded: bool = True,
    volume: int = 1_000,
    value: int = 10_000_000,
    instrument_id: str = INSTRUMENT_ID,
) -> DailyQuote:
    """시세 한 건. ``traded=False``면 체결이 없던 날의 모양이다."""
    price = Decimal(close)
    return DailyQuote(
        instrument_id=instrument_id,
        date=day,
        close=price,
        open=price if traded else None,
        high=price if traded else None,
        low=price if traded else None,
        volume=volume if traded else 0,
        value=value if traded else 0,
    )


def calendar(*days: date) -> list[date]:
    return sorted(days)


D1, D2, D3, D4 = (date(2026, 3, day) for day in (2, 3, 4, 5))
WINDOW = ListingWindow(INSTRUMENT_ID, D1)


def series_for(quotes: list[DailyQuote], days: list[date], window: ListingWindow = WINDOW):
    return build_instrument_series(quotes, days, window)


class TestSuspendedRowShape:
    """거래정지일의 모양은 확정되어 있다 (2026-09-11, contracts/market-dataset.md)."""

    def test_ohlc_is_the_previous_close_and_volume_is_zero(self) -> None:
        quotes = [quote(D1, "10000"), quote(D2, "10200"), quote(D3, "9999", traded=False)]
        built = series_for(quotes, calendar(D1, D2, D3))
        row = next(item for item in built.price_rows if item["date"] == D3)
        # OHLC 네 값이 모두 직전 거래일 종가라 서로 같다. 원천이 낸 기준가(9999)는 쓰지 않는다.
        assert row["open"] == row["high"] == row["low"] == row["close"] == Decimal("10200")
        assert row["volume"] == 0
        assert row["value"] == 0
        assert row["trading_suspended"] is True
        assert row["price_carried_forward"] is True

    def test_both_flags_are_stored(self) -> None:
        # 두 열의 뜻이 다르다. trading_suspended는 원인, price_carried_forward는 처리다.
        quotes = [quote(D1, "10000"), quote(D2, "10000", traded=False)]
        row = series_for(quotes, calendar(D1, D2)).price_rows[1]
        assert row["trading_suspended"] and row["price_carried_forward"]

    def test_a_traded_row_marks_neither_flag(self) -> None:
        quotes = [quote(D1, "10000"), quote(D2, "10200")]
        for row in series_for(quotes, calendar(D1, D2)).price_rows:
            assert row["trading_suspended"] is False
            assert row["price_carried_forward"] is False

    def test_zero_volume_alone_is_not_a_suspension(self) -> None:
        # **판정 기준은 trading_suspended이며 volume = 0이 아니다.** 정지가 아니면서 거래량이
        # 0인 날은 호가가 있어 체결이 가능하다. 두 조건을 같은 것으로 다루면 정지가 아닌 날의
        # 주문을 잘못 막는다 (contracts/market-dataset.md 「거래정지 처리」).
        quotes = [quote(D1, "10000"), quote(D2, "10200", volume=0, value=0)]
        row = series_for(quotes, calendar(D1, D2)).price_rows[1]
        assert row["volume"] == 0
        assert row["trading_suspended"] is False
        assert row["price_carried_forward"] is False

    def test_the_carried_row_keeps_the_integer_type(self) -> None:
        # volume·value가 BIGINT 열이다. 실수로 넘기면 적재가 거부된다.
        quotes = [quote(D1, "10000"), quote(D2, "10000", traded=False)]
        row = series_for(quotes, calendar(D1, D2)).price_rows[1]
        assert isinstance(row["volume"], int) and not isinstance(row["volume"], bool)
        assert isinstance(row["value"], int)


class TestMissingIsNotSuspension:
    """진짜 결측에는 행을 만들지 않는다 (004 계약, 원칙 VI)."""

    def test_a_trading_day_without_data_makes_no_row(self) -> None:
        quotes = [quote(D1, "10000"), quote(D3, "10200")]
        built = series_for(quotes, calendar(D1, D2, D3))
        assert [row["date"] for row in built.price_rows] == [D1, D3]

    def test_the_missing_day_is_recorded_as_a_gap(self) -> None:
        quotes = [quote(D1, "10000"), quote(D3, "10200")]
        built = series_for(quotes, calendar(D1, D2, D3))
        assert len(built.gap_rows) == 1
        gap = built.gap_rows[0]
        assert gap["reason"] == REASON_AMBIGUOUS_PRICE
        assert gap["start_date"] == D2
        assert gap["end_date"] == D2

    def test_consecutive_missing_days_form_one_range(self) -> None:
        quotes = [quote(D1, "10000"), quote(D4, "10200")]
        built = series_for(quotes, calendar(D1, D2, D3, D4))
        assert len(built.gap_rows) == 1
        assert (built.gap_rows[0]["start_date"], built.gap_rows[0]["end_date"]) == (D2, D3)

    def test_a_missing_day_is_not_counted_as_suspended(self) -> None:
        quotes = [quote(D1, "10000"), quote(D3, "10200")]
        built = series_for(quotes, calendar(D1, D2, D3))
        assert built.suspension_rows == ()
        assert built.build.carried_forward_dates == ()

    def test_a_listing_day_without_execution_makes_no_row(self) -> None:
        # 상장 첫날에는 직전 종가가 없다. 채울 값이 없으므로 임의 값을 만들지 않고 자료
        # 부족으로 남긴다 (data-model.md 「거래정지일 표현」).
        quotes = [quote(D1, "10000", traded=False), quote(D2, "10200")]
        built = series_for(quotes, calendar(D1, D2))
        assert [row["date"] for row in built.price_rows] == [D2]
        assert built.gap_rows[0]["reason"] == REASON_AMBIGUOUS_PRICE
        assert built.gap_rows[0]["start_date"] == D1


class TestListingWindow:
    """상장 구간 밖에는 행을 만들지 않는다 (FR-005, SC-003)."""

    def test_days_before_the_listing_date_are_not_built(self) -> None:
        window = ListingWindow(INSTRUMENT_ID, D2)
        quotes = [quote(D2, "10000"), quote(D3, "10200")]
        built = build_instrument_series(quotes, calendar(D1, D2, D3), window)
        assert [row["date"] for row in built.price_rows] == [D2, D3]
        # 상장 전 날짜는 결측으로도 기록하지 않는다. 그 종목이 없던 날이기 때문이다.
        assert built.gap_rows == ()

    def test_days_after_the_delisting_date_are_not_built(self) -> None:
        window = ListingWindow(INSTRUMENT_ID, D1, delisted_on=D2)
        quotes = [quote(D1, "10000"), quote(D2, "10200")]
        built = build_instrument_series(quotes, calendar(D1, D2, D3), window)
        assert [row["date"] for row in built.price_rows] == [D1, D2]
        assert built.gap_rows == ()

    def test_a_foreign_instrument_is_rejected(self) -> None:
        quotes = [quote(D1, "10000", instrument_id="069500.XKRX")]
        with pytest.raises(GapError, match="다른 종목"):
            series_for(quotes, calendar(D1))

    def test_a_duplicate_date_is_rejected(self) -> None:
        with pytest.raises(GapError, match="두 번"):
            series_for([quote(D1, "10000"), quote(D1, "10100")], calendar(D1))


class TestSuspensionPeriods:
    """정지 구간을 시작일·종료일·거래일 수로 기록한다. T015의 입력이다."""

    def test_consecutive_suspensions_form_one_period(self) -> None:
        quotes = [
            quote(D1, "10000"),
            quote(D2, "10000", traded=False),
            quote(D3, "10000", traded=False),
            quote(D4, "10500"),
        ]
        built = series_for(quotes, calendar(D1, D2, D3, D4))
        assert len(built.suspension_rows) == 1
        period = built.suspension_rows[0]
        assert period["start_date"] == D2
        assert period["end_date"] == D3
        assert period["trading_days"] == 2

    def test_separate_suspensions_form_separate_periods(self) -> None:
        quotes = [
            quote(D1, "10000"),
            quote(D2, "10000", traded=False),
            quote(D3, "10500"),
            quote(D4, "10500", traded=False),
        ]
        built = series_for(quotes, calendar(D1, D2, D3, D4))
        assert [period["start_date"] for period in built.suspension_rows] == [D2, D4]

    def test_a_weekend_does_not_split_a_period(self) -> None:
        # 달력 기준으로 금요일과 다음 월요일은 잇따른 거래일이다. 달력 일수로 나누면 주말을
        # 끼고 이어진 정지가 둘로 쪼개져 길이 분포가 왜곡된다.
        friday, monday = date(2026, 3, 6), date(2026, 3, 9)
        days = calendar(date(2026, 3, 5), friday, monday)
        quotes = [
            quote(date(2026, 3, 5), "10000"),
            quote(friday, "10000", traded=False),
            quote(monday, "10000", traded=False),
        ]
        built = build_instrument_series(
            quotes, days, ListingWindow(INSTRUMENT_ID, date(2026, 3, 5))
        )
        assert len(built.suspension_rows) == 1
        assert built.suspension_rows[0]["trading_days"] == 2

    def test_trading_days_counts_trading_days_not_calendar_days(self) -> None:
        friday, monday = date(2026, 3, 6), date(2026, 3, 9)
        days = calendar(date(2026, 3, 5), friday, monday)
        quotes = [
            quote(date(2026, 3, 5), "10000"),
            quote(friday, "10000", traded=False),
            quote(monday, "10000", traded=False),
        ]
        built = build_instrument_series(
            quotes, days, ListingWindow(INSTRUMENT_ID, date(2026, 3, 5))
        )
        # 달력 일수로 세면 금요일부터 월요일까지 4일이다. 거래일로는 2일이다.
        assert (monday - friday).days == 3
        assert built.suspension_rows[0]["trading_days"] == 2

    def test_no_suspension_makes_no_period(self) -> None:
        built = series_for([quote(D1, "10000"), quote(D2, "10200")], calendar(D1, D2))
        assert built.suspension_rows == ()

    def test_periods_from_two_instruments_are_rejected(self) -> None:
        rows = [
            {"instrument_id": INSTRUMENT_ID, "date": D1, "trading_suspended": True},
            {"instrument_id": "069500.XKRX", "date": D2, "trading_suspended": True},
        ]
        with pytest.raises(GapError, match="한 종목"):
            suspension_periods(rows, calendar(D1, D2))

    def test_the_period_is_derived_from_the_daily_flag(self) -> None:
        # 일별 표시가 원본이고 이 표는 파생이다. 둘이 어긋나면 일별 표시를 기준으로 다시
        # 만든다 (data-model.md suspension_period).
        quotes = [quote(D1, "10000"), quote(D2, "10000", traded=False)]
        built = series_for(quotes, calendar(D1, D2))
        flagged = [row["date"] for row in built.price_rows if row["trading_suspended"]]
        covered = [
            day
            for period in built.suspension_rows
            for day in (D1, D2)
            if period["start_date"] <= day <= period["end_date"]
        ]
        assert flagged == covered


class TestSuspendedTooLongIsForbidden:
    """``SUSPENDED_TOO_LONG``은 PD-19·PD-20 확정 전까지 쓰지 않는다 (FR-006)."""

    def test_the_switch_is_off(self) -> None:
        assert SUSPENSION_RULE_CONFIRMED is False

    def test_the_reason_is_rejected_while_the_switch_is_off(self) -> None:
        row = {
            "instrument_id": INSTRUMENT_ID,
            "start_date": D1,
            "end_date": D2,
            "reason": REASON_SUSPENDED_TOO_LONG,
        }
        with pytest.raises(GapError, match="PD-19"):
            validate_gap_rows([row])

    def test_it_is_not_in_the_allowed_set(self) -> None:
        assert REASON_SUSPENDED_TOO_LONG not in ALLOWED_GAP_REASONS
        assert ALLOWED_GAP_REASONS == {
            "AMBIGUOUS_PRICE",
            "MISSING_CORPORATE_ACTION",
            "RECONCILIATION_UNKNOWN",
            "INDEX_HISTORY_SHORT",
        }

    def test_a_long_suspension_is_still_kept_as_a_period(self) -> None:
        # 확정 전에는 정지를 자료 부족으로 빼지 않는다. 자료를 버리면 T015의 결정 근거가
        # 사라지고 다른 기능의 개발도 막힌다.
        days = [date(2026, 3, 2) + timedelta(days=n) for n in range(20)]
        quotes = [quote(days[0], "10000")] + [quote(day, "10000", traded=False) for day in days[1:]]
        built = build_instrument_series(quotes, days, ListingWindow(INSTRUMENT_ID, days[0]))
        assert built.suspension_rows[0]["trading_days"] == 19
        assert built.gap_rows == ()
        assert len(built.price_rows) == 20


class TestGapRowValidation:
    """``data_gap`` 행 검사. 적재 전에 막아야 절반만 들어간 표가 남지 않는다."""

    def test_an_unknown_reason_is_rejected(self) -> None:
        with pytest.raises(GapError, match="reason"):
            validate_gap_rows(
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "start_date": D1,
                        "end_date": D2,
                        "reason": "WHATEVER",
                    }
                ]
            )

    def test_a_reversed_range_is_rejected(self) -> None:
        with pytest.raises(GapError, match="end_date"):
            validate_gap_rows(
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "start_date": D2,
                        "end_date": D1,
                        "reason": REASON_AMBIGUOUS_PRICE,
                    }
                ]
            )

    def test_a_duplicate_key_is_rejected(self) -> None:
        # PK가 (dataset_id, instrument_id, start_date, reason)다. 두 번 들어 있으면 적재가
        # PK 위반으로 깨지고 그때는 절반만 들어간 표가 남는다.
        row = {
            "instrument_id": INSTRUMENT_ID,
            "start_date": D1,
            "end_date": D2,
            "reason": REASON_AMBIGUOUS_PRICE,
        }
        with pytest.raises(GapError, match="두 번"):
            validate_gap_rows([row, dict(row)])

    def test_a_bad_instrument_id_is_rejected(self) -> None:
        with pytest.raises(GapError, match="XKRX"):
            validate_gap_rows(
                [
                    {
                        "instrument_id": "091160.XKRX\n",
                        "start_date": D1,
                        "end_date": D2,
                        "reason": REASON_AMBIGUOUS_PRICE,
                    }
                ]
            )


class TestMergeConsecutive:
    """구간 묶기는 달력 기준이다."""

    def test_a_single_day_is_one_range(self) -> None:
        assert merge_consecutive([D1], calendar(D1, D2)) == [(D1, D1)]

    def test_a_date_outside_the_calendar_is_rejected(self) -> None:
        # 달력에 없는 날짜를 구간으로 만들면 휴장일이 자료 부족 구간에 섞인다.
        with pytest.raises(GapError, match="달력에 없는"):
            merge_consecutive([date(2026, 3, 7)], calendar(D1, D2))

    def test_empty_input_makes_no_range(self) -> None:
        assert merge_consecutive([], calendar(D1)) == []

    def test_a_hole_splits_the_range(self) -> None:
        assert merge_consecutive([D1, D3], calendar(D1, D2, D3)) == [(D1, D1), (D3, D3)]


class TestAdjustedCloseOnSuspendedDays:
    """정지일의 조정가는 직전 종가에 **그날의** 누적 계수를 곱한 값이다."""

    def test_without_events_it_equals_the_previous_adjusted_close(self) -> None:
        quotes = [quote(D1, "10000"), quote(D2, "10000", traded=False)]
        rows = series_for(quotes, calendar(D1, D2)).price_rows
        assert rows[1]["adjusted_close"] == rows[0]["adjusted_close"]

    def test_an_event_between_changes_the_factor(self) -> None:
        # 정지 구간 안에 배당락일이 들어가면 계수가 달라지는 것이 맞다. 직전 거래일의
        # 조정가를 그대로 베끼면 그 날부터의 수익률에 없던 계단이 생긴다.
        quotes = [
            quote(D1, "10000"),
            quote(D2, "10000", traded=False),
            quote(D3, "10000", traded=False),
        ]
        action = CorporateAction(
            instrument_id=INSTRUMENT_ID,
            ex_date=D3,
            event_type=EVENT_DISTRIBUTION,
            amount=Decimal("100"),
            pay_date=D3,
        )
        built = build_instrument_series(quotes, calendar(D1, D2, D3), WINDOW, [action])
        rows = {row["date"]: row for row in built.price_rows}
        assert rows[D2]["adjustment_factor"] == Decimal("0.990000000000")
        assert rows[D3]["adjustment_factor"] == Decimal("1.000000000000")
        assert rows[D2]["adjusted_close"] == Decimal("9900.00000000")
        assert rows[D3]["adjusted_close"] == Decimal("10000.00000000")

    def test_the_adjustment_reads_the_carried_close(self) -> None:
        # 조정 계열은 정지일의 직전 종가가 채워진 **원시 계열** 위에서 계산되어야 한다.
        # 원천 기준가 위에서 계산하면 같은 행의 close와 adjusted_close가 다른 계열이 된다.
        quotes = [quote(D1, "10000"), quote(D2, "9999", traded=False)]
        closes = raw_close_series(quotes, calendar(D1, D2), WINDOW)
        assert closes == {D1: Decimal("10000"), D2: Decimal("10000")}


class TestBuildOrder:
    """조정 계열과 일별 가격 행은 같은 계열을 가리켜야 한다."""

    def test_a_series_built_on_another_close_set_is_reported(self) -> None:
        # 다른 계열로 만든 조정을 넘기면 날짜가 모자라 조용히 넘어가지 않고 알린다.
        quotes = [quote(D1, "10000"), quote(D2, "10200")]
        wrong = build_adjustment_series(INSTRUMENT_ID, {D1: Decimal("10000")}, [])
        with pytest.raises(CorporateActionError, match="조정 종가가 없다"):
            build_daily_prices(quotes, calendar(D1, D2), WINDOW, wrong)


class TestRealFixture:
    """실제 응답으로 확인한다. 체결 없는 날의 모양을 손으로 지어내지 않는다."""

    def test_the_calendar_skips_the_weekend(self, fixture_calendar: list[date]) -> None:
        # 지수 자료는 휴장일에 빈 배열을 돌려준다. 그것이 거래일 달력의 근거다.
        assert date(2026, 9, 5) not in fixture_calendar
        assert date(2026, 9, 6) not in fixture_calendar
        assert len(fixture_calendar) == 8

    def test_the_no_trade_days_become_suspended_rows(self, fixture_calendar: list[date]) -> None:
        window = ListingWindow(FIXTURE_NO_TRADE_INSTRUMENT, date(2026, 1, 2))
        collected = collect_daily_quotes(
            FixtureSource(),
            fixture_calendar,
            instrument_ids=[FIXTURE_NO_TRADE_INSTRUMENT],
            windows=[window],
        )
        quotes = collected.for_instrument(FIXTURE_NO_TRADE_INSTRUMENT)
        built = build_instrument_series(quotes, fixture_calendar, window)
        suspended = [row["date"] for row in built.price_rows if row["trading_suspended"]]
        # 실제 응답에서 시가·고가·저가가 "0"이던 날이다.
        assert suspended == [date(2026, 9, 3), date(2026, 9, 8), date(2026, 9, 10)]
        assert len(built.price_rows) == len(fixture_calendar)
        assert built.gap_rows == ()

    def test_the_reference_price_is_replaced_by_the_previous_close(
        self, fixture_calendar: list[date]
    ) -> None:
        # 2026-09-10에 거래소가 낸 기준가는 10735인데 직전 거래일(09-09) 종가는 10730이다.
        # 확정된 규칙은 직전 종가를 쓰는 것이므로 기준가를 버린다. 둘이 다른 사례가 실제로
        # 있다는 사실이 이 규칙이 뜻을 가지는 근거다.
        window = ListingWindow(FIXTURE_NO_TRADE_INSTRUMENT, date(2026, 1, 2))
        collected = collect_daily_quotes(
            FixtureSource(),
            fixture_calendar,
            instrument_ids=[FIXTURE_NO_TRADE_INSTRUMENT],
            windows=[window],
        )
        quotes = collected.for_instrument(FIXTURE_NO_TRADE_INSTRUMENT)
        published = {item.date: item.close for item in quotes}
        assert published[date(2026, 9, 10)] == Decimal("10735")
        assert published[date(2026, 9, 9)] == Decimal("10730")

        built = build_instrument_series(quotes, fixture_calendar, window)
        rows = {row["date"]: row for row in built.price_rows}
        assert rows[date(2026, 9, 10)]["close"] == Decimal("10730")

    def test_a_traded_instrument_has_no_suspension(self, fixture_calendar: list[date]) -> None:
        window = ListingWindow("069500.XKRX", date(2002, 10, 14))
        collected = collect_daily_quotes(
            FixtureSource(),
            fixture_calendar,
            instrument_ids=["069500.XKRX"],
            windows=[window],
        )
        built = build_instrument_series(
            collected.for_instrument("069500.XKRX"), fixture_calendar, window
        )
        assert built.suspension_rows == ()
        assert all(row["volume"] > 0 for row in built.price_rows)


class TestLoadable:
    """만든 행이 적재 계층을 그대로 통과한다.

    인메모리 SQLite에 같은 열로 표를 만들어 확인한다. DB CHECK·트리거는 마이그레이션이
    원본이며 ``backend``의 ``EtfMarketDataSchemaIT``가 PostgreSQL 컨테이너로 검증한다.
    """

    @pytest.fixture
    def repository(self) -> MarketDataRepository:
        engine = create_engine("sqlite://")
        METADATA.create_all(engine)
        instance = MarketDataRepository(engine)
        instance.publish_dataset_version(
            DatasetVersion(
                dataset_id=DATASET_ID,
                data_deadline=DEADLINE,
                collected_at=DEADLINE,
                last_success_at=DEADLINE,
                complete=True,
                is_sample=False,
                source=SOURCE_KRX_OPEN_API,
                map_version_id=1,
            )
        )
        return instance

    def _built(self) -> Any:
        quotes = [
            quote(D1, "10000"),
            quote(D2, "10000", traded=False),
            quote(D4, "10500"),
        ]
        return build_instrument_series(quotes, calendar(D1, D2, D3, D4), WINDOW)

    def test_price_rows_are_accepted(self, repository: MarketDataRepository) -> None:
        built = self._built()
        assert repository.load_daily_price(DATASET_ID, list(built.price_rows)) == len(
            built.price_rows
        )
        with repository.engine.connect() as conn:
            stored = conn.execute(select(func.count()).select_from(METADATA.tables["daily_price"]))
            assert stored.scalar_one() == len(built.price_rows)

    def test_gap_rows_are_accepted(self, repository: MarketDataRepository) -> None:
        built = self._built()
        assert repository.load_data_gap(DATASET_ID, list(built.gap_rows)) == len(built.gap_rows)

    def test_suspension_rows_are_accepted(self, repository: MarketDataRepository) -> None:
        built = self._built()
        assert repository.load_suspension_period(DATASET_ID, list(built.suspension_rows)) == len(
            built.suspension_rows
        )

    def test_rows_carry_no_dataset_id(self) -> None:
        # 자료 버전은 적재 인자로만 받는다. 행이 직접 들고 오면 한 묶음 안에서 서로 다른
        # 자료 버전이 섞일 수 있고 그 상태는 DB가 막지 못한다 (repository.py).
        built = self._built()
        for group in (built.price_rows, built.gap_rows, built.suspension_rows):
            for row in group:
                assert "dataset_id" not in row


class TestActionsKnownFromReachesTheAdjustment:
    """``build_instrument_series``가 ``actions_known_from``을 넘겨야 FR-006이 동작한다.

    이 인자를 받지 않으면 ``MISSING_CORPORATE_ACTION`` 기록이 파이프라인 경로에서 영원히
    만들어지지 않는다. 2020년부터만 담은 기업행위 파일로 2010년 상장 종목을 처리하면
    2010~2019년의 누적 계수가 틀린 채로, 그 사실을 알리는 자료 부족 구간도 없이 발행된다.
    단위 검사만 있고 배선이 빠지면 그 상태가 초록색으로 남는다.
    """

    QUOTES = (
        quote(D1, "10000"),
        quote(D2, "10200"),
        quote(D3, "10100"),
        quote(D4, "10400"),
    )

    def test_without_the_argument_there_is_no_gap(self) -> None:
        built = build_instrument_series(list(self.QUOTES), calendar(D1, D2, D3, D4), WINDOW)
        assert built.gap_rows == ()

    def test_the_argument_produces_the_gap(self) -> None:
        built = build_instrument_series(
            list(self.QUOTES), calendar(D1, D2, D3, D4), WINDOW, (), actions_known_from=D3
        )
        reasons = [row["reason"] for row in built.gap_rows]
        assert reasons == [REASON_MISSING_CORPORATE_ACTION]
        gap = built.gap_rows[0]
        assert (gap["start_date"], gap["end_date"]) == (D1, D2)

    def test_the_gap_survives_validation_and_loads(self) -> None:
        # 만들어진 행이 검사와 적재를 통과해야 기록으로 남는다.
        built = build_instrument_series(
            list(self.QUOTES), calendar(D1, D2, D3, D4), WINDOW, (), actions_known_from=D3
        )
        engine = create_engine("sqlite://")
        METADATA.create_all(engine)
        store = MarketDataRepository(engine)
        store.publish_dataset_version(
            DatasetVersion(
                dataset_id=DATASET_ID,
                data_deadline=DEADLINE,
                collected_at=DEADLINE,
                last_success_at=DEADLINE,
                complete=True,
                is_sample=False,
                source=SOURCE_KRX_OPEN_API,
                map_version_id=1,
            )
        )
        assert store.load_data_gap(DATASET_ID, list(built.gap_rows)) == 1
        engine.dispose()
