"""섹터-ETF 대응표 검증과 발행 (spec 001 FR-001·FR-002·FR-003, SC-001).

대응표의 원본은 이 파이프라인이 소유한다(research.md 결정 7). Backend는 저장소를 읽기만 하고
대응표를 고치는 경로가 없으므로, 버전을 여닫는 책임은 전부 여기에 있다.

담는 것은 FR-001이 정한 여섯 가지다 — 섹터 이름·대표 ETF 이름·종목 식별자·상장일·기준 지수·
운용사. 국내 상장 비레버리지·비인버스 ETF만 받는다.

버전 규칙 (FR-003).

1. 발행된 대응표 버전은 고치지 않는다. 내용이 바뀌면 새 버전을 만든다.
2. ``effective_to``가 NULL인 행은 최대 하나다. 저장 스키마의 부분 UNIQUE 인덱스
   ``uk_sector_etf_map_version_open``이 이를 강제하므로, 새 버전을 열기 **전에** 이전 버전을
   닫아야 한다. 닫기와 열기는 :meth:`SectorMapRepository.publish`의 한 트랜잭션 안에 있다.
3. 같은 내용으로 다시 발행하면 새 버전을 만들지 않는다(멱등). "같은 내용"의 판정은
   :meth:`SectorEtfMap.fingerprint` — 섹터 코드 순으로 정규화한 항목 전체와 ``is_sample``을
   묶은 SHA-256 — 이며, 버전 이름은 판정에 넣지 않는다. 이름은 내용의 별칭일 뿐이다.

역할 분담. 저장 스키마
(``backend/src/main/resources/db/migration/V20260911114500__etf_market_data.sql``)가 최종
방어선이고 UNIQUE·CHECK·FK를 갖고 있다. 여기서는 DB 제약 이름만으로는 무엇을 어떻게 고쳐야
할지 알 수 없는 것들 — 레버리지·인버스 거부 근거, 섹터마다 대표 ETF 하나, 상장일·기준 지수
필수, 버전 여닫는 순서, 이미 발행된 버전의 내용 변경 — 을 먼저 검사해 뜻이 분명한 오류를 낸다.

적재 방향은 ``raw → processed → PostgreSQL`` 한 방향뿐이다(research.md 결정 8). 이 모듈이
저장소를 읽는 곳은 "지금 열린 버전이 같은 내용인가"를 판정하는 자리뿐이고, 읽은 내용을 파일로
되돌리지 않는다.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Final

from sqlalchemy import (
    BigInteger,
    Boolean,
    Column,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    String,
    Table,
    Text,
    UniqueConstraint,
    create_engine,
    select,
)
from sqlalchemy.engine import Connection, Engine

from data_pipeline.repository import INSTRUMENT_ID_PATTERN, database_url_from_env

#: 대응표 버전 이름의 날짜는 한국 기준이다. ``zoneinfo`` 대신 고정 오프셋을 쓰는 까닭은
#: 한국이 1988년 이후 서머타임을 쓰지 않고, tzdata가 없는 환경에서도 같은 값을 주기
#: 때문이다. 읽는 쪽인 ``worker/src/worker/market_data.py`` 의 ``KST`` 와 같은 정의다.
_KST: Final[timezone] = timezone(timedelta(hours=9), "KST")

#: ``sector.classification``. data-model.md가 업종과 테마 둘로 고정했다.
SECTOR_CLASSIFICATIONS: Final[frozenset[str]] = frozenset({"INDUSTRY", "THEME"})

#: 섹터 코드는 외부 식별자라 URL과 JSON에 그대로 실린다 (contracts/sector-api.md).
#:
#: 끝을 ``$``가 아니라 ``\Z``로 닫는다. 파이썬의 ``$``는 문자열 끝뿐 아니라 **끝에 붙은 줄바꿈
#: 앞**에서도 맞으므로 ``"SEMICONDUCTOR\n"``이 통과한다. 자바 쪽 ``SectorCode``는
#: ``String.matches()``라 그것을 거부하고, 그러면 파이프라인이 넣을 수 있는데 API가 거부하는
#: 코드가 생긴다. ``sector.code``에는 형식 CHECK가 없어 DB도 막아 주지 않는다.
SECTOR_CODE_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[A-Z][A-Z0-9_]{1,63}\Z")

#: 대응표 버전 이름. 기본 생성 형식은 ``SEM-{YYYY-MM-DD}-{NNN}``이다 (contracts/sector-api.md).
#: 끝을 ``\Z``로 닫는 이유는 위와 같다.
MAP_VERSION_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[A-Za-z0-9][A-Za-z0-9._-]{0,63}\Z")
MAP_VERSION_PREFIX: Final[str] = "SEM"
_MAX_VERSION_SEQUENCE: Final[int] = 999

#: PD-01 확정 전까지 쓰는 예시 대응표 (FR-002). 확정되면 T014가 새 버전으로 갈아 끼운다.
SAMPLE_SECTOR_MAP_PATH: Final[Path] = (
    Path(__file__).resolve().parents[3]
    / "specs/001-etf-market-data/contracts/fixtures/sample-sector-map.json"
)

#: 저장 스키마의 열 길이. 넘치면 PostgreSQL이 자르지 않고 거부하므로 미리 같은 한도로 검사한다.
_LEN_SECTOR_CODE: Final[int] = 64
_LEN_NAME: Final[int] = 200
_LEN_CLASSIFICATION: Final[int] = 16
_LEN_INSTRUMENT_ID: Final[int] = 32
_LEN_VERSION: Final[int] = 64
_LEN_DESCRIPTION: Final[int] = 2000


class SectorMapError(ValueError):
    """대응표 규칙 위반. 값을 고쳐 다시 발행해야 한다."""


class SectorMapFileError(SectorMapError):
    """대응표 파일을 읽지 못했거나 모양이 계약과 다르다."""


class LeveragedEtfRejectedError(SectorMapError):
    """레버리지·인버스 ETF를 대표 ETF로 쓰려 했다 (FR-001).

    거부한 근거를 :attr:`reasons`에 담는다. 저장 스키마는 ``leveraged``·``inverse``가 TRUE인
    행을 CHECK로 거부하므로 근거를 표에 남길 자리가 없다. 원천에서 읽은 값이 무엇이었는지는
    이 예외와 진입점이 찍는 출력이 유일한 기록이다.
    """

    def __init__(self, instrument_id: str, name: str, reasons: tuple[str, ...]) -> None:
        self.instrument_id = instrument_id
        self.name = name
        self.reasons = reasons
        super().__init__(
            "레버리지·인버스 ETF는 대표 ETF로 쓸 수 없다 (FR-001): "
            f"{instrument_id} {name} — {', '.join(reasons)}"
        )


class DuplicateSectorError(SectorMapError):
    """한 대응표 안에 같은 섹터가 두 번 들어 있다 (SC-001)."""


class ConflictingEtfAttributesError(SectorMapError):
    """같은 종목이 한 대응표 안에서 서로 다른 내용으로 두 번 나왔다.

    ``representative_etf``는 종목 하나에 행 하나라 두 값을 담을 자리가 없다. 막지 않으면 먼저
    온 쪽만 저장되고 뒤엣것은 조용히 사라지며, 내용 지문은 두 값을 다르게 세므로 저장본과 영영
    달라져 재실행이 멱등하지 않게 된다.
    """


class SectorMapVersionConflictError(SectorMapError):
    """이미 쓰인 버전 이름으로 다른 내용을 발행하려 했다 (FR-003)."""


class SectorMasterConflictError(SectorMapError):
    """이미 저장된 섹터·대표 ETF의 속성을 바꾸려 했다.

    ``sector``와 ``representative_etf``는 버전을 타지 않는 공용 표라, 여기서 값을 바꾸면 이미
    발행된 옛 버전이 가리키는 내용까지 함께 바뀐다. 발행된 버전은 고치지 않는다는 FR-003을
    지키려면 이 경로를 막아야 한다. 상품이 실제로 달라졌다면 다른 ``instrument_id``다.
    """


# ---------------------------------------------------------------------------
# 표 정의
# ---------------------------------------------------------------------------
# 마이그레이션이 원본이고 여기 정의는 INSERT·SELECT 문을 만들기 위한 열 사본이다. CHECK 제약은
# 옮기지 않는다 — 두 곳에 같은 제약을 적으면 둘이 갈라질 때 어느 쪽이 맞는지 알 수 없다.
# 시계열 쪽 표는 ``repository.METADATA``가 따로 갖고 있다. 두 묶음 사이에 FK가 없어 나눠 둔다.

SECTOR_METADATA: Final[MetaData] = MetaData()


def _identity_column() -> Column[int]:
    """관계형 표의 PK. PostgreSQL은 IDENTITY로 값을 만들고 SQLite 대역은 INTEGER로 받는다."""
    return Column(
        "id",
        BigInteger().with_variant(Integer, "sqlite"),
        primary_key=True,
        autoincrement=True,
    )


def _reference_column(name: str, target: str) -> Column[int]:
    return Column(
        name,
        BigInteger().with_variant(Integer, "sqlite"),
        ForeignKey(target),
        nullable=False,
    )


sector_table: Final[Table] = Table(
    "sector",
    SECTOR_METADATA,
    _identity_column(),
    Column("code", String(_LEN_SECTOR_CODE), nullable=False, unique=True),
    Column("name", String(_LEN_NAME), nullable=False),
    Column("classification", String(_LEN_CLASSIFICATION), nullable=False),
    Column("description", Text, nullable=True),
)

representative_etf_table: Final[Table] = Table(
    "representative_etf",
    SECTOR_METADATA,
    _identity_column(),
    Column("instrument_id", String(_LEN_INSTRUMENT_ID), nullable=False, unique=True),
    Column("name", String(_LEN_NAME), nullable=False),
    Column("listed_on", Date, nullable=False),
    Column("delisted_on", Date, nullable=True),
    Column("benchmark_index", String(_LEN_NAME), nullable=False),
    Column("manager", String(_LEN_NAME), nullable=False),
    Column("leveraged", Boolean, nullable=False, default=False),
    Column("inverse", Boolean, nullable=False, default=False),
)

sector_etf_map_version_table: Final[Table] = Table(
    "sector_etf_map_version",
    SECTOR_METADATA,
    _identity_column(),
    Column("version", String(_LEN_VERSION), nullable=False, unique=True),
    Column("effective_from", DateTime(timezone=True), nullable=False),
    Column("effective_to", DateTime(timezone=True), nullable=True),
    Column("is_sample", Boolean, nullable=False),
    Column("published_at", DateTime(timezone=True), nullable=False),
)

# "현재 버전은 최대 하나"를 마이그레이션과 같은 부분 UNIQUE 인덱스로 옮겨 둔다. 여기서 표를
# 만드는 것은 테스트 대역뿐이지만, 닫기를 빠뜨린 채 여는 실수는 그 대역에서도 걸려야 한다.
Index(
    "uk_sector_etf_map_version_open",
    sector_etf_map_version_table.c.effective_to.is_(None),
    unique=True,
    sqlite_where=sector_etf_map_version_table.c.effective_to.is_(None),
    postgresql_where=sector_etf_map_version_table.c.effective_to.is_(None),
)

sector_etf_map_entry_table: Final[Table] = Table(
    "sector_etf_map_entry",
    SECTOR_METADATA,
    _identity_column(),
    _reference_column("map_version_id", "sector_etf_map_version.id"),
    _reference_column("sector_id", "sector.id"),
    _reference_column("etf_id", "representative_etf.id"),
    UniqueConstraint("map_version_id", "sector_id", name="uk_sector_etf_map_entry_version_sector"),
)


# ---------------------------------------------------------------------------
# 값 검사 도우미
# ---------------------------------------------------------------------------


def _require_text(value: object, field: str, *, max_length: int) -> str:
    if not isinstance(value, str) or not value.strip():
        raise SectorMapError(f"{field}은(는) 비어 있을 수 없다: {value!r}")
    if value != value.strip():
        raise SectorMapError(f"{field}의 앞뒤에 공백이 있다: {value!r}")
    if len(value) > max_length:
        raise SectorMapError(f"{field}은(는) {max_length}자를 넘을 수 없다: {value!r}")
    return value


def _require_date(value: object, field: str) -> date:
    if isinstance(value, datetime):
        raise SectorMapError(f"{field}은(는) 날짜 열이라 시각을 담을 수 없다: {value!r}")
    if not isinstance(value, date):
        raise SectorMapError(f"{field}은(는) date여야 한다: {value!r}")
    return value


def _require_bool(value: object, field: str) -> bool:
    if not isinstance(value, bool):
        raise SectorMapError(f"{field}은(는) bool이어야 한다: {value!r}")
    return value


def _require_aware(value: object, field: str) -> datetime:
    if not isinstance(value, datetime):
        raise SectorMapError(f"{field}은(는) datetime이어야 한다: {type(value).__name__}")
    if value.tzinfo is None or value.tzinfo.utcoffset(value) is None:
        raise SectorMapError(
            f"{field}은(는) 시간대가 붙은 datetime이어야 한다 (저장 열이 timestamptz다): {value!r}"
        )
    return value


def validate_map_version(version: object) -> str:
    """대응표 버전 이름을 검사하고 그대로 돌려준다."""
    if not isinstance(version, str) or MAP_VERSION_PATTERN.match(version) is None:
        raise SectorMapError(
            "대응표 버전 이름은 영숫자로 시작하고 64자 이하의 영숫자·`.`·`_`·`-`여야 한다: "
            f"{version!r}"
        )
    return version


# ---------------------------------------------------------------------------
# 값 객체
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class RepresentativeEtf:
    """대표 ETF 하나. ``representative_etf`` 한 행에 대응한다 (FR-001).

    ``leveraged``·``inverse``를 값으로 들고 있는 이유는 원천에서 읽은 값을 확인하고 거부한
    근거를 남기기 위함이다. 참인 채로 발행하려 하면 :class:`LeveragedEtfRejectedError`다.
    """

    instrument_id: str
    name: str
    listed_on: date
    benchmark_index: str
    manager: str
    delisted_on: date | None = None
    leveraged: bool = False
    inverse: bool = False

    def __post_init__(self) -> None:
        if (
            not isinstance(self.instrument_id, str)
            or INSTRUMENT_ID_PATTERN.match(self.instrument_id) is None
        ):
            raise SectorMapError(
                f"instrument_id는 {{6자리}}.XKRX 형식이어야 한다: {self.instrument_id!r}"
            )
        _require_text(self.name, "대표 ETF 이름", max_length=_LEN_NAME)
        # 상장일과 기준 지수는 FR-001의 필수 항목이다. 저장 스키마도 NOT NULL이지만, 여기서
        # 막지 않으면 파이프라인을 돌린 사람이 받는 것은 열 이름뿐이다.
        _require_date(self.listed_on, f"{self.instrument_id}의 상장일(listed_on)")
        _require_text(
            self.benchmark_index,
            f"{self.instrument_id}의 기준 지수(benchmark_index)",
            max_length=_LEN_NAME,
        )
        _require_text(self.manager, f"{self.instrument_id}의 운용사(manager)", max_length=_LEN_NAME)
        if self.delisted_on is not None:
            _require_date(self.delisted_on, f"{self.instrument_id}의 상장폐지일(delisted_on)")
            if self.delisted_on <= self.listed_on:
                raise SectorMapError(
                    f"상장폐지일은 상장일 이후여야 한다: {self.instrument_id} "
                    f"{self.listed_on} → {self.delisted_on}"
                )
        _require_bool(self.leveraged, "leveraged")
        _require_bool(self.inverse, "inverse")
        reasons: list[str] = []
        if self.leveraged:
            reasons.append("leveraged=True")
        if self.inverse:
            reasons.append("inverse=True")
        if reasons:
            raise LeveragedEtfRejectedError(self.instrument_id, self.name, tuple(reasons))

    def to_row(self) -> dict[str, Any]:
        """``representative_etf`` INSERT에 쓸 값 묶음. 열 이름은 마이그레이션과 같다."""
        return {
            "instrument_id": self.instrument_id,
            "name": self.name,
            "listed_on": self.listed_on,
            "delisted_on": self.delisted_on,
            "benchmark_index": self.benchmark_index,
            "manager": self.manager,
            "leveraged": self.leveraged,
            "inverse": self.inverse,
        }


@dataclass(frozen=True)
class SectorMapEntry:
    """대응표 항목 하나. 섹터와 그 섹터의 대표 ETF **하나**를 묶는다 (SC-001).

    섹터마다 대표 ETF가 정확히 하나라는 규칙을 목록이 아니라 구조로 표현한다. 값이 하나뿐인
    필드면 둘을 넣을 자리가 없고, 비어 있는 경우도 만들 수 없다.
    """

    code: str
    name: str
    classification: str
    etf: RepresentativeEtf
    description: str | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.code, str) or SECTOR_CODE_PATTERN.match(self.code) is None:
            raise SectorMapError(
                f"섹터 코드는 대문자로 시작하는 2~64자의 대문자·숫자·`_`여야 한다: {self.code!r}"
            )
        _require_text(self.name, f"{self.code}의 섹터 이름", max_length=_LEN_NAME)
        _require_text(
            self.classification,
            f"{self.code}의 classification",
            max_length=_LEN_CLASSIFICATION,
        )
        if self.classification not in SECTOR_CLASSIFICATIONS:
            raise SectorMapError(
                f"classification은 {sorted(SECTOR_CLASSIFICATIONS)} 중 하나여야 한다: "
                f"{self.classification!r}"
            )
        if not isinstance(self.etf, RepresentativeEtf):
            raise SectorMapError(
                f"{self.code}의 representative_etf가 없다. 대표 ETF가 비어 있는 섹터는 "
                "만들 수 없다 (SC-001)"
            )
        if self.description is not None:
            _require_text(
                self.description, f"{self.code}의 description", max_length=_LEN_DESCRIPTION
            )

    def to_sector_row(self) -> dict[str, Any]:
        """``sector`` INSERT에 쓸 값 묶음."""
        return {
            "code": self.code,
            "name": self.name,
            "classification": self.classification,
            "description": self.description,
        }


@dataclass(frozen=True)
class SectorEtfMap:
    """대응표 한 벌. 발행되면 ``sector_etf_map_version`` 한 행과 그 항목들이 된다."""

    entries: tuple[SectorMapEntry, ...]
    is_sample: bool

    def __post_init__(self) -> None:
        if not isinstance(self.entries, tuple) or not self.entries:
            raise SectorMapError("대응표에 항목이 하나도 없다")
        _require_bool(self.is_sample, "is_sample")
        seen: dict[str, int] = {}
        etf_positions: dict[str, int] = {}
        for position, entry in enumerate(self.entries):
            if not isinstance(entry, SectorMapEntry):
                raise SectorMapError(
                    f"{position}번째 항목이 SectorMapEntry가 아니다: {type(entry).__name__}"
                )
            if entry.code in seen:
                # 섹터마다 대표 ETF가 정확히 하나다 (SC-001). 저장 스키마의
                # uk_sector_etf_map_entry_version_sector가 최종 방어선이고, 여기서는 어느
                # 항목끼리 부딪혔는지 알려 준다.
                raise DuplicateSectorError(
                    f"섹터 {entry.code}에 대표 ETF가 둘 이상 붙었다 "
                    f"({seen[entry.code]}번째와 {position}번째). 섹터마다 정확히 하나여야 "
                    "한다 (SC-001)"
                )
            seen[entry.code] = position

            # 같은 종목이 여러 섹터에 붙는 것은 허용하지만(data-model.md), 그때 속성까지
            # 같아야 한다. representative_etf는 종목 하나에 행 하나라 서로 다른 값을 담을
            # 자리가 없고, 저장하면 먼저 온 쪽만 남는다. 그대로 두면 뒤엣것이 조용히
            # 사라지고, 내용 지문은 두 값을 다르게 세므로 저장본과 영영 달라져 재실행이
            # 멱등하지 않게 된다(같은 파일로 돌릴 때마다 새 버전이 열린다).
            first = etf_positions.get(entry.etf.instrument_id)
            if first is not None and self.entries[first].etf != entry.etf:
                raise ConflictingEtfAttributesError(
                    f"같은 종목 {entry.etf.instrument_id}이(가) 서로 다른 내용으로 두 번 "
                    f"나온다 ({first}번째 {self.entries[first].code}, {position}번째 "
                    f"{entry.code}). 같은 종목이 여러 섹터에 붙는 것은 되지만 이름·상장일·"
                    "기준 지수·운용사는 같아야 한다. 상품이 실제로 다르다면 다른 종목코드다"
                )
            etf_positions.setdefault(entry.etf.instrument_id, position)

    @classmethod
    def of(cls, entries: Iterable[SectorMapEntry], *, is_sample: bool) -> SectorEtfMap:
        return cls(entries=tuple(entries), is_sample=is_sample)

    @property
    def codes(self) -> tuple[str, ...]:
        return tuple(entry.code for entry in self.entries)

    def canonical(self) -> dict[str, Any]:
        """내용 동일성 판정에 쓰는 정규형. 항목 차례는 뜻을 바꾸지 않으므로 코드로 정렬한다."""
        return {
            "is_sample": self.is_sample,
            "sectors": [
                {
                    "code": entry.code,
                    "name": entry.name,
                    "classification": entry.classification,
                    "description": entry.description,
                    "representative_etf": {
                        "instrument_id": entry.etf.instrument_id,
                        "name": entry.etf.name,
                        "listed_on": entry.etf.listed_on.isoformat(),
                        "delisted_on": (
                            None
                            if entry.etf.delisted_on is None
                            else entry.etf.delisted_on.isoformat()
                        ),
                        "benchmark_index": entry.etf.benchmark_index,
                        "manager": entry.etf.manager,
                    },
                }
                for entry in sorted(self.entries, key=lambda item: item.code)
            ],
        }

    def fingerprint(self) -> str:
        """내용 지문. 이 값이 같으면 같은 대응표로 보고 새 버전을 만들지 않는다.

        버전 이름은 넣지 않는다. 이름은 내용의 별칭일 뿐이라, 이름만 바꿔 다시 돌렸을 때
        내용이 같은데도 버전이 늘어나면 FR-003이 말하는 "내용이 바뀌면"과 어긋난다.
        ``leveraged``·``inverse``도 넣지 않는다 — 둘 다 거짓인 대응표만 여기까지 온다.
        """
        payload = json.dumps(self.canonical(), ensure_ascii=False, sort_keys=True)
        return hashlib.sha256(payload.encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------
# 대응표 파일 읽기
# ---------------------------------------------------------------------------
# ``$``로 시작하는 키는 주석으로 보고 건너뛴다. 예시 대응표는 그 값이 예시이며 PD-01에서
# 확정된다는 사실을 파일 안에서 밝혀야 하는데(FR-002), JSON에는 주석이 없어 자리를 마련한다.

_ETF_REQUIRED: Final[frozenset[str]] = frozenset(
    {"instrument_id", "name", "listed_on", "benchmark_index", "manager"}
)
_ETF_OPTIONAL: Final[frozenset[str]] = frozenset({"delisted_on", "leveraged", "inverse"})
_SECTOR_REQUIRED: Final[frozenset[str]] = frozenset({"code", "name", "classification"})
_SECTOR_OPTIONAL: Final[frozenset[str]] = frozenset({"description", "representative_etf"})
_ROOT_REQUIRED: Final[frozenset[str]] = frozenset({"is_sample", "sectors"})


def _visible_keys(payload: object, where: str) -> set[str]:
    if not isinstance(payload, Mapping):
        raise SectorMapFileError(f"{where}이(가) 객체가 아니다: {type(payload).__name__}")
    return {key for key in payload if not str(key).startswith("$")}


def _check_keys(
    payload: object, where: str, required: frozenset[str], optional: frozenset[str]
) -> None:
    keys = _visible_keys(payload, where)
    missing = sorted(required - keys)
    if missing:
        raise SectorMapFileError(f"{where}에 없는 항목: {missing}")
    unknown = sorted(keys - required - optional)
    if unknown:
        raise SectorMapFileError(f"{where}의 모르는 항목: {unknown}")


def _parse_date(value: object, field: str) -> date:
    if not isinstance(value, str):
        raise SectorMapFileError(f"{field}은(는) `YYYY-MM-DD` 문자열이어야 한다: {value!r}")
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise SectorMapFileError(f"{field}이(가) 날짜가 아니다: {value!r}") from exc


def _parse_etf(payload: object, code: str) -> RepresentativeEtf:
    if not isinstance(payload, Mapping):
        raise SectorMapFileError(
            f"{code}에 representative_etf가 없다. 대표 ETF가 비어 있는 섹터는 만들 수 없다 (SC-001)"
        )
    where = f"{code}의 representative_etf"
    _check_keys(payload, where, _ETF_REQUIRED, _ETF_OPTIONAL)
    delisted = payload.get("delisted_on")
    return RepresentativeEtf(
        instrument_id=payload["instrument_id"],
        name=payload["name"],
        listed_on=_parse_date(payload["listed_on"], f"{where}의 listed_on"),
        benchmark_index=payload["benchmark_index"],
        manager=payload["manager"],
        delisted_on=(None if delisted is None else _parse_date(delisted, f"{where}의 delisted_on")),
        leveraged=_require_bool(payload.get("leveraged", False), f"{where}의 leveraged"),
        inverse=_require_bool(payload.get("inverse", False), f"{where}의 inverse"),
    )


def sector_map_from_payload(payload: Mapping[str, Any]) -> SectorEtfMap:
    """계약 모양의 JSON 구조를 :class:`SectorEtfMap`으로 바꾼다.

    버전 이름은 파일에 담지 않는다. 이름을 정하는 것은 발행하는 쪽의 일이고(``--map-version``
    또는 자동 생성), 파일이 이름까지 들고 있으면 같은 내용을 다른 이름으로 부르는 사본이 생긴다.
    """
    _check_keys(payload, "대응표", _ROOT_REQUIRED, frozenset())
    sectors = payload["sectors"]
    if not isinstance(sectors, list):
        raise SectorMapFileError(f"sectors는 배열이어야 한다: {type(sectors).__name__}")
    is_sample = payload["is_sample"]
    if not isinstance(is_sample, bool):
        raise SectorMapFileError(f"is_sample은 true/false여야 한다: {is_sample!r}")
    entries: list[SectorMapEntry] = []
    for position, raw in enumerate(sectors):
        where = f"{position}번째 섹터"
        _check_keys(raw, where, _SECTOR_REQUIRED, _SECTOR_OPTIONAL)
        code = raw["code"]
        entries.append(
            SectorMapEntry(
                code=code,
                name=raw["name"],
                classification=raw["classification"],
                description=raw.get("description"),
                etf=_parse_etf(raw.get("representative_etf"), str(code)),
            )
        )
    return SectorEtfMap.of(entries, is_sample=is_sample)


def load_sector_map(path: Path) -> SectorEtfMap:
    """대응표 파일을 읽어 검증한다. 검증을 통과한 것만 발행할 수 있다."""
    if not path.exists():
        raise SectorMapFileError(f"대응표 파일이 없다: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise SectorMapFileError(f"대응표 파일이 JSON이 아니다: {path} ({exc})") from exc
    if not isinstance(payload, Mapping):
        raise SectorMapFileError(f"대응표 파일의 최상위가 객체가 아니다: {path}")
    return sector_map_from_payload(payload)


# ---------------------------------------------------------------------------
# 발행
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class PublishResult:
    """발행 결과. ``created``가 거짓이면 같은 내용이 이미 열려 있어 아무것도 만들지 않았다."""

    version: str
    map_version_id: int
    created: bool
    entry_count: int
    closed_version: str | None = None


def _as_aware(value: datetime | None) -> datetime | None:
    """저장소에서 읽은 시각에 시간대를 붙인다.

    PostgreSQL의 ``timestamptz``는 시간대가 붙은 값을 돌려주지만, 인메모리 SQLite 대역은
    시간대를 잃고 naive로 돌려준다. 저장은 UTC 기준이므로 naive면 UTC로 읽는다.
    """
    if value is None or value.tzinfo is not None:
        return value
    return value.replace(tzinfo=UTC)


class SectorMapRepository:
    """대응표 버전을 저장소에 발행한다. 파이프라인만 쓰고 Backend는 읽기만 한다 (결정 7)."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    @classmethod
    def from_env(cls, env_path: Path | None = None) -> SectorMapRepository:
        """``.env``의 ``DATABASE_URL``로 저장소를 연다.

        ``database_url_from_env``를 거치는 이유는 ``.env``가 컨테이너·Backend와 같은
        ``postgresql://`` 형식을 쓰는데 이 파트에 설치된 드라이버는 psycopg 3이기 때문이다.
        """
        return cls(create_engine(database_url_from_env(env_path)))

    @property
    def engine(self) -> Engine:
        return self._engine

    def current_version(self) -> tuple[str, SectorEtfMap] | None:
        """지금 열려 있는(``effective_to IS NULL``) 버전의 이름과 내용."""
        with self._engine.connect() as conn:
            row = self._open_version(conn)
            if row is None:
                return None
            return str(row.version), self._read_map(conn, int(row.id), bool(row.is_sample))

    def publish(
        self,
        sector_map: SectorEtfMap,
        *,
        version: str | None = None,
        published_at: datetime | None = None,
    ) -> PublishResult:
        """대응표를 새 버전으로 발행한다.

        이전 버전을 닫는 UPDATE와 새 버전을 여는 INSERT가 **한 트랜잭션** 안에 있다. 저장
        스키마의 ``uk_sector_etf_map_version_open``이 열린 버전을 최대 하나로 묶으므로 닫기가
        먼저 와야 하고, 둘을 나눠 두면 중간에 실패했을 때 "열린 버전 없음" 상태가 남아 조회가
        ``SECTOR_MAP_NOT_READY``가 된다.

        같은 내용이 이미 열려 있으면 아무것도 만들지 않고 ``created=False``를 돌려준다.
        """
        if not isinstance(sector_map, SectorEtfMap):
            raise SectorMapError(f"SectorEtfMap이어야 한다: {type(sector_map).__name__}")
        if version is not None:
            validate_map_version(version)
        moment = _require_aware(published_at or datetime.now(UTC), "published_at")

        with self._engine.begin() as conn:
            open_row = self._open_version(conn)
            if open_row is not None:
                # 저장된 행을 값 객체로 되살리면 지금의 입력 규칙이 다시 걸린다. 규칙을 나중에
                # 조인 뒤에는 옛 행이 그 규칙을 어길 수 있는데, 그때 예외를 그대로 올리면
                # 닫기·열기에 닿기도 전에 멈춰 **새 버전을 영영 발행할 수 없게 된다.**
                # 읽히지 않는 대응표는 "입력이 잘못됐다"가 아니라 "지금 것과 내용이 다르다"로
                # 본다. 그래야 새 버전을 발행해 빠져나갈 길이 남는다.
                try:
                    current = self._read_map(conn, int(open_row.id), bool(open_row.is_sample))
                except SectorMapError:
                    current = None
                if current is not None and current.fingerprint() == sector_map.fingerprint():
                    # 멱등. 이름만 다르게 다시 돌려도 버전이 늘지 않는다.
                    return PublishResult(
                        version=str(open_row.version),
                        map_version_id=int(open_row.id),
                        created=False,
                        entry_count=len(sector_map.entries),
                    )
                opened_at = _as_aware(open_row.effective_from)
                if opened_at is not None and moment <= opened_at:
                    raise SectorMapError(
                        f"발행 시각({moment.isoformat()})이 지금 열린 버전 {open_row.version}의"
                        f" 시작 시각({opened_at.isoformat()})보다 뒤여야 한다. 저장 스키마가"
                        " effective_to > effective_from을 요구한다"
                    )

            # 이름의 날짜는 한국 기준이다. moment는 시간대가 붙은 값이고 대개 UTC인데, 그대로
            # .date()를 쓰면 한국 오전 0~9시에 발행한 것이 전날 이름을 받는다. 같은 행의
            # effective_from을 읽는 쪽(worker의 _as_date)은 한국 기준으로 자르므로 이름과
            # 유효일이 하루 어긋나고, 전날 쓰던 순번과도 부딪힌다.
            label = version or self._next_version(conn, moment.astimezone(_KST).date())
            if self._version_exists(conn, label):
                raise SectorMapVersionConflictError(
                    f"이미 쓰인 대응표 버전 이름이다: {label}. 발행된 버전은 고치지 않고 "
                    "새 이름으로 발행한다 (FR-003)"
                )

            sector_ids = self._ensure_sectors(conn, sector_map)
            etf_ids = self._ensure_etfs(conn, sector_map)

            closed_version: str | None = None
            if open_row is not None:
                # 먼저 닫는다. 부분 UNIQUE 인덱스가 열린 버전을 하나로 묶고 있다.
                conn.execute(
                    sector_etf_map_version_table.update()
                    .where(sector_etf_map_version_table.c.id == open_row.id)
                    .values(effective_to=moment)
                )
                closed_version = str(open_row.version)

            inserted = conn.execute(
                sector_etf_map_version_table.insert().values(
                    version=label,
                    effective_from=moment,
                    effective_to=None,
                    is_sample=sector_map.is_sample,
                    published_at=moment,
                )
            )
            map_version_id = int(inserted.inserted_primary_key[0])
            conn.execute(
                sector_etf_map_entry_table.insert(),
                [
                    {
                        "map_version_id": map_version_id,
                        "sector_id": sector_ids[entry.code],
                        "etf_id": etf_ids[entry.etf.instrument_id],
                    }
                    for entry in sector_map.entries
                ],
            )

        return PublishResult(
            version=label,
            map_version_id=map_version_id,
            created=True,
            entry_count=len(sector_map.entries),
            closed_version=closed_version,
        )

    # -- 내부 --------------------------------------------------------------

    @staticmethod
    def _open_version(conn: Connection) -> Any:
        statement = select(
            sector_etf_map_version_table.c.id,
            sector_etf_map_version_table.c.version,
            sector_etf_map_version_table.c.effective_from,
            sector_etf_map_version_table.c.is_sample,
        ).where(sector_etf_map_version_table.c.effective_to.is_(None))
        return conn.execute(statement).first()

    @staticmethod
    def _version_exists(conn: Connection, version: str) -> bool:
        statement = (
            select(sector_etf_map_version_table.c.id)
            .where(sector_etf_map_version_table.c.version == version)
            .limit(1)
        )
        return conn.execute(statement).first() is not None

    @staticmethod
    def _next_version(conn: Connection, day: date) -> str:
        """``SEM-{YYYY-MM-DD}-{NNN}``. 같은 날 여러 번 발행하면 뒤 번호가 올라간다."""
        prefix = f"{MAP_VERSION_PREFIX}-{day:%Y-%m-%d}-"
        statement = select(sector_etf_map_version_table.c.version).where(
            sector_etf_map_version_table.c.version.like(f"{prefix}%")
        )
        used = {
            int(value[len(prefix) :])
            for value in conn.execute(statement).scalars()
            if value[len(prefix) :].isdigit()
        }
        sequence = max(used, default=0) + 1
        if sequence > _MAX_VERSION_SEQUENCE:
            raise SectorMapError(
                f"{day:%Y-%m-%d}에 발행한 대응표 버전이 {_MAX_VERSION_SEQUENCE}개를 넘었다. "
                "--map-version으로 이름을 직접 정한다"
            )
        return f"{prefix}{sequence:03d}"

    @staticmethod
    def _read_map(conn: Connection, map_version_id: int, is_sample: bool) -> SectorEtfMap:
        """한 버전의 내용을 읽어 값 객체로 되돌린다. 같은 내용인지 판정하는 데만 쓴다."""
        statement = (
            select(
                sector_table.c.code,
                sector_table.c.name.label("sector_name"),
                sector_table.c.classification,
                sector_table.c.description,
                representative_etf_table.c.instrument_id,
                representative_etf_table.c.name.label("etf_name"),
                representative_etf_table.c.listed_on,
                representative_etf_table.c.delisted_on,
                representative_etf_table.c.benchmark_index,
                representative_etf_table.c.manager,
            )
            .select_from(
                sector_etf_map_entry_table.join(
                    sector_table, sector_etf_map_entry_table.c.sector_id == sector_table.c.id
                ).join(
                    representative_etf_table,
                    sector_etf_map_entry_table.c.etf_id == representative_etf_table.c.id,
                )
            )
            .where(sector_etf_map_entry_table.c.map_version_id == map_version_id)
            .order_by(sector_table.c.code)
        )
        entries = [
            SectorMapEntry(
                code=row.code,
                name=row.sector_name,
                classification=row.classification,
                description=row.description,
                etf=RepresentativeEtf(
                    instrument_id=row.instrument_id,
                    name=row.etf_name,
                    listed_on=row.listed_on,
                    benchmark_index=row.benchmark_index,
                    manager=row.manager,
                    delisted_on=row.delisted_on,
                ),
            )
            for row in conn.execute(statement)
        ]
        if not entries:
            raise SectorMapError(
                f"대응표 버전 {map_version_id}에 항목이 하나도 없다. 발행이 중간에 끊겼을 수 있다"
            )
        return SectorEtfMap.of(entries, is_sample=is_sample)

    @staticmethod
    def _ensure_sectors(conn: Connection, sector_map: SectorEtfMap) -> dict[str, int]:
        """섹터 행을 확보한다. 이미 있으면 속성이 같아야 한다."""
        ids: dict[str, int] = {}
        for entry in sector_map.entries:
            row = conn.execute(
                select(
                    sector_table.c.id,
                    sector_table.c.name,
                    sector_table.c.classification,
                    sector_table.c.description,
                ).where(sector_table.c.code == entry.code)
            ).first()
            if row is None:
                inserted = conn.execute(sector_table.insert().values(**entry.to_sector_row()))
                ids[entry.code] = int(inserted.inserted_primary_key[0])
                continue
            stored = (row.name, row.classification, row.description)
            incoming = (entry.name, entry.classification, entry.description)
            if stored != incoming:
                raise SectorMasterConflictError(
                    f"이미 저장된 섹터 {entry.code}의 속성이 다르다: {stored} ≠ {incoming}. "
                    "섹터 표는 버전을 타지 않으므로 값을 바꾸면 이미 발행된 옛 버전이 가리키는 "
                    "내용까지 함께 바뀐다 (FR-003)"
                )
            ids[entry.code] = int(row.id)
        return ids

    @staticmethod
    def _ensure_etfs(conn: Connection, sector_map: SectorEtfMap) -> dict[str, int]:
        """대표 ETF 행을 확보한다. 같은 ETF가 여러 섹터에 붙는 것은 막지 않는다 (data-model.md)."""
        ids: dict[str, int] = {}
        for entry in sector_map.entries:
            etf = entry.etf
            if etf.instrument_id in ids:
                continue
            row = conn.execute(
                select(representative_etf_table).where(
                    representative_etf_table.c.instrument_id == etf.instrument_id
                )
            ).first()
            if row is None:
                inserted = conn.execute(representative_etf_table.insert().values(**etf.to_row()))
                ids[etf.instrument_id] = int(inserted.inserted_primary_key[0])
                continue
            stored = (
                row.name,
                row.listed_on,
                row.delisted_on,
                row.benchmark_index,
                row.manager,
                bool(row.leveraged),
                bool(row.inverse),
            )
            incoming = (
                etf.name,
                etf.listed_on,
                etf.delisted_on,
                etf.benchmark_index,
                etf.manager,
                etf.leveraged,
                etf.inverse,
            )
            if stored != incoming:
                raise SectorMasterConflictError(
                    f"이미 저장된 대표 ETF {etf.instrument_id}의 속성이 다르다: "
                    f"{stored} ≠ {incoming}. 대표 ETF 표는 버전을 타지 않으므로 값을 바꾸면 "
                    "이미 발행된 옛 버전이 가리키는 내용까지 함께 바뀐다 (FR-003). 상품이 "
                    "실제로 달라졌다면 다른 instrument_id다"
                )
            ids[etf.instrument_id] = int(row.id)
        return ids
