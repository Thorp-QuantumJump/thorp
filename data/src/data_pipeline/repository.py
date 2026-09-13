"""서비스 저장소(PostgreSQL) 적재 계층 (spec 001 FR-013·FR-017, research.md 결정 8).

적재 방향은 ``raw → processed → PostgreSQL`` 한 방향뿐이고, DB 적재는 자료 버전 발행의
마지막 단계다. 그래서 이 모듈에는 쓰기와 "넣어도 되는지"를 판단하는 존재 확인만 있고,
DB에서 시계열을 읽어 ``processed/``나 ``raw/``로 되돌리는 경로는 두지 않는다. 서비스가
읽는 곳은 PostgreSQL 하나이고(Backend·Worker), 되돌리는 경로를 만들면 같은 자료가 두 곳에서
달라질 수 있다.

적재 순서를 강제한다.

1. :meth:`MarketDataRepository.publish_dataset_version` 으로 ``dataset_version`` 행을 만든다.
2. 그 뒤에야 시계열 5개(``daily_price``·``corporate_action``·``daily_index``·``data_gap``·
   ``suspension_period``)를 적재할 수 있다.

이미 발행된 ``dataset_id``에 다시 발행하거나, 이미 적재한 시계열을 다시 넣는 시도는 거부한다
(FR-017). 자료를 고쳐야 하면 새 ``dataset_id``를 발행한다.

동시 실행. 존재 확인과 INSERT는 한 트랜잭션 안에 있지만 잠금을 걸지 않으므로, READ
COMMITTED에서 두 실행이 같은 ``dataset_id``를 동시에 다루면 확인이 둘 다 "없음"을 읽을 수
있다. 그때는 ``dataset_version``의 UNIQUE와 시계열 PK가 막고, 이 모듈이 그 위반을 같은
도메인 예외(:class:`DatasetAlreadyPublishedError`·:class:`DatasetAlreadyLoadedError`)로
바꿔 돌려준다. 즉 **잘못된 자료가 들어가지는 않지만 어느 쪽이 이길지는 정해져 있지 않다.**
수집을 병렬로 돌릴 계획이 생기면 ``dataset_id``에 advisory lock을 걸어야 한다.

대조 기록(``reconciliation_record``)과 대표 상품 정보(``instrument_profile``)는 시계열 5개와
적재 경로가 다르다. 시계열 표는
``dataset_id`` **문자열**을 갖고 그 값으로 ``dataset_version``을 참조하지만, 이 표는
``dataset_version_id``·``etf_id`` **bigint 외래 키**를 갖는다(data-model.md). 파이프라인이 다루는
값은 문자열 ``dataset_id``와 ``{6자리}.XKRX`` 종목 식별자뿐이므로, 적재 전에 두 식별자를 찾아
바꿔 넣어야 한다(:meth:`MarketDataRepository.load_reconciliation_records`). 없는 자료 버전이나
대응표에 없는 종목이면 FK 위반 메시지 대신 무엇이 없는지 말하는 오류를 낸다 — 여기서는 열 이름만
같은 표가 아니라 두 표를 잇는 조회가 끼기 때문에 원인 설명이 더 필요하다. ``etf_id`` 조회를
위해 ``representative_etf``의 열 사본도 함께 둔다(그 표는 대응표 쪽이 적재하고 이 모듈은 읽기만
한다). 재적재 거부 기준도 자료 버전 하나당 대조 기록 한 묶음이다 — 발행된 자료는 바뀌지 않으므로
같은 자료 버전을 다시 대조하면 결과가 같아야 한다 (FR-017).

역할 분담. 저장 스키마
(``backend/src/main/resources/db/migration/V20260911114500__etf_market_data.sql``)가 최종
방어선이고 UPDATE·DELETE 거부 트리거·FK·CHECK를 모두 갖고 있다. 여기서는 DB 제약 이름만
돌려주면 원인을 알 수 없는 것들 — 적재 순서, 재적재, 열 이름 표류, 거래정지일 표현,
기업행위 지급일 규칙 — 을 먼저 검사해 뜻이 분명한 오류를 낸다. OHLC 상하한이나 양수 조건처럼
DB CHECK 이름만으로 뜻이 통하는 값 범위 검사는 DB에 맡긴다.
"""

from __future__ import annotations

import numbers
import os
import re
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from math import isfinite
from pathlib import Path
from typing import Any, Final

import numpy as np
from dotenv import load_dotenv
from sqlalchemy import (
    JSON,
    BigInteger,
    Boolean,
    Column,
    Date,
    DateTime,
    ForeignKey,
    Integer,
    MetaData,
    Numeric,
    String,
    Table,
    Text,
    create_engine,
    select,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.engine import Connection, Engine
from sqlalchemy.exc import IntegrityError

from data_pipeline.dataset_version import (
    DatasetAlreadyLoadedError,
    DatasetAlreadyPublishedError,
    DatasetNotPublishedError,
    DatasetVersion,
    DatasetVersionError,
    validate_dataset_id,
)

# 아래 두 개체는 값 규칙 검사를 거친 형으로만 받는다. 대조 기록의 cause↔resolution 조합(FR-017)과
# 대표 상품 정보의 비중 합·상위 종목 개수·추적 차이 금지(FR-015)는 각 모듈 한 곳에만 두고, 여기서는
# 형을 요구해 검사를 거쳤음을 보장한다. 두 모듈 모두 이 모듈을 부르지 않으므로(적재 배선은
# scripts/ 진입점이 한다) 순환 import가 생기지 않는다.
from data_pipeline.instrument_profile import InstrumentProfile
from data_pipeline.reconciliation import ReconciliationRecord

# 끝을 ``$``가 아니라 ``\Z``로 닫는다. 파이썬의 ``$``는 끝에 붙은 줄바꿈 앞에서도 맞아
# "091160.XKRX\n"이 통과한다. 저장 스키마의 CHECK는 그것을 거부하므로, 그대로 두면 뜻이 분명한
# 오류 대신 제약 이름만 돌아온다.
INSTRUMENT_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9]{6}\.XKRX\Z")

