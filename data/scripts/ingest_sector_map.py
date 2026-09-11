"""섹터-ETF 대응표 발행 진입점 (spec 001 T005, FR-001·FR-002·FR-003).

사용 예::

    # PD-01 확정 전까지 쓰는 예시 대응표를 발행한다 (FR-002)
    python scripts/ingest_sector_map.py --sample

    # 확정된 대응표를 이름을 정해 발행한다 (T014)
    python scripts/ingest_sector_map.py --map-file map.json --map-version SEM-2026-09-11-001

    # 저장소를 건드리지 않고 파일만 검사한다
    python scripts/ingest_sector_map.py --sample --dry-run

재실행이 멱등하다. 지금 열려 있는 버전의 내용이 발행하려는 내용과 같으면 아무것도 만들지 않고
그대로 끝난다. 같은 내용을 다른 ``--map-version``으로 다시 돌려도 버전은 늘지 않는다 — 버전을
가르는 것은 내용이고 이름은 별칭이기 때문이다 (``sector_map.SectorEtfMap.fingerprint``).

접속 문자열은 ``.env``의 ``DATABASE_URL``에서 ``repository.database_url_from_env``를 거쳐
읽는다. 코드에 하드코딩하지 않는다 (``data/CLAUDE.md``).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from typing import Final

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:  # 진입점을 `python scripts/...`로 바로 실행하는 경우
    sys.path.insert(0, str(_SRC))

from data_pipeline.sector_map import (  # noqa: E402
    SAMPLE_SECTOR_MAP_PATH,
    LeveragedEtfRejectedError,
    PublishResult,
    SectorEtfMap,
    SectorMapError,
    SectorMapRepository,
    load_sector_map,
)

_EXIT_OK: Final[int] = 0
_EXIT_REJECTED: Final[int] = 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="섹터-ETF 대응표 발행 (spec 001 FR-001·FR-003)")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument(
        "--sample",
        action="store_true",
        help=f"PD-01 확정 전까지 쓰는 예시 대응표를 발행한다 ({SAMPLE_SECTOR_MAP_PATH.name})",
    )
    source.add_argument("--map-file", type=Path, help="발행할 대응표 JSON 경로")
    parser.add_argument(
        "--map-version",
        help="대응표 버전 이름. 생략하면 SEM-{YYYY-MM-DD}-{NNN}을 자동으로 만든다",
    )
    parser.add_argument("--env-file", type=Path, help="DATABASE_URL을 읽을 .env 경로")
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="저장소를 건드리지 않고 대응표 파일 검증만 한다",
    )
    return parser


def _describe(sector_map: SectorEtfMap, path: Path) -> None:
    kind = "예시 대응표 (FR-002, is_sample=true)" if sector_map.is_sample else "확정 대응표"
    print(f"{kind}: {path}")
    print(f"섹터 {len(sector_map.entries)}개, 내용 지문 {sector_map.fingerprint()[:12]}")
    for entry in sorted(sector_map.entries, key=lambda item: item.code):
        etf = entry.etf
        print(
            f"  {entry.code:<20} {entry.name} ({entry.classification}) → "
            f"{etf.instrument_id} {etf.name} / 상장 {etf.listed_on} / "
            f"{etf.benchmark_index} / {etf.manager}"
        )


def _report(result: PublishResult) -> None:
    if not result.created:
        print(
            f"이미 같은 내용이 {result.version}으로 열려 있다. 새 버전을 만들지 않았다 "
            f"(map_version_id={result.map_version_id})"
        )
        return
    if result.closed_version is not None:
        print(f"이전 버전 {result.closed_version}을(를) 닫았다")
    print(
        f"대응표 버전 {result.version} 발행 "
        f"(map_version_id={result.map_version_id}, 항목 {result.entry_count}개)"
    )


def main(argv: list[str] | None = None, *, repository: SectorMapRepository | None = None) -> int:
    """``repository``를 넘기면 그 저장소에 발행한다. 넘기지 않으면 ``.env``로 연다."""
    args = build_parser().parse_args(argv)
    path: Path = SAMPLE_SECTOR_MAP_PATH if args.sample else args.map_file

    try:
        sector_map = load_sector_map(path)
    except LeveragedEtfRejectedError as error:
        # 거부 근거를 남긴다. 저장 스키마는 leveraged·inverse가 참인 행을 CHECK로 거부하므로
        # 표에 남길 자리가 없고, 이 출력이 기록이다 (FR-001).
        print(f"거부: {error}", file=sys.stderr)
        return _EXIT_REJECTED
    except SectorMapError as error:
        print(f"거부: {error}", file=sys.stderr)
        return _EXIT_REJECTED

    if args.sample and not sector_map.is_sample:
        print(
            f"거부: --sample로 발행하는 대응표는 isSample이 true여야 한다 (FR-002): {path}",
            file=sys.stderr,
        )
        return _EXIT_REJECTED

    _describe(sector_map, path)
    if args.dry_run:
        print("--dry-run이라 저장소를 건드리지 않았다")
        return _EXIT_OK

    target = repository or SectorMapRepository.from_env(args.env_file)
    try:
        result = target.publish(sector_map, version=args.map_version)
    except SectorMapError as error:
        print(f"거부: {error}", file=sys.stderr)
        return _EXIT_REJECTED
    _report(result)
    return _EXIT_OK


if __name__ == "__main__":
    raise SystemExit(main())
