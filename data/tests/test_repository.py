"""서비스 저장소 적재 계층 (spec 001 FR-013·FR-017, research.md 결정 8).

실제 PostgreSQL 없이 돌린다. 적재 순서·재적재 거부·행 모양 검사는 모두 파이썬 계층의 규칙이라
인메모리 SQLite에 같은 열로 표를 만들어 확인할 수 있다. DB CHECK·트리거·FK는 마이그레이션이
원본이며 `backend`의 `EtfMarketDataSchemaIT`가 PostgreSQL 컨테이너로 검증한다.
"""

from __future__ import annotations

import re
from collections.abc import Iterator
from datetime import UTC, date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

import numpy as np
import pytest
from sqlalchemy import Table, create_engine, func, select

from data_pipeline.dataset_version import (
    SOURCE_KRX_OPEN_API,
    DatasetAlreadyLoadedError,
    DatasetAlreadyPublishedError,
    DatasetNotPublishedError,
    DatasetVersion,
    DatasetVersionError,
    dataset_deadline_date,
)
from data_pipeline.repository import (
    METADATA,
    MarketDataRepository,
    RepositoryError,
    RowValidationError,
    daily_index_table,
    daily_price_table,
    database_url_from_env,
    dataset_version_table,
    normalize_database_url,
)

DATASET_ID = "KRX_DAILY_20260911"
OTHER_DATASET_ID = "KRX_DAILY_20260912"
INSTRUMENT_ID = "069500.XKRX"
MOMENT = datetime(2026, 9, 11, 18, 0, tzinfo=UTC)

REPO_ROOT = Path(__file__).resolve().parents[2]
# 파일명을 고정하지 않고 찾는다. 마이그레이션 버전이 타임스탬프라 파일명이 바뀔 수 있는데,
# 고정해 두면 이름이 달라진 순간 아래 대조가 조용히 건너뛰어져 표류를 놓친다.
#
# 001의 표는 마이그레이션 두 개에 나뉘어 있다. 관계형·시계열 11개는 T003이 만들었고,
# instrument_profile은 data-model.md에 있는데도 빠져 있어 T013이 따로 만들었다. 그래서 파일
# 하나가 아니라 001이 만든 파일 전부에서 표 정의를 찾는다.
_MIGRATION_DIR = REPO_ROOT / "backend/src/main/resources/db/migration"
MIGRATIONS = sorted(
    [
        *_MIGRATION_DIR.glob("V*__etf_market_data.sql"),
        *_MIGRATION_DIR.glob("V*__instrument_profile.sql"),
    ]
)


def deadline_for(dataset_id: str) -> datetime:
    """``dataset_id``가 가리키는 날의 마감 시각. 둘은 같은 날이어야 한다 (research.md 결정 4)."""
    day = dataset_deadline_date(dataset_id)
    return datetime(day.year, day.month, day.day, 18, 0, tzinfo=UTC)


def make_version(dataset_id: str = DATASET_ID, **overrides: Any) -> DatasetVersion:
    values: dict[str, Any] = {
        "dataset_id": dataset_id,
        "data_deadline": deadline_for(dataset_id),
        "collected_at": MOMENT,
        "last_success_at": MOMENT,
        "complete": True,
        "is_sample": False,
        "source": SOURCE_KRX_OPEN_API,
        "map_version_id": 1,
    }
    values.update(overrides)
    return DatasetVersion(**values)


def price_row(day: int = 1, **overrides: Any) -> dict[str, Any]:
    row: dict[str, Any] = {
        "instrument_id": INSTRUMENT_ID,
        "date": date(2026, 9, day),
        "open": Decimal("35000.0000"),
        "high": Decimal("35500.0000"),
        "low": Decimal("34800.0000"),
        "close": Decimal("35200.0000"),
        "volume": 1_200_000,
        "value": 42_000_000_000,
        "adjusted_close": Decimal("35200.00000000"),
        "adjustment_factor": Decimal("1.000000000000"),
    }
    row.update(overrides)
    return row


def suspended_row(day: int = 2, **overrides: Any) -> dict[str, Any]:
    """거래정지일. OHLC 네 값이 직전 종가로 같고 volume·value는 0이다 (2026-09-11 확정)."""
    carried = Decimal("35200.0000")
    row = price_row(
        day,
        open=carried,
        high=carried,
        low=carried,
        close=carried,
        volume=0,
        value=0,
        trading_suspended=True,
        price_carried_forward=True,
    )
    row.update(overrides)
    return row


