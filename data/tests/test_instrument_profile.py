"""대표 상품 정보 산출 (spec 001 FR-011·FR-015, T012).

확인하는 것은 네 가지다. 유동성이 **거래대금 열**에서 나오는지(거래량×종가 추정이 아니다),
과거 구성 이력이 없을 때 현재 구성 기준 표시가 붙는지, **추적 차이 필드가 생기지 않는지**,
그리고 근거가 없을 때 0을 만들어 내지 않는지다.
"""

from __future__ import annotations

from collections.abc import Iterator
from datetime import UTC, date, datetime
from decimal import Decimal
from typing import Any

import pytest
from sqlalchemy import create_engine, func, select

from data_pipeline.dataset_version import (
    SOURCE_KRX_OPEN_API,
    DatasetAlreadyLoadedError,
    DatasetNotPublishedError,
    DatasetVersion,
)
from data_pipeline.instrument_profile import (
    MAX_TOP_HOLDINGS,
    SOURCE_ATTRIBUTION_KRX,
    SURVIVORSHIP_NOTE,
    Holding,
    HoldingsOverlap,
    InstrumentProfile,
    InstrumentProfileError,
    average_daily_trading_value,
    holdings_overlap_from_payload,
    liquidity_window,
    months_before,
    profiles_from_records,
)
from data_pipeline.repository import (
    METADATA,
    MarketDataRepository,
    RepresentativeEtfNotFoundError,
    RowValidationError,
    instrument_profile_table,
    representative_etf_table,
)

INSTRUMENT_ID = "091160.XKRX"
OTHER_ID = "305720.XKRX"
DEADLINE = date(2026, 9, 10)
DATASET_ID = "KRX_DAILY_20260910"

SUMMARY = "KRX 반도체 지수를 추종하며 유동시가총액 가중으로 구성종목을 담는다."


def price_row(day: date, *, value: int, instrument_id: str = INSTRUMENT_ID, **overrides: Any):
    row: dict[str, Any] = {
        "instrument_id": instrument_id,
        "date": day,
        "open": Decimal("35000"),
        "high": Decimal("35500"),
        "low": Decimal("34800"),
        "close": Decimal("35200"),
        "volume": 1_000_000,
        "value": value,
        "adjusted_close": Decimal("35200"),
        "adjustment_factor": Decimal("1"),
    }
    row.update(overrides)
    return row


def overlap(as_of_current: bool = True) -> HoldingsOverlap:
    return HoldingsOverlap(
        holdings=(
            Holding(name="삼성전자", weight=Decimal("0.2431")),
            Holding(name="SK하이닉스", weight=Decimal("0.2107")),
        ),
        as_of_current=as_of_current,
    )


def record(**overrides: Any) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "instrumentId": INSTRUMENT_ID,
        "indexMethodologySummary": SUMMARY,
        "holdingsOverlap": {
            "asOfCurrentComposition": True,
            "topHoldings": [
                {"name": "삼성전자", "weight": "0.2431"},
                {"name": "SK하이닉스", "weight": "0.2107"},
            ],
        },
    }
    payload.update(overrides)
    return payload


class TestLiquidityWindow:
    def test_window_is_the_three_months_ending_at_the_deadline(self) -> None:
        assert liquidity_window(date(2026, 9, 10)) == (date(2026, 6, 11), date(2026, 9, 10))

    def test_month_without_the_same_day_falls_back_to_its_last_day(self) -> None:
        # 5월 31일의 석 달 전은 2월 31일이 아니라 2월 말일이다.
        assert months_before(date(2026, 5, 31), 3) == date(2026, 2, 28)

    def test_leap_day_is_handled(self) -> None:
        assert months_before(date(2024, 5, 31), 3) == date(2024, 2, 29)

    def test_window_length_can_be_changed(self) -> None:
        assert liquidity_window(date(2026, 9, 10), months=1) == (
            date(2026, 8, 11),
            date(2026, 9, 10),
        )

    def test_zero_month_window_is_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError):
            liquidity_window(DEADLINE, months=0)


