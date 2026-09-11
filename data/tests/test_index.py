"""KOSPI 시리즈 지수 수집과 거래일 달력 (spec 001 T007, FR-009·FR-014).

**네트워크를 쓰지 않는다.** ``tests/fixtures/krx/``의 저장된 응답만 읽는다. 원천 대역
(:class:`test_krx_ingest.FixtureSource`)을 그 파일에서 가져오는 이유는 두 테스트가 같은
픽스처 규칙(파일명 ``{엔드포인트}_{YYYYMMDD}.json``, 없는 파일이면 실패)을 공유해야 하고,
두 곳에 같은 대역을 두면 한쪽만 고쳐졌을 때 어느 쪽이 맞는지 알 수 없기 때문이다.

거래일 달력의 근거가 이 자료라는 점이 여기서 확인하는 핵심이다. ETF 일별매매정보는 휴장일에도
행을 주지만(:mod:`data_pipeline.krx` 문서 2번) 지수는 휴장일에 **빈 배열**을 준다.
"""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, date, datetime
from decimal import Decimal

import pytest
from sqlalchemy import create_engine

# `tests/`는 패키지가 아니라 pytest가 그 디렉터리를 sys.path에 올린다. 그래서 같은 폴더의
# 테스트 모듈에서 원천 대역을 가져올 수 있다.
from test_krx_ingest import CANDIDATE_DAYS, TRADING_DAYS, FixtureSource

from data_pipeline import index
from data_pipeline.dataset_version import SOURCE_KRX_OPEN_API, DatasetVersion
from data_pipeline.krx import KrxResponseError, ListingWindow, QuotaLimitedSource
from data_pipeline.repository import METADATA, MarketDataRepository, daily_index_table

DATASET_ID = "KRX_DAILY_20260910"
DEADLINE = datetime(2026, 9, 10, 18, 0, tzinfo=UTC)
KODEX_200 = "069500.XKRX"

#: 실제 응답에 섞여 있는 "값이 비어 있는 행"의 모양이다. 픽스처에는 코스피·코스피 200 두 행만
#: 남겨 두었으므로(`kospi_dd_trd_*.json` 확인) 이 모양은 여기서 직접 만들어 확인한다.
#: 거래량만 있고 가격이 빈 행이며, 담으면 없던 지수를 만들어 내게 된다.
FOREIGN_INCLUSIVE_ROW = {
    "BAS_DD": "20260910",
    "IDX_CLSS": "KOSPI",
    "IDX_NM": "코스피 (외국주포함)",
    "CLSPRC_IDX": "",
    "OPNPRC_IDX": "",
    "HGPRC_IDX": "",
    "LWPRC_IDX": "",
    "ACC_TRDVOL": "422276446",
    "ACC_TRDVAL": "",
}


def collect(
    *,
    days: tuple[date, ...] = CANDIDATE_DAYS,
    codes: tuple[str, ...] = ("KOSPI", "KOSPI200"),
    source: object | None = None,
) -> index.IndexCollection:
    return index.collect_index_quotes(
        source if source is not None else FixtureSource(),  # type: ignore[arg-type]
        days,
        index_codes=codes,
    )


@pytest.fixture
def published() -> Iterator[MarketDataRepository]:
    """인메모리 SQLite에 같은 열로 표를 만든 저장소. ``test_repository.py``와 같은 방식이다."""
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
    try:
        yield store
    finally:
        engine.dispose()


class TestNameMapping:
    """원천의 ``IDX_NM``을 코드로 옮긴다. 코드를 한글 이름 그대로 쓰지 않는 이유는 원천 표기가
    바뀌면 키가 따라 바뀌어 옛 자료 버전과 이어지지 않기 때문이다."""

    def test_kospi_names_move_to_codes(self) -> None:
        collection = collect(days=(date(2026, 9, 10),))
        kospi = collection.for_index("KOSPI")
        kospi200 = collection.for_index("KOSPI200")
        assert [quote.close for quote in kospi] == [Decimal("7033.92")]
        assert [quote.close for quote in kospi200] == [Decimal("1112.13")]

    def test_all_four_prices_come_from_the_response(self) -> None:
        quote = collect(days=(date(2026, 9, 10),)).for_index("KOSPI")[0]
        assert (quote.open, quote.high, quote.low, quote.close) == (
            Decimal("7038.85"),
            Decimal("7072.79"),
            Decimal("6898.45"),
            Decimal("7033.92"),
        )
        assert (quote.volume, quote.value) == (422_276_446, 28_927_397_176_014)

    def test_unknown_code_is_rejected(self) -> None:
        """모르는 코드로 수집을 시작하면 이용 한도를 쓴 채로 실패한다. 호출 전에 막는다."""
        with pytest.raises(index.IndexDataError, match="모르는 지수 코드"):
            collect(days=(date(2026, 9, 10),), codes=("KOSDAQ",))


