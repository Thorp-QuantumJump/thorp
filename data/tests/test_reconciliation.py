"""공시 대조와 원인별 처리 (spec 001 FR-016·FR-017·SC-002).

실제 KRX OPEN API와 PostgreSQL 없이 돌린다. 표본 구성·원인 분류·처리 규칙은 모두 파이썬 계층의
규칙이고, 적재는 인메모리 SQLite에 마이그레이션과 같은 열로 표를 만들어 확인한다. DB의
CHECK·FK·트리거는 마이그레이션이 원본이며 `backend`의 `EtfMarketDataSchemaIT`가 PostgreSQL
컨테이너로 검증한다.
"""

from __future__ import annotations

import dataclasses
import importlib.util
import json
import re
from collections.abc import Iterator
from datetime import UTC, date, datetime
from decimal import Decimal
from pathlib import Path
from types import ModuleType
from typing import Any

import pytest
from sqlalchemy import create_engine, func, select

from data_pipeline.corporate_actions import CorporateAction
from data_pipeline.dataset_version import (
    SOURCE_KRX_OPEN_API,
    DatasetAlreadyLoadedError,
    DatasetNotPublishedError,
    DatasetVersion,
    dataset_deadline_date,
)
from data_pipeline.gaps import REASON_SUSPENDED_TOO_LONG
from data_pipeline.krx import ListingBoundaryError, ListingWindow
from data_pipeline.reconciliation import (
    CAUSE_DEFINITION_DIFF,
    CAUSE_MATCH,
    CAUSE_PIPELINE_ERROR,
    CAUSE_UNKNOWN,
    FIELD_AMOUNT,
    FIELD_LISTED_ON,
    FIELD_PAY_DATE,
    RESOLUTION_NONE,
    RESOLUTION_RANGE_EXCLUDED,
    RESOLUTION_RECORDED_ONLY,
    RESOLUTION_REPUBLISHED,
    SAMPLE_DISTRIBUTION,
    SAMPLE_LISTING,
    SAMPLE_SPLIT,
    ReconciliationError,
    ReconciliationRecord,
    ReconciliationTarget,
    build_queried_values,
    build_samples,
    reconcile,
    resolution_for,
    sc002_verdict,
)
from data_pipeline.repository import (
    METADATA,
    MarketDataRepository,
    RepresentativeEtfNotFoundError,
    RowValidationError,
    reconciliation_record_table,
    representative_etf_table,
)

DATASET_ID = "KRX_DAILY_20260911"
INSTRUMENT_ID = "069500.XKRX"
OTHER_INSTRUMENT_ID = "091160.XKRX"
LISTED_ON = date(2002, 10, 14)
EX_DATE = date(2026, 4, 29)
PAY_DATE = date(2026, 5, 8)
SPLIT_DATE = date(2026, 6, 1)
ORDINARY_DAY = date(2026, 5, 11)
CHECKED_AT = datetime(2026, 9, 11, 18, 0, tzinfo=UTC)
MOMENT = CHECKED_AT

REPO_ROOT = Path(__file__).resolve().parents[2]
# 파일명을 고정하지 않고 찾는다. 마이그레이션 버전이 타임스탬프라 이름이 바뀔 수 있는데,
# 고정해 두면 이름이 달라진 순간 대조가 조용히 건너뛰어져 표류를 놓친다.
_MIGRATIONS = sorted(
    (REPO_ROOT / "backend/src/main/resources/db/migration").glob("V*__etf_market_data.sql")
)
MIGRATION = _MIGRATIONS[-1] if _MIGRATIONS else None

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "scripts/reconcile.py"


# ---------------------------------------------------------------------------
# 표본과 값 만들기
# ---------------------------------------------------------------------------


def window(instrument_id: str = INSTRUMENT_ID, listed_on: date = LISTED_ON) -> ListingWindow:
    return ListingWindow(instrument_id=instrument_id, listed_on=listed_on)


