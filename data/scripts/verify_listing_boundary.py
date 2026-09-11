"""상장 구간 경계 검증 (spec 001 T007, SC-003·FR-005, quickstart 시나리오 2).

발행된 자료 버전의 ``daily_price``에 **상장 전·상장폐지 후 가격 행이 있는지 센다.** 수집
단계의 :func:`data_pipeline.krx.enforce_listing_window` 가 같은 조건을 막지만, 이 스크립트는
저장소에 실제로 들어간 것을 읽어 확인한다. 막는 쪽과 확인하는 쪽이 같은 코드면 그 코드가
틀렸을 때 아무도 알아채지 못한다.

**위반이 하나라도 있으면 0이 아닌 종료 코드로 끝낸다.** 행이 한 줄도 없는 경우도 위반으로
본다 — 빈 표를 조용히 통과시키면 검증 장치가 꺼진 것과 같다.

첫 행 날짜가 ``listed_on``과 같은지는 **함께 출력하되 위반으로 세지 않는다.** 수집 구간을
상장일보다 뒤에서 시작할 수 있어(``--start``) 그때는 첫 행이 상장일보다 뒤인 것이 정상이다.
SC-003이 요구하는 것은 "상장 전 자료를 만들지 않았다"이고 그 판정은 상장 전 행 수가 한다.

대응표(``representative_etf``)에 없는 종목의 가격 행도 위반이다. 견줄 상장일이 없어 경계를
확인할 수 없는데, 확인하지 못한 것을 통과로 세면 검사가 비게 된다.

사용 예::

    python scripts/verify_listing_boundary.py --dataset KRX_DAILY_20260910
"""

from __future__ import annotations

import argparse
import sys
from collections.abc import Sequence
from pathlib import Path

from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError

# `scripts/`를 직접 실행하면 `src/`가 sys.path에 없다. `ingest_krx.py`와 같은 이유다.
_DATA_ROOT = Path(__file__).resolve().parents[1]
if str(_DATA_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(_DATA_ROOT / "src"))

from data_pipeline.dataset_version import (  # noqa: E402
    DatasetVersionError,
    validate_dataset_id,
)
from data_pipeline.repository import (  # noqa: E402
    RepositoryError,
    database_url_from_env,
)

#: 자료 버전 존재 확인. 발행되지 않은 ``dataset_id``를 검사하면 "위반 0건"이 나오는데,
#: 그것은 통과가 아니라 검사할 자료가 없다는 뜻이라 구분해서 알린다.
_VERSION_SQL = text(
    "SELECT complete, failure_reason, data_deadline"
    " FROM dataset_version WHERE dataset_id = :dataset"
)

#: 종목별 경계. ``representative_etf``는 ``repository.py``에 SQLAlchemy ``Table`` 정의가 없다.
#: 정의를 더하면 ``test_repository.py``의 열 대조가 그 표까지 요구하게 되므로, 이 스크립트에서만
#: 쓰는 읽기 질의는 SQL로 직접 쓴다.
#: LEFT JOIN인 이유는 대응표에 없는 종목을 JOIN이 조용히 버리지 않게 하기 위함이다.
_BOUNDARY_SQL = text(
    """
    SELECT dp.instrument_id                                   AS instrument_id,
           COUNT(*)                                           AS row_count,
           MIN(dp.date)                                       AS first_date,
           MAX(dp.date)                                       AS last_date,
           re.listed_on                                       AS listed_on,
           re.delisted_on                                     AS delisted_on,
           COUNT(*) FILTER (WHERE dp.date < re.listed_on)     AS before_listing,
           COUNT(*) FILTER (
               WHERE re.delisted_on IS NOT NULL AND dp.date > re.delisted_on
           )                                                  AS after_delisting
      FROM daily_price dp
      LEFT JOIN representative_etf re ON re.instrument_id = dp.instrument_id
     WHERE dp.dataset_id = :dataset
     GROUP BY dp.instrument_id, re.listed_on, re.delisted_on
     ORDER BY dp.instrument_id
    """
)


class VerificationError(RuntimeError):
    """검증을 수행할 수 없는 상태. 검증 실패(위반 발견)와 구분한다."""


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "발행된 자료 버전의 상장 전·상장폐지 후 가격 행을 센다 (SC-003). "
            "위반이 하나라도 있으면 0이 아닌 종료 코드로 끝낸다."
        )
    )
    parser.add_argument(
        "--dataset",
        required=True,
        help="검사할 자료 버전 식별자 (KRX_DAILY_{YYYYMMDD})",
    )
    parser.add_argument(
        "--env-file",
        type=Path,
        default=None,
        help=".env 경로(선택). 지정하면 그 파일의 값이 이미 내보내진 환경 변수를 이긴다",
    )
    return parser


