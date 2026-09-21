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
import os
import sys
from pathlib import Path
from typing import Final

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:  # 진입점을 `python scripts/...`로 바로 실행하는 경우
    sys.path.insert(0, str(_SRC))

from sqlalchemy import create_engine  # noqa: E402

from data_pipeline.macro import fetch as transport  # noqa: E402
from data_pipeline.macro import ingest, registry  # noqa: E402
from data_pipeline.macro.config import load_source_config  # noqa: E402
from data_pipeline.repository import database_url_from_env  # noqa: E402

MODE_INITIAL = "initial"
MODE_REGULAR = "regular"
MODES = (MODE_INITIAL, MODE_REGULAR)

PROFILE_PROD = "prod"
PROFILE_DEV = "dev"
PROFILES = (PROFILE_PROD, PROFILE_DEV)

#: 예시 자료를 켜려면 함께 있어야 하는 환경변수 (FR-022).
#:
#: 깃발 하나로 켜지게 두지 않는 이유는, 운영 배치의 인자를 누가 복사해 붙이다가 섞여 들어갈
#: 수 있어서다. 환경변수는 실행 명령이 아니라 **환경**에 매여 있어 같이 복사되지 않는다.
SAMPLE_DATA_ENV: Final[str] = "MACRO_ALLOW_SAMPLE_DATA"


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
        "--profile",
        default=PROFILE_PROD,
        choices=PROFILES,
        help="dev 는 예시 자료를 쓸 수 있다. 기본은 prod 이며 예시 자료로 가는 길이 없다",
    )
    parser.add_argument(
        "--sample-root",
        default=None,
        metavar="PATH",
        help="dev 프로필에서 읽을 예시 응답 뿌리. prod 에서는 무시되지 않고 거부된다",
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


def build_fetcher(args: argparse.Namespace, timeout: float) -> transport.Fetcher:
    """프로필에 맞는 Fetcher 를 고른다 (FR-022).

    **운영에는 예시 자료로 가는 길이 없다.** 수집이 실패했다고 예시로 자동 대체하지 않으며,
    예시 인자를 운영 프로필에 주면 무시하지 않고 거부한다 — 무시하면 준 사람은 켜진 줄 알고,
    화면은 진짜 자료를 보여 주는 줄 안다. 둘 다 틀린 채로 굴러간다.

    개발 프로필은 **두 번 말해야** 켜진다. 인자와 환경변수가 모두 있어야 하며, 하나만 있으면
    켜지지 않고 왜 안 켜졌는지 말한다.
    """
    if args.profile == PROFILE_PROD:
        if args.sample_root is not None:
            raise SystemExit(
                "운영 프로필에 --sample-root 를 줬다. 운영은 예시 자료를 쓰지 않는다"
                f" (FR-022). 개발이면 --profile {PROFILE_DEV} 를 함께 준다"
            )
        return transport.RequestsFetcher(timeout)

    if os.environ.get(SAMPLE_DATA_ENV) != "1":
        raise SystemExit(
            f"--profile {PROFILE_DEV} 는 {SAMPLE_DATA_ENV}=1 이 함께 있어야 켜진다 (FR-022)."
            " 예시 자료가 운영 자료로 오인되지 않도록 두 번 확인한다"
        )
    if args.sample_root is None:
        raise SystemExit(f"--profile {PROFILE_DEV} 에는 --sample-root 가 필요하다")
    root = Path(args.sample_root)
    if not root.is_dir():
        raise SystemExit(f"예시 자료 뿌리가 없다: {root}")
    print(f"[예시 자료] {root} 를 읽는다. 이 실행의 결과는 실제 자료가 아니다")
    return transport.SampleFetcher(root)


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
    report = ingest.run(
        create_engine(database_url_from_env()),
        indicators,
        fetcher=build_fetcher(args, timeout),
        data_root=Path(args.data_root),
        mode=args.mode,
    )

    for outcome in report.outcomes:
        mark = "ok  " if outcome.succeeded else "실패"
        detail = f" ({outcome.error_class})" if outcome.error_class else ""
        print(f"  {mark} {outcome.indicator_id:32} {outcome.status}{detail}")

    print(
        f"받은 관측 {report.received_observations}건"
        f" / 실패 지표 {len(report.failed)}개"
        f" / 새 원본 이력 {report.raw_revisions_written}건"
    )

    # 아무것도 받지 못한 실행을 성공으로 말하지 않는다. 0 으로 끝나면 배치가 돈 줄 알고 넘어간다.
    if report.received_observations == 0:
        print("받은 관측이 0건이다. 원천·인증키·구간을 확인한다")
        return 1
    if not report.published:
        print(f"발행 보류: {report.publication_held}")
        # 보류 이유는 둘이다. 지표가 덜 찬 것(FR-011)과 전 구간이 짧은 것(FR-011b(3))인데,
        # 대응이 다르다 — 앞엣것은 실패한 지표를 고치고 뒤엣것은 공표 지연이나 원천 구간을 본다.
        print("17개가 다 차고 전 구간이 기대 대상월 수를 채운 뒤에만 공개한다. 수집 기록은 남았다")
        return 1
    print(f"자료 버전 {report.dataset_version_id} 를 발행했다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