def distribution(
    instrument_id: str = INSTRUMENT_ID,
    ex_date: date = EX_DATE,
    pay_date: date = PAY_DATE,
    amount: str = "120",
) -> CorporateAction:
    return CorporateAction(
        instrument_id=instrument_id,
        ex_date=ex_date,
        event_type="DISTRIBUTION",
        amount=Decimal(amount),
        pay_date=pay_date,
    )


def split(
    instrument_id: str = INSTRUMENT_ID, ex_date: date = SPLIT_DATE, ratio: str = "2"
) -> CorporateAction:
    return CorporateAction(
        instrument_id=instrument_id,
        ex_date=ex_date,
        event_type="SPLIT",
        amount=Decimal(ratio),
    )


def make_record(**overrides: Any) -> ReconciliationRecord:
    values: dict[str, Any] = {
        "instrument_id": INSTRUMENT_ID,
        "target_date": PAY_DATE,
        "sample_reason": SAMPLE_DISTRIBUTION,
        "field_name": FIELD_AMOUNT,
        "queried_value": "120",
        "disclosed_value": "120",
        "difference": None,
        "cause": CAUSE_MATCH,
        "checked_at": CHECKED_AT,
    }
    values.update(overrides)
    return ReconciliationRecord(**values)


# ---------------------------------------------------------------------------
# 저장소 대역
# ---------------------------------------------------------------------------


def make_version(dataset_id: str = DATASET_ID) -> DatasetVersion:
    day = dataset_deadline_date(dataset_id)
    deadline = datetime(day.year, day.month, day.day, 18, 0, tzinfo=UTC)
    return DatasetVersion(
        dataset_id=dataset_id,
        data_deadline=deadline,
        collected_at=MOMENT,
        last_success_at=MOMENT,
        complete=True,
        is_sample=False,
        source=SOURCE_KRX_OPEN_API,
        map_version_id=1,
    )


@pytest.fixture
def repository() -> Iterator[MarketDataRepository]:
    engine = create_engine("sqlite://")
    METADATA.create_all(engine)
    try:
        yield MarketDataRepository(engine)
    finally:
        engine.dispose()


def insert_representative_etf(
    repository: MarketDataRepository, instrument_id: str = INSTRUMENT_ID
) -> None:
    """대표 ETF 행을 넣는다. 이 표는 대응표 쪽이 적재하고 적재 계층은 읽기만 한다."""
    with repository.engine.begin() as conn:
        conn.execute(
            representative_etf_table.insert().values(
                instrument_id=instrument_id,
                name="KODEX 200",
                listed_on=LISTED_ON,
                benchmark_index="코스피 200",
                manager="삼성자산운용",
                leveraged=False,
                inverse=False,
            )
        )


@pytest.fixture
def published(repository: MarketDataRepository) -> MarketDataRepository:
    repository.publish_dataset_version(make_version())
    insert_representative_etf(repository)
    return repository


def count_records(repository: MarketDataRepository) -> int:
    with repository.engine.connect() as conn:
        return int(
            conn.execute(select(func.count()).select_from(reconciliation_record_table)).scalar_one()
        )


# ---------------------------------------------------------------------------
# 표본 구성 (FR-016)
# ---------------------------------------------------------------------------


