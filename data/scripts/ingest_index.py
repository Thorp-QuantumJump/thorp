"""지수 전용 수집 진입점 (spec 001 T007, FR-009·FR-014, contracts/market-dataset.md ※1).

**이 자료가 거래일 달력의 출처다.** ``daily_index``의 날짜 집합이 거래일 달력이며 별도 표를
두지 않는다. 개별 ETF는 거래정지될 수 있지만 지수는 장이 열리면 언제나 산출되므로, 지수에
그 날짜가 있다는 사실이 그날 장이 열렸다는 근거가 된다(:mod:`data_pipeline.index`).
그래서 이 스크립트만 따로 돌려도 뜻이 있다 — ETF 수집 전에 달력을 먼저 확보하거나,
확보 기간이 대표 ETF의 상장 이후를 덮는지 확인하는 용도다(FR-009).

``ingest_krx.py``도 같은 지수를 먼저 수집한다. 두 진입점이 같은 데이터 마감일을 만들면
같은 ``dataset_id``가 되어 뒤에 부른 쪽이 거부된다(FR-017). 자료를 고쳐야 하면 같은 버전을
손대지 않고 새 마감일로 발행한다.

사용 예::

    python scripts/ingest_index.py --start 2026-09-01 --until 2026-09-10 --map-version-id 1
    python scripts/ingest_index.py ... --index-codes KOSPI KOSPI200 --commit

인증키는 ``.env``의 ``KRX_API_KEY``에서만 읽는다(FR-012).
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from datetime import UTC, date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any

# `scripts/`를 직접 실행하면 `src/`가 sys.path에 없다. `ingest_krx.py`와 같은 이유다.
_DATA_ROOT = Path(__file__).resolve().parents[1]
if str(_DATA_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(_DATA_ROOT / "src"))

from data_pipeline import index, krx, repository  # noqa: E402
from data_pipeline.dataset_version import (  # noqa: E402
    SOURCE_KRX_OPEN_API,
    DatasetVersion,
    DatasetVersionError,
    build_dataset_id,
)

_RAW_ROOT = _DATA_ROOT / "raw"

#: 정규장 종료 시각과 시간대. ``ingest_krx.py``와 같은 값을 쓴다 — 두 진입점이 만든 마감
#: 시각이 다르면 같은 거래일의 자료 버전이 서로 다른 시각을 말하게 된다.
_KST: timezone = timezone(timedelta(hours=9))
_MARKET_CLOSE: time = time(15, 30)


class IndexIngestError(RuntimeError):
    """지수 수집 진입점이 더 진행할 수 없는 상태."""


def _parse_day(text: str) -> date:
    try:
        return date.fromisoformat(text)
    except ValueError as error:
        raise argparse.ArgumentTypeError(f"YYYY-MM-DD 형식이어야 한다: {text!r}") from error


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "KOSPI 시리즈 지수를 수집해 daily_index에 적재한다. "
            "이 표의 날짜 집합이 거래일 달력이며 별도 표를 두지 않는다. "
            "지수는 장이 열리면 언제나 산출되고 휴장일에는 빈 배열이 온다."
        )
    )
    parser.add_argument("--start", required=True, type=_parse_day, help="수집 시작일 YYYY-MM-DD")
    parser.add_argument("--until", required=True, type=_parse_day, help="수집 종료일 YYYY-MM-DD")
    parser.add_argument(
        "--index-codes",
        nargs="+",
        default=[index.CALENDAR_INDEX_CODE],
        metavar="CODE",
        help=(
            f"수집할 지수 코드. 쓸 수 있는 값은 {sorted(index.INDEX_SERIES)}이며 "
            f"거래일 달력의 근거는 {index.CALENDAR_INDEX_CODE}다 (기본값)"
        ),
    )
    parser.add_argument(
        "--map-version-id",
        required=True,
        type=int,
        help="대상 섹터-ETF 대응표 버전 (dataset_version.map_version_id, NOT NULL)",
    )
    parser.add_argument(
        "--simulate-quota-exhausted",
        action="store_true",
        help="이용 한도 도달을 재현한다 (FR-014). 실제 한도를 쓰지 않고 처리 경로만 확인한다",
    )
    parser.add_argument(
        "--commit",
        action="store_true",
        help=(
            "PostgreSQL에 적재한다. 없으면 수집까지만 하고 요약만 낸다. "
            "발행된 자료 버전은 UPDATE·DELETE가 막혀 되돌릴 수 없다 (FR-017)"
        ),
    )
    parser.add_argument(
        "--env-file",
        type=Path,
        default=None,
        help=".env 경로(선택). 지정하면 그 파일의 값이 이미 내보내진 환경 변수를 이긴다",
    )
    return parser


def resolve_index_codes(values: Sequence[str]) -> list[str]:
    """지수 코드를 검사한다. :data:`index.INDEX_SERIES` 에 없는 코드는 거부한다.

    수집을 시작한 뒤 거부하면 이용 한도를 쓴 채로 실패한다. 원천 이름과의 대응은 한 곳
    (``INDEX_SERIES``)에만 있으므로 호출 전에 여기서 걸러낸다.
    """
    resolved = list(dict.fromkeys(value.strip().upper() for value in values))
    unknown = [code for code in resolved if code not in index.INDEX_SERIES]
    if unknown:
        raise IndexIngestError(
            f"모르는 지수 코드다: {unknown}. 쓸 수 있는 값은 {sorted(index.INDEX_SERIES)}이며 "
            "새 지수를 쓰려면 index.INDEX_SERIES에 원천 이름을 먼저 등록해야 한다"
        )
    return resolved


def run(args: argparse.Namespace) -> int:
    codes = resolve_index_codes(args.index_codes)
    candidates = index.calendar_day_range(args.start, args.until)

    client = krx.KrxOpenApiClient.from_env(args.env_file)
    source: krx.DailyRowSource = krx.RawArchivingSource(inner=client, root=_RAW_ROOT)
    if args.simulate_quota_exhausted:
        # 한도 재현 덮개는 보관 덮개 **바깥**이다. 한도에 걸린 호출은 응답이 오지 않은
        # 호출이라 raw/에도 남지 않아야 한다.
        source = krx.QuotaLimitedSource(inner=source, allowed_calls=max(len(candidates) - 1, 1))

    collection = index.collect_index_quotes(source, candidates, index_codes=codes)
    # 마감일은 호출을 마친 마지막 날짜가 아니라 **값을 얻은 마지막 거래일**이다.
    # 후보가 달력 날짜라 호출 목록의 끝은 휴장일일 수 있고, 그대로 쓰면 마감일과
    # dataset_id가 휴장일로 발행된다. 발행본은 되돌릴 수 없다 (FR-017).
    deadline = collection.last_trading_day()
    if deadline is None:
        raise IndexIngestError(
            f"{args.start} ~ {args.until} 구간에서 하루도 수집하지 못해 데이터 마감일을 "
            "정할 수 없다"
        )
    calendar = collection.trading_calendar()
    dataset_id = build_dataset_id(deadline)
    rows: list[dict[str, Any]] = collection.to_rows()

    loaded: int | None = None
    if args.commit:
        now = datetime.now(UTC)
        version = DatasetVersion(
            dataset_id=dataset_id,
            data_deadline=datetime.combine(deadline, _MARKET_CLOSE, tzinfo=_KST),
            collected_at=now,
            last_success_at=now,
            complete=collection.complete,
            is_sample=False,
            source=SOURCE_KRX_OPEN_API,
            map_version_id=args.map_version_id,
            failure_reason=collection.failure_reason,
        )
        # PostgreSQL을 건드리기 전에 같은 열 정의의 인메모리 대역에 한 번 넣어 본다.
        # load_daily_index가 행 검사에서 걸리면 자료 버전 행은 이미 커밋되어 있고, 발행본은
        # DELETE도 거부되어 그 dataset_id가 빈 채로 영구히 남는다 (FR-017).
        # ingest_krx.py가 같은 이유로 같은 예행을 거친다.
        rehearsal = repository.MarketDataRepository.rehearsal()
        try:
            rehearsal.publish_dataset_version(version)
            rehearsal.load_daily_index(dataset_id, rows)
        finally:
            rehearsal.engine.dispose()

        store = repository.MarketDataRepository.from_env(args.env_file)
        # 자료 버전 → 시계열. 이 순서를 뒤집을 수 없다.
        store.publish_dataset_version(version)
        loaded = store.load_daily_index(dataset_id, rows)

    print(f"자료 버전: {dataset_id}")
    print(f"데이터 마감일: {deadline} (요청 종료일 {args.until})")
    print(f"지수 코드: {', '.join(codes)}")
    # 줄글에 em dash(U+2014)를 쓰지 않는다. 윈도 콘솔의 기본 부호화(cp949)가 그 글자를
    # 담지 못해 출력 자체가 UnicodeEncodeError로 죽는다.
    print(
        f"거래일 달력: {len(calendar)}일. {index.CALENDAR_INDEX_CODE}에 값이 있는 날짜가 "
        "그날 장이 열렸다는 근거이며, daily_index가 그 달력을 담는 표다"
    )
    print(f"daily_index: {len(rows)}행")
    print(f"수집 완료 여부: {'완료' if collection.complete else '부분 갱신'}")
    if not collection.complete:
        print(f"중단 사유: {collection.failure_reason}")
    if loaded is None:
        print("적재하지 않았다. PostgreSQL에 넣으려면 --commit을 붙인다 (발행본은 되돌릴 수 없다)")
    else:
        print(f"적재 완료: daily_index {loaded}행")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except (
        IndexIngestError,
        krx.KrxError,
        DatasetVersionError,
        repository.RepositoryError,
    ) as error:
        print(f"지수 수집을 중단했다: {type(error).__name__}: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
