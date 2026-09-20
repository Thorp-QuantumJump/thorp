"""`macro_*` 통합 테스트가 함께 쓰는 PostgreSQL 대역.

컨테이너를 띄우고 운영과 같은 마이그레이션을 적용한다. 스키마를 손으로 만들지 않는 이유는 그러면
마이그레이션이 틀려도 테스트가 통과하기 때문이다.

**Docker 가 없으면 건너뛴다. 다만 `THORP_REQUIRE_DOCKER=true` 면 실패로 만든다** — CI 는 그 값을
켜고 돌려, 건너뛴 것이 통과로 보이지 않게 한다.
"""

from __future__ import annotations

import os
import secrets
import subprocess
import time
from pathlib import Path

import psycopg
import pytest
from sqlalchemy import create_engine
from sqlalchemy.engine import URL
from sqlalchemy.exc import OperationalError

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