class TestSamples:
    def test_listing_distribution_and_split_are_sampled(self) -> None:
        """표본은 상장 첫날·분배금 지급일·분할일이다."""
        targets = build_samples([window()], [distribution(), split()])
        assert [(t.target_date, t.sample_reason) for t in targets] == [
            (LISTED_ON, SAMPLE_LISTING),
            (PAY_DATE, SAMPLE_DISTRIBUTION),
            (SPLIT_DATE, SAMPLE_SPLIT),
        ]

    def test_ordinary_trading_day_is_not_sampled(self) -> None:
        """평범한 거래일은 표본에 없다. 거래소가 원본인 값을 거래소와 대조할 실익이 없다."""
        targets = build_samples([window()], [distribution(), split()])
        assert ORDINARY_DAY not in {target.target_date for target in targets}

    def test_distribution_is_sampled_on_the_pay_date(self) -> None:
        """분배금 표본의 날짜는 지급일이다. 락일은 004가 다른 계산에 쓰는 다른 날이다."""
        targets = build_samples([window()], [distribution()])
        dates = {target.target_date for target in targets if target.sample_reason == "DISTRIBUTION"}
        assert dates == {PAY_DATE}
        assert EX_DATE not in dates

    def test_split_is_sampled_on_the_ex_date(self) -> None:
        targets = build_samples([window()], [split()])
        assert [t.target_date for t in targets if t.sample_reason == SAMPLE_SPLIT] == [SPLIT_DATE]

    def test_samples_are_sorted_and_deduplicated(self) -> None:
        """같은 (종목, 날짜, 사유)는 한 번만 들어간다."""
        actions = [distribution(), distribution(ex_date=date(2026, 4, 30))]
        targets = build_samples([window(OTHER_INSTRUMENT_ID), window()], actions)
        keys = [target.key for target in targets]
        assert keys == sorted(keys)
        assert len(keys) == len(set(keys))
        assert len([key for key in keys if key[2] == SAMPLE_DISTRIBUTION]) == 1

    def test_action_without_a_listing_window_is_rejected(self) -> None:
        """상장 구간이 없는 종목의 기업행위는 버리지 않고 알린다. 버리면 표본이 준다."""
        with pytest.raises(ReconciliationError, match="상장 구간이 없는"):
            build_samples([window()], [distribution(instrument_id=OTHER_INSTRUMENT_ID)])

    def test_action_before_listing_is_rejected(self) -> None:
        with pytest.raises(ReconciliationError, match="상장 전"):
            build_samples([window(listed_on=date(2026, 6, 15))], [split()])

    def test_duplicate_listing_window_is_rejected(self) -> None:
        with pytest.raises(ListingBoundaryError):
            build_samples([window(), window()])

    def test_queried_values_come_from_the_pipeline_inputs(self) -> None:
        """조회값은 대응표와 기업행위 입력에서 뽑는다. DB에서 읽어 되돌리지 않는다."""
        queried = build_queried_values([window()], [distribution(), split()])
        assert queried[(INSTRUMENT_ID, LISTED_ON, SAMPLE_LISTING)] == {
            FIELD_LISTED_ON: "2002-10-14"
        }
        assert queried[(INSTRUMENT_ID, PAY_DATE, SAMPLE_DISTRIBUTION)] == {
            FIELD_AMOUNT: "120",
            FIELD_PAY_DATE: "2026-05-08",
        }
        assert queried[(INSTRUMENT_ID, SPLIT_DATE, SAMPLE_SPLIT)][FIELD_AMOUNT] == "2"


# ---------------------------------------------------------------------------
# cause ↔ resolution 조합 (FR-017)
# ---------------------------------------------------------------------------


