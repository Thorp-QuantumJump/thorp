"""픽스처 ``three_etf_24m`` 을 001 시장 자료 표에 넣는다 (spec 004 T013, 로컬 dev 전용).

왜 필요한가
    백엔드가 접수 때 붙이는 자료 버전은 아직 상수 ``FIXTURE-three_etf_24m`` 이다
    (``FixtureMarketMetadataAdapter``). 워커는 그 이름의 ``dataset_version`` 행을
    찾으므로, 001 이 진짜 KRX 자료를 다른 이름으로 넣어도 quickstart S1 은 돌지 않는다.
    이 스크립트가 픽스처 CSV 를 그 이름으로 001 표에 넣어 접수 → 큐 → 워커 → 자료 →
    결과 → 조회를 실제 프로세스로 끝까지 돌 수 있게 한다. 진짜 자료를 가리키는 일은
    001 T019(자료 버전 규칙)와 함께 백엔드 어댑터를 바꾸는 후속이다.

무엇을 넣나 (원본: ``backend/.../V20260911114500__etf_market_data.sql``)
    sector 3 · representative_etf 3 · sector_etf_map_version 1(SECTOR-ETF-2026-09) ·
    sector_etf_map_entry 3 · dataset_version 1(FIXTURE-three_etf_24m, is_sample) ·
    daily_price · corporate_action · daily_index.
    ``daily_index`` 는 픽스처에 없어 **가격 날짜의 합집합으로 만든다** — 워커는 거래일
    달력을 지수 행에서 읽는다(계약 ※1). 값은 상수라 지수로서의 뜻은 없고 "그날 장이
    열렸다"는 사실만 담는다.

원칙
    - 001 의 적재 계층(``data/src/data_pipeline/repository.py``)을 쓰지 않는다. 그쪽은
      ``dataset_id`` 를 ``KRX_DAILY_{YYYYMMDD}`` 로 검증해 픽스처 이름을 거부한다 —
      맞는 거부다. 대신 표 제약(CHECK·FK·불변 트리거)이 그대로 걸리므로 잘못된 행은
      DB 가 막는다.
    - 멱등하다. ``dataset_version`` 에 같은 ``dataset_id`` 가 있으면 아무것도 하지 않는다.
      자료 표는 UPDATE·DELETE 를 트리거가 거부하므로 한 트랜잭션으로 넣어 절반만
      들어간 상태를 남기지 않는다.
    - 운영 DB 에 넣지 않는다. 합성 자료는 사용자에게 가지 않는다(원칙 X).
      ``is_sample = true``·``source = 'FIXTURE'`` 로 표시한다.

사용::

    MARKET_DATA_URL=postgresql://thorp:thorp@localhost:5432/thorp \\
        python scripts/load_three_etf_fixture.py
"""

from __future__ import annotations

import csv
import os
import sys
from collections.abc import Iterable, Mapping
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

FIXTURE_DIR = Path(__file__).resolve().parents[1] / "examples" / "three_etf_24m"
DATASET_ID = "FIXTURE-three_etf_24m"
MAP_VERSION = "SECTOR-ETF-2026-09"
#: 픽스처에는 지수가 없다. 거래일 달력만을 위한 자리 표시 지수다.
INDEX_CODE = "FIXTURE_CALENDAR"
INDEX_LEVEL = Decimal("1000.0000")
DATA_DEADLINE = datetime(2024, 12, 31, 15, 30, tzinfo=UTC)


