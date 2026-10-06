"""`macro_*` 통합 테스트가 함께 쓰는 PostgreSQL 대역.

컨테이너를 띄우고 운영과 같은 마이그레이션을 적용한다. 스키마를 손으로 만들지 않는 이유는 그러면
마이그레이션이 틀려도 테스트가 통과하기 때문이다.

**컨테이너는 세션에 하나이고, 테스트마다 마이그레이션을 마친 템플릿에서 새 데이터베이스를
복제한다.** 테스트마다 컨테이너를 띄우면 건당 수 초가 들고, 다른 작업과 Docker 를 나눠 쓸 때
그 많은 `docker` 호출 중 하나가 시간 초과로 끝나 일부 테스트만 오류가 났다. 데이터베이스를
복제하면 테스트끼리 표를 공유하지 않으므로 격리는 컨테이너를 따로 띄울 때와 같다. 다만 역할·서버
설정처럼 데이터베이스 밖의 객체는 공유한다. 지금 마이그레이션과 테스트는 그런 것을 만들지 않는다.

**Docker 가 없으면 건너뛴다. 다만 `THORP_REQUIRE_DOCKER=true` 면 실패로 만든다** — CI 는 그 값을
켜고 돌려, 건너뛴 것이 통과로 보이지 않게 한다.
"""

from __future__ import annotations

import os
import secrets
import subprocess
import time
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

import psycopg
import pytest
from psycopg import sql
from sqlalchemy import Engine, create_engine
from sqlalchemy.engine import URL
from sqlalchemy.exc import OperationalError

#: macro 스키마 마이그레이션을 담은 곳. 목록을 손으로 적지 않고 **파일 이름으로 찾는다** —
#: 적어 두면 새 마이그레이션을 더할 때마다 여기를 같이 고쳐야 하고, 잊으면 테스트만 옛 스키마로
#: 돌아 통과한다. 그 통과는 운영이 새 컬럼으로 도는 사실을 감춘다.
MIGRATION_DIR = (
    Path(__file__).resolve().parents[2]
    / "backend" / "src" / "main" / "resources" / "db" / "migration"
)

#: 이름순이 곧 판 번호순이다(``V<타임스탬프>__``).
def macro_migrations() -> list[Path]:
    return sorted(MIGRATION_DIR.glob("V*__macro_*.sql"))


#: 마이그레이션을 적용해 두고 테스트마다 복제하는 데이터베이스.
TEMPLATE_DATABASE = "macro_template"

#: `docker` 호출 한 번의 시간 한도. 엔진이 꺼져 있으면 호출은 바로 실패하므로 이 값은 그때 기다리는
#: 시간이 아니다. 다른 작업이 Docker 를 바쁘게 쓰는 동안 응답이 늦어지는 경우와 첫 실행에서 이미지를
#: 받는 경우를 견디는 값이다. 호출은 세션에 몇 번뿐이라 넉넉하게 둔다.
DOCKER_TIMEOUT_SECONDS = 120


def _docker(*arguments: str) -> str:
    result = subprocess.run(
        ["docker", *arguments],
        capture_output=True,
        text=True,
        check=True,
        timeout=DOCKER_TIMEOUT_SECONDS,
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


@dataclass(frozen=True)
class PostgresServer:
    """세션 동안 떠 있는 컨테이너의 접속 정보."""

    host: str
    port: int
    password: str

    def url(self, database: str) -> URL:
        return URL.create(
            "postgresql+psycopg", username="postgres", password=self.password,
            host=self.host, port=self.port, database=database,
        )

    def connect(self, database: str, *, autocommit: bool = False) -> psycopg.Connection:
        return psycopg.connect(
            host=self.host, port=self.port, dbname=database,
            user="postgres", password=self.password, autocommit=autocommit,
        )


@pytest.fixture(scope="session")
def postgres_server() -> Iterator[PostgresServer]:
    """컨테이너를 띄우고 템플릿 데이터베이스에 마이그레이션을 적용한다. 세션이 끝나면 멈춘다."""
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
                f"POSTGRES_DB={TEMPLATE_DATABASE}",
                "postgres:16-alpine",
            )
        )
        _docker(*run_arguments)
        if not network:
            port = int(_docker("port", name, "5432/tcp").rsplit(":", 1)[1])
        server = PostgresServer(host=host, port=port, password=password)
        probe = create_engine(server.url(TEMPLATE_DATABASE))
        try:
            deadline = time.monotonic() + 45
            while True:
                try:
                    with probe.connect():
                        break
                except OperationalError:
                    if time.monotonic() >= deadline:
                        raise
                    time.sleep(0.25)
        finally:
            # 템플릿에 접속이 남아 있으면 복제(`CREATE DATABASE ... TEMPLATE`)가 거절된다.
            probe.dispose()
        with server.connect(TEMPLATE_DATABASE) as connection:
            for migration in macro_migrations():
                connection.execute(migration.read_text(encoding="utf-8"))
        yield server
    finally:
        try:
            _docker("stop", name)
        except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass


@pytest.fixture
def postgres_engine(postgres_server: PostgresServer) -> Iterator[Engine]:
    """마이그레이션까지 마친 빈 데이터베이스. 테스트마다 템플릿에서 새로 복제한다."""
    database = f"macro_test_{secrets.token_hex(5)}"
    with postgres_server.connect("postgres", autocommit=True) as admin:
        admin.execute(
            sql.SQL("CREATE DATABASE {} TEMPLATE {}").format(
                sql.Identifier(database), sql.Identifier(TEMPLATE_DATABASE)
            )
        )
    engine = create_engine(postgres_server.url(database))
    try:
        yield engine
    finally:
        engine.dispose()
        # 테스트가 URL 로 따로 만든 엔진이 접속을 남겨도 지울 수 있게 FORCE 로 끊는다.
        with postgres_server.connect("postgres", autocommit=True) as admin:
            admin.execute(
                sql.SQL("DROP DATABASE {} WITH (FORCE)").format(sql.Identifier(database))
            )