class TestCauseAndResolution:
    @pytest.mark.parametrize(
        ("cause", "resolution"),
        [
            (CAUSE_MATCH, RESOLUTION_NONE),
            (CAUSE_PIPELINE_ERROR, RESOLUTION_REPUBLISHED),
            (CAUSE_DEFINITION_DIFF, RESOLUTION_RECORDED_ONLY),
            (CAUSE_UNKNOWN, RESOLUTION_RANGE_EXCLUDED),
        ],
    )
    def test_the_four_pairs_are_accepted(self, cause: str, resolution: str) -> None:
        assert resolution_for(cause) == resolution
        mismatch = {} if cause == CAUSE_MATCH else {"disclosed_value": "118"}
        record = make_record(cause=cause, resolution=resolution, **mismatch)
        assert record.resolution == resolution

    @pytest.mark.parametrize(
        ("cause", "resolution"),
        [
            (CAUSE_UNKNOWN, RESOLUTION_RECORDED_ONLY),
            (CAUSE_PIPELINE_ERROR, RESOLUTION_NONE),
            (CAUSE_MATCH, RESOLUTION_RANGE_EXCLUDED),
            (CAUSE_DEFINITION_DIFF, RESOLUTION_REPUBLISHED),
            (CAUSE_UNKNOWN, "WHATEVER"),
        ],
    )
    def test_other_combinations_are_rejected(self, cause: str, resolution: str) -> None:
        mismatch = {} if cause == CAUSE_MATCH else {"disclosed_value": "118"}
        with pytest.raises(ReconciliationError, match="조합"):
            make_record(cause=cause, resolution=resolution, **mismatch)

    def test_resolution_is_derived_when_omitted(self) -> None:
        """두 값을 각각 받으면 어긋난 조합을 만들 여지가 생기므로 cause에서 유도한다."""
        assert make_record(cause=CAUSE_UNKNOWN, disclosed_value="118").resolution == (
            RESOLUTION_RANGE_EXCLUDED
        )

    def test_unknown_cause_is_rejected(self) -> None:
        with pytest.raises(ReconciliationError, match="cause"):
            make_record(cause="TYPO", disclosed_value="118")

    def test_match_with_different_values_is_rejected(self) -> None:
        """일치로 기록되었는데 값이 다르면 SC-002 판정이 조용히 틀린다."""
        with pytest.raises(ReconciliationError, match="MATCH"):
            make_record(cause=CAUSE_MATCH, queried_value="120", disclosed_value="118")

    def test_mismatch_with_equal_values_is_rejected(self) -> None:
        with pytest.raises(ReconciliationError, match="같다"):
            make_record(cause=CAUSE_UNKNOWN, queried_value="120", disclosed_value="120")

    def test_digit_formatting_is_not_a_difference(self) -> None:
        """``120``과 ``120.00000000``은 표기만 다르다. 문자열로만 보면 구간 제외로 떨어진다."""
        record = make_record(queried_value="120.00000000", disclosed_value="120")
        assert record.cause == CAUSE_MATCH

    def test_blank_value_cannot_be_recorded(self) -> None:
        with pytest.raises(ReconciliationError, match="disclosed_value"):
            make_record(cause=CAUSE_MATCH, queried_value="120", disclosed_value="   ")

    def test_checked_at_needs_a_timezone(self) -> None:
        with pytest.raises(ReconciliationError, match="시간대"):
            make_record(checked_at=datetime(2026, 9, 11, 18, 0))

    def test_record_is_frozen(self) -> None:
        """기록을 만든 뒤 값을 고치는 경로가 없다 (FR-017)."""
        record = make_record()
        with pytest.raises(dataclasses.FrozenInstanceError):
            record.cause = CAUSE_PIPELINE_ERROR  # type: ignore[misc]


# ---------------------------------------------------------------------------
# 대조와 원인 분류
# ---------------------------------------------------------------------------


def run_reconcile(
    disclosed_amount: str = "118",
    *,
    definition_diff_fields: tuple[str, ...] = (),
    pipeline_error_fields: tuple[str, ...] = (),
) -> Any:
    """분배금 표본 하나를 대조한다. 조회값 120에 공시값을 달리 준다."""
    windows = [window()]
    actions = [distribution(amount="120")]
    targets = build_samples(windows, actions)
    queried = build_queried_values(windows, actions)
    disclosed = {
        (INSTRUMENT_ID, LISTED_ON, SAMPLE_LISTING): {FIELD_LISTED_ON: "2002-10-14"},
        (INSTRUMENT_ID, PAY_DATE, SAMPLE_DISTRIBUTION): {
            FIELD_AMOUNT: disclosed_amount,
            FIELD_PAY_DATE: "2026-05-08",
        },
    }
    return reconcile(
        targets,
        queried,
        disclosed,
        definition_diff_fields=definition_diff_fields,
        pipeline_error_fields=pipeline_error_fields,
        checked_at=CHECKED_AT,
    )