def read_csv(name: str) -> list[dict[str, str]]:
    with (FIXTURE_DIR / name).open(encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def build_daily_price_rows(
    raw: Iterable[Mapping[str, str]], adjusted: Iterable[Mapping[str, str]]
) -> list[dict[str, Any]]:
    """원시 OHLCV 와 조정 종가를 한 행으로 합친다.

    ``value``(거래대금)는 픽스처에 없어 ``close × volume`` 으로 채운다 — 워커는 읽지
    않는 열이고 표가 NOT NULL 을 요구한다. ``adjustment_factor = adjusted_close / close``,
    ``price_carried_forward`` 는 거래정지와 같다(픽스처는 정지일에 직전 종가를 쓴다).
    표의 CHECK(정지일은 OHLC 가 같고 거래량 0)가 픽스처 생성기의 규칙과 일치하는지는
    INSERT 가 검사한다.
    """
    adjusted_by_key = {
        (row["instrument_id"], row["date"]): Decimal(row["adjusted_close"]) for row in adjusted
    }
    rows: list[dict[str, Any]] = []
    for row in raw:
        key = (row["instrument_id"], row["date"])
        if key not in adjusted_by_key:
            raise ValueError(f"adjusted close missing for {key}")
        close = Decimal(row["close"])
        volume = int(row["volume"])
        suspended = row["trading_suspended"].strip().lower() == "true"
        rows.append(
            {
                "dataset_id": DATASET_ID,
                "instrument_id": row["instrument_id"],
                "date": row["date"],
                "open": Decimal(row["open"]),
                "high": Decimal(row["high"]),
                "low": Decimal(row["low"]),
                "close": close,
                "volume": volume,
                "value": close * volume,
                "adjusted_close": adjusted_by_key[key],
                "adjustment_factor": adjusted_by_key[key] / close,
                "trading_suspended": suspended,
                "price_carried_forward": suspended,
            }
        )
    return rows


def build_daily_index_rows(price_rows: Iterable[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """가격 행이 있는 날짜의 합집합 = 거래일 달력. 지수 값은 상수다."""
    dates = sorted({row["date"] for row in price_rows})
    return [
        {
            "dataset_id": DATASET_ID,
            "index_code": INDEX_CODE,
            "date": day,
            "open": INDEX_LEVEL,
            "high": INDEX_LEVEL,
            "low": INDEX_LEVEL,
            "close": INDEX_LEVEL,
            "volume": 0,
            "value": 0,
        }
        for day in dates
    ]


def build_corporate_action_rows(
    distributions: Iterable[Mapping[str, str]], splits: Iterable[Mapping[str, str]]
) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for row in distributions:
        rows.append(
            {
                "dataset_id": DATASET_ID,
                "instrument_id": row["instrument_id"],
                "ex_date": row["ex_date"],
                "pay_date": row["pay_date"],
                "event_type": "DISTRIBUTION",
                "amount": Decimal(row["amount"]),
            }
        )
    for row in splits:
        rows.append(
            {
                "dataset_id": DATASET_ID,
                "instrument_id": row["instrument_id"],
                "ex_date": row["ex_date"],
                "pay_date": None,
                "event_type": "SPLIT",
                "amount": Decimal(row["amount"]),
            }
        )
    return rows


def sector_attributes(sector_id: str) -> dict[str, Any]:
    return {
        "name": f"{sector_id}(픽스처)",
        "classification": "INDUSTRY",
        "description": "spec 004 픽스처 섹터",
    }


def etf_attributes(instrument: Mapping[str, str]) -> dict[str, Any]:
    return {
        "name": instrument["name"],
        "listed_on": instrument["listed_on"],
        "delisted_on": instrument["delisted_on"] or None,
        "benchmark_index": instrument["benchmark_index"],
        "manager": "픽스처",
    }


class MasterConflictError(RuntimeError):
    """이미 있는 섹터·대표 ETF 행의 속성이 픽스처와 다르다.

    ``sector``·``representative_etf`` 는 버전을 타지 않는 공용 표라 여기서 값을 바꾸면 이미
    발행된 대응표가 가리키는 내용까지 바뀐다. 001 적재 계층도 같은 경우를
    ``SectorMasterConflictError`` 로 막는다(FR-003). 같으면 기존 id 를 쓰고, 다르면 멈춘다.
    """


def _normalize(value: Any) -> str | None:
    """DB 가 돌려주는 date·None 과 CSV 의 문자열·빈 값을 같은 모양으로 맞춘다."""
    if value is None or value == "":
        return None
    return str(value)


def _ensure_master_row(
    cursor: Any, table: str, key_column: str, key: str, attributes: Mapping[str, Any]
) -> int:
    """공용 마스터 행을 확보한다 — 없으면 INSERT, 있으면 속성이 같을 때만 그 id 를 돌려준다."""
    columns = list(attributes)
    cursor.execute(f"SELECT id, {', '.join(columns)} FROM {table} WHERE {key_column} = %s", (key,))
    found = cursor.fetchone()
    if found is None:
        placeholders = ", ".join(["%s"] * (len(columns) + 1))
        cursor.execute(
            f"INSERT INTO {table} ({key_column}, {', '.join(columns)})"
            f" VALUES ({placeholders}) RETURNING id",
            (key, *attributes.values()),
        )
        return int(cursor.fetchone()[0])
    stored = tuple(_normalize(v) for v in found[1:])
    incoming = tuple(_normalize(v) for v in attributes.values())
    if stored != incoming:
        raise MasterConflictError(
            f"{table} {key} already exists with different attributes: {stored} != {incoming}."
            " The table is unversioned, so changing it would rewrite what every published"
            " map version points at. Load the fixture into an empty local database instead."
        )
    return int(found[0])


def _insert_many(cursor: Any, table: str, rows: list[dict[str, Any]]) -> None:
    if not rows:
        return
    columns = list(rows[0])
    placeholders = ", ".join(f"%({column})s" for column in columns)
    cursor.executemany(f"INSERT INTO {table} ({', '.join(columns)}) VALUES ({placeholders})", rows)


def load(connection: Any) -> bool:
    """픽스처를 넣는다. 이미 있으면 False. 모든 INSERT 는 호출자가 연 한 트랜잭션 안이다."""
    with connection.cursor() as cursor:
        cursor.execute("SELECT 1 FROM dataset_version WHERE dataset_id = %s", (DATASET_ID,))
        if cursor.fetchone() is not None:
            return False

        instruments = read_csv("instruments.csv")
        mapping = read_csv("mapping.csv")
        now = datetime.now(UTC)

        # 섹터·대표 ETF. 코드는 대응표의 sector_id 열, 이름은 픽스처임을 드러낸다.
        # 공용 표라 이미 있는 행은 고치지 않는다 — 같으면 재사용, 다르면 MasterConflictError.
        sector_ids: dict[str, int] = {}
        for entry in mapping:
            sector_ids[entry["sector_id"]] = _ensure_master_row(
                cursor,
                "sector",
                "code",
                entry["sector_id"],
                sector_attributes(entry["sector_id"]),
            )
        etf_ids: dict[str, int] = {}
        for instrument in instruments:
            etf_ids[instrument["instrument_id"]] = _ensure_master_row(
                cursor,
                "representative_etf",
                "instrument_id",
                instrument["instrument_id"],
                etf_attributes(instrument),
            )

        # 대응표 버전. 열린 버전은 하나만 허용된다(uk_sector_etf_map_version_open) —
        # 001 예시 대응표가 이미 열려 있으면 여기서 UNIQUE 위반으로 멈춘다. 그 환경에서는
        # 픽스처와 예시 대응표를 함께 둘 수 없다.
        cursor.execute("SELECT id FROM sector_etf_map_version WHERE version = %s", (MAP_VERSION,))
        found = cursor.fetchone()
        if found is not None:
            map_version_id = found[0]
        else:
            cursor.execute(
                "INSERT INTO sector_etf_map_version"
                " (version, effective_from, effective_to, is_sample, published_at)"
                " VALUES (%s, %s, NULL, TRUE, %s) RETURNING id",
                (MAP_VERSION, mapping[0]["effective_from"], now),
            )
            map_version_id = cursor.fetchone()[0]
            cursor.executemany(
                "INSERT INTO sector_etf_map_entry (map_version_id, sector_id, etf_id)"
                " VALUES (%s, %s, %s)",
                [
                    (map_version_id, sector_ids[e["sector_id"]], etf_ids[e["instrument_id"]])
                    for e in mapping
                ],
            )

        cursor.execute(
            "INSERT INTO dataset_version (dataset_id, data_deadline, collected_at,"
            " last_success_at, complete, failure_reason, is_sample, source, map_version_id)"
            " VALUES (%s, %s, %s, %s, TRUE, NULL, TRUE, 'FIXTURE', %s)",
            (DATASET_ID, DATA_DEADLINE, now, now, map_version_id),
        )

        prices = build_daily_price_rows(read_csv("prices_raw.csv"), read_csv("prices_adjusted.csv"))
        _insert_many(cursor, "daily_price", prices)
        _insert_many(cursor, "daily_index", build_daily_index_rows(prices))
        _insert_many(
            cursor,
            "corporate_action",
            build_corporate_action_rows(read_csv("distributions.csv"), read_csv("splits.csv")),
        )
        return True


def main() -> int:
    url = os.environ.get("MARKET_DATA_URL")
    if not url:
        print("MARKET_DATA_URL is not set (e.g. postgresql://thorp:thorp@localhost:5432/thorp)")
        return 1
    import psycopg

    with psycopg.connect(url) as connection:
        inserted = load(connection)
        connection.commit()
    if inserted:
        print(f"loaded fixture {DATASET_ID} into {url.split('@')[-1]}")
    else:
        print(f"fixture {DATASET_ID} already present; nothing to do")
    return 0


if __name__ == "__main__":
    sys.exit(main())