class TestAverageDailyTradingValue:
    def test_average_comes_from_the_trading_value_column(self) -> None:
        rows = [
            price_row(date(2026, 9, 8), value=10_000_000_000),
            price_row(date(2026, 9, 9), value=20_000_000_000),
            price_row(date(2026, 9, 10), value=30_000_000_000),
        ]

        assert average_daily_trading_value(rows, INSTRUMENT_ID, data_deadline=DEADLINE) == Decimal(
            20_000_000_000
        )

    def test_volume_times_close_is_not_used(self) -> None:
        """거래량×종가 추정과 다른 값이 나온다. 그날의 가격 변동만큼 어긋나기 때문이다."""
        rows = [price_row(date(2026, 9, 10), value=42_000_000_000, volume=1_000_000)]
        estimated = Decimal(1_000_000) * Decimal("35200")  # 35,200,000,000

        actual = average_daily_trading_value(rows, INSTRUMENT_ID, data_deadline=DEADLINE)

        assert actual == Decimal(42_000_000_000)
        assert actual != estimated

    def test_row_without_the_value_column_is_rejected(self) -> None:
        row = price_row(date(2026, 9, 10), value=1)
        del row["value"]

        with pytest.raises(InstrumentProfileError, match="거래대금"):
            average_daily_trading_value([row], INSTRUMENT_ID, data_deadline=DEADLINE)

    def test_rows_outside_the_window_are_ignored(self) -> None:
        rows = [
            price_row(date(2026, 1, 5), value=99_000_000_000),  # 구간 밖
            price_row(date(2026, 9, 10), value=10_000_000_000),
        ]

        assert average_daily_trading_value(rows, INSTRUMENT_ID, data_deadline=DEADLINE) == Decimal(
            10_000_000_000
        )

    def test_other_instruments_are_ignored(self) -> None:
        rows = [
            price_row(date(2026, 9, 10), value=10_000_000_000),
            price_row(date(2026, 9, 10), value=99_000_000_000, instrument_id=OTHER_ID),
        ]

        assert average_daily_trading_value(rows, INSTRUMENT_ID, data_deadline=DEADLINE) == Decimal(
            10_000_000_000
        )

    def test_suspended_day_counts_as_zero(self) -> None:
        """정지일은 장이 열렸는데 거래되지 않은 날이라 0원이 사실이다. 빼면 유동성이 좋아 보인다."""
        rows = [
            price_row(date(2026, 9, 9), value=10_000_000_000),
            price_row(date(2026, 9, 10), value=0, volume=0, trading_suspended=True),
        ]

        assert average_daily_trading_value(rows, INSTRUMENT_ID, data_deadline=DEADLINE) == Decimal(
            5_000_000_000
        )

    def test_average_is_rounded_to_won(self) -> None:
        rows = [
            price_row(date(2026, 9, 9), value=1),
            price_row(date(2026, 9, 10), value=2),
        ]

        # 1.5원은 원 단위로 반올림해 2원이다. 원보다 작은 자리는 거래대금에 뜻이 없다.
        average = average_daily_trading_value(rows, INSTRUMENT_ID, data_deadline=DEADLINE)
        assert average == Decimal(2)

    def test_no_rows_is_an_error_not_zero(self) -> None:
        """근거가 없으면 0원을 만들어 내지 않는다 (원칙 VI)."""
        with pytest.raises(InstrumentProfileError, match="만들어 내지 않는다"):
            average_daily_trading_value([], INSTRUMENT_ID, data_deadline=DEADLINE)

    def test_negative_value_is_rejected(self) -> None:
        rows = [price_row(date(2026, 9, 10), value=-1)]

        with pytest.raises(InstrumentProfileError):
            average_daily_trading_value(rows, INSTRUMENT_ID, data_deadline=DEADLINE)