CORPORATE_ACTION_EVENT_TYPES: Final[frozenset[str]] = frozenset({"DISTRIBUTION", "SPLIT"})
DATA_GAP_REASONS: Final[frozenset[str]] = frozenset(
    {
        "AMBIGUOUS_PRICE",
        "MISSING_CORPORATE_ACTION",
        "SUSPENDED_TOO_LONG",
        "RECONCILIATION_UNKNOWN",
        "INDEX_HISTORY_SHORT",
    }
)

DEFAULT_CHUNK_SIZE: Final[int] = 1000

# PostgreSQL의 unique_violation. 유일성 위반만 재적재로 해석하고 FK·CHECK 위반과 구분한다.
_PG_UNIQUE_VIOLATION: Final[str] = "23505"


class RepositoryError(RuntimeError):
    """저장소 설정 오류. 값이 아니라 실행 환경 문제다."""


class RowValidationError(DatasetVersionError):
    """적재하려는 행이 저장 스키마의 모양과 맞지 않는다."""


class RepresentativeEtfNotFoundError(RowValidationError):
    """대조 기록이 가리키는 종목이 ``representative_etf``에 없다.

    ``reconciliation_record.etf_id``가 그 표를 참조하므로 FK 위반이 되는데, DB가 돌려주는
    제약 이름만으로는 어느 종목이 없는지 알 수 없다.
    """


# ---------------------------------------------------------------------------
# 표 정의
# ---------------------------------------------------------------------------
# 마이그레이션이 원본이고 여기 정의는 INSERT 문을 만들기 위한 열 사본이다. 표를 만들지 않으므로
# CHECK 제약은 옮기지 않는다 — 두 곳에 같은 제약을 적으면 둘이 갈라질 때 어느 쪽이 맞는지
# 알 수 없다. IDENTITY인 ``id``는 파이프라인이 채우지 않으므로 INSERT에서 빠진다.

METADATA: Final[MetaData] = MetaData()

dataset_version_table: Final[Table] = Table(
    "dataset_version",
    METADATA,
    # PostgreSQL은 IDENTITY로 값을 만든다. SQLite는 BIGINT 기본 키에 값을 만들어 주지 않아
    # 인메모리 대역에서만 INTEGER로 바꾼다 — 저장 스키마는 그대로 BIGINT다.
    Column(
        "id",
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    ),
    Column("dataset_id", String(64), nullable=False, unique=True),
    Column("data_deadline", DateTime(timezone=True), nullable=False),
    Column("collected_at", DateTime(timezone=True), nullable=False),
    Column("last_success_at", DateTime(timezone=True), nullable=False),
    Column("complete", Boolean, nullable=False),
    Column("failure_reason", Text, nullable=True),
    Column("is_sample", Boolean, nullable=False),
    Column("source", String(64), nullable=False),
    Column("map_version_id", BigInteger, nullable=False),
)

representative_etf_table: Final[Table] = Table(
    "representative_etf",
    METADATA,
    # 이 모듈은 이 표를 읽기만 한다(``etf_id`` 조회). 적재는 대응표 쪽이 맡는다.
    Column(
        "id",
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    ),
    Column("instrument_id", String(32), nullable=False, unique=True),
    Column("name", String(200), nullable=False),
    Column("listed_on", Date, nullable=False),
    Column("delisted_on", Date, nullable=True),
    Column("benchmark_index", String(200), nullable=False),
    Column("manager", String(200), nullable=False),
    Column("leveraged", Boolean, nullable=False, default=False),
    Column("inverse", Boolean, nullable=False, default=False),
)

reconciliation_record_table: Final[Table] = Table(
    "reconciliation_record",
    METADATA,
    # 시계열 표와 달리 문자열 ``dataset_id``가 아니라 bigint 외래 키 두 개를 갖는다
    # (data-model.md). 적재 전에 두 식별자를 찾아 넣는다.
    Column(
        "id",
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    ),
    Column(
        "dataset_version_id",
        BigInteger,
        ForeignKey("dataset_version.id"),
        nullable=False,
    ),
    Column("etf_id", BigInteger, ForeignKey("representative_etf.id"), nullable=False),
    Column("target_date", Date, nullable=False),
    Column("sample_reason", String(16), nullable=False),
    Column("field_name", String(64), nullable=False),
    Column("queried_value", Text, nullable=False),
    Column("disclosed_value", Text, nullable=False),
    Column("difference", Text, nullable=True),
    Column("cause", String(20), nullable=False),
    Column("resolution", String(20), nullable=False),
    Column("checked_at", DateTime(timezone=True), nullable=False),
)

instrument_profile_table: Final[Table] = Table(
    "instrument_profile",
    METADATA,
    # 대조 기록과 같은 모양이다 — 문자열 dataset_id가 아니라 bigint 외래 키 두 개를 갖는다
    # (data-model.md). 시계열이 아니라 대표 ETF 하나에 딸린 표시용 묶음이기 때문이다.
    Column(
        "id",
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    ),
    Column("etf_id", BigInteger, ForeignKey("representative_etf.id"), nullable=False),
    Column(
        "dataset_version_id",
        BigInteger,
        ForeignKey("dataset_version.id"),
        nullable=False,
    ),
    Column("index_methodology_summary", Text, nullable=False),
    # PostgreSQL은 jsonb다. 예행용 SQLite에는 그 형이 없어 표를 만들 수 없으므로 변형을 둔다.
    Column("holdings_overlap", JSONB().with_variant(JSON, "sqlite"), nullable=False),
    Column("holdings_as_of_current", Boolean, nullable=False),
    # 금액이지만 TEXT다. NUMERIC은 scale을 임의로 정해야 하고 넘는 값을 조용히 반올림한다
    # (data-model.md instrument_profile 불변식, backend/CLAUDE.md).
    Column("avg_daily_value_3m", Text, nullable=False),
    Column("source_attribution", String(200), nullable=False),
    Column("survivorship_note", Text, nullable=False),
)