class TestReconcile:
    def test_equal_values_are_a_match(self) -> None:
        outcome = run_reconcile("120")
        assert [record.cause for record in outcome.records] == [CAUSE_MATCH] * 3
        assert outcome.republish_required == ()
        assert outcome.excluded_ranges == ()

    def test_unclassified_mismatch_defaults_to_unknown(self) -> None:
        """분류하지 않은 불일치는 가장 보수적인 UNKNOWN(구간 제외)이다."""
        outcome = run_reconcile("118")
        mismatched = outcome.mismatched
        assert [record.cause for record in mismatched] == [CAUSE_UNKNOWN]
        assert [record.resolution for record in mismatched] == [RESOLUTION_RANGE_EXCLUDED]
        assert mismatched[0].difference == "120 - 118 = 2"

    def test_definition_diff_is_recorded_only(self) -> None:
        outcome = run_reconcile("118", definition_diff_fields=(FIELD_AMOUNT,))
        assert [record.resolution for record in outcome.mismatched] == [RESOLUTION_RECORDED_ONLY]
        assert outcome.excluded_ranges == ()
        assert outcome.republish_required == ()

    def test_pipeline_error_requires_a_new_dataset_version(self) -> None:
        """수집·가공 오류는 값을 고치지 않고 새 자료 버전으로 재발행한다 (FR-017)."""
        outcome = run_reconcile("118", pipeline_error_fields=(FIELD_AMOUNT,))
        assert len(outcome.republish_required) == 1
        record = outcome.republish_required[0]
        assert record.resolution == RESOLUTION_REPUBLISHED
        # 값은 그대로 남는다. 개별 값을 고치는 경로가 없다.
        assert (record.queried_value, record.disclosed_value) == ("120", "118")
        assert outcome.excluded_ranges == ()

    def test_module_has_no_value_fixing_entry_point(self) -> None:
        """FR-017. '고친다'는 뜻의 공개 함수가 없어야 한다."""
        import data_pipeline.reconciliation as module

        suspicious = [
            name
            for name in dir(module)
            if not name.startswith("_")
            and any(word in name.lower() for word in ("fix", "repair", "correct", "overwrite"))
        ]
        assert suspicious == []

    def test_overlapping_classifications_are_rejected(self) -> None:
        with pytest.raises(ReconciliationError, match="같은 항목"):
            run_reconcile(
                "118",
                definition_diff_fields=(FIELD_AMOUNT,),
                pipeline_error_fields=(FIELD_AMOUNT,),
            )

    def test_duplicate_target_is_rejected(self) -> None:
        target = ReconciliationTarget(INSTRUMENT_ID, PAY_DATE, SAMPLE_DISTRIBUTION)
        with pytest.raises(ReconciliationError, match="두 번"):
            reconcile([target, target], {}, {}, checked_at=CHECKED_AT)

    def test_sample_without_disclosure_is_reported_not_skipped(self) -> None:
        """공시값을 못 구한 것은 대조를 하지 않은 것이다. 건너뛰면 분모가 조용히 줄어든다."""
        targets = build_samples([window()], [distribution()])
        queried = build_queried_values([window()], [distribution()])
        outcome = reconcile(targets, queried, {}, checked_at=CHECKED_AT)
        assert outcome.records == ()
        assert len(outcome.unreconciled) == 2
        assert all("공시값" in item.reason for item in outcome.unreconciled)

    def test_unpaired_field_is_reported(self) -> None:
        """항목 이름이 짝지어지지 않으면 그 항목은 대조하지 않은 것이다."""
        targets = [ReconciliationTarget(INSTRUMENT_ID, PAY_DATE, SAMPLE_DISTRIBUTION)]
        key = (INSTRUMENT_ID, PAY_DATE, SAMPLE_DISTRIBUTION)
        outcome = reconcile(
            targets,
            {key: {FIELD_AMOUNT: "120"}},
            {key: {FIELD_AMOUNT: "120", "net_asset_value": "35000"}},
            checked_at=CHECKED_AT,
        )
        assert len(outcome.records) == 1
        assert outcome.unreconciled[0].fields == ("net_asset_value",)