class TestHoldingsOverlap:
    def test_holdings_are_sorted_by_weight(self) -> None:
        built = HoldingsOverlap(
            holdings=(
                Holding(name="SK하이닉스", weight=Decimal("0.2107")),
                Holding(name="삼성전자", weight=Decimal("0.2431")),
            ),
            as_of_current=True,
        )

        assert [holding.name for holding in built.holdings] == ["삼성전자", "SK하이닉스"]

    def test_json_value_keeps_weights_as_strings(self) -> None:
        assert overlap().to_json_value() == {
            "topHoldings": [
                {"name": "삼성전자", "weight": "0.2431"},
                {"name": "SK하이닉스", "weight": "0.2107"},
            ]
        }

    def test_empty_holdings_are_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError, match="원칙 VI"):
            HoldingsOverlap(holdings=(), as_of_current=True)

    def test_duplicated_name_is_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError, match="두 번"):
            HoldingsOverlap(
                holdings=(
                    Holding(name="삼성전자", weight=Decimal("0.2")),
                    Holding(name="삼성전자", weight=Decimal("0.1")),
                ),
                as_of_current=True,
            )

    def test_weights_over_one_are_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError, match="합"):
            HoldingsOverlap(
                holdings=(
                    Holding(name="가", weight=Decimal("0.7")),
                    Holding(name="나", weight=Decimal("0.5")),
                ),
                as_of_current=True,
            )

    @pytest.mark.parametrize("weight", ["0", "-0.1", "1.0001"])
    def test_weight_outside_zero_to_one_is_rejected(self, weight: str) -> None:
        with pytest.raises(InstrumentProfileError):
            Holding(name="가", weight=Decimal(weight))

    def test_too_many_holdings_are_rejected(self) -> None:
        holdings = tuple(
            Holding(name=f"종목{index}", weight=Decimal("0.001"))
            for index in range(MAX_TOP_HOLDINGS + 1)
        )

        with pytest.raises(InstrumentProfileError, match=str(MAX_TOP_HOLDINGS)):
            HoldingsOverlap(holdings=holdings, as_of_current=True)


class TestFileInput:
    def test_payload_is_parsed(self) -> None:
        built = holdings_overlap_from_payload(record()["holdingsOverlap"], "항목")

        assert built.as_of_current is True
        assert built.holdings[0].name == "삼성전자"

    def test_numeric_weight_is_rejected(self) -> None:
        payload = {
            "asOfCurrentComposition": True,
            "topHoldings": [{"name": "가", "weight": 0.2431}],
        }

        with pytest.raises(InstrumentProfileError, match="문자열"):
            holdings_overlap_from_payload(payload, "항목")

    def test_missing_as_of_flag_is_rejected(self) -> None:
        """현재 구성 기준인지 말하지 않은 채로는 만들 수 없다 (FR-015)."""
        payload = {"topHoldings": [{"name": "가", "weight": "0.2"}]}

        with pytest.raises(InstrumentProfileError, match="asOfCurrentComposition"):
            holdings_overlap_from_payload(payload, "항목")

    def test_a_false_as_of_flag_is_rejected(self) -> None:
        """과거 구성 이력을 주는 원천이 없으므로 당시 구성을 재현했다고 표현할 수 없다 (FR-015)."""
        payload = {
            "asOfCurrentComposition": False,
            "topHoldings": [{"name": "가", "weight": "0.2"}],
        }

        with pytest.raises(InstrumentProfileError, match="참이어야 한다"):
            holdings_overlap_from_payload(payload, "항목")

    def test_source_attribution_cannot_be_supplied(self) -> None:
        """출처 표시는 원천이 정하는 한 문장이라 파일이 덮어쓸 수 없다 (FR-011, SC-005).

        받아 주면 SC-005의 "100%"를 코드가 아니라 파일을 쓰는 사람이 보장하게 된다.
        """
        with pytest.raises(InstrumentProfileError, match="모르는 키"):
            profiles_from_records(
                [record(sourceAttribution="아무 출처")],
                [price_row(DEADLINE, value=1_000)],
                data_deadline=DEADLINE,
            )

    def test_source_attribution_is_always_the_exchange(self) -> None:
        built = profiles_from_records(
            [record()], [price_row(DEADLINE, value=1_000)], data_deadline=DEADLINE
        )

        assert built[0].source_attribution == SOURCE_ATTRIBUTION_KRX

    def test_unknown_key_is_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError, match="모르는 키"):
            profiles_from_records(
                [record(note="메모")],
                [price_row(DEADLINE, value=1_000)],
                data_deadline=DEADLINE,
            )

    def test_tracking_difference_is_refused(self) -> None:
        """추적 차이는 계산 정의가 PD-21로 남아 이번 범위에서 뺐다. 조용히 버리지 않고 거부한다."""
        with pytest.raises(InstrumentProfileError, match="PD-21"):
            profiles_from_records(
                [record(trackingDifference="0.0021")],
                [price_row(DEADLINE, value=1_000)],
                data_deadline=DEADLINE,
            )

    def test_precomputed_liquidity_is_refused(self) -> None:
        """일평균 거래대금은 파일이 들고 올 수 없다. 두 곳에서 만든 숫자는 갈라져도 모른다."""
        with pytest.raises(InstrumentProfileError, match="모르는 키"):
            profiles_from_records(
                [record(avgDailyValue3m="1240000000")],
                [price_row(DEADLINE, value=1_000)],
                data_deadline=DEADLINE,
            )

    def test_duplicated_instrument_is_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError, match="두 번"):
            profiles_from_records(
                [record(), record()],
                [price_row(DEADLINE, value=1_000)],
                data_deadline=DEADLINE,
            )

    def test_bad_instrument_id_is_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError, match="XKRX"):
            profiles_from_records(
                [record(instrumentId="091160")],
                [price_row(DEADLINE, value=1_000)],
                data_deadline=DEADLINE,
            )