daily_price_table: Final[Table] = Table(
    "daily_price",
    METADATA,
    Column(
        "dataset_id",
        String(64),
        ForeignKey("dataset_version.dataset_id"),
        primary_key=True,
    ),
    Column("instrument_id", String(32), primary_key=True),
    Column("date", Date, primary_key=True),
    Column("open", Numeric(18, 4), nullable=False),
    Column("high", Numeric(18, 4), nullable=False),
    Column("low", Numeric(18, 4), nullable=False),
    Column("close", Numeric(18, 4), nullable=False),
    Column("volume", BigInteger, nullable=False),
    Column("value", BigInteger, nullable=False),
    Column("adjusted_close", Numeric(20, 8), nullable=False),
    Column("adjustment_factor", Numeric(20, 12), nullable=False),
    Column("trading_suspended", Boolean, nullable=False, default=False),
    Column("price_carried_forward", Boolean, nullable=False, default=False),
)

corporate_action_table: Final[Table] = Table(
    "corporate_action",
    METADATA,
    Column(
        "dataset_id",
        String(64),
        ForeignKey("dataset_version.dataset_id"),
        primary_key=True,
    ),
    Column("instrument_id", String(32), primary_key=True),
    Column("ex_date", Date, primary_key=True),
    Column("event_type", String(16), primary_key=True),
    Column("pay_date", Date, nullable=True),
    Column("amount", Numeric(20, 8), nullable=False),
)

daily_index_table: Final[Table] = Table(
    "daily_index",
    METADATA,
    Column(
        "dataset_id",
        String(64),
        ForeignKey("dataset_version.dataset_id"),
        primary_key=True,
    ),
    Column("index_code", String(32), primary_key=True),
    Column("date", Date, primary_key=True),
    Column("open", Numeric(18, 4), nullable=False),
    Column("high", Numeric(18, 4), nullable=False),
    Column("low", Numeric(18, 4), nullable=False),
    Column("close", Numeric(18, 4), nullable=False),
    Column("volume", BigInteger, nullable=False),
    Column("value", BigInteger, nullable=False),
)

data_gap_table: Final[Table] = Table(
    "data_gap",
    METADATA,
    Column(
        "dataset_id",
        String(64),
        ForeignKey("dataset_version.dataset_id"),
        primary_key=True,
    ),
    Column("instrument_id", String(32), primary_key=True),
    Column("start_date", Date, primary_key=True),
    Column("reason", String(32), primary_key=True),
    Column("end_date", Date, nullable=False),
)

suspension_period_table: Final[Table] = Table(
    "suspension_period",
    METADATA,
    Column(
        "dataset_id",
        String(64),
        ForeignKey("dataset_version.dataset_id"),
        primary_key=True,
    ),
    Column("instrument_id", String(32), primary_key=True),
    Column("start_date", Date, primary_key=True),
    Column("end_date", Date, nullable=False),
    Column("trading_days", Integer, nullable=False),
)


# ---------------------------------------------------------------------------
# 행 검사
# ---------------------------------------------------------------------------


def _as_date(value: object, field: str) -> date:
    """DATE 열에 넣을 값으로 맞춘다. 시각이 붙어 있으면 자정일 때만 받는다."""
    if isinstance(value, datetime):
        # pandas.Timestamp도 datetime의 하위형이라 여기로 온다.
        if (value.hour, value.minute, value.second, value.microsecond) != (0, 0, 0, 0):
            raise RowValidationError(f"{field}은(는) 날짜 열이라 시각을 담을 수 없다: {value!r}")
        return value.date()
    if isinstance(value, date):
        return value
    raise RowValidationError(f"{field}은(는) date여야 한다: {value!r}")


def _require_instrument_id(value: object, field: str = "instrument_id") -> str:
    if not isinstance(value, str) or INSTRUMENT_ID_PATTERN.match(value) is None:
        raise RowValidationError(f"{field}은(는) {{6자리}}.XKRX 형식이어야 한다: {value!r}")
    return value


def _require_bool(value: object, field: str) -> bool:
    """bool을 요구하되 ``numpy.bool_``도 받는다.

    이 파트는 pandas 기반이고 이 모듈이 DataFrame과 PostgreSQL 사이의 유일한 다리다.
    ``numpy.bool_``은 ``bool``의 하위형이 아니라서 형만 보면 거부되는데, 값은 같고
    상자만 다르므로 받아서 파이썬 ``bool``로 바꾼다.
    """
    if isinstance(value, bool):
        return value
    if isinstance(value, np.bool_):
        return bool(value)
    raise RowValidationError(f"{field}은(는) bool이어야 한다: {value!r}")