@pytest.fixture
def repository() -> Iterator[MarketDataRepository]:
    engine = create_engine("sqlite://")
    METADATA.create_all(engine)
    try:
        yield MarketDataRepository(engine)
    finally:
        engine.dispose()


@pytest.fixture
def published(repository: MarketDataRepository) -> MarketDataRepository:
    repository.publish_dataset_version(make_version())
    return repository


def count_rows(repository: MarketDataRepository, table: Table) -> int:
    with repository.engine.connect() as conn:
        return int(conn.execute(select(func.count()).select_from(table)).scalar_one())


class TestPublish:
    def test_publish_writes_one_row(self, repository: MarketDataRepository) -> None:
        repository.publish_dataset_version(make_version())
        assert repository.is_published(DATASET_ID) is True
        assert count_rows(repository, dataset_version_table) == 1

    def test_republishing_same_dataset_id_is_rejected(
        self, published: MarketDataRepository
    ) -> None:
        # FR-017. 발행된 버전은 고치지 않고 새 dataset_id를 발행한다.
        with pytest.raises(DatasetAlreadyPublishedError):
            published.publish_dataset_version(make_version(source="FIXED_TEST_DATA"))
        assert count_rows(published, dataset_version_table) == 1

    def test_next_deadline_can_be_published(self, published: MarketDataRepository) -> None:
        published.publish_dataset_version(make_version(OTHER_DATASET_ID))
        assert count_rows(published, dataset_version_table) == 2

    def test_incomplete_version_carries_reason(self, repository: MarketDataRepository) -> None:
        repository.publish_dataset_version(
            make_version(complete=False, failure_reason="이용 한도 도달")
        )
        with repository.engine.connect() as conn:
            stored = conn.execute(
                select(
                    dataset_version_table.c.complete,
                    dataset_version_table.c.failure_reason,
                )
            ).one()
        assert bool(stored.complete) is False
        assert stored.failure_reason == "이용 한도 도달"


class TestLoadOrder:
    def test_timeseries_without_dataset_version_is_rejected(
        self, repository: MarketDataRepository
    ) -> None:
        with pytest.raises(DatasetNotPublishedError):
            repository.load_daily_price(DATASET_ID, [price_row()])
        assert count_rows(repository, daily_price_table) == 0

    def test_timeseries_after_publish_is_accepted(self, published: MarketDataRepository) -> None:
        assert published.load_daily_price(DATASET_ID, [price_row(1), price_row(2)]) == 2
        assert count_rows(published, daily_price_table) == 2

    def test_timeseries_belongs_to_the_published_dataset(
        self, published: MarketDataRepository
    ) -> None:
        published.load_daily_price(DATASET_ID, [price_row()])
        with published.engine.connect() as conn:
            stored = conn.execute(select(daily_price_table.c.dataset_id)).scalars().all()
        assert stored == [DATASET_ID]

    def test_reloading_a_published_dataset_is_rejected(
        self, published: MarketDataRepository
    ) -> None:
        # T004 필수 테스트. 발행된 버전에 다시 적재하면 거부한다 (FR-017).
        published.load_daily_price(DATASET_ID, [price_row(1)])
        with pytest.raises(DatasetAlreadyLoadedError):
            published.load_daily_price(DATASET_ID, [price_row(2)])
        assert count_rows(published, daily_price_table) == 1

    def test_other_dataset_is_loaded_independently(self, published: MarketDataRepository) -> None:
        published.load_daily_price(DATASET_ID, [price_row(1)])
        published.publish_dataset_version(make_version(OTHER_DATASET_ID))
        published.load_daily_price(OTHER_DATASET_ID, [price_row(1)])
        assert count_rows(published, daily_price_table) == 2

    def test_rows_are_written_in_chunks(self, published: MarketDataRepository) -> None:
        rows = [price_row(day) for day in range(1, 6)]
        assert published.load_daily_price(DATASET_ID, rows, chunk_size=2) == 5
        assert count_rows(published, daily_price_table) == 5

    def test_empty_load_writes_nothing(self, published: MarketDataRepository) -> None:
        assert published.load_daily_price(DATASET_ID, []) == 0
        assert count_rows(published, daily_price_table) == 0

    def test_invalid_row_leaves_the_table_untouched(self, published: MarketDataRepository) -> None:
        rows = [price_row(1), price_row(2, volume="많음"), price_row(3)]
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, rows)
        assert count_rows(published, daily_price_table) == 0


