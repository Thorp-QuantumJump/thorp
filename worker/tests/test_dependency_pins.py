"""고정한 의존성 버전이 두 파일에서 같은지(spec 004 T022).

운영 이미지는 ``requirements.txt`` 로 설치하고 개발은 ``pyproject.toml``/``uv.lock`` 으로 맞춘다.
한쪽만 고정하면 **운영에서만 다른 버전이 돌고** 그때 결과가 마지막 자리에서 갈린다 — 재현을
믿을 수 없게 되는데 테스트는 전부 통과한다. 그래서 어긋남을 여기서 잡는다.
"""

import re
import tomllib
from pathlib import Path

WORKER = Path(__file__).resolve().parents[1]
PIN = re.compile(r"^(?P<name>[A-Za-z0-9_.\-]+)(?P<extras>\[[^\]]*\])?==(?P<version>[^\s;]+)$")


def _pins(lines: list[str]) -> dict[str, str]:
    found = {}
    for line in lines:
        match = PIN.match(line.strip())
        if match:
            found[match["name"].lower().replace("_", "-")] = match["version"]
    return found


def test_pinned_versions_match_between_pyproject_and_requirements() -> None:
    pyproject = tomllib.loads((WORKER / "pyproject.toml").read_text(encoding="utf-8"))
    declared = _pins(pyproject["project"]["dependencies"])
    installed = _pins((WORKER / "requirements.txt").read_text(encoding="utf-8").splitlines())

    assert declared, "pyproject 에 정확한 버전으로 고정한 의존성이 하나도 없다"
    assert {name: installed.get(name) for name in declared} == declared
