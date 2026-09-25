"""PostgreSQL 적재 (spec FR-029·FR-030).

`regime_search_snapshot`·`regime_baseline_month`·`regime_search_case`를 한 트랜잭션에 쓴다.
**결과가 달라진 것만 새 revision으로 저장하고 이전 행을 덮어쓰지 않는다** — 내용이 같으면
행이 늘지 않아야 배치를 두 번 돌려도 멱등하다(SC-002).

보관 대상은 각 (모드·기준월)의 가장 최신 항목과 006 기록이 가리키는 항목 둘뿐이고, 정리는
적재를 마친 **같은 실행에서** 한다 — 사람이 따로 돌리지 않는다(FR-029).

표 정의를 여기에 두는 이유는 `repository.py`와 같다 — 최종 방어선은 Flyway 마이그레이션이고
(010은 T086), 여기서는 같은 열로 인메모리 SQLite에 적재 규칙을 그대로 돌려 볼 수 있어야 한다.
**열 이름·제약이 마이그레이션과 어긋나면 적재가 운영에서만 깨진다.**

⚠ `UNIQUE (mode, field_id, baseline_month, …)`는 PostgreSQL에서 **`field_id`가 NULL인 종합 행을
묶지 못한다**(NULL은 서로 구별된다). 이 모듈의 판정은 `field_id IS NULL`을 명시해 걸러 내므로
영향을 받지 않지만, 마이그레이션(T086)은 `NULLS NOT DISTINCT`나 `COALESCE(field_id,'ALL')` 식으로
그 자리를 막아야 스키마가 최종 방어선 노릇을 한다.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any, Final

from sqlalchemy import (
    JSON,
    BigInteger,
    Column,
    Date,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    MetaData,
    SmallInteger,
    String,
    Table,
    UniqueConstraint,
    and_,
    create_engine,
    delete,
    func,
    select,
    update,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.engine import Connection, Engine

from data_pipeline.regime.macro.types import format_month, require_month
from data_pipeline.regime.snapshot import (
    COMPOSITE_SCOPE,
    SearchScope,
    Snapshot,
    Versions,
)

METADATA: Final[MetaData] = MetaData()

#: 저장 자릿수를 가진 값은 전부 JSONB 문자열이다 — `NUMERIC`은 scale을 임의로 정하고 넘는 값을
#: 조용히 반올림한다(research R4, `V2__backtest_result.sql` 규약).
_JSON = JSONB().with_variant(JSON, "sqlite")
_BIGINT = BigInteger().with_variant(Integer, "sqlite")

regime_search_snapshot_table: Final[Table] = Table(
    "regime_search_snapshot",
    METADATA,
    Column("id", _BIGINT, primary_key=True, autoincrement=True),
    Column("snapshot_key", String(200), nullable=False),
    Column("mode", String(16), nullable=False),
    Column("field_id", String(32)),
    Column("baseline_month", Date, nullable=False),
    Column("revision", Integer, nullable=False),
    Column("content_hash", String(64), nullable=False),
    Column("data_version", String(40), nullable=False),
    Column("registry_version", String(40), nullable=False),
    Column("model_version", String(40), nullable=False),
    Column("status", String(24), nullable=False),
    Column("status_reason", String(48)),
    Column("scale", _JSON, nullable=False),
    Column("comparison_months", _JSON, nullable=False),
    Column("ranked_candidates", _JSON, nullable=False),
    Column("candidate_counts", _JSON, nullable=False),
    Column("data_asof", _JSON, nullable=False),
    Column("computed_at", DateTime, nullable=False),
    Column("created_at", DateTime, nullable=False),
    Column("updated_at", DateTime, nullable=False),
    UniqueConstraint("snapshot_key", name="uk_regime_snapshot_key"),
    UniqueConstraint(
        "mode", "field_id", "baseline_month", "revision", name="uk_regime_snapshot_revision"
    ),
    UniqueConstraint(
        "mode", "field_id", "baseline_month", "content_hash", name="uk_regime_snapshot_content"
    ),
    Index(
        "ix_regime_snapshot_latest",
        "mode",
        "field_id",
        "baseline_month",
        "revision",
    ),
)

regime_baseline_month_table: Final[Table] = Table(
    "regime_baseline_month",
    METADATA,
    Column("scope_id", String(32), primary_key=True),
    Column("baseline_month", Date, nullable=False),
    Column("earliest_month", Date, nullable=False),
    Column("registry_version", String(40), nullable=False),
    Column("data_version", String(40), nullable=False),
    Column("updated_at", DateTime, nullable=False),
)

regime_search_case_table: Final[Table] = Table(
    "regime_search_case",
    METADATA,
    Column("id", _BIGINT, primary_key=True, autoincrement=True),
    Column(
        "snapshot_id",
        _BIGINT,
        ForeignKey("regime_search_snapshot.id", ondelete="CASCADE"),
        nullable=False,
    ),
    Column("rank", SmallInteger, nullable=False),
    Column("end_month", Date, nullable=False),
    Column("distance", _JSON, nullable=False),
    Column("indicator_values", _JSON, nullable=False),
    Column("explanation", _JSON, nullable=False),
    Column("era_position", _JSON),
    Column("created_at", DateTime, nullable=False),
    Column("updated_at", DateTime, nullable=False),
    UniqueConstraint("snapshot_id", "rank", name="uk_regime_case_rank"),
    UniqueConstraint("snapshot_id", "end_month", name="uk_regime_case_month"),
    Index("ix_regime_case_rank", "snapshot_id", "rank"),
)

#: 006·014가 소유하는 표. **배치는 읽기만 한다** — 보관 정리가 "기록이 가리키는 항목"을 남겨야
#: 하고(FR-029), 철회는 가리키던 참조를 NULL로 만들어야 한다(FR-035). 행을 만들지는 않는다.
regime_search_log_table: Final[Table] = Table(
    "regime_search_log",
    METADATA,
    Column("id", _BIGINT, primary_key=True, autoincrement=True),
    Column("owner_user_id", _BIGINT, nullable=False),
    Column(
        "snapshot_id",
        _BIGINT,
        ForeignKey("regime_search_snapshot.id", ondelete="SET NULL"),
    ),
    Column("snapshot_key", String(200), nullable=False),
    Column("entry_path", String(24), nullable=False),
    Column("baseline_origin", String(16), nullable=False),
    Column("viewed_at", DateTime, nullable=False),
    Index("ix_regime_log_owner", "owner_user_id", "viewed_at"),
)


class RegimeLoadError(RuntimeError):
    """사전 계산 결과를 적재할 수 없다."""


class VersionNotBumpedError(RegimeLoadError):
    """같은 식별 키에 다른 내용이 나왔다 — 버전을 올리지 않고 계산이나 문장을 바꿨다.

    식별 키에는 `revision`이 없어 `UNIQUE (snapshot_key)`가 한 키에 한 내용만 허용한다
    (data-model §2). 자료·등록표 버전이 같은데 내용이 달라졌다면 코드나 설정(블록 구성·문장 표기·
    맥락 표)이 바뀐 것이고, 그 변경은 모델 버전을 올려야 한다(data-model §6). DB 오류로 흘려보내지
    않고 이 사유로 알린다.
    """


class ContentRevertedError(RegimeLoadError):
    """내용이 이전 `revision`의 것으로 되돌아갔다.

    `UNIQUE (mode, field_id, baseline_month, content_hash)`가 같은 내용을 두 번 담는 것을 막는다
    (data-model §2). 자료가 개정됐다가 되돌려진 경우가 여기 해당하는데, **어느 쪽을 최신으로
    삼아야 하는지가 스키마만으로는 정해지지 않으므로** 조용히 넘기지 않고 그 기준월을 실패로
    알린다. 이전 `revision`은 그대로 남아 계속 조회된다(spec FR-034).
    """


@dataclass(frozen=True)
class LoadResult:
    """적재 한 건의 결과."""

    snapshot_key: str
    revision: int
    inserted: bool

    @property
    def unchanged(self) -> bool:
        return not self.inserted


@dataclass(frozen=True)
class BaselineRecord:
    """배치가 남기는 기준월 (data-model §3).

    `earliest_month`는 배치가 채운다 — 사전 계산이 **실제로 있는** 가장 오래된 기준월이라
    적재와 같은 트랜잭션에서 세야 맞는다.
    """

    scope_id: str
    baseline_month: date
    registry_version: str
    data_version: str


@dataclass(frozen=True)
class Withdrawal:
    """철회 한 건의 기록 (spec FR-035, SC-012)."""

    snapshot_key: str
    mode: str
    field_id: str | None
    baseline_month: date
    revision: int
    reason: str
    withdrawn_at: datetime
    detached_logs: int

    def to_record(self) -> dict[str, Any]:
        return {
            "snapshotKey": self.snapshot_key,
            "mode": self.mode,
            "fieldId": self.field_id,
            "scopeId": self.field_id or COMPOSITE_SCOPE,
            "baselineMonth": format_month(self.baseline_month),
            "revision": self.revision,
            "reason": self.reason,
            "withdrawnAt": self.withdrawn_at.isoformat(),
            "detachedLogs": self.detached_logs,
        }


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def _scope_clause(mode: str, field_id: str | None) -> Any:
    """(모드·분야)를 고르는 조건. **`field_id`가 NULL인 종합을 `=`로 고르지 않는다.**

    `field_id = NULL`은 어떤 행과도 참이 되지 않아 종합 행을 하나도 고르지 못한다. 그 상태는
    "종합 결과가 없다"와 생김새가 같아 배치가 매번 새 revision을 쌓아도 아무도 알아채지 못한다.
    """
    table = regime_search_snapshot_table
    if field_id is None:
        return and_(table.c.mode == mode, table.c.field_id.is_(None))
    return and_(table.c.mode == mode, table.c.field_id == field_id)


class RegimeRepository:
    """사전 계산 결과를 서비스 저장소에 넣는다. 계산은 하지 않는다."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    @classmethod
    def from_env(cls, env_path: Path | None = None) -> RegimeRepository:
        from data_pipeline.repository import database_url_from_env

        return cls(create_engine(database_url_from_env(env_path)))

    @classmethod
    def rehearsal(cls) -> RegimeRepository:
        """같은 열 정의의 인메모리 SQLite 저장소. 적재 규칙 확인·시험용이다."""
        engine = create_engine("sqlite://")
        METADATA.create_all(engine)
        return cls(engine)

    @property
    def engine(self) -> Engine:
        return self._engine

    # -- 읽기 ---------------------------------------------------------------------------

    def loaded_data_version(self) -> str | None:
        """가장 최근에 적재한 자료 버전. 아무것도 없으면 ``None``(첫 적재)이다.

        `changed_since()`에 넘길 값이다 — 008에게 "내가 마지막으로 읽은 뒤 무엇이 바뀌었나"를
        묻는 자리라 **DB에 있는 값**이어야 한다. 이번 실행의 버전을 넘기면 늘 "바뀐 것 없음"이다.
        """
        table = regime_search_snapshot_table
        with self._engine.connect() as conn:
            return conn.execute(
                select(table.c.data_version).order_by(table.c.id.desc()).limit(1)
            ).scalar_one_or_none()

    def has_model_version(self, scope: SearchScope, model_version: str) -> bool:
        """그 범위에 이 모델 버전으로 적재한 항목이 하나라도 있나.

        없으면 모델 버전이 바뀐 뒤 처음 도는 실행이다 — 그 범위는 전 구간을 다시 계산한다
        (data-model §6). 자료 버전만 보면 `changed_since()`가 준 달부터만 돌아, 옛 모델의 결과가
        과거 기준월에 남는다.
        """
        table = regime_search_snapshot_table
        with self._engine.connect() as conn:
            return (
                conn.execute(
                    select(table.c.id)
                    .where(_scope_clause(scope.mode, scope.field_id))
                    .where(table.c.model_version == model_version)
                    .limit(1)
                ).first()
                is not None
            )

    def latest_revision(self, scope: SearchScope, baseline_month: date) -> tuple[int, str] | None:
        """그 (모드·분야·기준월)의 가장 큰 `revision`과 그 `content_hash`."""
        with self._engine.connect() as conn:
            return self._latest_revision(conn, scope, baseline_month)

    def _latest_revision(
        self, conn: Connection, scope: SearchScope, baseline_month: date
    ) -> tuple[int, str] | None:
        table = regime_search_snapshot_table
        row = conn.execute(
            select(table.c.revision, table.c.content_hash)
            .where(_scope_clause(scope.mode, scope.field_id))
            .where(table.c.baseline_month == baseline_month)
            .order_by(table.c.revision.desc())
            .limit(1)
        ).one_or_none()
        return None if row is None else (int(row[0]), str(row[1]))

    def count_snapshots(self) -> int:
        with self._engine.connect() as conn:
            return int(
                conn.execute(
                    select(func.count()).select_from(regime_search_snapshot_table)
                ).scalar_one()
            )

    def count_cases(self) -> int:
        with self._engine.connect() as conn:
            return int(
                conn.execute(
                    select(func.count()).select_from(regime_search_case_table)
                ).scalar_one()
            )

    # -- 적재 ---------------------------------------------------------------------------

    def save(
        self,
        snapshot: Snapshot,
        *,
        computed_at: datetime | None = None,
        baseline_record: BaselineRecord | None = None,
    ) -> LoadResult:
        """기준월 하나를 적재한다 — snapshot·사례·기준월 기록이 **한 트랜잭션**이다.

        내용이 직전 `revision`과 같으면 **새 행을 만들지 않는다**(spec FR-030, SC-002). 같은
        결과에 버전 이름표만 다른 사본이 쌓이면 재현이 또렷해지는 게 아니라 흐려진다.

        실패하면 그 기준월은 아무것도 남기지 않고 **이전 `revision`이 그대로 조회된다**
        (spec FR-034, SC-010). 사례를 쓰다 죽어 머리만 남은 항목이 조회되는 일이 없다.
        """
        moment = computed_at or _now()
        content = snapshot.content_hash
        with self._engine.begin() as conn:
            previous = self._latest_revision(conn, snapshot.scope, snapshot.baseline_month)
            if previous is not None and previous[1] == content:
                if baseline_record is not None:
                    self._write_baseline(conn, baseline_record, moment)
                return LoadResult(
                    snapshot_key=snapshot.key, revision=previous[0], inserted=False
                )
            revision = 1 if previous is None else previous[0] + 1
            self._require_new_key(conn, snapshot)
            self._require_new_content(conn, snapshot, content)
            snapshot_id = self._insert_snapshot(conn, snapshot, revision, content, moment)
            self._insert_cases(conn, snapshot, snapshot_id, moment)
            if baseline_record is not None:
                self._write_baseline(conn, baseline_record, moment)
        return LoadResult(snapshot_key=snapshot.key, revision=revision, inserted=True)

    def _require_new_key(self, conn: Connection, snapshot: Snapshot) -> None:
        table = regime_search_snapshot_table
        existing = conn.execute(
            select(table.c.revision).where(table.c.snapshot_key == snapshot.key)
        ).scalar_one_or_none()
        if existing is not None:
            raise VersionNotBumpedError(
                f"{snapshot.key}에 revision {existing}의 내용과 다른 결과가 나왔다 "
                f"(모델 {snapshot.versions.model}, 자료 {snapshot.versions.data}, "
                f"등록표 {snapshot.versions.registry}). 버전이 같은데 결과가 바뀌었으면 계산·문장·"
                "설정을 바꾼 것이므로 모델 버전을 올린다(data-model §6)"
            )

    def _require_new_content(self, conn: Connection, snapshot: Snapshot, content: str) -> None:
        table = regime_search_snapshot_table
        existing = conn.execute(
            select(table.c.revision)
            .where(_scope_clause(snapshot.scope.mode, snapshot.scope.field_id))
            .where(table.c.baseline_month == snapshot.baseline_month)
            .where(table.c.content_hash == content)
        ).scalar_one_or_none()
        if existing is not None:
            raise ContentRevertedError(
                f"{snapshot.scope.scope_id} {format_month(snapshot.baseline_month)}의 내용이 "
                f"revision {existing}의 것과 같다. 같은 내용을 두 번 담지 않는다"
                "(data-model §2) — 어느 쪽이 최신인지는 사람이 정한다"
            )

    def _insert_snapshot(
        self,
        conn: Connection,
        snapshot: Snapshot,
        revision: int,
        content: str,
        moment: datetime,
    ) -> int:
        result = conn.execute(
            regime_search_snapshot_table.insert().values(
                snapshot_key=snapshot.key,
                mode=snapshot.scope.mode,
                field_id=snapshot.scope.field_id,
                baseline_month=snapshot.baseline_month,
                revision=revision,
                content_hash=content,
                data_version=snapshot.versions.data,
                registry_version=snapshot.versions.registry,
                model_version=snapshot.versions.model,
                status=snapshot.status.status,
                status_reason=snapshot.status.reason,
                scale=snapshot.scale,
                comparison_months=snapshot.comparison_months,
                ranked_candidates=snapshot.ranked_candidates,
                candidate_counts=snapshot.candidate_counts,
                data_asof=snapshot.data_asof,
                computed_at=moment,
                created_at=moment,
                updated_at=moment,
            )
        )
        return int(result.inserted_primary_key[0])

    def _insert_cases(
        self, conn: Connection, snapshot: Snapshot, snapshot_id: int, moment: datetime
    ) -> None:
        if not snapshot.cases:
            return
        conn.execute(
            regime_search_case_table.insert(),
            [
                {
                    "snapshot_id": snapshot_id,
                    "rank": case.rank,
                    "end_month": case.end_month,
                    "distance": case.distance,
                    "indicator_values": case.indicator_values,
                    "explanation": case.explanation,
                    "era_position": case.era_position,
                    "created_at": moment,
                    "updated_at": moment,
                }
                for case in snapshot.cases
            ],
        )

    # -- 기준월 기록 --------------------------------------------------------------------

    def _write_baseline(
        self, conn: Connection, record: BaselineRecord, moment: datetime
    ) -> None:
        """분야별 T_g·종합 T_all과 사전 계산이 있는 가장 오래된 기준월을 남긴다 (data-model §3).

        **적재와 같은 트랜잭션이다.** 따로 쓰면 배치가 중간에 죽었을 때 "화면이 고를 수 있다고
        말하는 달"과 "결과가 실제로 있는 달"이 어긋나고, 그 어긋남은 404로만 드러난다(포트 계약
        §0.2).
        """
        require_month(record.baseline_month, "기준월 기록의 기준월")
        earliest = self._earliest_baseline_month(conn, record.scope_id)
        if earliest is None:
            # 그 범위에 적재된 snapshot이 하나도 없다. 고를 수 있다고 말할 달이 없으므로 쓰지
            # 않는다 — 비어 있는 것이 "결과가 있다"보다 정확하다.
            return
        table = regime_baseline_month_table
        values = {
            "baseline_month": record.baseline_month,
            "earliest_month": earliest,
            "registry_version": record.registry_version,
            "data_version": record.data_version,
            "updated_at": moment,
        }
        updated = conn.execute(
            update(table).where(table.c.scope_id == record.scope_id).values(**values)
        )
        if updated.rowcount == 0:
            conn.execute(table.insert().values(scope_id=record.scope_id, **values))

    def _earliest_baseline_month(self, conn: Connection, scope_id: str) -> date | None:
        table = regime_search_snapshot_table
        clause = (
            table.c.field_id.is_(None)
            if scope_id == COMPOSITE_SCOPE
            else table.c.field_id == scope_id
        )
        value = conn.execute(
            select(func.min(table.c.baseline_month)).where(clause)
        ).scalar_one_or_none()
        if value is None:
            return None
        return value if isinstance(value, date) else date.fromisoformat(str(value))

    def baseline_months(self) -> dict[str, dict[str, Any]]:
        """기록된 기준월 전부 — 확인·시험용 읽기다."""
        table = regime_baseline_month_table
        with self._engine.connect() as conn:
            return {
                row.scope_id: {
                    "baselineMonth": row.baseline_month,
                    "earliestMonth": row.earliest_month,
                    "registryVersion": row.registry_version,
                    "dataVersion": row.data_version,
                }
                for row in conn.execute(select(table)).all()
            }

    # -- 보관 정리 ----------------------------------------------------------------------

    def prune(self) -> int:
        """옛 `revision`을 정리한다 (spec FR-029, data-model §7).

        남기는 것은 둘뿐이다 — 각 (모드·분야·기준월)의 **가장 큰 revision**과 `regime_search_log`가
        가리키는 revision. **적재를 마친 뒤 같은 실행에서** 돌린다: 사람이 따로 돌리는 잡을 두면
        그 잡이 돌지 않은 기간만큼 무기한 보관이 된다(constitution "기록·개인정보·보관").

        철회(FR-035)와 다르다 — 정리는 보존 대상을 건드리지 않고, 철회는 기록이 가리키는 항목까지
        사람이 지정해 지운다.
        """
        table = regime_search_snapshot_table
        with self._engine.begin() as conn:
            # 최신 판정을 파이썬에서 한다. `field_id`가 NULL인 종합 행은 SQL의 같음 비교로
            # 묶이지 않아(NULL은 서로 구별된다) 방언마다 다르게 도는데, 그 차이가 "종합만
            # 정리되지 않는다"로 나타나면 알아채기 어렵다. 행 수는 기준월 × 모드라 작다.
            rows = conn.execute(
                select(
                    table.c.id,
                    table.c.mode,
                    table.c.field_id,
                    table.c.baseline_month,
                    table.c.revision,
                )
            ).all()
            best: dict[tuple[str, str, Any], tuple[int, int]] = {}
            for row in rows:
                key = (row.mode, row.field_id or COMPOSITE_SCOPE, row.baseline_month)
                current = best.get(key)
                if current is None or row.revision > current[0]:
                    best[key] = (int(row.revision), int(row.id))
            keep = {snapshot_id for _, snapshot_id in best.values()}
            keep.update(
                row.snapshot_id
                for row in conn.execute(
                    select(regime_search_log_table.c.snapshot_id).where(
                        regime_search_log_table.c.snapshot_id.is_not(None)
                    )
                ).all()
            )
            victims = [int(row.id) for row in rows if int(row.id) not in keep]
            if not victims:
                return 0
            conn.execute(
                delete(regime_search_case_table).where(
                    regime_search_case_table.c.snapshot_id.in_(victims)
                )
            )
            conn.execute(delete(table).where(table.c.id.in_(victims)))
            return len(victims)

    # -- 철회 ---------------------------------------------------------------------------

    def withdraw(
        self,
        *,
        scope: SearchScope,
        baseline_month: date,
        reason: str,
        revision: int | None = None,
        at: datetime | None = None,
    ) -> Withdrawal:
        """사람이 지정한 `revision`을 지운다 (spec FR-035).

        **배치가 스스로 판단해 부르지 않는다.** 계산 오류가 확인된 경우에만 쓰며, 자료 개정처럼
        값이 정상적으로 달라지는 경우는 FR-030대로 새 `revision`이다.

        지운 뒤에는 이전 `revision`이 다시 최신이 된다. 그 항목을 가리키던 기록은 **행이 남고
        `snapshot_id`만 NULL이 되며**, `snapshot_key` 사본으로 무엇을 봤는지는 드러난다.
        """
        require_month(baseline_month, "철회 대상 기준월")
        if not reason.strip():
            raise RegimeLoadError("철회 사유가 비어 있다. 무엇을 왜 지웠는지가 기록의 전부다")
        moment = at or _now()
        table = regime_search_snapshot_table
        with self._engine.begin() as conn:
            query = (
                select(table.c.id, table.c.revision, table.c.snapshot_key)
                .where(_scope_clause(scope.mode, scope.field_id))
                .where(table.c.baseline_month == baseline_month)
            )
            if revision is not None:
                query = query.where(table.c.revision == revision)
            row = conn.execute(
                query.order_by(table.c.revision.desc()).limit(1)
            ).one_or_none()
            if row is None:
                raise RegimeLoadError(
                    f"철회할 항목이 없다: {scope.scope_id} "
                    f"{format_month(baseline_month)} revision={revision}"
                )
            detached = conn.execute(
                update(regime_search_log_table)
                .where(regime_search_log_table.c.snapshot_id == row.id)
                .values(snapshot_id=None)
            ).rowcount
            conn.execute(
                delete(regime_search_case_table).where(
                    regime_search_case_table.c.snapshot_id == row.id
                )
            )
            conn.execute(delete(table).where(table.c.id == row.id))
        return Withdrawal(
            snapshot_key=str(row.snapshot_key),
            mode=scope.mode,
            field_id=scope.field_id,
            baseline_month=baseline_month,
            revision=int(row.revision),
            reason=reason.strip(),
            withdrawn_at=moment,
            detached_logs=int(detached or 0),
        )


