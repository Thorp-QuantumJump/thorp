"""실제 PostgreSQL 제약과 JSONB를 거치는 원자적 발행 통합 테스트."""

from __future__ import annotations

import os
import secrets
import subprocess
import time
from dataclasses import replace
from datetime import date
from decimal import Decimal
from pathlib import Path

import psycopg
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import URL
from sqlalchemy.exc import OperationalError

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

MIGRATIONS = (
    "V20260918120000__macro_registry.sql",
    "V20260918153100__macro_normalized_missing_reasons.sql",
)


def _docker(*arguments: str) -> str:
    result = subprocess.run(
        ["docker", *arguments], capture_output=True, text=True, check=True, timeout=30
    )
    return result.stdout.strip()


def _current_container_network() -> str | None:
    """Docker 소켓을 공유하는 컨테이너 안이면 현재 컨테이너의 네트워크를 돌려준다."""
    if not Path("/.dockerenv").exists():
        return None
    hostname = os.getenv("HOSTNAME")
    if not hostname:
        return None
    try:
        networks = _docker(
            "inspect",
            "--format",
            "{{range $name, $_ := .NetworkSettings.Networks}}{{$name}}{{println}}{{end}}",
            hostname,
        ).splitlines()
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        return None
    return networks[0] if networks else None


@pytest.fixture
def postgres_engine():
    required = os.getenv("THORP_REQUIRE_DOCKER", "").lower() == "true"
    try:
        _docker("info", "--format", "{{.ServerVersion}}")
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
        if required:
            pytest.fail("THORP_REQUIRE_DOCKER=true인데 Docker 엔진에 접속할 수 없다")
        pytest.skip("PostgreSQL 통합 테스트용 Docker 엔진이 없다")

    name = f"macro-publish-test-{secrets.token_hex(5)}"
    password = secrets.token_urlsafe(24)
    network = _current_container_network()
    try:
        run_arguments = ["run", "-d", "--rm", "--name", name]
        if network:
            run_arguments.extend(("--network", network))
            host = name
            port = 5432
        else:
            run_arguments.extend(("-p", "127.0.0.1::5432"))
            host = "127.0.0.1"
        run_arguments.extend(
            (
                "-e",
                f"POSTGRES_PASSWORD={password}",
                "-e",
                "POSTGRES_DB=macro_test",
                "postgres:16-alpine",
            )
        )
        _docker(*run_arguments)
        if not network:
            port = int(_docker("port", name, "5432/tcp").rsplit(":", 1)[1])
        url = URL.create(
            "postgresql+psycopg", username="postgres", password=password,
            host=host, port=port, database="macro_test",
        )
        engine = create_engine(url)
        deadline = time.monotonic() + 45
        while True:
            try:
                with engine.connect():
                    break
            except OperationalError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.25)
        migration_dir = Path(__file__).resolve().parents[2] / "backend" / "src" / "main" \
            / "resources" / "db" / "migration"
        with psycopg.connect(
            host=host, port=port, dbname="macro_test",
            user="postgres", password=password,
        ) as connection:
            for migration in MIGRATIONS:
                connection.execute((migration_dir / migration).read_text(encoding="utf-8"))
        yield engine
    finally:
        if "engine" in locals():
            engine.dispose()
        try:
            _docker("stop", name)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass


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
