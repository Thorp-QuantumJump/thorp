"""실제 PostgreSQL 제약과 JSONB를 거치는 원자적 발행 통합 테스트."""

from __future__ import annotations

from dataclasses import replace
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import text

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


def test_atomic_publish_against_postgresql(postgres_engine, tmp_path: Path) -> None:
    indicators = tuple(replace(indicator, series_start="2026-07") for indicator in INDICATORS)
    registry_id = register_calculation_version(
        postgres_engine, "calc-1", "integration test", indicators
    )
    with postgres_engine.begin() as connection:
        display_id = connection.execute(
            text("INSERT INTO macro_display_version "
                 "(version_key, change_reason, created_at) "
                 "VALUES ('display-1', 'integration test', CURRENT_TIMESTAMP) RETURNING id")
        ).scalar_one()
    initialize_current_publication(postgres_engine, registry_id, display_id)
    raw = tmp_path / "raw" / "macro" / "source.json"
    processed = tmp_path / "processed" / "macro" / "part.parquet"
    raw.parent.mkdir(parents=True)
    processed.parent.mkdir(parents=True)
    raw.write_bytes(b"raw")
    processed.write_bytes(b"processed")
    observations = {
        indicator.indicator_id: [
            NormalizedObservation(
                indicator_id=indicator.indicator_id, period="2026-07",
                transformed_value=Decimal("1.25"), missing_reason=None,
                raw_values=(("2026-07", Decimal("1.25")),),
                transform_rule=indicator.transform_rule, raw_unit=indicator.raw_unit,
                transformed_unit=indicator.transformed_unit, source_updated_at=None,
                # 개편으로 나뉜 계열만 채워지는 칸이다. 여기서는 저장되는지만 본다.
                break_segment="2021-06" if indicator.indicator_id == "housing_sale_price"
                else None,
            )
        ]
        for indicator in indicators
    }
    request = AtomicPublicationRequest(
        version_key="dataset-1", attempt_key="attempt-1",
        pointer=PublicationPointer(None, registry_id, display_id),
        indicators=indicators, observations=observations,
        raw_files=(raw,), processed_files=(processed,), data_root=tmp_path,
    )
    dataset_id = publish_atomically(postgres_engine, request)
    with postgres_engine.connect() as connection:
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() == dataset_id
        assert connection.execute(
            text("SELECT count(*) FROM macro_observation WHERE dataset_version_id=:id"),
            {"id": dataset_id},
        ).scalar_one() == 17
        # 어느 표가 이 달을 줬는지가 자료에 남는다 (T076, FR-010). NULL 로 박혀 있으면
        # 이어 붙인 값과 원천이 직접 말한 값을 나중에 구분할 수 없다.
        segments = dict(
            connection.execute(
                text("SELECT indicator_id, break_segment FROM macro_observation"
                     " WHERE dataset_version_id=:id"),
                {"id": dataset_id},
            ).all()
        )
        assert segments["housing_sale_price"] == "2021-06"
        assert segments["consumer_price_index"] is None
        assert connection.execute(
            text("SELECT count(*) FROM macro_baseline WHERE dataset_version_id=:id"),
            {"id": dataset_id},
        ).scalar_one() == 8
        assert connection.execute(
            text("SELECT reference_month FROM macro_baseline "
                 "WHERE dataset_version_id=:id AND scope_id='ALL'"),
            {"id": dataset_id},
        ).scalar_one() == date(2026, 7, 1)

    incomplete = replace(
        request, version_key="dataset-2", attempt_key="attempt-2",
        pointer=PublicationPointer(dataset_id, registry_id, display_id),
        indicators=tuple(replace(i, series_start="2026-06") for i in indicators),
    )
    with pytest.raises(ValueError, match="빠진 월"):
        publish_atomically(postgres_engine, incomplete)
    with postgres_engine.connect() as connection:
        assert connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one() == dataset_id
        assert connection.execute(
            text("SELECT status FROM macro_collection_attempt WHERE attempt_key='attempt-2'")
        ).scalar_one() == "FAILED"