class TestInstrumentProfile:
    def test_row_carries_every_column_of_the_table(self) -> None:
        profile = InstrumentProfile(
            instrument_id=INSTRUMENT_ID,
            index_methodology_summary=SUMMARY,
            holdings=overlap(),
            avg_daily_value_3m=Decimal(1_240_000_000),
        )

        assert profile.to_row() == {
            "instrument_id": INSTRUMENT_ID,
            "index_methodology_summary": SUMMARY,
            "holdings_overlap": {
                "topHoldings": [
                    {"name": "삼성전자", "weight": "0.2431"},
                    {"name": "SK하이닉스", "weight": "0.2107"},
                ]
            },
            "holdings_as_of_current": True,
            "avg_daily_value_3m": "1240000000",
            "source_attribution": SOURCE_ATTRIBUTION_KRX,
            "survivorship_note": SURVIVORSHIP_NOTE,
        }

    def test_row_has_no_tracking_difference_column(self) -> None:
        profile = InstrumentProfile(
            instrument_id=INSTRUMENT_ID,
            index_methodology_summary=SUMMARY,
            holdings=overlap(),
            avg_daily_value_3m=Decimal(1),
        )

        assert not [key for key in profile.to_row() if "tracking" in key.lower()]

    def test_amount_is_stored_in_plain_notation(self) -> None:
        """계약이 금액을 plain 문자열로 규정한다. 지수 표기가 나오면 저장 CHECK에 걸린다."""
        profile = InstrumentProfile(
            instrument_id=INSTRUMENT_ID,
            index_methodology_summary=SUMMARY,
            holdings=overlap(),
            avg_daily_value_3m=Decimal("1E+9"),
        )

        assert profile.to_row()["avg_daily_value_3m"] == "1000000000"

    def test_source_and_note_default_to_the_required_wording(self) -> None:
        profiles = profiles_from_records(
            [record()], [price_row(DEADLINE, value=1_000)], data_deadline=DEADLINE
        )

        assert profiles[0].source_attribution == "한국거래소 통계정보"  # FR-011
        assert "상장폐지" in profiles[0].survivorship_note  # FR-015

    def test_as_of_current_flag_survives_to_the_row(self) -> None:
        profile = InstrumentProfile(
            instrument_id=INSTRUMENT_ID,
            index_methodology_summary=SUMMARY,
            holdings=overlap(as_of_current=False),
            avg_daily_value_3m=Decimal(1),
        )

        assert profile.to_row()["holdings_as_of_current"] is False

    def test_blank_methodology_summary_is_rejected(self) -> None:
        with pytest.raises(InstrumentProfileError):
            InstrumentProfile(
                instrument_id=INSTRUMENT_ID,
                index_methodology_summary="   ",
                holdings=overlap(),
                avg_daily_value_3m=Decimal(1),
            )


# ---------------------------------------------------------------------------
# 적재 (T012의 산출값이 저장소에 닿는 경로)
# ---------------------------------------------------------------------------


