"""경제지표 수집 진입점 (spec 008 FR-017).

최초 한 번은 전 구간을 받고, 그 뒤로는 새 발표분과 최근 N개월만 다시 본다. 뒤쪽 구간을 매번
전부 다시 받으면 원천 한도를 쓸데없이 소모하고, 새 발표분만 받으면 정정을 놓친다 — 관세청은
매월 15일경 전월까지를 정정한다(pd11 §4).

사용 예::

    # 최초 전 구간 적재
    .venv/bin/python scripts/ingest_macro.py --mode initial

    # 정기 수집 — 새 발표분과 지표별 최근 N개월
    .venv/bin/python scripts/ingest_macro.py --mode regular

    # 지표 하나만
    .venv/bin/python scripts/ingest_macro.py --mode regular --indicator dubai_crude

    # 무엇을 부를지만 보고 실제로 부르지 않는다
    .venv/bin/python scripts/ingest_macro.py --mode initial --dry-run

**기본값이 없다.** ``--mode`` 는 반드시 적어야 한다. 최초 적재와 정기 수집은 호출량이 자릿수로
다르고, 기본값을 두면 어느 쪽이 도는지 모르는 채로 실행된다.

**아무것도 하지 않은 실행을 성공으로 말하지 않는다.** 대상이 0개면 그 사실을 적어 내보낸다 —
조용히 0으로 끝나는 실행은 배치가 돈 줄 알고 넘어가게 만든다.

수집 본체는 아직 없다(T018~T031). 지금은 대상을 고르고 계획을 보여주는 데까지 한다.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:  # 진입점을 `python scripts/...`로 바로 실행하는 경우
    sys.path.insert(0, str(_SRC))

from data_pipeline.macro import fetch as transport  # noqa: E402
from data_pipeline.macro import ingest, registry  # noqa: E402
from data_pipeline.macro.config import load_source_config  # noqa: E402

MODE_INITIAL = "initial"
MODE_REGULAR = "regular"
MODES = (MODE_INITIAL, MODE_REGULAR)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="ingest_macro",
        description="경제지표 17개를 공식 원천에서 수집한다 (spec 008)",
    )
    parser.add_argument(
        "--mode",
        required=True,
        choices=MODES,
        help="initial 은 전 구간, regular 는 새 발표분과 최근 N개월 (FR-017)",
    )
    parser.add_argument(
        "--indicator",
        action="append",
        default=None,
        metavar="INDICATOR_ID",
        help="이 지표만 수집한다. 여러 번 줄 수 있다. 생략하면 17개 전부",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="원천을 부르지 않고 대상과 계획만 보여준다",
    )
    parser.add_argument(
        "--data-root",
        default="/data",
        metavar="PATH",
        help="raw/processed 를 쓸 뿌리. 운영 컨테이너는 DATA_DIR 이 여기로 마운트된다",
    )
    return parser


def select_indicators(requested: list[str] | None) -> tuple[registry.IndicatorDefinition, ...]:
    """수집 대상을 고른다. 모르는 지표 id 는 조용히 건너뛰지 않고 멈춘다."""
    registry.validate()
    if not requested:
        return registry.INDICATORS

    known = {indicator.indicator_id: indicator for indicator in registry.INDICATORS}
    unknown = [indicator_id for indicator_id in requested if indicator_id not in known]
    if unknown:
        raise SystemExit(
            f"등록표에 없는 지표다: {', '.join(unknown)}\n"
            f"쓸 수 있는 값: {', '.join(sorted(known))}"
        )
    return tuple(known[indicator_id] for indicator_id in requested)


def describe_plan(
    indicators: tuple[registry.IndicatorDefinition, ...], mode: str
) -> list[str]:
    """무엇을 어디서 얼마만큼 받을지 한 줄씩 적는다."""
    lines: list[str] = []
    for indicator in indicators:
        if mode == MODE_INITIAL:
            span = f"{indicator.series_start} ~ 현재 (전 구간)"
        else:
            span = f"새 발표분 + 최근 {indicator.refresh_months_n}개월"
        lines.append(
            f"  {indicator.indicator_id:32} {indicator.primary_source:8} {span}"
        )
    return lines


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    indicators = select_indicators(args.indicator)

    print(f"모드: {args.mode} | 대상 {len(indicators)}개")
    for line in describe_plan(indicators, args.mode):
        print(line)

    if not indicators:
        print("대상이 0개다. 수집할 것이 없다")
        return 1

    if args.dry_run:
        print("--dry-run 이라 원천을 부르지 않았다")
        return 0

    timeout = max(load_source_config(source).timeout_seconds for source in registry.used_sources())
    outcomes = ingest.collect(
        indicators,
        fetcher=transport.RequestsFetcher(timeout),
        data_root=Path(args.data_root),
        mode=args.mode,
    )

    failed = [outcome for outcome in outcomes if not outcome.succeeded]
    for outcome in outcomes:
        mark = "ok  " if outcome.succeeded else "실패"
        detail = f" ({outcome.error_class})" if outcome.error_class else ""
        print(f"  {mark} {outcome.indicator_id:32} {outcome.status}{detail}")

    received = sum(len(outcome.observations) for outcome in outcomes)
    print(f"받은 관측 {received}건 / 실패 지표 {len(failed)}개")

    # 아무것도 받지 못한 실행을 성공으로 말하지 않는다. 0 으로 끝나면 배치가 돈 줄 알고 넘어간다.
    if received == 0:
        print("받은 관측이 0건이다. 원천·인증키·구간을 확인한다")
        return 1
    if failed:
        print("일부 지표를 받지 못했다. 발행은 17개가 모두 찬 뒤에만 한다(FR-011)")
        return 1
    print("수집을 마쳤다. 발행은 적재 단계에서 이어진다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