class TestBlankRows:
    def test_row_without_prices_is_dropped(self) -> None:
        """값이 없는 행을 0으로 채우면 없던 지수를 만들고, 그 날짜가 달력에 들어가면
        결측을 휴일로 오인하는 판정까지 어긋난다."""
        assert (
            index.normalize_index_row(
                FOREIGN_INCLUSIVE_ROW, expected_date=date(2026, 9, 10), index_code="KOSPI"
            )
            is None
        )

    def test_only_the_registered_series_are_collected(self) -> None:
        collection = collect(days=(date(2026, 9, 10),))
        assert sorted({quote.index_code for quote in collection.quotes}) == ["KOSPI", "KOSPI200"]

    def test_basis_date_mismatch_is_reported(self) -> None:
        with pytest.raises(KrxResponseError, match="BAS_DD"):
            index.normalize_index_row(
                FOREIGN_INCLUSIVE_ROW, expected_date=date(2026, 9, 9), index_code="KOSPI"
            )


class TestTradingCalendar:
    def test_weekend_is_not_a_trading_day(self) -> None:
        """휴장일에는 빈 배열이 온다. 그것이 거래일 판정을 이 자료에 맡기는 근거다."""
        calendar = collect().trading_calendar()
        assert date(2026, 9, 5) not in calendar
        assert date(2026, 9, 6) not in calendar
        assert calendar == list(TRADING_DAYS)

    def test_calendar_day_range_is_the_candidate_list(self) -> None:
        """지수 수집은 달력을 **만드는** 단계라 달력을 입력으로 받을 수 없다."""
        candidates = index.calendar_day_range(date(2026, 9, 1), date(2026, 9, 10))
        assert candidates == list(CANDIDATE_DAYS)
        assert date(2026, 9, 6) in candidates

    def test_reversed_range_is_rejected(self) -> None:
        with pytest.raises(index.IndexDataError):
            index.calendar_day_range(date(2026, 9, 10), date(2026, 9, 1))

    def test_calendar_comes_from_the_calendar_index(self) -> None:
        assert index.CALENDAR_INDEX_CODE == "KOSPI"
        collection = collect()
        assert collection.trading_calendar() == collection.trading_calendar("KOSPI")


class TestRowShape:
    def test_row_columns_match_the_migration(self) -> None:
        """``daily_index`` 열 이름과 맞는지 본다.

        마이그레이션과의 대조는 ``test_repository.py``의 ``test_columns_match_the_migration``이
        ``daily_index_table``에 대해 이미 하고 있으므로, 여기서는 그 표 정의와 맞추면 된다.
        같은 파싱을 두 곳에 두면 둘이 갈라진다.
        """
        quote = collect(days=(date(2026, 9, 10),)).for_index("KOSPI")[0]
        expected = {column.name for column in daily_index_table.columns} - {"dataset_id"}
        assert set(quote.to_row()) == expected

    def test_row_does_not_carry_the_dataset_id(self) -> None:
        """자료 버전은 적재 인자로만 받는다. 행이 들고 오면 한 묶음에 두 버전이 섞일 수 있다."""
        quote = collect(days=(date(2026, 9, 10),)).for_index("KOSPI")[0]
        assert "dataset_id" not in quote.to_row()

    def test_rows_are_loadable(self, published: MarketDataRepository) -> None:
        rows = collect().to_rows()
        assert published.load_daily_index(DATASET_ID, rows) == len(rows)
        assert len(rows) == 2 * len(TRADING_DAYS)


class TestIndexCoverage:
    """FR-009. 지수 확보 기간이 대표 ETF의 상장 이후 전체를 덮는지 검사한다."""

    def test_listing_before_the_calendar_is_not_covered(self) -> None:
        calendar = list(TRADING_DAYS)
        coverage = index.verify_index_coverage(
            [ListingWindow(KODEX_200, listed_on=date(2026, 8, 20))],
            calendar,
            data_deadline=date(2026, 9, 10),
        )[0]
        assert coverage.covers is False
        assert coverage.missing_ranges == ((date(2026, 8, 20), date(2026, 8, 31)),)

    def test_full_coverage_after_listing(self) -> None:
        coverage = index.verify_index_coverage(
            [ListingWindow(KODEX_200, listed_on=date(2026, 9, 2))],
            list(TRADING_DAYS),
            data_deadline=date(2026, 9, 10),
        )[0]
        assert coverage.covers is True
        assert coverage.missing_ranges == ()

    def test_calendar_short_at_the_tail_is_not_covered(self) -> None:
        """마감일이 달력 끝보다 뒤면 그 뒤 구간은 지수 자료가 없는 구간이다."""
        coverage = index.verify_index_coverage(
            [ListingWindow(KODEX_200, listed_on=date(2026, 9, 2))],
            list(TRADING_DAYS[:-2]),
            data_deadline=date(2026, 9, 10),
        )[0]
        assert coverage.covers is False
        assert coverage.missing_ranges == ((date(2026, 9, 9), date(2026, 9, 10)),)

    def test_delisted_instrument_ends_at_the_delisting_date(self) -> None:
        """상장폐지된 종목의 요구 종료일은 마감일이 아니라 상장폐지일이다."""
        coverage = index.verify_index_coverage(
            [ListingWindow(KODEX_200, listed_on=date(2026, 9, 2), delisted_on=date(2026, 9, 8))],
            list(TRADING_DAYS),
            data_deadline=date(2026, 9, 10),
        )[0]
        assert coverage.required_end == date(2026, 9, 8)
        assert coverage.covers is True


