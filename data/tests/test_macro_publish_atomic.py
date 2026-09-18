"""파일 봉인/DB 포인터 경계와 밀린 발행의 실패 기록 (T042)."""

from __future__ import annotations

import json
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from decimal import Decimal
from pathlib import Path
from threading import Event

import pytest
from sqlalchemy import create_engine, text

from data_pipeline.macro.normalize import NormalizedObservation
from data_pipeline.macro.publish import (
    AtomicPublicationRequest,
    ConcurrentPublication,
    PublicationPointer,
    publish_atomically,
    switch_calculation_registry,
    switch_display_version,
)
from data_pipeline.macro.registry import INDICATORS, nominal_weights
from data_pipeline.macro.versioning import value_rule_fingerprint

TEST_INDICATORS = tuple(replace(indicator, series_start="2026-07") for indicator in INDICATORS)


def _observations(months: tuple[str, ...] = ("2026-07",)) -> dict[str, list[NormalizedObservation]]:
    return {
        indicator.indicator_id: [
            NormalizedObservation(
                indicator_id=indicator.indicator_id,
                period=month,
                transformed_value=Decimal("1.25"),
                missing_reason=None,
                raw_values=((month, Decimal("1.25")),),
                transform_rule=indicator.transform_rule,
                raw_unit=indicator.raw_unit,
                transformed_unit=indicator.transformed_unit,
                source_updated_at=None,
            )
            for month in months
        ]
        for indicator in TEST_INDICATORS
    }


@pytest.fixture
def publishing_fixture(tmp_path: Path):
    engine = create_engine(f"sqlite:///{tmp_path / 'macro.db'}")
    statements = (
        "CREATE TABLE macro_calculation_registry_version (id INTEGER PRIMARY KEY, "
        "value_rule_fingerprint TEXT, status TEXT)",
        "CREATE TABLE macro_display_version (id INTEGER PRIMARY KEY)",
        "CREATE TABLE macro_indicator_definition (registry_version_id INTEGER, "
        "indicator_id TEXT, field_id TEXT, nominal_weight TEXT)",
        "CREATE TABLE macro_collection_attempt (attempt_key TEXT PRIMARY KEY, mode TEXT, "
        "started_at TEXT, finished_at TEXT, status TEXT)",
        "CREATE TABLE macro_dataset_version (id INTEGER PRIMARY KEY AUTOINCREMENT, "
        "version_key TEXT UNIQUE, processing_registry_version_id INTEGER, "
        "value_rule_fingerprint TEXT, status TEXT, complete_indicator_count INTEGER, "
        "is_sample_data INTEGER, raw_manifest_hash TEXT, processed_manifest_hash TEXT, "
        "published_at TEXT, created_at TEXT)",
        "CREATE TABLE macro_observation (dataset_version_id INTEGER, indicator_id TEXT, "
        "target_month TEXT, raw_value TEXT, transformed_value TEXT, released_at TEXT, "
        "received_at TEXT, break_segment TEXT, missing_reason TEXT)",
        "CREATE TABLE macro_baseline (dataset_version_id INTEGER, registry_version_id INTEGER, "
        "scope_id TEXT, reference_month TEXT, reference_month_held_back_by TEXT, computed_at TEXT)",
        "CREATE TABLE macro_current_publication (singleton_key TEXT PRIMARY KEY, "
        "dataset_version_id INTEGER, registry_version_id INTEGER, display_version_id INTEGER, "
        "switched_at TEXT)",
    )
    with engine.begin() as connection:
        weights = nominal_weights(TEST_INDICATORS)
        for statement in statements:
            connection.execute(text(statement))
        connection.execute(
            text("INSERT INTO macro_calculation_registry_version VALUES "
                 "(1, :fingerprint, 'PUBLISHED')"),
            {"fingerprint": value_rule_fingerprint(TEST_INDICATORS)},
        )
        for indicator in TEST_INDICATORS:
            connection.execute(
                text("INSERT INTO macro_indicator_definition VALUES "
                     "(1, :indicator, :field, :weight)"),
                {"indicator": indicator.indicator_id, "field": indicator.field_id,
                 "weight": json.dumps(str(weights[indicator.indicator_id]))},
            )
        connection.execute(text("INSERT INTO macro_display_version VALUES (1)"))
        connection.execute(
            text("INSERT INTO macro_current_publication VALUES ('X', NULL, 1, 1, '2026-01-01')")
        )
    raw = tmp_path / "raw" / "macro" / "raw.json"
    processed = tmp_path / "processed" / "macro" / "part.parquet"
    raw.parent.mkdir(parents=True)
    processed.parent.mkdir(parents=True)
    raw.write_bytes(b"raw source")
    processed.write_bytes(b"processed parquet")
    request = AtomicPublicationRequest(
        version_key="macro-1",
        attempt_key="attempt-1",
        pointer=PublicationPointer(None, 1, 1),
        indicators=TEST_INDICATORS,
        observations=_observations(),
        raw_files=(raw,),
        processed_files=(processed,),
        data_root=tmp_path,
    )
    return engine, request


