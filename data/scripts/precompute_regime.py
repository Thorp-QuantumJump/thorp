"""경제환경 검색 사전 계산 진입점 (spec 010 FR-030).

기준월마다 척도·거리·순위를 미리 계산해 PostgreSQL에 적재한다. 사용자 요청 처리 중에는 척도도
거리도 계산하지 않으므로(spec FR-030·SC-002a) 이 배치가 010 계산의 유일한 실행 자리다.

사용 예::

    # 008 완성 전 — 임시 대역으로 전 구간 계산 (quickstart 사전 준비)
    .venv/Scripts/python scripts/precompute_regime.py --source fixture --all

    # 자료가 바뀐 달부터만 (research R3의 t ≥ M)
    .venv/Scripts/python scripts/precompute_regime.py --since 2024-01

    # 기준월 하나만 다시 계산
    .venv/Scripts/python scripts/precompute_regime.py --baseline-month 2018-06

**본체는 묶음 M5에서 채운다.** 지금 서 있는 것은 플래그 정의와 운영 적재 가드(T008)이며, 계산
없이 성공했다고 말하지 않는다 — 아무것도 하지 않은 실행이 0으로 끝나면 배치가 돈 줄 알고 넘어간다.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path
from typing import Final

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:  # 진입점을 `python scripts/...`로 바로 실행하는 경우
    sys.path.insert(0, str(_SRC))

from data_pipeline.regime.macro import (  # noqa: E402
    SOURCE_CHOICES,
    SOURCE_FIXTURE,
    SOURCE_MACRO,
)
from data_pipeline.regime.macro.types import MacroSourceError, parse_month  # noqa: E402

_EXIT_REJECTED: Final[int] = 1
#: 계산 본체가 아직 없다는 뜻. 거부(1)와 구분해야 가드가 막은 것인지 미구현인지 알 수 있다.
_EXIT_NOT_IMPLEMENTED: Final[int] = 3

#: 운영 환경 표시. 이 값일 때 임시 대역 적재를 막는다 (contracts/macro-indicator-port.md §4.1).
ENV_VARIABLE: Final[str] = "THORP_ENV"
PROD_ENV: Final[str] = "prod"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="경제환경 검색 사전 계산 (spec 010 FR-030)",
    )
    parser.add_argument(
        "--source",
        choices=SOURCE_CHOICES,
        default=SOURCE_MACRO,
        help=(
            f"지표 소스. 기본값 {SOURCE_MACRO}(008 구현체)이며 "
            f"{SOURCE_FIXTURE}는 008 완성 전까지 쓰는 임시 대역이라 명시해야 쓰인다"
        ),
    )
    scope = parser.add_mutually_exclusive_group()
    scope.add_argument(
        "--all",
        action="store_true",
        help="자료가 있는 전 구간의 기준월을 다시 계산한다",
    )
    scope.add_argument(
        "--since",
        metavar="YYYY-MM",
        help="이 달 이후의 기준월만 계산한다. 생략하면 changed_since()가 정한 M을 쓴다",
    )
    parser.add_argument(
        "--baseline-month",
        metavar="YYYY-MM",
        help="이 기준월 하나만 계산한다",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="계산만 하고 적재하지 않는다",
    )
    return parser


def reject_fixture_in_production(source: str, environment: str | None) -> str | None:
    """운영에서 임시 대역을 쓰려 하면 거부 사유를, 아니면 ``None``을 돌려준다 (계약 §4.1).

    **적재 전에 막는 것이 요점이다.** 가장 오래 남는 것은 코드가 아니라 DB에 적재된 행이라,
    한 번 들어간 가짜 자료는 코드를 되돌려도 남는다. 설정 실수 하나로 그 경로가 열리지 않도록
    계산을 시작하기 전에 끝낸다.
    """
    if source != SOURCE_FIXTURE:
        return None
    if (environment or "").strip().lower() != PROD_ENV:
        return None
    return (
        f"{ENV_VARIABLE}={PROD_ENV}에서는 --source {SOURCE_FIXTURE}로 적재할 수 없다. "
        "임시 대역은 지어낸 값이라 운영 자료가 될 수 없다 "
        "(contracts/macro-indicator-port.md §4.1). "
        f"운영에서는 --source {SOURCE_MACRO}를 쓴다"
    )


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    rejection = reject_fixture_in_production(args.source, os.environ.get(ENV_VARIABLE))
    if rejection is not None:
        print(f"거부: {rejection}", file=sys.stderr)
        return _EXIT_REJECTED

    try:
        if args.since is not None:
            parse_month(args.since)
        if args.baseline_month is not None:
            parse_month(args.baseline_month)
    except MacroSourceError as error:
        print(f"거부: {error}", file=sys.stderr)
        return _EXIT_REJECTED

    print(
        "사전 계산 본체는 묶음 M5에서 채운다 "
        "(tasks.md T058~T079). 지금은 플래그와 운영 적재 가드만 서 있다.",
        file=sys.stderr,
    )
    return _EXIT_NOT_IMPLEMENTED


if __name__ == "__main__":
    raise SystemExit(main())