class TestRowShape:
    def test_row_may_not_carry_its_own_dataset_id(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, [price_row(dataset_id=OTHER_DATASET_ID)])

    def test_missing_column_is_rejected(self, published: MarketDataRepository) -> None:
        row = price_row()
        del row["adjusted_close"]
        with pytest.raises(RowValidationError, match="adjusted_close"):
            published.load_daily_price(DATASET_ID, [row])

    def test_unknown_column_is_rejected(self, published: MarketDataRepository) -> None:
        # 001 기준 열 이름을 쓴다. adj_close 같은 다른 표기는 조용히 버리지 않고 거부한다.
        with pytest.raises(RowValidationError, match="adj_close"):
            published.load_daily_price(DATASET_ID, [price_row(adj_close=1)])

    def test_null_in_required_column_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, [price_row(close=None)])

    def test_non_numeric_value_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError, match="volume"):
            published.load_daily_price(DATASET_ID, [price_row(volume="많음")])

    def test_nan_price_is_rejected(self, published: MarketDataRepository) -> None:
        # 결측을 NaN으로 흘려보내지 않는다 (FR-005, 원칙 VI).
        with pytest.raises(RowValidationError, match="adjusted_close"):
            published.load_daily_price(DATASET_ID, [price_row(adjusted_close=float("nan"))])

    def test_duplicate_key_inside_one_batch_is_rejected(
        self, published: MarketDataRepository
    ) -> None:
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, [price_row(1), price_row(1)])

    def test_bad_instrument_id_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, [price_row(instrument_id="069500")])

    def test_date_column_rejects_a_time_of_day(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, [price_row(date=datetime(2026, 9, 1, 15, 30))])

    def test_midnight_datetime_is_accepted_as_a_date(self, published: MarketDataRepository) -> None:
        published.load_daily_price(DATASET_ID, [price_row(date=datetime(2026, 9, 1))])
        with published.engine.connect() as conn:
            stored = conn.execute(select(daily_price_table.c.date)).scalar_one()
        assert stored == date(2026, 9, 1)


class TestSuspendedDays:
    def test_suspended_row_is_stored(self, published: MarketDataRepository) -> None:
        published.load_daily_price(DATASET_ID, [price_row(1), suspended_row(2)])
        with published.engine.connect() as conn:
            flags = (
                conn.execute(
                    select(daily_price_table.c.trading_suspended).order_by(daily_price_table.c.date)
                )
                .scalars()
                .all()
            )
        assert [bool(flag) for flag in flags] == [False, True]

    @pytest.mark.parametrize(
        "broken",
        [
            {"volume": 10},
            {"value": 100},
            {"price_carried_forward": False},
            {"high": Decimal("35300.0000")},
        ],
    )
    def test_suspended_row_must_keep_the_agreed_shape(
        self, published: MarketDataRepository, broken: dict[str, Any]
    ) -> None:
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, [suspended_row(2, **broken)])

    def test_zero_volume_alone_is_not_a_suspension(self, published: MarketDataRepository) -> None:
        # 판정 기준은 trading_suspended이며 volume = 0이 아니다.
        published.load_daily_price(DATASET_ID, [price_row(1, volume=0, value=0)])
        assert count_rows(published, daily_price_table) == 1


class TestCorporateAction:
    def distribution(self, **overrides: Any) -> dict[str, Any]:
        row: dict[str, Any] = {
            "instrument_id": INSTRUMENT_ID,
            "ex_date": date(2026, 4, 29),
            "pay_date": date(2026, 5, 8),
            "event_type": "DISTRIBUTION",
            "amount": Decimal("120.00000000"),
        }
        row.update(overrides)
        return row

    def split(self, **overrides: Any) -> dict[str, Any]:
        row: dict[str, Any] = {
            "instrument_id": INSTRUMENT_ID,
            "ex_date": date(2026, 6, 1),
            "event_type": "SPLIT",
            "amount": Decimal("2.00000000"),
        }
        row.update(overrides)
        return row

    def test_both_dates_are_stored(self, published: MarketDataRepository) -> None:
        assert published.load_corporate_action(DATASET_ID, [self.distribution()]) == 1

    def test_split_is_stored_without_pay_date(self, published: MarketDataRepository) -> None:
        assert published.load_corporate_action(DATASET_ID, [self.split()]) == 1

    def test_distribution_without_pay_date_is_rejected(
        self, published: MarketDataRepository
    ) -> None:
        with pytest.raises(RowValidationError):
            published.load_corporate_action(DATASET_ID, [self.distribution(pay_date=None)])

    def test_pay_date_before_ex_date_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_corporate_action(
                DATASET_ID, [self.distribution(pay_date=date(2026, 4, 1))]
            )

    def test_split_with_pay_date_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_corporate_action(DATASET_ID, [self.split(pay_date=date(2026, 6, 5))])

    def test_unknown_event_type_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_corporate_action(DATASET_ID, [self.split(event_type="MERGER")])

    def test_reload_is_rejected(self, published: MarketDataRepository) -> None:
        published.load_corporate_action(DATASET_ID, [self.distribution()])
        with pytest.raises(DatasetAlreadyLoadedError):
            published.load_corporate_action(DATASET_ID, [self.split()])