def test_failure_before_pointer_swap_rolls_back_snapshot_and_keeps_unreferenced_manifest(
    publishing_fixture,
) -> None:
    engine, request = publishing_fixture

    with pytest.raises(RuntimeError, match="injected"):
        publish_atomically(
            engine, request,
            before_pointer_swap=lambda: (_ for _ in ()).throw(RuntimeError("injected")),
        )

    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() is None
        assert (
            connection.execute(text("SELECT count(*) FROM macro_dataset_version")).scalar_one()
            == 0
        )
        assert connection.execute(text("SELECT count(*) FROM macro_observation")).scalar_one() == 0
        assert connection.execute(
            text("SELECT status FROM macro_collection_attempt WHERE attempt_key='attempt-1'")
        ).scalar_one() == "FAILED"
    assert (request.data_root / "processed/macro/manifests/macro-1.raw.json").exists()


def test_complete_snapshot_and_all_baselines_are_published_together(publishing_fixture) -> None:
    engine, request = publishing_fixture

    dataset_id = publish_atomically(engine, request)

    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() == dataset_id
        assert connection.execute(text("SELECT count(*) FROM macro_observation")).scalar_one() == 17
        assert connection.execute(text("SELECT count(*) FROM macro_baseline")).scalar_one() == 8


def test_later_processing_does_not_mutate_published_file(publishing_fixture) -> None:
    engine, request = publishing_fixture
    publish_atomically(engine, request)
    sealed = (
        request.data_root / "processed/macro/versions/macro-1/part.parquet"
    )
    request.processed_files[0].write_bytes(b"new processing")

    assert sealed.read_bytes() == b"processed parquet"


def test_stale_publisher_loses_and_is_recorded_failed(publishing_fixture) -> None:
    engine, request = publishing_fixture
    winner_id = publish_atomically(engine, request)
    loser = replace(request, version_key="macro-2", attempt_key="attempt-2")

    with pytest.raises(ConcurrentPublication):
        publish_atomically(engine, loser)

    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() == winner_id
        assert (
            connection.execute(text("SELECT count(*) FROM macro_dataset_version")).scalar_one()
            == 1
        )
        assert connection.execute(
            text("SELECT status FROM macro_collection_attempt WHERE attempt_key='attempt-2'")
        ).scalar_one() == "FAILED"


def test_two_parallel_publishers_produce_one_public_version(publishing_fixture) -> None:
    engine, first = publishing_fixture
    second = replace(first, version_key="macro-2", attempt_key="attempt-2")
    start = Event()

    def run(request: AtomicPublicationRequest):
        start.wait(timeout=5)
        try:
            return publish_atomically(engine, request)
        except ConcurrentPublication as error:
            return error

    with ThreadPoolExecutor(max_workers=2) as executor:
        futures = [executor.submit(run, request) for request in (first, second)]
        start.set()
        outcomes = [future.result(timeout=15) for future in futures]

    assert sum(isinstance(outcome, int) for outcome in outcomes) == 1
    assert sum(isinstance(outcome, ConcurrentPublication) for outcome in outcomes) == 1
    with engine.connect() as connection:
        assert (
            connection.execute(text("SELECT count(*) FROM macro_dataset_version")).scalar_one()
            == 1
        )
        statuses = connection.execute(
            text("SELECT status FROM macro_collection_attempt ORDER BY attempt_key")
        ).scalars().all()
        assert sorted(statuses) == ["FAILED", "SUCCEEDED"]


def test_mismatched_fingerprint_never_changes_pointer(publishing_fixture) -> None:
    engine, request = publishing_fixture
    changed = (replace(request.indicators[0], raw_unit="new unit"), *request.indicators[1:])
    mismatch = replace(request, indicators=changed)

    with pytest.raises(ValueError, match="지문"):
        publish_atomically(engine, mismatch)

    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() is None


