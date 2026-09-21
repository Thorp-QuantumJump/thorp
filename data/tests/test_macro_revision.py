"""과거 개정은 이전 버전을 고치지 않고 17개 전체 새 버전을 만든다 (T052)."""

from __future__ import annotations

from dataclasses import replace
from decimal import Decimal
from pathlib import Path

from sqlalchemy import text

from data_pipeline.macro.ingest import merge_published_observations
from data_pipeline.macro.normalize import NormalizedObservation
from data_pipeline.macro.publish import (
    AtomicPublicationRequest,
    PublicationPointer,
    publish_atomically,
)
from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.versioning import (
    initialize_current_publication,
    register_calculation_version,
)


def _values(
    indicators, changed: str | None = None, months: tuple[str, ...] = ("2026-07",)
):
    return {
        indicator.indicator_id: [
            NormalizedObservation(
                indicator_id=indicator.indicator_id, period=month,
                transformed_value=(
                    Decimal("2") if indicator.indicator_id == changed else Decimal("1")
                ),
                missing_reason=None, raw_values=((month, Decimal("1")),),
                transform_rule=indicator.transform_rule, raw_unit=indicator.raw_unit,
                transformed_unit=indicator.transformed_unit, source_updated_at=None,
            )
            for month in months
        ]
        for indicator in indicators
    }


def _files(root: Path, suffix: str) -> tuple[Path, Path]:
    raw = root / "raw" / "macro" / f"source-{suffix}.json"
    processed = root / "processed" / "macro" / f"part-{suffix}.parquet"
    raw.parent.mkdir(parents=True, exist_ok=True)
    processed.parent.mkdir(parents=True, exist_ok=True)
    raw.write_bytes(f"raw-{suffix}".encode())
    processed.write_bytes(f"processed-{suffix}".encode())
    return raw, processed


def test_revision_publishes_new_complete_version_and_preserves_previous(
    postgres_engine, tmp_path: Path
) -> None:
    indicators = tuple(replace(i, series_start="2026-06") for i in INDICATORS)
    registry_id = register_calculation_version(postgres_engine, "calc-revision", "test", indicators)
    with postgres_engine.begin() as connection:
        display_id = connection.execute(text(
            "INSERT INTO macro_display_version (version_key, change_reason, created_at)"
            " VALUES ('display-revision', 'test', CURRENT_TIMESTAMP) RETURNING id"
        )).scalar_one()
    initialize_current_publication(postgres_engine, registry_id, display_id)

    raw1, processed1 = _files(tmp_path, "1")
    first = publish_atomically(postgres_engine, AtomicPublicationRequest(
        version_key="revision-1", attempt_key="revision-attempt-1",
        pointer=PublicationPointer(None, registry_id, display_id), indicators=indicators,
        observations=_values(indicators, months=("2026-06", "2026-07")),
        raw_files=(raw1,), processed_files=(processed1,),
        data_root=tmp_path,
    ))
    merged = merge_published_observations(
        postgres_engine, first, indicators,
        _values(indicators, indicators[0].indicator_id),
    )
    raw2, processed2 = _files(tmp_path, "2")
    second = publish_atomically(postgres_engine, AtomicPublicationRequest(
        version_key="revision-2", attempt_key="revision-attempt-2",
        pointer=PublicationPointer(first, registry_id, display_id), indicators=indicators,
        observations=merged,
        raw_files=(raw2,), processed_files=(processed2,), data_root=tmp_path,
    ))

    with postgres_engine.connect() as connection:
        assert connection.execute(text(
            "SELECT count(*) FROM macro_observation WHERE dataset_version_id=:id"
        ), {"id": second}).scalar_one() == 34
        old, new = connection.execute(text(
            "SELECT dataset_version_id, transformed_value FROM macro_observation"
            " WHERE indicator_id=:indicator AND target_month='2026-07-01'"
            " ORDER BY dataset_version_id"
        ), {"indicator": indicators[0].indicator_id}).all()
        assert str(old[1]).strip('"') == "1"
        assert str(new[1]).strip('"') == "2"
        assert connection.execute(text(
            "SELECT dataset_version_id FROM macro_current_publication"
        )).scalar_one() == second
        assert connection.execute(text(
            "SELECT count(*) FROM macro_observation"
            " WHERE dataset_version_id=:id AND target_month='2026-06-01'"
        ), {"id": second}).scalar_one() == 17
