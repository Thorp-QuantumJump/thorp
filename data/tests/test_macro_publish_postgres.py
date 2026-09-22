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


def _observation(indicator, period: str, value: str) -> NormalizedObservation:
    return NormalizedObservation(
        indicator_id=indicator.indicator_id,
        period=period,
        transformed_value=Decimal(value),
        missing_reason=None,
        raw_values=((period, Decimal(value)),),
        transform_rule=indicator.transform_rule,
        raw_unit=indicator.raw_unit,
        transformed_unit=indicator.transformed_unit,
        source_updated_at=None,
    )


def _publish(engine, indicators, observations, pointer, version_key, tmp_path, attempt):
    raw = tmp_path / "raw" / "macro" / f"{version_key}.json"
    processed = tmp_path / "processed" / "macro" / f"{version_key}.parquet"
    raw.parent.mkdir(parents=True, exist_ok=True)
    processed.parent.mkdir(parents=True, exist_ok=True)
    raw.write_bytes(version_key.encode())
    processed.write_bytes(version_key.encode())
    return publish_atomically(
        engine,
        AtomicPublicationRequest(
            version_key=version_key,
            attempt_key=attempt,
            pointer=pointer,
            indicators=indicators,
            observations=observations,
            raw_files=(raw,),
            processed_files=(processed,),
            data_root=tmp_path,
        ),
    )


def test_정기_수집이_예전_달을_지우지_않는다(postgres_engine, tmp_path: Path) -> None:
    """전 구간 발행 뒤 최근 N개월만 다시 받아도 새 버전에 예전 달이 남아야 한다.

    quickstart 의 '증분 수집 보존' 시나리오다(FR-017). 정기 수집은 새 발표분과 최근 N개월만
    받으므로, 발행이 이번에 받은 것만 담으면 **공개된 과거가 매달 N개월로 줄어든다.** 응답도
    값도 멀쩡하고 줄어든 사실은 어디에도 드러나지 않는다.
    """
    indicators = tuple(replace(i, series_start="2026-05") for i in INDICATORS)
    registry_id = register_calculation_version(
        postgres_engine, "calc-inc", "증분 보존", indicators
    )
    with postgres_engine.begin() as connection:
        display_id = connection.execute(
            text(
                "INSERT INTO macro_display_version (version_key, change_reason, created_at)"
                " VALUES ('display-inc', '증분 보존', CURRENT_TIMESTAMP) RETURNING id"
            )
        ).scalar_one()
    initialize_current_publication(postgres_engine, registry_id, display_id)

    full = {
        i.indicator_id: [
            _observation(i, "2026-05", "1.0"),
            _observation(i, "2026-06", "1.1"),
            _observation(i, "2026-07", "1.2"),
        ]
        for i in indicators
    }
    first = _publish(
        postgres_engine,
        indicators,
        full,
        PublicationPointer(None, registry_id, display_id),
        "ds-full",
        tmp_path,
        "att-full",
    )

    # 정기 수집: 최근 한 달만 받았다.
    recent = {i.indicator_id: [_observation(i, "2026-07", "1.3")] for i in indicators}
    second = _publish(
        postgres_engine,
        indicators,
        recent,
        PublicationPointer(first, registry_id, display_id),
        "ds-recent",
        tmp_path,
        "att-recent",
    )

    with postgres_engine.begin() as connection:
        months = connection.execute(
            text(
                "SELECT target_month FROM macro_observation"
                " WHERE dataset_version_id = :id AND indicator_id = :indicator"
                " ORDER BY target_month"
            ),
            {"id": second, "indicator": indicators[0].indicator_id},
        ).scalars().all()

    assert [m.isoformat() for m in months] == ["2026-05-01", "2026-06-01", "2026-07-01"], (
        "정기 수집 뒤 공개된 과거가 줄었다. 새 자료 버전이 이번에 받은 달만 담고 있다"
    )


def test_과거_개정이_새_버전을_만들고_앞_버전은_그대로다(postgres_engine, tmp_path: Path) -> None:
    """한 지표의 예전 달 값이 바뀌면 새 불변 버전이 생기고 앞 버전 값은 변하지 않는다.

    quickstart 의 '과거 개정' 시나리오다(FR-009). 앞 버전을 제자리에서 고치면 그 버전으로 낸
    결과를 다시 만들 수 없다 — 재현성이 무너지는 자리다(원칙 VIII).
    """
    indicators = tuple(replace(i, series_start="2026-05") for i in INDICATORS)
    registry_id = register_calculation_version(
        postgres_engine, "calc-rev", "개정", indicators
    )
    with postgres_engine.begin() as connection:
        display_id = connection.execute(
            text(
                "INSERT INTO macro_display_version (version_key, change_reason, created_at)"
                " VALUES ('display-rev', '개정', CURRENT_TIMESTAMP) RETURNING id"
            )
        ).scalar_one()
    initialize_current_publication(postgres_engine, registry_id, display_id)

    months = ("2026-05", "2026-06", "2026-07")
    first = _publish(
        postgres_engine,
        indicators,
        {i.indicator_id: [_observation(i, m, "1.0") for m in months] for i in indicators},
        PublicationPointer(None, registry_id, display_id),
        "ds-rev-1",
        tmp_path,
        "att-rev-1",
    )

    # 2026-05 가 개정됐다. 정기 재조회가 그 달을 함께 받아 온 상황이다.
    revised = {
        i.indicator_id: [_observation(i, "2026-05", "9.9"), _observation(i, "2026-07", "1.0")]
        for i in indicators
    }
    second = _publish(
        postgres_engine,
        indicators,
        revised,
        PublicationPointer(first, registry_id, display_id),
        "ds-rev-2",
        tmp_path,
        "att-rev-2",
    )

    with postgres_engine.begin() as connection:
        def value(dataset: int, month: str) -> str:
            return connection.execute(
                text(
                    "SELECT transformed_value #>> '{}' FROM macro_observation"
                    " WHERE dataset_version_id=:d AND indicator_id=:i AND target_month=:m"
                ),
                {"d": dataset, "i": indicators[0].indicator_id, "m": f"{month}-01"},
            ).scalar_one()

        assert value(second, "2026-05") == "9.9", "개정값이 새 버전에 반영되지 않았다"
        assert value(first, "2026-05") == "1.0", "앞 버전의 값이 바뀌었다 — 불변이어야 한다"
        # 받지 않은 2026-06 은 앞 버전에서 그대로 이어받는다.
        assert value(second, "2026-06") == "1.0"