class TestOtherTimeseries:
    def index_row(self, day: int = 1, **overrides: Any) -> dict[str, Any]:
        row: dict[str, Any] = {
            "index_code": "KOSPI200",
            "date": date(2026, 9, day),
            "open": Decimal("410.0000"),
            "high": Decimal("413.0000"),
            "low": Decimal("408.0000"),
            "close": Decimal("412.0000"),
            "volume": 90_000_000,
            "value": 7_000_000_000_000,
        }
        row.update(overrides)
        return row

    def test_daily_index_is_the_trading_calendar(self, published: MarketDataRepository) -> None:
        assert published.load_daily_index(DATASET_ID, [self.index_row(1), self.index_row(2)]) == 2
        assert count_rows(published, daily_index_table) == 2

    def test_daily_index_requires_the_dataset_version(
        self, repository: MarketDataRepository
    ) -> None:
        with pytest.raises(DatasetNotPublishedError):
            repository.load_daily_index(DATASET_ID, [self.index_row()])

    def test_blank_index_code_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_daily_index(DATASET_ID, [self.index_row(index_code=" ")])

    def test_data_gap_is_stored(self, published: MarketDataRepository) -> None:
        assert (
            published.load_data_gap(
                DATASET_ID,
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "start_date": date(2026, 3, 2),
                        "end_date": date(2026, 3, 6),
                        "reason": "AMBIGUOUS_PRICE",
                    }
                ],
            )
            == 1
        )

    def test_unknown_gap_reason_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_data_gap(
                DATASET_ID,
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "start_date": date(2026, 3, 2),
                        "end_date": date(2026, 3, 6),
                        "reason": "WHATEVER",
                    }
                ],
            )

    def test_reversed_gap_range_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError):
            published.load_data_gap(
                DATASET_ID,
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "start_date": date(2026, 3, 6),
                        "end_date": date(2026, 3, 2),
                        "reason": "AMBIGUOUS_PRICE",
                    }
                ],
            )

    def test_suspension_period_is_stored(self, published: MarketDataRepository) -> None:
        assert (
            published.load_suspension_period(
                DATASET_ID,
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "start_date": date(2026, 9, 2),
                        "end_date": date(2026, 9, 4),
                        "trading_days": 3,
                    }
                ],
            )
            == 1
        )

    def test_suspension_period_needs_at_least_one_trading_day(
        self, published: MarketDataRepository
    ) -> None:
        with pytest.raises(RowValidationError):
            published.load_suspension_period(
                DATASET_ID,
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "start_date": date(2026, 9, 2),
                        "end_date": date(2026, 9, 4),
                        "trading_days": 0,
                    }
                ],
            )


