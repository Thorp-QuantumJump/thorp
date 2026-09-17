"""지표 소스 구현체 선택 — 운영 코드가 픽스처를 아는 **유일한 자리**.

`--source` 플래그 하나만 보고 고른다. 고르는 곳을 여기 하나로 모으는 이유는 격리 때문이다
(contracts/macro-indicator-port.md §4.1) — 배치 곳곳에서 구현체를 직접 만들면 008이 왔을 때
지울 자리를 뒤져야 하고, 그러다 남은 한 곳이 가짜 자료를 운영에 흘린다.

**구현체는 이름이 아니라 모듈 경로로 고른다.** 픽스처 클래스를 ``import``하지 않으므로 이
파일 밖 어디에도 픽스처 심볼이 없고, `fixture/`를 지우면 여기 한 줄만 지우면 된다 — 계약
§4.3의 폐기 절차 2단계가 그것이다. 각 구현 모듈은 ``create_source()`` 하나를 내놓기로 한다.
"""

from __future__ import annotations

from importlib import import_module
from typing import Final

from data_pipeline.regime.macro.source import MacroIndicatorSource

#: 008 구현체. 완성되면 이것만 남는다.
SOURCE_MACRO: Final[str] = "macro"

#: ⚠ 008 완성 전까지 쓰는 임시 대역. 계약 §4.3의 폐기 대상이다.
SOURCE_FIXTURE: Final[str] = "fixture"

#: `--source`가 받는 값. 기본값은 `SOURCE_MACRO`이며 픽스처는 명시해야만 쓰인다 —
#: 기본이 픽스처면 아무 플래그 없이 돌린 배치가 가짜 자료를 적재한다.
SOURCE_CHOICES: Final[tuple[str, ...]] = (SOURCE_MACRO, SOURCE_FIXTURE)

_IMPLEMENTATION_MODULES: Final[dict[str, str]] = {
    SOURCE_MACRO: "data_pipeline.regime.macro.postgres",
    SOURCE_FIXTURE: "data_pipeline.regime.macro.fixture.source",
}


class UnknownMacroSourceError(ValueError):
    """`--source`가 모르는 값이다."""


class MacroSourceUnavailableError(RuntimeError):
    """고른 구현체가 아직 없다."""


def create_source(name: str) -> MacroIndicatorSource:
    """`--source` 값에 해당하는 지표 소스를 만든다."""
    try:
        module_path = _IMPLEMENTATION_MODULES[name]
    except KeyError:
        raise UnknownMacroSourceError(
            f"모르는 지표 소스다: {name!r}. 쓸 수 있는 값은 {', '.join(SOURCE_CHOICES)}"
        ) from None
    try:
        module = import_module(module_path)
    except ModuleNotFoundError as error:
        # 008 구현체가 아직 없어 기본값 `macro`로 돌리면 여기로 온다. 픽스처로 조용히
        # 물러나지 않는다 — 그러면 가짜 자료가 기본 경로로 적재된다.
        raise MacroSourceUnavailableError(
            f"지표 소스 {name!r}의 구현이 없다({module_path}). "
            "008이 완성되기 전에는 --source fixture 로 임시 대역을 명시해 쓴다"
        ) from error
    return module.create_source()
