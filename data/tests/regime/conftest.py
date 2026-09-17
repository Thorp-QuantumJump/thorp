"""`regime` 시험 공통 설정.

`macro_source`는 **구현체마다 도는 fixture**다. 지금은 임시 대역 하나뿐이고 008이 완성되면
`SOURCE_NAMES`에 한 줄을 더하는 것으로 같은 계약 시험이 실제 구현체에도 돈다
(contracts/macro-indicator-port.md §5의 첫 번째 확인 항목).
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date
from pathlib import Path

import pytest

from data_pipeline.regime.macro import SOURCE_FIXTURE, create_source
from data_pipeline.regime.macro.source import MacroIndicatorSource
from data_pipeline.regime.macro.types import MonthlyValue

#: 계약 시험을 돌릴 구현체. 008이 오면 `SOURCE_MACRO`를 더한다.
SOURCE_NAMES: tuple[str, ...] = (SOURCE_FIXTURE,)

#: 운영 코드 검사(T012·T013)의 대상 뿌리. 010이 만든 자리만 본다.
REGIME_PACKAGE: Path = Path(__file__).resolve().parents[2] / "src" / "data_pipeline" / "regime"
PRECOMPUTE_SCRIPT: Path = Path(__file__).resolve().parents[2] / "scripts" / "precompute_regime.py"
FIXTURE_PACKAGE: Path = REGIME_PACKAGE / "macro" / "fixture"


def production_sources() -> list[Path]:
    """010 운영 코드 파일 — **디렉터리를 훑어** 모은다.

    손으로 적은 목록을 쓰지 않는 이유는 목록이 늘 뒤처지기 때문이다. 새 모듈을 더한 사람이
    목록도 같이 고칠 것이라고 기대하면, 고치지 않은 그 파일만 검사 밖으로 나간다.
    """
    files = [path for path in REGIME_PACKAGE.rglob("*.py") if FIXTURE_PACKAGE not in path.parents]
    files.append(PRECOMPUTE_SCRIPT)
    return sorted(files)


@pytest.fixture(params=SOURCE_NAMES)
def macro_source(request: pytest.FixtureRequest) -> MacroIndicatorSource:
    return create_source(request.param)


def make_series(
    start: tuple[int, int], values: Sequence[float | None]
) -> dict[date, MonthlyValue]:
    """단위 시험용 월별 계열 — ``None``인 달은 **키를 만들지 않는다**.

    008 대역이 아니라 여기서 계열을 짓는 이유는 계약 §4.4가 정한 바다. 운영 픽스처는 008이
    오면 사라지지만 `SCALE_ZERO`·동률 같은 모양은 실제 자료에 없을 수 있어, 같은 모양을 담은
    **단위 시험 입력은 남겨야** 그 경로가 검증 밖으로 나가지 않는다.

    원값은 변환값과 같게 둔다. 척도·비교 시점 판정은 변환값만 보므로(spec FR-009) 원값이
    무엇이든 결과가 달라지지 않고, 다르게 두면 읽는 사람이 그 차이에 뜻이 있다고 여긴다.
    """
    series: dict[date, MonthlyValue] = {}
    year, month = start
    for value in values:
        current = date(year, month, 1)
        if value is not None:
            series[current] = MonthlyValue(month=current, value=value, raw=value)
        year, month = (year + 1, 1) if month == 12 else (year, month + 1)
    return series