def test_display_switch_only_changes_display_pointer(publishing_fixture) -> None:
    engine, request = publishing_fixture
    dataset_id = publish_atomically(engine, request)
    with engine.begin() as connection:
        connection.execute(text("INSERT INTO macro_display_version VALUES (2)"))

    switch_display_version(engine, PublicationPointer(dataset_id, 1, 1), 2)

    with engine.connect() as connection:
        pointer = connection.execute(
            text("SELECT dataset_version_id, registry_version_id, display_version_id "
                 "FROM macro_current_publication")
        ).one()
        assert pointer == (dataset_id, 1, 2)
        assert connection.execute(text("SELECT count(*) FROM macro_observation")).scalar_one() == 17


def test_mapping_switch_reuses_observations_and_recomputes_baselines(publishing_fixture) -> None:
    engine, request = publishing_fixture
    dataset_id = publish_atomically(engine, request)
    with engine.begin() as connection:
        connection.execute(
            text("INSERT INTO macro_calculation_registry_version VALUES "
                 "(2, :fingerprint, 'PUBLISHED')"),
            {"fingerprint": value_rule_fingerprint(TEST_INDICATORS)},
        )
        connection.execute(
            text("INSERT INTO macro_indicator_definition "
                 "SELECT 2, indicator_id, field_id, nominal_weight "
                 "FROM macro_indicator_definition WHERE registry_version_id=1")
        )

    switch_calculation_registry(engine, PublicationPointer(dataset_id, 1, 1), 2, TEST_INDICATORS)

    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT registry_version_id FROM macro_current_publication")
        ).scalar_one() == 2
        assert (
            connection.execute(text("SELECT count(*) FROM macro_dataset_version")).scalar_one()
            == 1
        )
        assert connection.execute(text("SELECT count(*) FROM macro_observation")).scalar_one() == 17
        assert connection.execute(
            text("SELECT count(*) FROM macro_baseline WHERE registry_version_id=2")
        ).scalar_one() == 8


def test_incomplete_initial_history_is_rejected(publishing_fixture) -> None:
    engine, request = publishing_fixture
    incomplete = replace(
        request,
        indicators=tuple(replace(i, series_start="2026-06") for i in request.indicators),
    )
    with pytest.raises(ValueError, match="빠진 월"):
        publish_atomically(engine, incomplete)
    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT status FROM macro_collection_attempt WHERE attempt_key='attempt-1'")
        ).scalar_one() == "FAILED"
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() is None


def test_later_snapshot_cannot_drop_a_previously_published_month(publishing_fixture) -> None:
    engine, request = publishing_fixture
    first = replace(request, observations=_observations(("2026-07", "2026-08")))
    dataset_id = publish_atomically(engine, first)
    second = replace(
        request, version_key="macro-2", attempt_key="attempt-2",
        pointer=PublicationPointer(dataset_id, 1, 1),
    )
    with pytest.raises(ValueError, match="기존 가용 월"):
        publish_atomically(engine, second)
    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() == dataset_id


def test_publish_rejects_field_mapping_not_saved_in_registry(publishing_fixture) -> None:
    engine, request = publishing_fixture
    changed = (
        replace(request.indicators[0], field_id="overseas"),
        *request.indicators[1:],
    )
    with pytest.raises(ValueError, match="분야·가중"):
        publish_atomically(engine, replace(request, indicators=changed))


def test_registry_switch_rejects_weight_mismatch(publishing_fixture) -> None:
    engine, request = publishing_fixture
    dataset_id = publish_atomically(engine, request)
    with engine.begin() as connection:
        connection.execute(
            text("INSERT INTO macro_calculation_registry_version VALUES "
                 "(2, :fingerprint, 'PUBLISHED')"),
            {"fingerprint": value_rule_fingerprint(TEST_INDICATORS)},
        )
        connection.execute(
            text("INSERT INTO macro_indicator_definition "
                 "SELECT 2, indicator_id, field_id, nominal_weight "
                 "FROM macro_indicator_definition WHERE registry_version_id=1")
        )
        connection.execute(
            text("UPDATE macro_indicator_definition SET nominal_weight=:weight "
                 "WHERE registry_version_id=2 AND indicator_id=:indicator"),
            {"weight": json.dumps("0.9"), "indicator": TEST_INDICATORS[0].indicator_id},
        )
    with pytest.raises(ValueError, match="분야·가중"):
        switch_calculation_registry(
            engine, PublicationPointer(dataset_id, 1, 1), 2, TEST_INDICATORS
        )
    with engine.connect() as connection:
        assert connection.execute(
            text("SELECT registry_version_id FROM macro_current_publication")
        ).scalar_one() == 1
