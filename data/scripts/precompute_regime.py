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

    # 계산이 잘못된 것으로 확인된 항목을 철회 (사람이 지정할 때만, spec FR-035)
    .venv/Scripts/python scripts/precompute_regime.py --withdraw \\
        --mode field --field {분야 id} --baseline-month 2018-06 --reason "척도 구간 오산정"

**아무것도 하지 않은 실행을 성공으로 말하지 않는다.** 재계산 대상이 0개면 그 사실을 소요 시간과
함께 적어 내보낸다 — 0으로 끝나는 조용한 실행은 배치가 돈 줄 알고 넘어가게 만든다(spec FR-033).
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from collections.abc import Mapping, Sequence
from datetime import date
from pathlib import Path
from typing import Any, Final

_SRC = Path(__file__).resolve().parents[1] / "src"
if str(_SRC) not in sys.path:  # 진입점을 `python scripts/...`로 바로 실행하는 경우
    sys.path.insert(0, str(_SRC))

from data_pipeline.regime.context import DEFAULT_CONTEXT, load_context  # noqa: E402
from data_pipeline.regime.load import (  # noqa: E402
    RegimeLoadError,
    RegimeRepository,
    baseline_record_for,
    snapshot_frame_rows,
)
from data_pipeline.regime.macro import (  # noqa: E402
    SOURCE_CHOICES,
    SOURCE_MACRO,
    create_source,
)
from data_pipeline.regime.macro.source import MacroIndicatorSource  # noqa: E402
from data_pipeline.regime.macro.types import (  # noqa: E402
    MacroSourceError,
    MonthlyValue,
    format_month,
    parse_month,
)
from data_pipeline.regime.phase import DEFAULT_CONFIG as DEFAULT_PHASE_CONFIG  # noqa: E402
from data_pipeline.regime.phase import PhaseConfigError  # noqa: E402
from data_pipeline.regime.phase import load_config as load_phase_config  # noqa: E402
from data_pipeline.regime.snapshot import (  # noqa: E402
    MODE_COMPOSITE,
    MODE_FIELD,
    SearchScope,
    Snapshot,
    SnapshotBuilder,
    Versions,
)
from data_pipeline.regime.watermark import (  # noqa: E402
    ORIGIN_REQUESTED,
    ORIGIN_SERIES_START,
    Watermark,
    recomputed_months,
    series_start,
    watermark_for,
)

_EXIT_REJECTED: Final[int] = 1
#: 일부 기준월이 실패했다. 그 기준월은 이전 `revision`이 그대로 남는다(spec FR-034).
_EXIT_PARTIAL: Final[int] = 4
#: 계산한 기준월이 하나도 없다. 0으로 끝내면 배치가 돈 줄 알고 넘어간다(spec FR-033).
_EXIT_NOTHING: Final[int] = 5

_DATA_ROOT: Final[Path] = Path(__file__).resolve().parents[1]
#: 중간 산출 Parquet의 뿌리. 파티션 규칙은 `data/CLAUDE.md`(임의 변경 금지).
PROCESSED_ROOT: Final[Path] = _DATA_ROOT / "processed"

#: 종합 비교 v3 국면 모델의 블록 구성 (spec 010 v3 개정안 §3.2). 시험은 대역 등록표에 맞춰 바꾼다.
PHASE_CONFIG: Path = DEFAULT_PHASE_CONFIG