class TestExcludedRanges:
    def multi_field_outcome(self) -> Any:
        """같은 종목·같은 날짜에서 두 항목이 어긋난 대조."""
        targets = [ReconciliationTarget(INSTRUMENT_ID, PAY_DATE, SAMPLE_DISTRIBUTION)]
        key = (INSTRUMENT_ID, PAY_DATE, SAMPLE_DISTRIBUTION)
        return reconcile(
            targets,
            {key: {FIELD_AMOUNT: "120", FIELD_PAY_DATE: "2026-05-08"}},
            {key: {FIELD_AMOUNT: "118", FIELD_PAY_DATE: "2026-05-11"}},
            checked_at=CHECKED_AT,
        )

    def test_range_excluded_becomes_a_one_day_gap(self) -> None:
        outcome = run_reconcile("118")
        assert outcome.excluded_ranges == (
            {
                "instrument_id": INSTRUMENT_ID,
                "start_date": PAY_DATE,
                "end_date": PAY_DATE,
                "reason": "RECONCILIATION_UNKNOWN",
            },
        )

    def test_two_mismatched_fields_merge_into_one_gap_row(self) -> None:
        """data_gap의 PK가 (dataset_id, instrument_id, start_date, reason)이라 행이 합쳐진다."""
        outcome = self.multi_field_outcome()
        assert len(outcome.mismatched) == 2
        assert len(outcome.excluded_ranges) == 1
        assert outcome.excluded_ranges[0]["start_date"] == PAY_DATE

    def test_only_reconciliation_unknown_is_produced(self) -> None:
        """이 모듈이 만드는 자료 부족 구간의 사유는 RECONCILIATION_UNKNOWN뿐이다."""
        outcome = self.multi_field_outcome()
        reasons = {row["reason"] for row in outcome.excluded_ranges}
        assert reasons == {"RECONCILIATION_UNKNOWN"}
        assert REASON_SUSPENDED_TOO_LONG not in reasons

    def test_no_gap_is_made_for_other_resolutions(self) -> None:
        outcome = run_reconcile("118", definition_diff_fields=(FIELD_AMOUNT,))
        assert outcome.excluded_ranges == ()


# ---------------------------------------------------------------------------
# SC-002 판정
# ---------------------------------------------------------------------------


class TestSc002:
    def test_all_matched_is_satisfied(self) -> None:
        verdict = sc002_verdict(run_reconcile("120"))
        assert verdict.satisfied is True
        assert (verdict.matched, verdict.mismatched) == (3, 0)
        assert verdict.sample_count == 2

    def test_mismatch_with_recorded_cause_is_satisfied(self) -> None:
        """불일치가 있어도 원인 분류와 처리가 기록에 남으면 SC-002를 충족한다."""
        verdict = sc002_verdict(run_reconcile("118"))
        assert verdict.satisfied is True
        assert verdict.mismatched == 1
        assert verdict.cause_counts[CAUSE_UNKNOWN] == 1

    def test_pipeline_error_is_reported_in_the_verdict(self) -> None:
        verdict = sc002_verdict(run_reconcile("118", pipeline_error_fields=(FIELD_AMOUNT,)))
        assert verdict.republish_required is True

    def test_unreconciled_sample_makes_the_verdict_false(self) -> None:
        targets = build_samples([window()], [distribution()])
        queried = build_queried_values([window()], [distribution()])
        verdict = sc002_verdict(reconcile(targets, queried, {}, checked_at=CHECKED_AT))
        assert verdict.satisfied is False
        assert verdict.unreconciled_sample_count == 2
        assert "대조하지 않은 표본" in verdict.detail


# ---------------------------------------------------------------------------
# 적재
# ---------------------------------------------------------------------------