def baseline_record_for(
    scope: SearchScope, baseline_month: date, versions: Versions
) -> BaselineRecord:
    """그 범위의 기준월 기록 (data-model §3)."""
    return BaselineRecord(
        scope_id=scope.scope_id,
        baseline_month=baseline_month,
        registry_version=versions.registry,
        data_version=versions.data,
    )


def snapshot_frame_rows(
    snapshots: Iterable[Snapshot], results: Mapping[str, LoadResult] | None = None
) -> list[dict[str, Any]]:
    """중간 산출 Parquet에 쓸 행 (T073).

    적재된 것과 같은 값을 다시 적지 않는다 — **어느 기준월을 어떤 상태로 계산했는지**만 남겨
    배치가 무엇을 했는지 되짚을 수 있게 한다. 사례 본문은 PostgreSQL이 원본이다.
    """
    rows: list[dict[str, Any]] = []
    for snapshot in snapshots:
        result = None if results is None else results.get(snapshot.key)
        rows.append(
            {
                "snapshot_key": snapshot.key,
                "mode": snapshot.scope.mode,
                "scope_id": snapshot.scope.scope_id,
                "baseline_month": snapshot.baseline_month,
                "status": snapshot.status.status,
                "status_reason": snapshot.status.reason,
                "content_hash": snapshot.content_hash,
                "data_version": snapshot.versions.data,
                "registry_version": snapshot.versions.registry,
                "model_version": snapshot.versions.model,
                "cases": len(snapshot.cases),
                "ranked_candidates": len(snapshot.ranked_candidates),
                "revision": None if result is None else result.revision,
                "inserted": None if result is None else result.inserted,
            }
        )
    return rows


def failure_rows(failures: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    """실패한 범위와 사유 (spec FR-034). 비어 있어도 형태를 유지한다."""
    return [dict(failure) for failure in failures]