#: 특이 사례에 붙이는 당시 맥락 표 (개정안 §4). `scripts/collect_regime_context.py`가 만든다.
CONTEXT_TABLE: Path = DEFAULT_CONTEXT
PROCESSED_DATASET: Final[str] = "regime_search"
#: 철회 기록. 표가 없으므로 파일로 남긴다 — 남기지 않은 철회가 0건이어야 한다(SC-012).
WITHDRAWAL_LOG: Final[Path] = PROCESSED_ROOT / PROCESSED_DATASET / "withdrawals.jsonl"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="경제환경 검색 사전 계산 (spec 010 FR-030)",
    )
    parser.add_argument(
        "--source",
        choices=SOURCE_CHOICES,
        default=SOURCE_MACRO,
        help=f"지표 소스. 지금은 {SOURCE_MACRO}(008 구현체) 하나다",
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
        help="이 기준월 하나만 계산한다. --withdraw에서는 철회할 기준월이다",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="계산만 하고 적재하지 않는다",
    )
    parser.add_argument(
        "--env-file",
        metavar="PATH",
        help="DATABASE_URL을 읽을 .env 경로. 생략하면 기본 탐색을 쓴다",
    )
    withdraw = parser.add_argument_group(
        "철회 (spec FR-035)",
        "계산이 잘못된 것으로 확인된 항목만 지운다. 배치가 스스로 판단하지 않는다",
    )
    withdraw.add_argument(
        "--withdraw",
        action="store_true",
        help="지정한 범위의 revision을 지워 이전 것이 다시 최신이 되게 한다",
    )
    withdraw.add_argument(
        "--mode",
        choices=(MODE_FIELD.lower(), MODE_COMPOSITE.lower()),
        help="철회할 검색 모드",
    )
    withdraw.add_argument("--field", metavar="FIELD_ID", help="철회할 분야 id (field 모드)")
    withdraw.add_argument(
        "--revision", type=int, metavar="N", help="철회할 revision. 생략하면 가장 최신"
    )
    withdraw.add_argument("--reason", metavar="TEXT", help="철회 사유. 기록에 남는다")
    return parser




def load_series(
    source: MacroIndicatorSource,
) -> dict[str, dict[date, MonthlyValue]]:
    """등록표의 모든 지표 계열을 한 번에 읽는다.

    **`raw_series()`를 부르지 않는다.** 거리도 설명 문장도 변환 후 값을 쓴다(spec FR-015,
    포트 계약 §1) — 원값을 함께 읽어 두면 어느 자리에선가 섞여 들어간다.
    """
    registry = source.registry()
    return {entry.id: source.monthly_series(entry.id) for entry in registry.indicators}


def scopes_of(source: MacroIndicatorSource) -> list[tuple[SearchScope, date | None]]:
    """계산할 (범위, 그 범위의 최신 기준월) 목록 — 분야 순서대로, 종합이 마지막이다.

    최신 기준월이 ``None``인 범위는 008이 T_g·T_all을 내지 못한 경우다. 그때도 범위를 목록에서
    빼지 않는다 — 부르는 쪽이 관측 최신월로 물러나 계산 불가를 **행으로** 남긴다(spec FR-039).
    """
    registry = source.registry()
    baselines = source.baseline_months()
    scopes: list[tuple[SearchScope, date | None]] = [
        (SearchScope.field(field.id), baselines.by_field.get(field.id))
        for field in registry.ordered_fields()
    ]
    scopes.append((SearchScope.composite(), baselines.composite))
    return scopes


def write_processed(rows: Sequence[Mapping[str, Any]], root: Path) -> list[Path]:
    """중간 산출을 ``범위/연도`` 파티션 Parquet으로 쓴다 (T073).

    파티션 구조는 `data/CLAUDE.md`가 정한 ``{dataset}/{키}={값}/year={연도}``를 그대로 따른다 —
    추후 Spark가 같은 구조로 읽는 전제라 임의로 바꾸지 않는다. 같은 파티션을 다시 쓰면 덮어써서
    재실행이 멱등하다.
    """
    if not rows:
        return []
    import pandas as pd

    frame = pd.DataFrame(list(rows))
    written: list[Path] = []
    years = pd.to_datetime(frame["baseline_month"]).dt.year
    for (scope_id, year), part in frame.groupby([frame["scope_id"], years], sort=True):
        target = root / PROCESSED_DATASET / f"scope_id={scope_id}" / f"year={int(year)}"
        target.mkdir(parents=True, exist_ok=True)
        path = target / "part-0.parquet"
        part.reset_index(drop=True).to_parquet(path, index=False)
        written.append(path)
    return written


