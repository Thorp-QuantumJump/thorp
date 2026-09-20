"""등록표를 DB에 세우는 진입점 (spec 008 FR-002·FR-003).

**수집보다 먼저 한 번 돌린다.** `ingest_macro.py` 는 현재 포인터를 읽어 세 버전을 고정하는데,
포인터가 없으면 그 자리에서 멈춘다. 계산 등록표와 화면 분류는 자료보다 먼저 있어야 한다 — 첫
수집 전에도 화면은 17개가 무엇인지 보여줄 수 있어야 하기 때문이다.

사용 예::

    # 무엇을 세울지만 본다
    .venv/bin/python scripts/register_macro_registry.py --dry-run

    # 실제로 세운다
    .venv/bin/python scripts/register_macro_registry.py \\
        --calculation-key macro-calc-v1 --display-key macro-display-v1

**두 번 돌려도 안전하지 않다.** 같은 버전 키로 다시 세우려 하면 거절된다 — 발행한 등록표는
불변이고, 값을 고치려면 행을 고치는 것이 아니라 새 버전을 올린다(FR-003). 이미 포인터가 있으면
건드리지 않고 그 사실을 알린다.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from sqlalchemy import create_engine, text

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:  # 진입점을 `python scripts/...`로 바로 실행하는 경우
    sys.path.insert(0, str(_SRC))

from data_pipeline.macro import display, registry  # noqa: E402
from data_pipeline.macro.versioning import (  # noqa: E402
    initialize_current_publication,
    register_calculation_version,
    register_display_version,
)
from data_pipeline.repository import database_url_from_env  # noqa: E402


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="register_macro_registry",
        description="17개 지표의 계산 등록표와 화면 분류를 세운다 (spec 008)",
    )
    parser.add_argument("--calculation-key", default="macro-calc-v1")
    parser.add_argument("--display-key", default="macro-display-v1")
    parser.add_argument(
        "--reason", default="초기 등록 (pd11 실조회 확정값)", help="이 버전을 만든 이유"
    )
    parser.add_argument(
        "--dry-run", action="store_true", help="DB를 건드리지 않고 세울 내용만 보여준다"
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    registry.validate()
    indicators = registry.INDICATORS

    print(f"계산 등록표 {args.calculation_key} — 지표 {len(indicators)}개")
    for field_id, (name, order, _) in sorted(display.FIELDS.items(), key=lambda x: x[1][1]):
        members = [i.indicator_id for i in indicators if i.field_id == field_id]
        print(f"  {order}. {name:12} {len(members)}개")
    print(
        f"화면 분류 {args.display_key} — 분야 {len(display.FIELDS)}개"
        f" / 지표 {len(display.INDICATORS)}개"
    )

    if args.dry_run:
        print("--dry-run 이라 DB를 건드리지 않았다")
        return 0

    engine = create_engine(database_url_from_env())
    with engine.begin() as connection:
        existing = connection.execute(
            text(
                "SELECT registry_version_id FROM macro_current_publication"
                " WHERE singleton_key = 'X'"
            )
        ).scalar_one_or_none()
    if existing is not None:
        # 조용히 덮지 않는다. 다른 작업이 세운 버전을 지우면 그 버전을 참조하던 자료가 뜬다.
        print(f"이미 포인터가 있다 (계산 등록표 {existing}). 새 버전을 올리려면 versioning 을 쓴다")
        return 1

    calculation_id = register_calculation_version(
        engine, args.calculation_key, args.reason, indicators
    )
    display_id = register_display_version(
        engine,
        args.display_key,
        args.reason,
        display.FIELDS,
        display.INDICATORS,
        indicators,
    )
    initialize_current_publication(engine, calculation_id, display_id)
    print(f"세웠다 — 계산 등록표 {calculation_id} / 화면 분류 {display_id}")
    print("이제 scripts/ingest_macro.py 로 수집할 수 있다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