class TestIndexHistoryGaps:
    def test_gap_rows_use_the_index_history_reason(self) -> None:
        coverages = index.verify_index_coverage(
            [ListingWindow(KODEX_200, listed_on=date(2026, 8, 20))],
            list(TRADING_DAYS),
            data_deadline=date(2026, 9, 10),
        )
        rows = index.index_history_gap_rows(coverages)
        assert [row["reason"] for row in rows] == [index.REASON_INDEX_HISTORY_SHORT]
        assert rows[0]["reason"] == "INDEX_HISTORY_SHORT"

    def test_gap_rows_go_straight_into_the_repository(
        self, published: MarketDataRepository
    ) -> None:
        """``data_gap`` 적재에 그대로 넣을 수 있어야 한다.

        열 이름을 옮겨 담는 자리를 두면 그 자리가 표류를 숨긴다.
        """
        coverages = index.verify_index_coverage(
            [ListingWindow(KODEX_200, listed_on=date(2026, 8, 20))],
            list(TRADING_DAYS),
            data_deadline=date(2026, 9, 10),
        )
        rows = index.index_history_gap_rows(coverages)
        assert published.load_data_gap(DATASET_ID, rows) == 1

    def test_covered_instrument_makes_no_gap_row(self) -> None:
        coverages = index.verify_index_coverage(
            [ListingWindow(KODEX_200, listed_on=date(2026, 9, 2))],
            list(TRADING_DAYS),
            data_deadline=date(2026, 9, 10),
        )
        assert index.index_history_gap_rows(coverages) == []

    def test_bad_instrument_id_is_rejected(self) -> None:
        coverage = index.IndexCoverage(
            instrument_id="069500",
            required_start=date(2026, 9, 1),
            required_end=date(2026, 9, 10),
            covers=False,
            missing_ranges=((date(2026, 9, 1), date(2026, 9, 2)),),
        )
        with pytest.raises(index.IndexDataError, match="XKRX"):
            index.index_history_gap_rows([coverage])


class TestQuotaLimit:
    """이용 한도 (FR-014). 중단하고 그때까지 모은 것을 돌려준다."""

    def test_collection_stops_with_a_reason(self) -> None:
        source = QuotaLimitedSource(inner=FixtureSource(), allowed_calls=3)
        collection = collect(source=source)
        assert collection.complete is False
        assert collection.failure_reason
        assert "지수" in collection.failure_reason
        assert collection.called_dates == list(CANDIDATE_DAYS[:3])
        assert collection.uncalled_count == len(CANDIDATE_DAYS) - 3


class TestDeadlineIsATradingDay:
    """자료 마감일은 **값을 얻은 마지막 거래일**이며 호출을 마친 마지막 날짜가 아니다.

    지수 수집의 후보는 달력 날짜라 호출 목록의 끝이 휴장일일 수 있다. 그 값을 마감일로 쓰면
    ``dataset_id``와 ``data_deadline``이 휴장일로 발행되고, 발행본은 트리거가 UPDATE·DELETE를
    막아 되돌릴 수 없다 (FR-017).
    """

    def test_called_dates_can_end_on_a_closed_day(self) -> None:
        # 2026-09-06은 일요일이다. 호출은 마쳤고 빈 배열이 왔다.
        collection = collect(days=tuple(CANDIDATE_DAYS[:6]))
        assert collection.called_dates[-1] == date(2026, 9, 6)

    def test_last_trading_day_skips_the_closed_day(self) -> None:
        collection = collect(days=tuple(CANDIDATE_DAYS[:6]))
        assert collection.last_trading_day() == date(2026, 9, 4)

    def test_last_trading_day_is_none_without_data(self) -> None:
        # 휴장일만 물었으면 마감일로 쓸 날짜가 없다. 없는 날짜를 지어내지 않는다.
        collection = collect(days=(date(2026, 9, 6),))
        assert collection.last_trading_day() is None

    def test_calendar_is_short_when_the_quota_runs_out(self) -> None:
        """한도로 끊긴 달력을 그대로 쓰면 그 뒤 거래일이 휴장일로 보인다.

        그래서 부른 쪽이 ``complete``를 보고 부분 갱신으로 발행해야 한다. 달력 자체는
        수집한 만큼만 담는다 — 없는 날짜를 지어내지 않는다.
        """
        source = QuotaLimitedSource(inner=FixtureSource(), allowed_calls=3)
        collection = collect(source=source)
        assert collection.trading_calendar() == list(CANDIDATE_DAYS[:3])