def append_withdrawal(record: Mapping[str, Any], path: Path) -> None:
    """철회 기록을 한 줄 덧붙인다 (spec FR-035, SC-012).

    덮어쓰지 않는다 — 무엇을 왜 지웠는지가 남지 않으면 철회와 자료 유실이 구분되지 않는다.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")


def _withdraw(args: argparse.Namespace) -> int:
    """철회 명령 (spec FR-035). 배치 본체와 같은 실행에 섞지 않는다."""
    missing = [
        name
        for name, value in (
            ("--mode", args.mode),
            ("--baseline-month", args.baseline_month),
            ("--reason", args.reason),
        )
        if not value
    ]
    if missing:
        print(f"거부: 철회에는 {', '.join(missing)}이(가) 있어야 한다", file=sys.stderr)
        return _EXIT_REJECTED
    mode = args.mode.upper()
    if mode == MODE_FIELD and not args.field:
        print("거부: 분야 검색을 철회하려면 --field가 있어야 한다", file=sys.stderr)
        return _EXIT_REJECTED
    if mode == MODE_COMPOSITE and args.field:
        print("거부: 종합 검색에는 --field를 주지 않는다", file=sys.stderr)
        return _EXIT_REJECTED

    scope = (
        SearchScope.field(args.field) if mode == MODE_FIELD else SearchScope.composite()
    )
    repository = RegimeRepository.from_env(Path(args.env_file) if args.env_file else None)
    try:
        withdrawal = repository.withdraw(
            scope=scope,
            baseline_month=parse_month(args.baseline_month),
            reason=args.reason,
            revision=args.revision,
        )
    except RegimeLoadError as error:
        print(f"거부: {error}", file=sys.stderr)
        return _EXIT_REJECTED
    record = withdrawal.to_record()
    append_withdrawal(record, WITHDRAWAL_LOG)
    print(f"철회: {record['snapshotKey']} revision={record['revision']}")
    print(f"사유: {record['reason']}")
    print(f"기록: {WITHDRAWAL_LOG}")
    if withdrawal.detached_logs:
        print(f"참조하던 기록 {withdrawal.detached_logs}건이 '철회됨'이 된다")
    return 0


def _requested_start(
    args: argparse.Namespace, series: Mapping[str, Mapping[date, MonthlyValue]]
) -> date | None:
    if args.all:
        return series_start(series)
    if args.since:
        return parse_month(args.since)
    return None


def _precompute(args: argparse.Namespace, source: MacroIndicatorSource) -> int:
    started = time.monotonic()
    registry = source.registry()
    series = load_series(source)
    versions = Versions(data=source.data_version(), registry=registry.version)
    # 종합 비교는 v3 국면 모델이다(spec 010 v3-experiment.md §6 채택). 분야 검색은 v1 그대로다.
    # 블록 구성이 등록표와 어긋나면 계산을 시작하지 않는다 — 빠진 지표가 조용히 종합에서 사라진다.
    try:
        phase_config = load_phase_config(
            PHASE_CONFIG, indicator_ids=[entry.id for entry in registry.indicators]
        )
    except PhaseConfigError as error:
        print(f"거부: {error}", file=sys.stderr)
        return _EXIT_REJECTED
    builder = SnapshotBuilder(
        registry,
        series,
        source.publication,
        versions,
        phase_config=phase_config,
        context_events=load_context(CONTEXT_TABLE),
    )

    repository = (
        None
        if args.dry_run
        else RegimeRepository.from_env(Path(args.env_file) if args.env_file else None)
    )
    loaded_version = None if repository is None else repository.loaded_data_version()
    watermark = watermark_for(
        source,
        series,
        loaded_data_version=loaded_version,
        requested=_requested_start(args, series),
    )
    only = parse_month(args.baseline_month) if args.baseline_month else None

    snapshots: list[Snapshot] = []
    results: dict[str, Any] = {}
    failures: list[dict[str, Any]] = []
    inserted = 0
    unchanged = 0

    for scope, latest in scopes_of(source):
        months = builder.observable_months(scope)
        if not months:
            continue
        # T_g·T_all이 없으면 관측 최신월로 물러난다. 그 달의 결과는 계산 불가로 저장되며,
        # 조회는 404가 아니라 200 + `NOT_COMPUTABLE`을 받는다(spec FR-039, 계약 §1).
        horizon = latest if latest is not None else months[-1]
        scope_watermark = watermark
        if (
            repository is not None
            and watermark.origin != ORIGIN_REQUESTED
            and not repository.has_model_version(scope, builder.versions_for(scope).model)
        ):
            # 모델 버전이 바뀐 범위는 전 구간이다(data-model §6). 종합이 v3로 바뀐 첫 실행이 여기다.
            scope_watermark = Watermark(month=series_start(series), origin=ORIGIN_SERIES_START)
        targets = [
            month
            for month in recomputed_months(months, scope_watermark)
            if month <= horizon and (only is None or month == only)
        ]
        for month in targets:
            try:
                snapshot = builder.build(scope, month)
            except Exception as error:  # noqa: BLE001 - 한 기준월의 실패가 배치를 끝내지 않는다
                failures.append(
                    {
                        "scopeId": scope.scope_id,
                        "baselineMonth": format_month(month),
                        "stage": "BUILD",
                        "reason": f"{type(error).__name__}: {error}",
                    }
                )
                continue
            snapshots.append(snapshot)
            if repository is None:
                continue
            try:
                result = repository.save(
                    snapshot,
                    baseline_record=(
                        baseline_record_for(scope, horizon, builder.versions_for(scope))
                        if month == horizon
                        else None
                    ),
                )
            except Exception as error:  # noqa: BLE001 - 이전 revision이 그대로 남는다
                failures.append(
                    {
                        "scopeId": scope.scope_id,
                        "baselineMonth": format_month(month),
                        "stage": "LOAD",
                        "reason": f"{type(error).__name__}: {error}",
                    }
                )
                continue
            results[snapshot.key] = result
            if result.inserted:
                inserted += 1
            else:
                unchanged += 1

    pruned = 0 if repository is None else repository.prune()
    written = write_processed(
        snapshot_frame_rows(snapshots, results or None), PROCESSED_ROOT
    )
    elapsed = time.monotonic() - started

    print(
        f"자료 버전: {versions.data} / 등록표 {versions.registry} / 모델 {versions.model}"
        f" (종합 {phase_config.version})"
    )
    print(f"재계산 시작월: {watermark.describe()}")
    print(f"다시 계산한 기준월: {len(snapshots)}개 (범위 × 기준월)")
    if repository is not None:
        print(f"새 revision {inserted}개 / 내용이 같아 넘어간 항목 {unchanged}개")
        print(f"보관 정리로 지운 항목: {pruned}개")
    else:
        print("--dry-run이라 적재하지 않았다")
    print(f"중간 산출 파티션: {len(written)}개")
    print(f"소요 시간: {elapsed:.1f}초")
    if not snapshots:
        print(
            "계산한 기준월이 없다 — 워터마크가 자료 범위 밖이거나 --baseline-month가 "
            "대상에 없다. 아무것도 하지 않았다",
            file=sys.stderr,
        )
        return _EXIT_NOTHING
    if failures:
        print(
            f"실패한 기준월 {len(failures)}개 — 그 달은 이전 revision이 그대로 남는다:",
            file=sys.stderr,
        )
        for failure in failures:
            print(
                f"  {failure['scopeId']} {failure['baselineMonth']} "
                f"[{failure['stage']}] {failure['reason']}",
                file=sys.stderr,
            )
        return _EXIT_PARTIAL
    return 0


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        if args.since is not None:
            parse_month(args.since)
        if args.baseline_month is not None:
            parse_month(args.baseline_month)
    except MacroSourceError as error:
        print(f"거부: {error}", file=sys.stderr)
        return _EXIT_REJECTED

    if args.withdraw:
        return _withdraw(args)
    return _precompute(args, create_source(args.source))


if __name__ == "__main__":
    raise SystemExit(main())