def _check_daily_price(row: dict[str, Any]) -> None:
    _require_instrument_id(row["instrument_id"])
    suspended = _require_bool(row["trading_suspended"], "trading_suspended")
    carried = _require_bool(row["price_carried_forward"], "price_carried_forward")
    if not suspended:
        return
    # 거래정지일 표현을 고정한다 (2026-09-11 확정, data-model.md 「거래정지일 표현」).
    # 판정 기준은 trading_suspended이며 volume = 0이 아니다. 진짜 결측은 행을 만들지 않는다.
    close = row["close"]
    same_ohlc = row["open"] == close and row["high"] == close and row["low"] == close
    if not (same_ohlc and row["volume"] == 0 and row["value"] == 0 and carried):
        raise RowValidationError(
            "거래정지일은 OHLC 네 값이 모두 직전 종가로 같고 volume·value가 0이며 "
            f"price_carried_forward가 참이어야 한다: {row['instrument_id']} {row['date']}"
        )


def _check_corporate_action(row: dict[str, Any]) -> None:
    _require_instrument_id(row["instrument_id"])
    event_type = row["event_type"]
    if event_type not in CORPORATE_ACTION_EVENT_TYPES:
        raise RowValidationError(
            f"event_type은 {sorted(CORPORATE_ACTION_EVENT_TYPES)} 중 하나여야 한다: {event_type!r}"
        )
    pay_date = row["pay_date"]
    if event_type == "DISTRIBUTION":
        # 배당락일은 조정 계수 산출에, 지급일은 현금 반영에 쓴다 (004 계약).
        if pay_date is None:
            raise RowValidationError(
                f"분배금은 pay_date가 있어야 한다: {row['instrument_id']} {row['ex_date']}"
            )
        if pay_date < row["ex_date"]:
            raise RowValidationError(
                f"pay_date는 ex_date 이후여야 한다: {row['instrument_id']} "
                f"{row['ex_date']} → {pay_date}"
            )
    elif pay_date is not None:
        raise RowValidationError(
            f"분할은 pay_date가 없어야 한다: {row['instrument_id']} {row['ex_date']}"
        )


def _check_daily_index(row: dict[str, Any]) -> None:
    index_code = row["index_code"]
    if not isinstance(index_code, str) or not index_code.strip():
        raise RowValidationError(f"index_code는 비어 있을 수 없다: {index_code!r}")


def _check_data_gap(row: dict[str, Any]) -> None:
    _require_instrument_id(row["instrument_id"])
    if row["reason"] not in DATA_GAP_REASONS:
        raise RowValidationError(
            f"reason은 {sorted(DATA_GAP_REASONS)} 중 하나여야 한다: {row['reason']!r}"
        )
    if row["end_date"] < row["start_date"]:
        raise RowValidationError(
            f"end_date는 start_date 이후여야 한다: {row['instrument_id']} "
            f"{row['start_date']} → {row['end_date']}"
        )


def _check_suspension_period(row: dict[str, Any]) -> None:
    _require_instrument_id(row["instrument_id"])
    if row["end_date"] < row["start_date"]:
        raise RowValidationError(
            f"end_date는 start_date 이후여야 한다: {row['instrument_id']} "
            f"{row['start_date']} → {row['end_date']}"
        )
    trading_days = row["trading_days"]
    if not isinstance(trading_days, int) or isinstance(trading_days, bool) or trading_days < 1:
        raise RowValidationError(f"trading_days는 1 이상의 정수여야 한다: {trading_days!r}")


def _require_integer(value: object, field: str) -> None:
    """정수를 요구하되 ``numpy.int64`` 같은 numpy 정수도 받는다.

    ``numbers.Integral``은 numpy 정수를 포함하고 ``float``는 포함하지 않는다. 실수는
    그대로 거부한다. ``DataFrame.iterrows()``는 수치 열이 섞인 행을 ``float64``로 올려
    ``volume``을 실수로 만드는데, 그것을 조용히 받으면 거래대금처럼 큰 정수에서 값이
    달라질 수 있다. 부르는 쪽은 ``to_dict("records")``나 ``itertuples()``를 쓴다.
    """
    if isinstance(value, bool | np.bool_) or not isinstance(value, numbers.Integral):
        raise RowValidationError(f"{field}은(는) 정수여야 한다: {value!r}")


def _require_number(value: object, field: str) -> None:
    """숫자 열의 형만 본다. 값의 범위(양수·상하한)는 저장 스키마의 CHECK가 본다.

    ``numpy.float64``는 ``float``의 하위형이라 그대로 통과하지만 ``numpy.int64``는
    ``int``의 하위형이 아니므로 ``numbers.Integral``로 함께 받는다.
    """
    if isinstance(value, bool | np.bool_) or not isinstance(
        value, numbers.Integral | float | Decimal
    ):
        raise RowValidationError(f"{field}은(는) 숫자여야 한다: {value!r}")
    if not isfinite(float(value)):
        raise RowValidationError(
            f"{field}에 유한하지 않은 값이 들어 있다: {value!r}. 결측은 채우지 않는다"
        )


@dataclass(frozen=True)
class _TableSpec:
    """한 시계열 표의 적재 규칙. ``dataset_id``는 적재 인자로 받으므로 행에 넣지 않는다."""

    table: Table
    required: tuple[str, ...]
    optional: Mapping[str, Any]
    date_fields: tuple[str, ...]
    integer_fields: tuple[str, ...]
    number_fields: tuple[str, ...]
    key: tuple[str, ...]
    check: Callable[[dict[str, Any]], None]


