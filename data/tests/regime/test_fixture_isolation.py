"""임시 대역 격리 (contracts/macro-indicator-port.md §4.1·§4.3, tasks.md T012).

008이 완성된 날 "어디를 지워야 하지"를 뒤지게 되면 가짜 자료가 운영에 남는다. 그래서 지울 것이
`macro/fixture/` 한 디렉터리에 모이고, 바깥과 이어지는 곳은 팩터리 한 줄뿐이어야 한다.

**대상 파일은 손 목록이 아니라 디렉터리를 훑어 모은다**(`conftest.production_sources`). 목록을
적어 두면 새로 더한 모듈이 목록에 빠진 채 검사 밖으로 나가고, 격리가 무너진 것을 아무도 모른다.
"""

from __future__ import annotations

import ast
import re
from pathlib import Path

from conftest import FIXTURE_PACKAGE, production_sources

#: 픽스처 패키지를 가리키는 경로 표기. 폐기 절차 2단계가 지우는 그 줄이다 (계약 §4.3).
FIXTURE_PATH_PATTERN = re.compile(r"macro[./]fixture")

#: 구현 모듈이 공통으로 내놓기로 한 이름. 픽스처만의 것이 아니므로 격리 위반이 아니다.
FACTORY_CONVENTION = {"create_source"}


def _module_level_names(path: Path) -> set[str]:
    """그 파일이 정의한 모듈 수준 이름 — 클래스·함수·상수."""
    tree = ast.parse(path.read_text(encoding="utf-8"))
    names: set[str] = set()
    for node in tree.body:
        if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            names.add(node.name)
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            names.add(node.target.id)
        elif isinstance(node, ast.Assign):
            names.update(
                target.id for target in node.targets if isinstance(target, ast.Name)
            )
    return names


def _fixture_only_names() -> set[str]:
    """픽스처 패키지에만 있는 이름.

    운영 코드가 **스스로 정의한** 이름은 뺀다. `main`처럼 진입점이면 으레 갖는 이름까지
    금지어로 삼으면 격리와 무관한 곳에서 시험이 깨지고, 그러면 사람이 시험을 느슨하게 만든다.
    남는 것은 픽스처에만 있는 이름 — 그것이 밖에 있으면 진짜 위반이다.
    """
    inside: set[str] = set()
    for path in FIXTURE_PACKAGE.rglob("*.py"):
        inside |= _module_level_names(path)
    outside: set[str] = set()
    for path in production_sources():
        outside |= _module_level_names(path)
    return inside - outside - FACTORY_CONVENTION


class TestIsolation:
    def test_훑어서_모은_대상이_비어_있지_않다(self) -> None:
        """대상이 0건이면 아래 시험이 전부 공짜로 통과한다. 검사가 살아 있는지를 먼저 본다."""
        assert len(production_sources()) >= 2

    def test_운영_코드가_픽스처를_import하지_않는다(self) -> None:
        offenders = []
        for path in production_sources():
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if isinstance(node, ast.Import):
                    modules = [alias.name for alias in node.names]
                elif isinstance(node, ast.ImportFrom):
                    modules = [node.module or ""]
                else:
                    continue
                if any(FIXTURE_PATH_PATTERN.search(module) for module in modules):
                    offenders.append(f"{path.name}:{node.lineno}")
        assert not offenders, (
            f"운영 코드가 픽스처를 import한다: {offenders}. "
            "import하면 그 파일도 삭제 대상이 되고 경계가 무너진다"
        )

    def test_픽스처에만_있는_이름이_밖에_없다(self) -> None:
        """운영 코드는 인터페이스만 안다 (계약 §4.1)."""
        names = _fixture_only_names()
        assert names, "픽스처에만 있는 이름을 하나도 못 찾았다 — 검사가 헛돌고 있다"
        offenders = []
        for path in production_sources():
            text = path.read_text(encoding="utf-8")
            offenders.extend(
                f"{path.name}:{name}"
                for name in names
                if re.search(rf"\b{re.escape(name)}\b", text)
            )
        assert not offenders, f"픽스처 이름이 운영 코드에 새어 나갔다: {offenders}"

    def test_픽스처_경로를_아는_운영_파일이_팩터리_하나뿐이다(self) -> None:
        """폐기 절차 2단계가 지우는 줄이 한 곳에 모여 있는가 (계약 §4.3)."""
        aware = [
            path.name
            for path in production_sources()
            if FIXTURE_PATH_PATTERN.search(path.read_text(encoding="utf-8"))
        ]
        assert aware == ["__init__.py"], (
            f"픽스처 경로를 아는 운영 파일이 팩터리 말고도 있다: {aware}"
        )

    def test_지울_것이_한_디렉터리에_모여_있다(self) -> None:
        """폐기 절차 1단계는 디렉터리 삭제 하나다. 흩어져 있으면 한 번에 지울 수 없다."""
        assert FIXTURE_PACKAGE.is_dir()
        assert (FIXTURE_PACKAGE / "indicators.json").exists()
        assert [
            path for path in FIXTURE_PACKAGE.rglob("*") if path.is_file()
        ], "픽스처 디렉터리가 비어 있다"