class TestLoad:
    """``MarketDataRepository.load_instrument_profiles``.

    별도 진입점을 만들지 않고 기존 적재 계층을 늘린 까닭은 이 표가 대조 기록과 같은 모양이기
    때문이다 — 문자열 ``dataset_id``가 아니라 ``dataset_version_id``·``etf_id`` 두 bigint 외래
    키를 갖고, 그 변환·적재 순서 강제·재적재 거부가 이미 그 모듈에 있다.
    """

    @pytest.fixture
    def repository(self) -> Iterator[MarketDataRepository]:
        engine = create_engine("sqlite://")
        METADATA.create_all(engine)
        try:
            yield MarketDataRepository(engine)
        finally:
            engine.dispose()

    @pytest.fixture
    def published(self, repository: MarketDataRepository) -> MarketDataRepository:
        repository.publish_dataset_version(
            DatasetVersion(
                dataset_id=DATASET_ID,
                data_deadline=datetime(2026, 9, 10, 18, 0, tzinfo=UTC),
                collected_at=datetime(2026, 9, 10, 18, 0, tzinfo=UTC),
                last_success_at=datetime(2026, 9, 10, 18, 0, tzinfo=UTC),
                complete=True,
                is_sample=False,
                source=SOURCE_KRX_OPEN_API,
                map_version_id=1,
            )
        )
        with repository.engine.begin() as conn:
            conn.execute(
                representative_etf_table.insert().values(
                    instrument_id=INSTRUMENT_ID,
                    name="KODEX 반도체",
                    listed_on=date(2006, 6, 27),
                    benchmark_index="KRX 반도체",
                    manager="삼성자산운용",
                    leveraged=False,
                    inverse=False,
                )
            )
        return repository

    @staticmethod
    def make_profile(**overrides: Any) -> InstrumentProfile:
        values: dict[str, Any] = {
            "instrument_id": INSTRUMENT_ID,
            "index_methodology_summary": SUMMARY,
            "holdings": overlap(),
            "avg_daily_value_3m": Decimal(1_240_000_000),
        }
        values.update(overrides)
        return InstrumentProfile(**values)

    @staticmethod
    def count(repository: MarketDataRepository) -> int:
        with repository.engine.connect() as conn:
            return int(
                conn.execute(
                    select(func.count()).select_from(instrument_profile_table)
                ).scalar_one()
            )

    def test_profile_is_written_with_resolved_identifiers(
        self, published: MarketDataRepository
    ) -> None:
        assert published.load_instrument_profiles(DATASET_ID, [self.make_profile()]) == 1

        with published.engine.connect() as conn:
            row = conn.execute(select(instrument_profile_table)).one()
        assert row.dataset_version_id == 1
        assert row.etf_id == 1
        assert row.avg_daily_value_3m == "1240000000"
        assert row.holdings_as_of_current is True
        assert row.source_attribution == SOURCE_ATTRIBUTION_KRX

    def test_unpublished_dataset_is_rejected(self, repository: MarketDataRepository) -> None:
        with pytest.raises(DatasetNotPublishedError):
            repository.load_instrument_profiles(DATASET_ID, [self.make_profile()])
        assert self.count(repository) == 0

    def test_reloading_the_same_dataset_is_rejected(self, published: MarketDataRepository) -> None:
        """자료 버전마다 한 벌이다. 고쳐 쓰지 않고 새 dataset_id로 만든다 (FR-017)."""
        published.load_instrument_profiles(DATASET_ID, [self.make_profile()])
        with pytest.raises(DatasetAlreadyLoadedError):
            published.load_instrument_profiles(DATASET_ID, [self.make_profile()])
        assert self.count(published) == 1

    def test_missing_representative_etf_is_reported(self, repository: MarketDataRepository) -> None:
        repository.publish_dataset_version(
            DatasetVersion(
                dataset_id=DATASET_ID,
                data_deadline=datetime(2026, 9, 10, 18, 0, tzinfo=UTC),
                collected_at=datetime(2026, 9, 10, 18, 0, tzinfo=UTC),
                last_success_at=datetime(2026, 9, 10, 18, 0, tzinfo=UTC),
                complete=True,
                is_sample=False,
                source=SOURCE_KRX_OPEN_API,
                map_version_id=1,
            )
        )
        with pytest.raises(RepresentativeEtfNotFoundError, match=INSTRUMENT_ID):
            repository.load_instrument_profiles(DATASET_ID, [self.make_profile()])
        assert self.count(repository) == 0

    def test_plain_mapping_is_rejected(self, published: MarketDataRepository) -> None:
        """FR-015의 값 규칙을 거친 값만 적재한다."""
        with pytest.raises(RowValidationError, match="InstrumentProfile"):
            published.load_instrument_profiles(DATASET_ID, [self.make_profile().to_row()])
        assert self.count(published) == 0

    def test_duplicate_instrument_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError, match="두 번"):
            published.load_instrument_profiles(
                DATASET_ID, [self.make_profile(), self.make_profile()]
            )
        assert self.count(published) == 0

    def test_empty_load_writes_nothing(self, published: MarketDataRepository) -> None:
        assert published.load_instrument_profiles(DATASET_ID, []) == 0
        assert self.count(published) == 0