_SPECS: Final[dict[str, _TableSpec]] = {
    "daily_price": _TableSpec(
        table=daily_price_table,
        required=(
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
        ),
        optional={"trading_suspended": False, "price_carried_forward": False},
        date_fields=("date",),
        integer_fields=("volume", "value"),
        number_fields=(
            "open",
            "high",
            "low",
            "close",
            "adjusted_close",
            "adjustment_factor",
        ),
        key=("instrument_id", "date"),
        check=_check_daily_price,
    ),
    "corporate_action": _TableSpec(
        table=corporate_action_table,
        required=("instrument_id", "ex_date", "event_type", "amount"),
        optional={"pay_date": None},
        date_fields=("ex_date", "pay_date"),
        integer_fields=(),
        number_fields=("amount",),
        key=("instrument_id", "ex_date", "event_type"),
        check=_check_corporate_action,
    ),
    "daily_index": _TableSpec(
        table=daily_index_table,
        required=("index_code", "date", "open", "high", "low", "close", "volume", "value"),
        optional={},
        date_fields=("date",),
        integer_fields=("volume", "value"),
        number_fields=("open", "high", "low", "close"),
        key=("index_code", "date"),
        check=_check_daily_index,
    ),
    "data_gap": _TableSpec(
        table=data_gap_table,
        required=("instrument_id", "start_date", "end_date", "reason"),
        optional={},
        date_fields=("start_date", "end_date"),
        integer_fields=(),
        number_fields=(),
        key=("instrument_id", "start_date", "reason"),
        check=_check_data_gap,
    ),
    "suspension_period": _TableSpec(
        table=suspension_period_table,
        required=("instrument_id", "start_date", "end_date", "trading_days"),
        optional={},
        date_fields=("start_date", "end_date"),
        integer_fields=("trading_days",),
        number_fields=(),
        key=("instrument_id", "start_date"),
        check=_check_suspension_period,
    ),
}


def _normalize_rows(
    spec: _TableSpec, dataset_id: str, rows: Iterable[Mapping[str, Any]]
) -> list[dict[str, Any]]:
    """행을 INSERT 값으로 바꾸면서 열 이름과 표 고유 규칙을 검사한다.

    ``dataset_id``는 인자로 받은 값을 넣는다. 행이 직접 들고 오게 하면 한 묶음 안에서 서로 다른
    자료 버전이 섞일 수 있는데, 그 상태는 DB가 막지 못한다(각 행은 FK를 만족하기 때문이다).
    """
    allowed = set(spec.required) | set(spec.optional)
    normalized: list[dict[str, Any]] = []
    seen: dict[tuple[Any, ...], int] = {}
    for position, raw in enumerate(rows):
        if not isinstance(raw, Mapping):
            raise RowValidationError(
                f"{spec.table.name} {position}번째 행이 매핑이 아니다: {type(raw).__name__}"
            )
        keys = set(raw)
        if "dataset_id" in keys:
            raise RowValidationError(
                f"{spec.table.name} {position}번째 행에 dataset_id가 들어 있다. "
                "자료 버전은 적재 인자로만 받는다"
            )
        missing = sorted(set(spec.required) - keys)
        if missing:
            raise RowValidationError(f"{spec.table.name} {position}번째 행에 없는 열: {missing}")
        unknown = sorted(keys - allowed)
        if unknown:
            raise RowValidationError(f"{spec.table.name} {position}번째 행의 모르는 열: {unknown}")

        row: dict[str, Any] = {"dataset_id": dataset_id}
        row.update({name: raw[name] for name in spec.required})
        for name, default in spec.optional.items():
            row[name] = raw.get(name, default)
        for name in spec.date_fields:
            if row[name] is not None:
                row[name] = _as_date(row[name], name)
        for name in spec.required:
            if row[name] is None:
                raise RowValidationError(
                    f"{spec.table.name} {position}번째 행의 {name}이(가) 비어 있다. "
                    "결측은 값을 지어내지 않고 행을 만들지 않는다"
                )
        for name in spec.integer_fields:
            _require_integer(row[name], name)
        for name in spec.number_fields:
            _require_number(row[name], name)
        spec.check(row)

        identity = tuple(row[name] for name in spec.key)
        if identity in seen:
            raise RowValidationError(
                f"{spec.table.name}에 같은 키가 두 번 들어 있다 "
                f"({seen[identity]}번째와 {position}번째): {identity}"
            )
        seen[identity] = position
        normalized.append(row)
    return normalized


# ---------------------------------------------------------------------------
# 접속
# ---------------------------------------------------------------------------


def normalize_database_url(url: str) -> str:
    """``.env``의 ``postgresql://`` 형식을 설치된 드라이버(psycopg 3)에 맞춘다.

    SQLAlchemy는 드라이버를 적지 않은 ``postgresql://``을 psycopg2로 해석하는데, 이 파트에
    설치된 것은 psycopg 3(``psycopg[binary]``)이다. ``.env``는 컨테이너·Backend와 같은 형식을
    쓰므로 값을 고치지 않고 여기서 맞춘다.
    """
    if url.startswith("postgresql://"):
        return "postgresql+psycopg://" + url[len("postgresql://") :]
    if url.startswith("postgres://"):
        return "postgresql+psycopg://" + url[len("postgres://") :]
    return url


def database_url_from_env(env_path: Path | None = None) -> str:
    """``.env``의 ``DATABASE_URL``을 읽는다. 접속 정보를 코드에 두지 않는다.

    ``env_path``를 명시하면 그 파일의 값이 이미 내보내진 환경 변수를 이긴다. 기본값
    ``override=False``로 두면 셸에 공용 DB의 ``DATABASE_URL``이 걸려 있을 때 파일을
    지정해도 무시되어, 의도한 곳이 아닌 DB에 적재된다. 발행된 자료는 트리거가
    UPDATE·DELETE를 막아 되돌릴 수 없으므로 조용히 다른 곳에 넣는 실패는 치명적이다.
    없는 경로를 넘기면 조용히 넘어가지 않고 알린다.
    """
    if env_path is None:
        load_dotenv()
    else:
        if not env_path.exists():
            raise RepositoryError(f"지정한 환경 파일이 없다: {env_path}")
        load_dotenv(env_path, override=True)
    url = (os.environ.get("DATABASE_URL") or "").strip()
    if not url:
        raise RepositoryError(
            "DATABASE_URL이 비어 있다. data/.env.example을 data/.env로 복사해 값을 채운다"
        )
    return normalize_database_url(url)


