"""pyproject 에 정확한 버전으로 고정한 의존성을 uv.lock 이 그대로 따르는지(spec 004 T022·T027).

운영 이미지는 ``uv sync --frozen`` 으로 lock 만 보고 설치한다. pyproject 의 고정 버전을 바꾸고
``uv lock`` 을 잊으면 이미지는 옛 버전을 조용히 설치하는데 테스트는 전부 통과한다 — 재현을 믿을 수
없게 된다. CI 의 ``uv sync --locked`` 도 이 어긋남을 잡지만, 이 테스트는 어디서 pytest 를 돌려도
잡는다.
"""

import hashlib
import re
import tomllib
from pathlib import Path

WORKER = Path(__file__).resolve().parents[1]
BACKEND_CONFIG = WORKER.parent / "backend" / "src" / "main" / "resources" / "application.yml"
ENGINE_VERSION = re.compile(r'^\s*engine-version:\s*"(?P<value>[^"]*)"', re.MULTILINE)
PIN = re.compile(r"^(?P<name>[A-Za-z0-9_.\-]+)(?P<extras>\[[^\]]*\])?==(?P<version>[^\s;]+)$")


def _normalise(name: str) -> str:
    return re.sub(r"[-_.]+", "-", name).lower()


def _pins(requirements: list[str]) -> dict[str, str]:
    found = {}
    for requirement in requirements:
        match = PIN.match(requirement.strip())
        if match:
            found[_normalise(match["name"])] = match["version"]
    return found


def _locked_versions() -> dict[str, set[str]]:
    lock = tomllib.loads((WORKER / "uv.lock").read_text(encoding="utf-8"))
    locked: dict[str, set[str]] = {}
    for package in lock["package"]:
        # 워커 자신(편집 설치, 동적 버전)은 version 이 없다.
        if "version" in package:
            locked.setdefault(_normalise(package["name"]), set()).add(package["version"])
    return locked


def test_pinned_versions_match_between_pyproject_and_lock() -> None:
    pyproject = tomllib.loads((WORKER / "pyproject.toml").read_text(encoding="utf-8"))
    declared = _pins(pyproject["project"]["dependencies"])
    locked = _locked_versions()

    assert declared, "pyproject 에 정확한 버전으로 고정한 의존성이 하나도 없다"
    # lock 은 Python 버전마다 다른 판을 고를 수 있으므로(numpy) 고정한 패키지는 판이 하나여야 한다.
    assert {name: locked.get(name) for name in declared} == {
        name: {version} for name, version in declared.items()
    }


def test_backend_engine_version_carries_the_uv_lock_fingerprint() -> None:
    """재현 키의 실행환경 지문이 지금 lock 과 같은지(spec 004 T028).

    재현 키는 워커 실행 전에 백엔드가 만들므로 워커 결과를 쓸 수 없다. 대신 백엔드
    ``thorp.backtest.engine-version`` 에 lock 지문을 넣어 두고, lock 이 바뀌었는데 설정을 잊으면
    옛 환경의 결과가 REUSED 로 돌아가는 것을 여기서 막는다.
    """
    lock = (WORKER / "uv.lock").read_bytes().replace(b"\r\n", b"\n")
    expected = f"uv.lock {hashlib.sha256(lock).hexdigest()[:12]}"
    match = ENGINE_VERSION.search(BACKEND_CONFIG.read_text(encoding="utf-8"))

    assert match, f"{BACKEND_CONFIG} 에서 engine-version 을 찾지 못했다"
    assert match["value"].endswith(f" / {expected}"), (
        f"engine-version 의 지문이 uv.lock 과 다르다. 끝을 '{expected}' 로 바꾼다: {match['value']}"
    )
