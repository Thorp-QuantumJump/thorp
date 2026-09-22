"""지표 소스 구현체 선택.

`--source` 플래그 하나만 보고 고른다. **임시 대역은 T071 에서 폐기했다** — 008 구현체가
들어왔으므로 고를 것이 하나뿐이지만, 고르는 자리는 남겨 둔다. 다음 구현이 생길 때 배치
곳곳에서 구현체를 직접 만들지 않게 하는 것이 이 모듈의 몫이다.

**구현체는 이름이 아니라 모듈 경로로 고른다.** 구현 클래스를 ``import``하지 않으므로 구현을
갈아끼워도 이 파일 밖은 그대로다. 각 구현 모듈은 ``create_source()`` 하나를 내놓기로 한다.
"""

from __future__ import annotations

from importlib import import_module
from typing import Final

from data_pipeline.regime.macro.source import MacroIndicatorSource

#: 008 구현체. 완성되면 이것만 남는다.
SOURCE_MACRO: Final[str] = "macro"

#: `--source`가 받는 값.
SOURCE_CHOICES: Final[tuple[str, ...]] = (SOURCE_MACRO,)

_IMPLEMENTATION_MODULES: Final[dict[str, str]] = {
    SOURCE_MACRO: "data_pipeline.regime.macro.postgres",
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
        # 조용히 다른 구현으로 물러나지 않는다 — 그러면 의도하지 않은 자료가 적재된다.
        raise MacroSourceUnavailableError(
            f"지표 소스 {name!r}의 구현이 없다({module_path})"
        ) from error
    return module.create_source()