# ---------------------------------------------------------------------------
# 적재
# ---------------------------------------------------------------------------


class MarketDataRepository:
    """자료 버전과 시계열을 서비스 저장소에 넣는다. 읽어 되돌리는 경로는 없다."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    @classmethod
    def from_env(cls, env_path: Path | None = None) -> MarketDataRepository:
        """``.env``의 ``DATABASE_URL``로 저장소를 연다."""
        return cls(create_engine(database_url_from_env(env_path)))

    @classmethod
    def rehearsal(cls) -> MarketDataRepository:
        """같은 열 정의의 인메모리 SQLite 저장소. **적재 예행용이다.**

        행 검사는 표 하나 단위로 일어나므로, 네 번째 표에서 걸리면 앞의 세 표는 이미 들어가
        있다. 그 상태는 발행본이라 DELETE도 거부되어 되돌릴 수 없다. 그래서 진입점은 전체
        묶음을 여기에 먼저 통째로 넣어 보고, 통과한 뒤에만 실제 저장소를 건드린다.

        **CHECK와 트리거는 이 대역에 없다.** 마이그레이션이 원본이고 이 예행은 파이썬 계층의
        행 검사와 적재 순서까지만 확인한다. 그 위는 실제 적재에서 DB가 본다.

        쓰고 나면 :attr:`engine` 을 ``dispose()`` 한다. 인메모리 대역이라 접속이 닫히면
        내용도 함께 사라진다.
        """
        engine = create_engine("sqlite://")
        METADATA.create_all(engine)
        return cls(engine)

    @property
    def engine(self) -> Engine:
        return self._engine

    # -- 자료 버전 ---------------------------------------------------------

    def publish_dataset_version(self, version: DatasetVersion) -> None:
        """자료 버전을 발행한다. 적재의 첫 단계이며 시계열보다 먼저 와야 한다.

        이미 있는 ``dataset_id``면 :class:`DatasetAlreadyPublishedError`다. 발행된 버전은
        고치지 않고 새 ``dataset_id``를 발행한다 (FR-017).
        """
        if not isinstance(version, DatasetVersion):
            raise DatasetVersionError(f"DatasetVersion이어야 한다: {type(version).__name__}")
        row = version.to_row()
        already = DatasetAlreadyPublishedError(
            f"이미 발행된 자료 버전이다: {version.dataset_id}. "
            "발행된 버전은 고치지 않고 새 dataset_id로 발행한다 (FR-017)"
        )
        try:
            with self._engine.begin() as conn:
                if self._published(conn, version.dataset_id):
                    raise already
                conn.execute(dataset_version_table.insert().values(**row))
        except IntegrityError as error:
            # 확인과 INSERT 사이에 다른 실행이 같은 dataset_id를 넣으면 여기로 온다.
            # uk_dataset_version_dataset_id가 최종 방어선이고, 부르는 쪽이 잡는 것은
            # 도메인 예외이므로 원래 오류를 붙여 같은 예외로 바꾼다.
            if self._is_unique_violation(error):
                raise already from error
            raise

    def is_published(self, dataset_id: str) -> bool:
        """자료 버전 행이 있는지 확인한다. 적재해도 되는지 판단하는 용도다."""
        validate_dataset_id(dataset_id)
        with self._engine.connect() as conn:
            return self._published(conn, dataset_id)

    # -- 시계열 ------------------------------------------------------------

    def load_daily_price(
        self,
        dataset_id: str,
        rows: Iterable[Mapping[str, Any]],
        *,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> int:
        """일별 가격을 적재한다. 거래정지일도 행을 만들고 ``trading_suspended``로 표시한다."""
        return self._load("daily_price", dataset_id, rows, chunk_size=chunk_size)

    def load_corporate_action(
        self,
        dataset_id: str,
        rows: Iterable[Mapping[str, Any]],
        *,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> int:
        """기업행위를 적재한다. ``ex_date``와 ``pay_date``를 모두 담는다."""
        return self._load("corporate_action", dataset_id, rows, chunk_size=chunk_size)

    def load_daily_index(
        self,
        dataset_id: str,
        rows: Iterable[Mapping[str, Any]],
        *,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> int:
        """지수 자료를 적재한다. 이 표의 날짜 집합이 거래일 달력이다."""
        return self._load("daily_index", dataset_id, rows, chunk_size=chunk_size)

    def load_data_gap(
        self,
        dataset_id: str,
        rows: Iterable[Mapping[str, Any]],
        *,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> int:
        """자료 부족 구간을 적재한다."""
        return self._load("data_gap", dataset_id, rows, chunk_size=chunk_size)

    def load_suspension_period(
        self,
        dataset_id: str,
        rows: Iterable[Mapping[str, Any]],
        *,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> int:
        """거래정지 구간을 적재한다. ``daily_price``의 정지 표시에서 파생된 표다."""
        return self._load("suspension_period", dataset_id, rows, chunk_size=chunk_size)

    # -- 대조 기록 ---------------------------------------------------------

    def load_reconciliation_records(
        self,
        dataset_id: str,
        records: Iterable[ReconciliationRecord],
        *,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> int:
        """공시 대조 기록을 적재한다 (FR-016·FR-017).

        시계열과 달리 ``dataset_version_id``·``etf_id``를 찾아 넣으므로 ``_load``를 쓰지
        않는다. 검사 순서는 같다 — DB를 건드리기 전에 행을 모두 검사하고, 자료 버전이
        발행되지 않았으면 :class:`DatasetNotPublishedError`, 이미 그 자료 버전의 대조 기록이
        있으면 :class:`DatasetAlreadyLoadedError` 다. 같은 자료 버전을 다시 대조하면 결과가
        같아야 하므로(발행된 자료는 바뀌지 않는다) 재적재를 거부하는 것이 맞다.
        """
        if chunk_size < 1:
            raise RepositoryError(f"chunk_size는 1 이상이어야 한다: {chunk_size}")
        validate_dataset_id(dataset_id)
        prepared = list(records)
        seen: dict[tuple[str, date, str, str], int] = {}
        for position, record in enumerate(prepared):
            if not isinstance(record, ReconciliationRecord):
                raise RowValidationError(
                    f"reconciliation_record {position}번째가 ReconciliationRecord가 아니다: "
                    f"{type(record).__name__}. cause↔resolution 조합 검사를 거친 값만 적재한다"
                )
            identity = (
                record.instrument_id,
                record.target_date,
                record.sample_reason,
                record.field_name,
            )
            if identity in seen:
                # 같은 표본의 같은 항목이 두 번 기록되면 SC-002의 일치·불일치 건수가 부풀고,
                # 어느 쪽이 맞는지 알 수 없다. 표에는 PK가 없으므로 여기서 막는다.
                raise RowValidationError(
                    f"같은 표본의 같은 항목이 두 번 들어 있다 "
                    f"({seen[identity]}번째와 {position}번째): {identity}"
                )
            seen[identity] = position

        already = DatasetAlreadyLoadedError(
            f"reconciliation_record에 이미 {dataset_id} 자료의 대조 기록이 있다. "
            "발행된 자료는 바뀌지 않으므로 같은 자료 버전을 다시 대조해도 결과가 같다 (FR-017)"
        )
        try:
            with self._engine.begin() as conn:
                version_id = self._dataset_version_id(conn, dataset_id)
                if self._has_reconciliation_records(conn, version_id):
                    raise already
                etf_ids = {
                    instrument_id: self._representative_etf_id(conn, instrument_id)
                    for instrument_id in sorted({record.instrument_id for record in prepared})
                }
                rows: list[dict[str, Any]] = []
                for record in prepared:
                    row = record.to_row()
                    instrument_id = row.pop("instrument_id")
                    row["dataset_version_id"] = version_id
                    row["etf_id"] = etf_ids[instrument_id]
                    rows.append(row)
                for start in range(0, len(rows), chunk_size):
                    chunk: Sequence[dict[str, Any]] = rows[start : start + chunk_size]
                    if chunk:
                        conn.execute(reconciliation_record_table.insert(), list(chunk))
        except IntegrityError as error:
            if self._is_unique_violation(error):
                raise already from error
            raise
        return len(prepared)

    # -- 대표 상품 정보 -----------------------------------------------------

    def load_instrument_profiles(
        self,
        dataset_id: str,
        profiles: Iterable[InstrumentProfile],
        *,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ) -> int:
        """대표 상품 정보를 적재한다 (FR-015, T012·T013).

        **여기에 두는 이유**: 이 표는 시계열과 달리 ``dataset_version_id``·``etf_id`` 두 bigint
        외래 키를 갖고(data-model.md), 그 변환은 이미 :meth:`load_reconciliation_records` 가
        하고 있다. 같은 변환을 별도 모듈에 다시 쓰면 적재 순서 강제와 재적재 거부라는 이 모듈의
        규칙이 두 곳으로 갈라진다. 일평균 거래대금을 **다시 계산하지 않고** 받는 것도 같은
        까닭이다 — 산출은 :mod:`data_pipeline.instrument_profile` 한 곳의 일이다.

        시계열과 같은 순서로 검사한다. DB를 건드리기 전에 값을 모두 갖추고, 자료 버전이
        발행되지 않았으면 :class:`DatasetNotPublishedError`, 이미 그 자료 버전의 대표 상품
        정보가 있으면 :class:`DatasetAlreadyLoadedError` 다. 자료 버전마다 한 벌이며 고쳐
        쓰지 않는다(FR-017).
        """
        if chunk_size < 1:
            raise RepositoryError(f"chunk_size는 1 이상이어야 한다: {chunk_size}")
        validate_dataset_id(dataset_id)
        prepared = list(profiles)
        seen: dict[str, int] = {}
        for position, profile in enumerate(prepared):
            if not isinstance(profile, InstrumentProfile):
                raise RowValidationError(
                    f"instrument_profile {position}번째가 InstrumentProfile이 아니다: "
                    f"{type(profile).__name__}. FR-015의 값 규칙을 거친 값만 적재한다"
                )
            if profile.instrument_id in seen:
                # 저장 스키마의 UNIQUE (etf_id, dataset_version_id)가 최종 방어선이지만,
                # 여기서 막아야 어느 항목이 겹쳤는지 알려 줄 수 있다.
                raise RowValidationError(
                    f"{profile.instrument_id}의 대표 상품 정보가 두 번 들어 있다 "
                    f"({seen[profile.instrument_id]}번째와 {position}번째)"
                )
            seen[profile.instrument_id] = position

        already = DatasetAlreadyLoadedError(
            f"instrument_profile에 이미 {dataset_id} 자료의 대표 상품 정보가 있다. "
            "발행된 자료는 덧붙이거나 고치지 않고 새 dataset_id로 다시 만든다 (FR-017)"
        )
        try:
            with self._engine.begin() as conn:
                version_id = self._dataset_version_id(conn, dataset_id)
                if self._has_instrument_profiles(conn, version_id):
                    raise already
                rows: list[dict[str, Any]] = []
                for profile in prepared:
                    row = profile.to_row()
                    instrument_id = row.pop("instrument_id")
                    row["dataset_version_id"] = version_id
                    row["etf_id"] = self._representative_etf_id(conn, instrument_id)
                    rows.append(row)
                for start in range(0, len(rows), chunk_size):
                    chunk: Sequence[dict[str, Any]] = rows[start : start + chunk_size]
                    if chunk:
                        conn.execute(instrument_profile_table.insert(), list(chunk))
        except IntegrityError as error:
            if self._is_unique_violation(error):
                raise already from error
            raise
        return len(prepared)

    # -- 내부 --------------------------------------------------------------

    def _load(
        self,
        table_name: str,
        dataset_id: str,
        rows: Iterable[Mapping[str, Any]],
        *,
        chunk_size: int,
    ) -> int:
        spec = _SPECS[table_name]
        if chunk_size < 1:
            raise RepositoryError(f"chunk_size는 1 이상이어야 한다: {chunk_size}")
        validate_dataset_id(dataset_id)
        # DB를 건드리기 전에 행을 모두 검사한다. 절반만 들어간 표를 남기면 재적재 거부에 걸려
        # 되돌릴 수 없다(발행된 자료는 DELETE도 거부된다).
        prepared = _normalize_rows(spec, dataset_id, rows)

        already = DatasetAlreadyLoadedError(
            f"{table_name}에 이미 {dataset_id} 자료가 적재되어 있다. "
            "발행된 자료는 덧붙이거나 고치지 않고 새 dataset_id로 다시 만든다 (FR-017)"
        )
        try:
            with self._engine.begin() as conn:
                if not self._published(conn, dataset_id):
                    raise DatasetNotPublishedError(
                        f"자료 버전 행이 없다: {dataset_id}. "
                        "publish_dataset_version으로 자료 버전을 먼저 발행한 뒤 시계열을 적재한다"
                    )
                if self._has_rows(conn, spec.table, dataset_id):
                    raise already
                for start in range(0, len(prepared), chunk_size):
                    chunk: Sequence[dict[str, Any]] = prepared[start : start + chunk_size]
                    if chunk:
                        conn.execute(spec.table.insert(), list(chunk))
        except IntegrityError as error:
            # 두 실행이 같은 dataset_id를 동시에 적재하면 확인이 둘 다 "없음"을 읽는다.
            # 시계열 PK가 최종 방어선이며, 그때도 도메인 예외로 알린다.
            if self._is_unique_violation(error):
                raise already from error
            raise
        return len(prepared)

    @staticmethod
    def _is_unique_violation(error: IntegrityError) -> bool:
        """유일성 위반인지 가린다. FK·CHECK 위반을 재적재로 오인하지 않기 위함이다."""
        origin = getattr(error, "orig", None)
        sqlstate = getattr(origin, "sqlstate", None)
        if sqlstate is not None:
            return sqlstate == _PG_UNIQUE_VIOLATION
        # SQLite에는 sqlstate가 없다. 테스트 대역에서만 이 갈래를 탄다.
        return "UNIQUE constraint failed" in str(origin)

    @staticmethod
    def _published(conn: Connection, dataset_id: str) -> bool:
        statement = (
            select(dataset_version_table.c.dataset_id)
            .where(dataset_version_table.c.dataset_id == dataset_id)
            .limit(1)
        )
        return conn.execute(statement).first() is not None

    @staticmethod
    def _has_rows(conn: Connection, table: Table, dataset_id: str) -> bool:
        statement = select(table.c.dataset_id).where(table.c.dataset_id == dataset_id).limit(1)
        return conn.execute(statement).first() is not None

    @staticmethod
    def _dataset_version_id(conn: Connection, dataset_id: str) -> int:
        """문자열 ``dataset_id``로 ``dataset_version.id``를 찾는다 (대조 기록의 FK 대상)."""
        statement = (
            select(dataset_version_table.c.id)
            .where(dataset_version_table.c.dataset_id == dataset_id)
            .limit(1)
        )
        found = conn.execute(statement).scalar_one_or_none()
        if found is None:
            raise DatasetNotPublishedError(
                f"자료 버전 행이 없다: {dataset_id}. "
                "publish_dataset_version으로 자료 버전을 먼저 발행한 뒤 대조 기록을 적재한다"
            )
        return int(found)

    @staticmethod
    def _representative_etf_id(conn: Connection, instrument_id: str) -> int:
        """종목 식별자로 ``representative_etf.id``를 찾는다."""
        statement = (
            select(representative_etf_table.c.id)
            .where(representative_etf_table.c.instrument_id == instrument_id)
            .limit(1)
        )
        found = conn.execute(statement).scalar_one_or_none()
        if found is None:
            raise RepresentativeEtfNotFoundError(
                f"대표 ETF 행이 없다: {instrument_id}. reconciliation_record.etf_id가 "
                "representative_etf를 참조하므로 대응표를 먼저 적재해야 한다"
            )
        return int(found)

    @staticmethod
    def _has_instrument_profiles(conn: Connection, dataset_version_id: int) -> bool:
        statement = (
            select(instrument_profile_table.c.id)
            .where(instrument_profile_table.c.dataset_version_id == dataset_version_id)
            .limit(1)
        )
        return conn.execute(statement).first() is not None

    @staticmethod
    def _has_reconciliation_records(conn: Connection, dataset_version_id: int) -> bool:
        statement = (
            select(reconciliation_record_table.c.id)
            .where(reconciliation_record_table.c.dataset_version_id == dataset_version_id)
            .limit(1)
        )
        return conn.execute(statement).first() is not None
