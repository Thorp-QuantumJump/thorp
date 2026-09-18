"""로컬 DB의 시장 자료를 서버에 넣을 SQL로 뽑는다.

## 왜 스크립트로 뽑는가

``pg_dump``로 표 몇 개만 떠서 넣으면 **자료 버전 이름을 바꿀 수 없다.** 판 번호는 "그 DB 안에서
몇 번째냐"를 뜻하므로 로컬 이름을 서버에 그대로 옮기면 안 된다. 로컬은 2년치 1판이 있어 ``_R2``
지만, 서버가 비어 있으면 그것이 **첫 판**이다. 여기서 이름을 바꿔 뽑는다.

대응표 번호(``map_version_id``)도 같은 문제다. 서버에서 새로 매겨지므로 하드코딩하지 않고
``sector_etf_map_version``을 먼저 넣은 뒤 그 id를 찾아 쓰게 만든다.

## 안전 장치

* **한 트랜잭션이다.** 중간에 실패하면 아무것도 남지 않는다. 발행본은 지울 수 없으므로(FR-017)
  절반만 들어간 상태가 가장 나쁘다
* **이미 있으면 넣지 않는다**(``ON CONFLICT DO NOTHING``). 두 번 돌려도 같은 결과다
* 쓰기는 하지 않고 **파일로만 뽑는다.** 서버에 넣는 것은 사람이 따로 한다

## 쓰는 법

    python sql/02-export-local.py --source KRX_DAILY_20260915_R2 \\
        --target KRX_DAILY_20260915 --out sql/03-load-server.sql
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

_DATA_ROOT = Path(__file__).resolve().parents[1]
if str(_DATA_ROOT / "src") not in sys.path:
    sys.path.insert(0, str(_DATA_ROOT / "src"))

from sqlalchemy import create_engine, text  # noqa: E402

from data_pipeline.dataset_version import validate_dataset_id  # noqa: E402
from data_pipeline.repository import database_url_from_env  # noqa: E402


def quote(value: object) -> str:
    """SQL 리터럴로 바꾼다. ``None``은 NULL이고 문자열은 작은따옴표를 두 번 써서 escape한다."""
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def rows_of(conn, sql: str, **params: object) -> list[dict[str, object]]:
    result = conn.execute(text(sql), params)
    return [dict(zip(result.keys(), row, strict=True)) for row in result]


def insert_many(
    out: list[str],
    table: str,
    columns: tuple[str, ...],
    rows: list[dict[str, object]],
    conflict: str,
) -> None:
    """``INSERT ... ON CONFLICT DO NOTHING`` 을 1,000행씩 끊어 쓴다.

    한 문장에 3만 행을 담으면 psql이 읽다가 메모리를 크게 쓴다. 끊어도 한 트랜잭션 안이라
    중간에 실패하면 전부 되돌아간다.
    """
    if not rows:
        out.append(f"-- {table}: 옮길 행이 없다\n")
        return
    out.append(f"\n-- {table}: {len(rows)}행")
    for start in range(0, len(rows), 1000):
        chunk = rows[start : start + 1000]
        values = ",\n  ".join(
            "(" + ", ".join(quote(row[column]) for column in columns) + ")" for row in chunk
        )
        out.append(f"INSERT INTO {table} ({', '.join(columns)})\nVALUES\n  {values}\n{conflict};")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, help="로컬에서 뽑을 자료 버전")
    parser.add_argument("--target", required=True, help="서버에 넣을 이름. 판 번호가 다를 수 있다")
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    args = parser.parse_args(argv)

    validate_dataset_id(args.source)
    validate_dataset_id(args.target)

    engine = create_engine(database_url_from_env(args.env_file))
    out: list[str] = [
        "-- 서버 DB에 시장 자료를 넣는다. 로컬에서 확인한 것을 그대로 옮긴 것이다.",
        f"-- 원본 자료 버전: {args.source}",
        f"-- 넣을 이름:      {args.target}",
        "--",
        "-- 한 트랜잭션이다. 중간에 실패하면 아무것도 남지 않는다.",
        "-- 발행본은 UPDATE·DELETE가 막혀 있으므로(FR-017) 절반만 들어간 상태가 가장 나쁘다.",
        "",
        "BEGIN;",
    ]

    with engine.connect() as conn:
        sectors = rows_of(
            conn,
            "SELECT DISTINCT s.code, s.name, s.classification FROM sector s"
            " JOIN sector_etf_map_entry m ON m.sector_id = s.id ORDER BY s.code",
        )
        insert_many(
            out,
            "sector",
            ("code", "name", "classification"),
            sectors,
            "ON CONFLICT (code) DO NOTHING",
        )

        etfs = rows_of(
            conn,
            "SELECT instrument_id, name, listed_on, delisted_on, benchmark_index, manager,"
            " leveraged, inverse FROM representative_etf ORDER BY instrument_id",
        )
        insert_many(
            out,
            "representative_etf",
            (
                "instrument_id",
                "name",
                "listed_on",
                "delisted_on",
                "benchmark_index",
                "manager",
                "leveraged",
                "inverse",
            ),
            etfs,
            "ON CONFLICT (instrument_id) DO NOTHING",
        )

        maps = rows_of(
            conn,
            "SELECT v.version, v.effective_from, v.effective_to, v.is_sample, v.published_at"
            " FROM sector_etf_map_version v JOIN dataset_version d ON d.map_version_id = v.id"
            " WHERE d.dataset_id = :src",
            src=args.source,
        )
        insert_many(
            out,
            "sector_etf_map_version",
            ("version", "effective_from", "effective_to", "is_sample", "published_at"),
            maps,
            "ON CONFLICT (version) DO NOTHING",
        )
        map_version = maps[0]["version"] if maps else None
        # 대응표 항목은 서버에서 매겨진 id를 찾아 넣는다. 로컬 id를 그대로 쓰면 엉뚱한 행을
        # 가리킨다.
        entries = rows_of(
            conn,
            "SELECT s.code AS sector_code, e.instrument_id"
            " FROM sector_etf_map_entry m"
            " JOIN sector_etf_map_version v ON v.id = m.map_version_id"
            " JOIN sector s ON s.id = m.sector_id"
            " JOIN representative_etf e ON e.id = m.etf_id"
            " WHERE v.version = :ver ORDER BY s.code",
            ver=map_version,
        )
        if entries:
            out.append(f"\n-- sector_etf_map_entry: {len(entries)}행 (id는 서버 값을 찾아 쓴다)")
            pairs = ", ".join(
                f"({quote(row['sector_code'])}, {quote(row['instrument_id'])})" for row in entries
            )
            out.append(
                "INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)\n"
                "SELECT v.id, s.id, e.id\n"
                f"  FROM (VALUES {pairs}) AS pair (sector_code, instrument_id)\n"
                f"  JOIN sector_etf_map_version v ON v.version = {quote(map_version)}\n"
                "  JOIN sector s ON s.code = pair.sector_code\n"
                "  JOIN representative_etf e ON e.instrument_id = pair.instrument_id\n"
                "ON CONFLICT (map_version_id, sector_id) DO NOTHING;"
            )

        version = rows_of(
            conn,
            "SELECT data_deadline, collected_at, last_success_at, complete, failure_reason,"
            " is_sample, source FROM dataset_version WHERE dataset_id = :src",
            src=args.source,
        )
        if not version:
            print(f"오류: 로컬에 {args.source} 가 없다", file=sys.stderr)
            return 2
        row = version[0]
        out.append("\n-- dataset_version: 1행. 이 뒤로는 되돌릴 수 없다")
        out.append(
            "INSERT INTO dataset_version (dataset_id, data_deadline, collected_at,"
            " last_success_at, complete, failure_reason, is_sample, source, map_version_id)\n"
            f"SELECT {quote(args.target)}, {quote(row['data_deadline'])},"
            f" {quote(row['collected_at'])}, {quote(row['last_success_at'])},"
            f" {quote(row['complete'])}, {quote(row['failure_reason'])},"
            f" {quote(row['is_sample'])}, {quote(row['source'])}, v.id\n"
            f"  FROM sector_etf_map_version v WHERE v.version = {quote(map_version)}\n"
            "ON CONFLICT (dataset_id) DO NOTHING;"
        )

        for table, columns, order in (
            (
                "daily_index",
                ("index_code", "date", "open", "high", "low", "close", "volume", "value"),
                "index_code, date",
            ),
            (
                "daily_price",
                (
                    "instrument_id",
                    "date",
                    "open",
                    "high",
                    "low",
                    "close",
                    "volume",
                    "value",
                    "adjusted_close",
                    "adjustment_factor",
                    "trading_suspended",
                    "price_carried_forward",
                ),
                "instrument_id, date",
            ),
            (
                "corporate_action",
                ("instrument_id", "ex_date", "pay_date", "event_type", "amount"),
                "instrument_id, ex_date",
            ),
            (
                "data_gap",
                ("instrument_id", "start_date", "end_date", "reason"),
                "instrument_id, start_date",
            ),
            (
                "suspension_period",
                ("instrument_id", "start_date", "end_date", "trading_days"),
                "instrument_id, start_date",
            ),
        ):
            picked = rows_of(
                conn,
                f"SELECT {', '.join(columns)} FROM {table}"
                f" WHERE dataset_id = :src ORDER BY {order}",
                src=args.source,
            )
            for item in picked:
                item["dataset_id"] = args.target
            insert_many(
                out,
                table,
                ("dataset_id", *columns),
                picked,
                "ON CONFLICT DO NOTHING",
            )

    out.append("\nCOMMIT;")
    args.out.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"{args.out} 에 썼다")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
