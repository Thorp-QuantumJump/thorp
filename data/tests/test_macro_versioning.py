"""세 버전의 독립성과 값 규칙 지문 (T036·T037·T041)."""

from dataclasses import replace
from decimal import Decimal

from sqlalchemy import create_engine, text

from data_pipeline.macro.registry import (
    FIELD_IDS,
    INDICATORS,
    NOMINAL_WEIGHT_POLICY,
    nominal_weights,
)
from data_pipeline.macro.versioning import (
    VersionNumbers,
    initialize_current_publication,
    plan_change,
    register_calculation_version,
    register_display_version,
    value_rule_fingerprint,
)


def test_display_only_changes_display_version() -> None:
    before = VersionNumbers(1, 1, 1)
    change = plan_change(
        before,
        INDICATORS,
        INDICATORS,
        nominal_weights(),
        nominal_weights(),
        {"production": "생산"},
        {"production": "산업 생산"},
    )

    assert change.versions == VersionNumbers(2, 1, 1)
    assert not change.reprocess_all_months
    assert not change.recompute_baselines


def test_mapping_or_weight_changes_only_calculation_registry() -> None:
    before = VersionNumbers(1, 1, 1)
    weights = nominal_weights()
    changed = dict(weights)
    changed[INDICATORS[0].indicator_id] += Decimal("0.01")
    change = plan_change(before, INDICATORS, INDICATORS, weights, changed, {}, {})

    assert change.versions == VersionNumbers(1, 2, 1)
    assert not change.reprocess_all_months
    assert change.recompute_baselines
    assert change.reuse_observations


def test_field_mapping_change_keeps_dataset_version() -> None:
    moved = (replace(INDICATORS[0], field_id="labor"), *INDICATORS[1:])

    change = plan_change(
        VersionNumbers(1, 1, 1), INDICATORS, moved,
        nominal_weights(), nominal_weights(moved), {}, {},
    )

    assert change.versions == VersionNumbers(1, 2, 1)
    assert change.reuse_observations


def test_value_rule_change_requires_full_reprocessing() -> None:
    changed = (replace(INDICATORS[0], raw_unit="새 단위"), *INDICATORS[1:])
    change = plan_change(
        VersionNumbers(1, 1, 1), INDICATORS, changed, nominal_weights(), nominal_weights(), {}, {}
    )

    assert change.versions == VersionNumbers(1, 2, 2)
    assert change.reprocess_all_months
    assert not change.reuse_observations


def test_fingerprint_is_order_independent_but_includes_source_selection() -> None:
    original = value_rule_fingerprint(INDICATORS)

    assert value_rule_fingerprint(tuple(reversed(INDICATORS))) == original
    changed = (replace(INDICATORS[0], item_code="new_item"), *INDICATORS[1:])
    assert value_rule_fingerprint(changed) != original


def test_metadata_change_does_not_change_value_fingerprint() -> None:
    changed = (replace(INDICATORS[0], agency="표시용 기관명"), *INDICATORS[1:])

    assert value_rule_fingerprint(changed) == value_rule_fingerprint(INDICATORS)
    plan = plan_change(
        VersionNumbers(1, 1, 1), INDICATORS, changed,
        nominal_weights(), nominal_weights(), {}, {},
    )
    assert plan.versions == VersionNumbers(1, 2, 1)
    assert not plan.reprocess_all_months


def test_registry_versions_persist_weights_and_thorp_policy(tmp_path) -> None:
    engine = create_engine(f"sqlite:///{tmp_path / 'versions.db'}")
    with engine.begin() as connection:
        connection.execute(text(
            "CREATE TABLE macro_calculation_registry_version "
            "(id INTEGER PRIMARY KEY AUTOINCREMENT, "
            "version_key TEXT, value_rule_fingerprint TEXT, change_reason TEXT, "
            "nominal_weight_policy TEXT, source_policy TEXT, refresh_months_basis TEXT, "
            "status TEXT, created_at TEXT)"
        ))
        connection.execute(text(
            "CREATE TABLE macro_indicator_definition (registry_version_id INTEGER, "
            "indicator_id TEXT, field_id TEXT, primary_source TEXT, table_code TEXT, "
            "item_code TEXT, org_id TEXT, classification TEXT, source_cycle TEXT, "
            "series_start TEXT, link_chain TEXT, raw_unit TEXT, transformed_unit TEXT, "
            "transform_rule TEXT, "
            "release_rule TEXT, break_link_rule TEXT, missing_rule TEXT, agency TEXT, "
            "attribution TEXT, license_status TEXT, license_review_status TEXT, "
            "published_precision INTEGER, refresh_months_n INTEGER, nominal_weight TEXT, "
            "trading_calendar TEXT, publication_lag_months INTEGER, "
            "revision_history_available INTEGER)"
        ))
        connection.execute(text(
            "CREATE TABLE macro_display_version (id INTEGER PRIMARY KEY AUTOINCREMENT, "
            "version_key TEXT, change_reason TEXT, created_at TEXT)"
        ))
        connection.execute(text(
            "CREATE TABLE macro_display_field (display_version_id INTEGER, field_id TEXT, "
            "display_name TEXT, display_order INTEGER, description TEXT)"
        ))
        connection.execute(text(
            "CREATE TABLE macro_display_indicator (display_version_id INTEGER, "
            "indicator_id TEXT, display_name TEXT, display_order INTEGER)"
        ))
        connection.execute(text(
            "CREATE TABLE macro_current_publication (singleton_key TEXT PRIMARY KEY, "
            "dataset_version_id INTEGER, registry_version_id INTEGER, "
            "display_version_id INTEGER, switched_at TEXT)"
        ))

    registry_id = register_calculation_version(engine, "CALC-1", "initial", INDICATORS)
    fields = {field_id: (field_id, order, None) for order, field_id in enumerate(FIELD_IDS)}
    indicator_names = {
        indicator.indicator_id: (indicator.indicator_id, order)
        for order, indicator in enumerate(INDICATORS)
    }
    display_id = register_display_version(
        engine, "DISPLAY-1", "initial", fields, indicator_names, INDICATORS
    )
    initialize_current_publication(engine, registry_id, display_id)
    initialize_current_publication(engine, registry_id, display_id)

    with engine.connect() as connection:
        assert connection.execute(text(
            "SELECT nominal_weight_policy FROM macro_calculation_registry_version WHERE id=:id"
        ), {"id": registry_id}).scalar_one() == NOMINAL_WEIGHT_POLICY
        assert connection.execute(text(
            "SELECT count(*) FROM macro_indicator_definition WHERE registry_version_id=:id"
        ), {"id": registry_id}).scalar_one() == 17
        assert connection.execute(text(
            "SELECT count(*) FROM macro_display_field WHERE display_version_id=:id"
        ), {"id": display_id}).scalar_one() == 7
        assert connection.execute(text(
            "SELECT count(*) FROM macro_current_publication"
        )).scalar_one() == 1