class TestConnection:
    def test_url_is_pointed_at_the_installed_driver(self) -> None:
        assert (
            normalize_database_url("postgresql://thorp:thorp@localhost:5432/thorp")
            == "postgresql+psycopg://thorp:thorp@localhost:5432/thorp"
        )

    def test_explicit_driver_is_left_alone(self) -> None:
        url = "postgresql+psycopg://thorp:thorp@postgres:5432/thorp"
        assert normalize_database_url(url) == url

    def test_url_comes_from_env_file(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.delenv("DATABASE_URL", raising=False)
        env_file = tmp_path / ".env"
        env_file.write_text(
            "DATABASE_URL=postgresql://thorp:thorp@localhost:5432/thorp\n",
            encoding="utf-8",
        )
        assert database_url_from_env(env_file).startswith("postgresql+psycopg://")

    def test_missing_url_is_reported(self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
        monkeypatch.delenv("DATABASE_URL", raising=False)
        with pytest.raises(RepositoryError):
            database_url_from_env(tmp_path / "absent.env")

    def test_no_credentials_are_hardcoded(self) -> None:
        source = (
            Path(__file__).resolve().parents[1] / "src/data_pipeline/repository.py"
        ).read_text(encoding="utf-8")
        assert 'create_engine("postgresql' not in source


_NOT_A_COLUMN = ("CONSTRAINT", "CHECK", "PRIMARY", "UNIQUE", "FOREIGN")


def parse_migration_columns(table_name: str) -> list[str]:
    """마이그레이션에서 한 표의 열 이름을 읽는다. 코드와 스키마의 표류를 잡는다."""
    assert MIGRATIONS, (
        "001의 마이그레이션을 찾지 못했다. 마이그레이션이 사라졌거나 이름 규칙이 바뀌었다면"
        " 이 대조를 건너뛰지 말고 여기를 고쳐야 한다"
    )
    match = None
    for migration in MIGRATIONS:
        sql = migration.read_text(encoding="utf-8")
        match = re.search(rf"CREATE TABLE {table_name} \((.*?)\n\);", sql, flags=re.DOTALL)
        if match is not None:
            break
    assert match is not None, f"{table_name} 정의를 찾지 못했다"
    body = "\n".join(line.split("--")[0] for line in match.group(1).splitlines())

    # 괄호 깊이 0의 쉼표로 항목을 나눈다. CHECK 제약이 여러 줄에 걸쳐 있어 줄 단위로는 못 자른다.
    items: list[str] = []
    depth = 0
    current: list[str] = []
    for character in body:
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
        if character == "," and depth == 0:
            items.append("".join(current))
            current = []
            continue
        current.append(character)
    items.append("".join(current))

    columns: list[str] = []
    for item in items:
        stripped = item.strip()
        if not stripped:
            continue
        # 첫 낱말을 **정확히** 비교한다. 앞머리 일치로 걸러면 CHECK으로 시작하는 열 이름
        # (reconciliation_record.checked_at)이 제약으로 오인되어 대조에서 조용히 빠진다.
        if stripped.split()[0].upper() in _NOT_A_COLUMN:
            continue
        columns.append(stripped.split()[0])
    return columns


@pytest.mark.parametrize(
    "table",
    sorted(METADATA.tables.values(), key=lambda item: item.name),
    ids=lambda table: table.name,
)
def test_columns_match_the_migration(table: Table) -> None:
    assert sorted(column.name for column in table.columns) == sorted(
        parse_migration_columns(table.name)
    )


class TestCodeReviewFixes:
    """코드리뷰(2026-09-11)에서 나온 지적을 막는 회귀 테스트."""

    def test_numpy_integers_are_accepted(self, published: MarketDataRepository) -> None:
        # 이 파트는 pandas 기반이고 이 모듈이 DataFrame에서 PostgreSQL로 가는 유일한 다리다.
        # numpy.int64는 int의 하위형이 아니라 형만 보면 거부된다.
        row = price_row(volume=np.int64(1_200_000), value=np.int64(42_000_000_000))
        assert published.load_daily_price(DATASET_ID, [row]) == 1

    def test_numpy_bools_are_accepted(self, published: MarketDataRepository) -> None:
        row = suspended_row(trading_suspended=np.bool_(True), price_carried_forward=np.bool_(True))
        assert published.load_daily_price(DATASET_ID, [row]) == 1

    def test_float_volume_is_still_rejected(self, published: MarketDataRepository) -> None:
        # iterrows()는 수치 행을 float64로 올린다. 거래대금처럼 큰 정수에서 값이 달라질 수
        # 있으므로 조용히 받지 않는다.
        with pytest.raises(RowValidationError):
            published.load_daily_price(DATASET_ID, [price_row(volume=1_200_000.0)])

    def test_dataset_id_must_match_the_deadline(self) -> None:
        # research.md 결정 4. 어긋난 채 발행되면 발행본이 불변이라 바로잡을 수 없다.
        with pytest.raises(DatasetVersionError):
            DatasetVersion(
                dataset_id="KRX_DAILY_20260912",
                data_deadline=deadline_for(DATASET_ID),
                collected_at=MOMENT,
                last_success_at=MOMENT,
                complete=True,
                is_sample=False,
                source=SOURCE_KRX_OPEN_API,
                map_version_id=1,
            )

    def test_missing_env_file_is_reported(self, tmp_path: Path) -> None:
        # 없는 경로를 조용히 무시하면 셸에 걸린 다른 DATABASE_URL로 적재된다.
        with pytest.raises(RepositoryError):
            database_url_from_env(tmp_path / "없는파일.env")

    def test_explicit_env_file_beats_the_environment(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        monkeypatch.setenv("DATABASE_URL", "postgresql://공용:x@shared:5432/thorp")
        env_file = tmp_path / ".env"
        env_file.write_text("DATABASE_URL=postgresql://local:x@localhost:5432/thorp\n", "utf-8")
        assert "localhost" in database_url_from_env(env_file)