class TestLoad:
    def test_records_are_written_with_resolved_identifiers(
        self, published: MarketDataRepository
    ) -> None:
        outcome = run_reconcile("118")
        assert published.load_reconciliation_records(DATASET_ID, outcome.records) == 3
        assert count_records(published) == 3
        with published.engine.connect() as conn:
            row = conn.execute(
                select(
                    reconciliation_record_table.c.dataset_version_id,
                    reconciliation_record_table.c.etf_id,
                    reconciliation_record_table.c.cause,
                    reconciliation_record_table.c.resolution,
                ).where(reconciliation_record_table.c.cause == CAUSE_UNKNOWN)
            ).one()
        assert row.dataset_version_id == 1
        assert row.etf_id == 1
        assert row.resolution == RESOLUTION_RANGE_EXCLUDED

    def test_unpublished_dataset_is_rejected(self, repository: MarketDataRepository) -> None:
        insert_representative_etf(repository)
        with pytest.raises(DatasetNotPublishedError):
            repository.load_reconciliation_records(DATASET_ID, [make_record()])
        assert count_records(repository) == 0

    def test_reloading_the_same_dataset_is_rejected(self, published: MarketDataRepository) -> None:
        """발행된 자료는 바뀌지 않으므로 같은 자료 버전의 대조 결과도 같다 (FR-017)."""
        published.load_reconciliation_records(DATASET_ID, [make_record()])
        with pytest.raises(DatasetAlreadyLoadedError):
            published.load_reconciliation_records(
                DATASET_ID, [make_record(field_name=FIELD_PAY_DATE)]
            )
        assert count_records(published) == 1

    def test_missing_representative_etf_is_reported(self, repository: MarketDataRepository) -> None:
        """FK 위반 메시지만으로는 무엇이 없는지 알 수 없다."""
        repository.publish_dataset_version(make_version())
        with pytest.raises(RepresentativeEtfNotFoundError, match=INSTRUMENT_ID):
            repository.load_reconciliation_records(DATASET_ID, [make_record()])
        assert count_records(repository) == 0

    def test_duplicate_record_is_rejected(self, published: MarketDataRepository) -> None:
        with pytest.raises(RowValidationError, match="두 번"):
            published.load_reconciliation_records(DATASET_ID, [make_record(), make_record()])
        assert count_records(published) == 0

    def test_plain_mapping_is_rejected(self, published: MarketDataRepository) -> None:
        """조합 검사를 거친 값만 적재한다."""
        with pytest.raises(RowValidationError, match="ReconciliationRecord"):
            published.load_reconciliation_records(DATASET_ID, [make_record().to_row()])

    def test_empty_load_writes_nothing(self, published: MarketDataRepository) -> None:
        assert published.load_reconciliation_records(DATASET_ID, []) == 0
        assert count_records(published) == 0


def parse_migration_columns(table_name: str) -> list[str]:
    """마이그레이션에서 한 표의 열 이름을 순서대로 읽는다. 코드와 스키마의 표류를 잡는다."""
    assert MIGRATION is not None, (
        "V*__etf_market_data.sql을 찾지 못했다. 마이그레이션이 사라졌거나 이름 규칙이 바뀌었다면"
        " 이 대조를 건너뛰지 말고 여기를 고쳐야 한다"
    )
    sql = MIGRATION.read_text(encoding="utf-8")
    match = re.search(rf"CREATE TABLE {table_name} \((.*?)\n\);", sql, flags=re.DOTALL)
    assert match is not None, f"{table_name} 정의를 찾지 못했다"
    columns: list[str] = []
    for line in match.group(1).splitlines():
        stripped = line.split("--")[0].strip()
        if not stripped or stripped[0] in "()'":
            continue
        first = stripped.split()[0]
        # 첫 낱말이 제약 키워드와 **정확히** 같을 때만 건너뛴다. 앞머리만 보고 자르면
        # ``checked_at``이 ``CHECK``으로 시작해 열 하나가 조용히 빠진다.
        if first.upper() in {"CONSTRAINT", "CHECK", "PRIMARY", "UNIQUE", "FOREIGN"}:
            continue
        columns.append(first)
    return columns


@pytest.mark.parametrize(
    "table",
    [reconciliation_record_table, representative_etf_table],
    ids=lambda table: table.name,
)
def test_columns_match_the_migration(table: Any) -> None:
    """열 이름과 순서가 마이그레이션과 같아야 INSERT가 깨지지 않는다."""
    assert [column.name for column in table.columns] == parse_migration_columns(table.name)


# ---------------------------------------------------------------------------
# 진입점 (quickstart 시나리오 5)
# ---------------------------------------------------------------------------


