"""pyproject 에 정확한 버전으로 고정한 의존성을 uv.lock 이 그대로 따르는지(spec 004 T022·T027).

운영 이미지는 ``uv sync --frozen`` 으로 lock 만 보고 설치한다. pyproject 의 고정 버전을 바꾸고
``uv lock`` 을 잊으면 이미지는 옛 버전을 조용히 설치하는데 테스트는 전부 통과한다 — 재현을 믿을 수
없게 된다. CI 의 ``uv sync --locked`` 도 이 어긋남을 잡지만, 이 테스트는 어디서 pytest 를 돌려도
잡는다.
"""

import re
import tomllib
from pathlib import Path

WORKER = Path(__file__).resolve().parents[1]
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