def run(args: argparse.Namespace) -> int:
    dataset_id = validate_dataset_id(args.dataset)
    # 접속 문자열은 반드시 이 함수를 거친다. `.env`의 `postgresql://`을 그대로 넘기면
    # SQLAlchemy가 psycopg2로 해석하는데 이 파트에 설치된 것은 psycopg 3뿐이다.
    engine = create_engine(database_url_from_env(args.env_file))
    try:
        with engine.connect() as conn:
            version = conn.execute(_VERSION_SQL, {"dataset": dataset_id}).first()
            if version is None:
                raise VerificationError(
                    f"발행된 자료 버전이 아니다: {dataset_id}. 검사할 자료가 없는 것이므로 "
                    "위반 0건으로 통과시키지 않는다"
                )
            rows = conn.execute(_BOUNDARY_SQL, {"dataset": dataset_id}).all()
    finally:
        engine.dispose()

    before_listing = sum(int(row.before_listing) for row in rows)
    after_delisting = sum(int(row.after_delisting) for row in rows)
    unmapped = sum(int(row.row_count) for row in rows if row.listed_on is None)
    total = sum(int(row.row_count) for row in rows)

    print(f"자료 버전: {dataset_id} (complete={bool(version.complete)})")
    print(f"데이터 마감일: {version.data_deadline}")
    if version.failure_reason:
        print(f"중단 사유: {version.failure_reason}")
    print(f"일별 가격 행: {total}건 / 종목 {len(rows)}개")
    print(f"상장 전 가격 행: {before_listing}건")
    print(f"상장폐지 이후 가격 행: {after_delisting}건")
    print(f"대응표에 없는 종목의 가격 행: {unmapped}건")
    print("종목별 경계")
    for row in rows:
        if row.listed_on is None:
            print(
                f"  {row.instrument_id}  {row.row_count}행  "
                f"첫 행 {row.first_date} ~ 마지막 행 {row.last_date}  "
                "representative_etf에 없어 경계를 확인할 수 없다"
            )
            continue
        if row.first_date == row.listed_on:
            first_note = "listed_on과 같다"
        elif row.first_date < row.listed_on:
            # 이 경우가 바로 SC-003 위반이다. "뒤다"와 한 낱말로 묶으면 위반이 정상 표시로 읽힌다.
            first_note = "상장일보다 앞이다 (위반)"
        else:
            first_note = "상장일보다 뒤다 (수집 구간이 상장 이후에서 시작한 경우다)"
        last_note = (
            "delisted_on 없음"
            if row.delisted_on is None
            else (
                f"delisted_on {row.delisted_on} 이전이다"
                if row.last_date <= row.delisted_on
                else f"delisted_on {row.delisted_on}을 넘었다"
            )
        )
        print(
            f"  {row.instrument_id}  {row.row_count}행  "
            f"첫 행 {row.first_date} (listed_on {row.listed_on}, {first_note})  "
            f"마지막 행 {row.last_date} ({last_note})"
        )

    violations = before_listing + after_delisting + unmapped
    if total == 0:
        print("검증 실패: 이 자료 버전에 일별 가격 행이 없다. 빈 표는 통과가 아니다")
        return 1
    if violations:
        print(f"검증 실패: 상장 구간을 벗어난 가격 행 {violations}건 (SC-003, FR-005)")
        return 1
    print("검증 통과: 상장 구간을 벗어난 가격 행이 없다 (SC-003)")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except (VerificationError, DatasetVersionError, RepositoryError) as error:
        print(f"검증을 수행할 수 없다: {type(error).__name__}: {error}", file=sys.stderr)
        return 2
    except SQLAlchemyError as error:
        # 표가 없거나 접속이 안 되는 경우다. SQLAlchemy 추적을 그대로 흘리면 운영자가
        # "마이그레이션이 적용되지 않았다"를 읽어내기 어려우므로 원인을 먼저 말한다.
        # 그래도 종료 코드는 2다. 검증하지 못한 것을 통과로 끝내면 장치가 꺼진 것과 같다.
        print(f"저장소를 읽을 수 없다: {type(error).__name__}: {error}", file=sys.stderr)
        print(
            "표가 없다면 Flyway 마이그레이션(V*__etf_market_data.sql)이 그 DB에 "
            "적용되지 않은 것이다. DATABASE_URL이 가리키는 곳을 확인한다.",
            file=sys.stderr,
        )
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