def load_script() -> ModuleType:
    """``scripts/reconcile.py``를 파일 경로로 읽어 온다 (scripts는 꾸러미가 아니다)."""
    spec = importlib.util.spec_from_file_location("reconcile_entrypoint", SCRIPT_PATH)
    assert spec is not None and spec.loader is not None, f"진입점을 읽을 수 없다: {SCRIPT_PATH}"
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_inputs(tmp_path: Path, disclosed_amount: str) -> list[str]:
    (tmp_path / "listing.json").write_text(
        json.dumps([{"instrument_id": INSTRUMENT_ID, "listed_on": "2002-10-14"}]),
        encoding="utf-8",
    )
    (tmp_path / "actions.json").write_text(
        json.dumps(
            [
                {
                    "instrument_id": INSTRUMENT_ID,
                    "ex_date": "2026-04-29",
                    "pay_date": "2026-05-08",
                    "event_type": "DISTRIBUTION",
                    "amount": "120",
                }
            ]
        ),
        encoding="utf-8",
    )
    (tmp_path / "disclosure.json").write_text(
        json.dumps(
            [
                {
                    "instrument_id": INSTRUMENT_ID,
                    "target_date": "2002-10-14",
                    "sample_reason": "LISTING",
                    "values": {"listed_on": "2002-10-14"},
                },
                {
                    "instrument_id": INSTRUMENT_ID,
                    "target_date": "2026-05-08",
                    "sample_reason": "DISTRIBUTION",
                    "values": {"amount": disclosed_amount, "pay_date": "2026-05-08"},
                },
            ]
        ),
        encoding="utf-8",
    )
    return [
        "--dataset",
        DATASET_ID,
        "--listing",
        str(tmp_path / "listing.json"),
        "--corporate-actions",
        str(tmp_path / "actions.json"),
        "--disclosure",
        str(tmp_path / "disclosure.json"),
    ]


class TestEntryPoint:
    def test_all_matched_exits_zero(self, tmp_path: Path, capsys: pytest.CaptureFixture) -> None:
        script = load_script()
        assert script.main(write_inputs(tmp_path, "120")) == 0
        output = capsys.readouterr().out
        assert "SC-002: 충족" in output
        assert "적재: 건너뜀" in output

    def test_pipeline_error_exits_nonzero(
        self, tmp_path: Path, capsys: pytest.CaptureFixture
    ) -> None:
        """재발행이 필요한 상태를 성공으로 끝내면 그 자료 버전이 그대로 쓰인다."""
        script = load_script()
        argv = [*write_inputs(tmp_path, "118"), "--pipeline-error-fields", "amount"]
        assert script.main(argv) == 1
        assert "재발행 필요" in capsys.readouterr().out

    def test_unreconciled_sample_exits_nonzero(
        self, tmp_path: Path, capsys: pytest.CaptureFixture
    ) -> None:
        """공시값을 못 구한 표본이 있으면 성공으로 끝내지 않는다 (SC-002)."""
        script = load_script()
        argv = write_inputs(tmp_path, "120")
        (tmp_path / "disclosure.json").write_text(
            json.dumps(
                [
                    {
                        "instrument_id": INSTRUMENT_ID,
                        "target_date": "2002-10-14",
                        "sample_reason": "LISTING",
                        "values": {"listed_on": "2002-10-14"},
                    }
                ]
            ),
            encoding="utf-8",
        )
        assert script.main(argv) == 2
        assert "SC-002: 미충족" in capsys.readouterr().out

    def test_disclosure_for_a_non_sample_day_is_reported(
        self, tmp_path: Path, capsys: pytest.CaptureFixture
    ) -> None:
        """평범한 거래일의 공시값은 대조에 쓰이지 않는다. 조용히 버리지 않고 알린다."""
        script = load_script()
        argv = write_inputs(tmp_path, "120")
        records = json.loads((tmp_path / "disclosure.json").read_text(encoding="utf-8"))
        records.append(
            {
                "instrument_id": INSTRUMENT_ID,
                "target_date": ORDINARY_DAY.isoformat(),
                "sample_reason": "DISTRIBUTION",
                "values": {"amount": "120"},
            }
        )
        (tmp_path / "disclosure.json").write_text(json.dumps(records), encoding="utf-8")
        assert script.main(argv) == 0
        output = capsys.readouterr().out
        assert "표본에 없어 쓰지 않은 공시값 1건" in output
        assert str(ORDINARY_DAY) in output
