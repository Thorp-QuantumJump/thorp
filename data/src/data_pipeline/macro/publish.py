"""발행 게이트와 기준월 — 17개가 다 되기 전에는 공개하지 않는다 (FR-011·FR-013·FR-016a).

발행은 두 관문을 모두 지나야 한다. 17개 각각에 **유효한 월별 변환값이 1건 이상** 있어야 하고,
17개 전부의 이용 권리가 미확인이 아니어야 한다. 하나라도 어긋나면 현재 포인터를 움직이지 않고
직전 완결 버전을 그대로 둔다 — 반쯤 채운 버전을 내보내면 소비자는 그것이 완결본인지 알 길이
없다. 009·010 은 직전 완결 버전이 있으면 계속 읽고, 없으면 등록 정보와 자료 없음 상태만 받는다.

**원값만 있는 달은 유효 건수에 넣지 않는다.** 원천이 값을 줬어도 변환을 거치지 못했으면
(분모가 0이었거나, 거래일이 덜 모였거나, 계열이 시작되기 전이거나) 그 달은 화면에 내보낼 수
없다. 원값 유무로 세면 17개가 다 찼다고 판정되면서 실제로는 빈 화면이 나간다(SC-008).

**권리 확인 대기는 발행을 막지 않는다.** 조건 자체를 모르는 미확인만 막는다. 두바이유는 공식
원천이 조건을 공개했는데 그 계열이 어느 쪽인지 물을 창구가 없는 상태라 확인 대기로 두고 쓴다
(pd11 §1, FR-016a). 둘을 한 상태로 묶으면 두바이유 하나 때문에 나머지 16개까지 멈춘다.

**기준월은 교집합의 최대월이다**(FR-013). 분야 g 의 `T_g` 는 그 분야 지표들이 **함께** 값을 가진
달 중 가장 최근이고, `T_all` 은 17개가 함께 가진 달 중 가장 최근이다. 교집합이 비면 자료 없음
(``None``)이다 — 가장 가까운 달로 대신하지 않는다. 지표마다 최신월이 다를 때 "대충 최근"을 골라
주면, 어느 지표는 실제로 그 달 값이 없는데 비교에 들어간다.

**기준월을 물린 지표를 함께 남긴다.** `T_g` 가 뒤로 밀렸을 때 어느 지표 때문인지 밝히지 않으면,
화면은 "6월까지만 볼 수 있다"만 말하고 왜 그런지는 아무도 답하지 못한다(FR-023).

**보류된 판정에는 기준월을 달지 않는다.** 보류인데 기준월이 채워져 있으면 그 값이 공개된 것처럼
읽힌다. 보류 사유는 :attr:`PublicationDecision.blockers` 와 유효 건수가 답한다.

발행 판정과 기준월은 순수 함수로 유지한다. 파일 봉인과 DB 포인터 교체는 아래의 별도
함수에서 수행하며, 이 경계가 실패해도 판정 자체는 변하지 않는다.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Final

from sqlalchemy import Connection, Engine, text

from data_pipeline.macro.normalize import NormalizedObservation
from data_pipeline.macro.registry import (
    EXPECTED_INDICATOR_COUNT,
    FIELD_IDS,
    LICENSE_SUSPENDED,
    REVIEW_PENDING_RIGHTS,
    REVIEW_VERIFIED,
    IndicatorDefinition,
    RegistryError,
    collectible,
    nominal_weights,
)
from data_pipeline.macro.registry import (
    validate as validate_registry,
)
from data_pipeline.macro.versioning import value_rule_fingerprint

#: 전체 지표를 묶는 기준월의 범위 식별자. 7개 분야 id 와 같은 자리에 들어간다(data-model §2).
SCOPE_ALL: Final[str] = "ALL"

#: 발행 보류 사유. 화면 문구가 아니라 판정 결과다.
BLOCK_NO_VALID_VALUE: Final[str] = "NO_VALID_VALUE"
"""유효한 월별 변환값이 0건이다. 원값만 있는 달은 세지 않는다 (FR-011, SC-008)."""

BLOCK_LICENSE_UNVERIFIED: Final[str] = "LICENSE_UNVERIFIED"
"""이용 조건이 미확인이거나 권리 상태가 알려진 허용값이 아니다 (FR-016a)."""

PUBLISHABLE_LICENSE_REVIEW_STATUSES: Final[frozenset[str]] = frozenset(
    {REVIEW_VERIFIED, REVIEW_PENDING_RIGHTS}
)
"""확인 완료와 명시된 권리 확인 대기만 발행할 수 있다."""

BLOCK_ALL_SUSPENDED: Final[str] = "ALL_SUSPENDED"
"""수집 가능한 지표가 하나도 남지 않았다 (FR-016b).

중단된 지표를 완결 기준에서 빼는 것은 나머지로 화면을 이어 가기 위해서다. 나머지가 없으면
뺄 것도 없다 — 이때 그냥 통과시키면 지표가 0개인 자료 버전이 발행되고, 폴인터가 그쪽으로
옮겨 가면서 멀쩡하던 직전 완결 버전이 화면에서 사라진다."""

BLOCK_NOT_COLLECTED: Final[str] = "NOT_COLLECTED"
"""등록표에 있는데 관측이 아예 오지 않았다. 유효 건수 0 과 구분한다 — 전자는 수집이 돌지
않았다는 뜻이고 후자는 받았으나 변환하지 못했다는 뜻이라 대응이 다르다."""


class PublishError(ValueError):
    """발행 판정을 내릴 수 없는 입력이다."""


@dataclass(frozen=True)
class PublicationBlocker:
    """발행을 막은 지표 하나와 그 사유."""

    indicator_id: str
    reason: str


@dataclass(frozen=True)
class Baseline:
    """범위 하나의 기준월. `macro_baseline` 한 행이 된다 (data-model §2)."""

    #: 7개 분야 id 또는 :data:`SCOPE_ALL`.
    scope_id: str
    #: 교집합의 최대월. 교집합이 비면 ``None`` 이고 그것은 자료 없음이다.
    reference_month: str | None
    #: 후보 월에 유효 변환값이 없어 기준월을 물린 지표. 후보 월이 곧 기준월이면 비어 있다.
    held_back_by: tuple[str, ...]

    @property
    def has_data(self) -> bool:
        return self.reference_month is not None


@dataclass(frozen=True)
class PublicationDecision:
    """발행해도 되는가, 된다면 기준월은 언제인가."""

    blockers: tuple[PublicationBlocker, ...]
    #: 발행 가능할 때만 채워진다. 보류된 판정에는 기준월을 달지 않는다 — 모듈 docstring 참고.
    baselines: tuple[Baseline, ...]
    #: 지표별 유효 변환값 건수. 보류 사유를 사람이 읽을 때의 근거다.
    valid_month_counts: Mapping[str, int]
    #: 이용이 중단되어 이 버전에 넣지 않은 지표와 그 사유 (FR-016b, 원칙 VI).
    #:
    #: 빈 자리로 두지 않고 함께 내보내는 이유는, 조용히 사라지면 화면이 "원래 없던 지표"와
    #: "빠진 지표"를 같게 보여 주기 때문이다. 이미 발행한 버전에서 지우지는 않는다.
    suspended: tuple[tuple[str, str], ...] = ()

    @property
    def can_publish(self) -> bool:
        return not self.blockers

    def baseline_for(self, scope_id: str) -> Baseline | None:
        for baseline in self.baselines:
            if baseline.scope_id == scope_id:
                return baseline
        return None


def valid_months(observations: Iterable[NormalizedObservation]) -> frozenset[str]:
    """변환값이 실제로 있는 대상월만 모은다.

    결측 사유가 붙은 달은 뺀다 — 원값이 있든 없든 화면에 내보낼 값이 없기 때문이다.
    """
    return frozenset(o.period for o in observations if not o.is_missing)


def _valid_by_indicator(
    indicators: Sequence[IndicatorDefinition],
    observations: Mapping[str, Sequence[NormalizedObservation]],
) -> dict[str, frozenset[str]]:
    return {
        indicator.indicator_id: valid_months(observations.get(indicator.indicator_id, ()))
        for indicator in indicators
    }


def gate(
    indicators: Sequence[IndicatorDefinition],
    observations: Mapping[str, Sequence[NormalizedObservation]],
) -> tuple[PublicationBlocker, ...]:
    """발행을 막는 것이 있는지 지표마다 본다. 비어 있으면 발행해도 된다.

    **첫 번째에서 멈추지 않고 전부 모은다.** 하나만 알려 주면 고치고 다시 돌렸을 때 또 다른
    하나가 나오고, 몇 번을 돌려야 끝나는지 알 수 없다.
    """
    if len(indicators) != EXPECTED_INDICATOR_COUNT:
        raise PublishError(
            f"발행 판정은 등록표의 {EXPECTED_INDICATOR_COUNT}개 전부를 받아야 한다:"
            f" {len(indicators)}개가 왔다"
        )
    try:
        validate_registry(tuple(indicators))
    except RegistryError as error:
        raise PublishError(f"발행 등록표가 유효하지 않다: {error}") from error

    # **중단된 지표는 완결 기준에서 뺀다**(FR-016b). FR-011 의 "17개 전부"를 글자 그대로 읽으면
    # 지표 하나가 중단되는 순간 영영 발행할 수 없고, 그러면 중단이 플랫폼을 통째로 멈춘다.
    # spec 이 두바이유를 두고 "빠져도 나머지 6개 분야 검색은 유지된다"고 적은 것이 이 뜻이다.
    # 빠진 지표는 조용히 사라지지 않고 `suspended` 로 함께 나간다(원칙 VI).
    blockers: list[PublicationBlocker] = []
    live = collectible(indicators)
    if not live:
        return (PublicationBlocker(SCOPE_ALL, BLOCK_ALL_SUSPENDED),)
    for indicator in live:
        if indicator.license_review_status not in PUBLISHABLE_LICENSE_REVIEW_STATUSES:
            blockers.append(PublicationBlocker(indicator.indicator_id, BLOCK_LICENSE_UNVERIFIED))
        received = observations.get(indicator.indicator_id)
        if received is None:
            blockers.append(PublicationBlocker(indicator.indicator_id, BLOCK_NOT_COLLECTED))
            continue
        if not valid_months(received):
            blockers.append(PublicationBlocker(indicator.indicator_id, BLOCK_NO_VALID_VALUE))
    return tuple(blockers)


def _baseline_for_scope(
    scope_id: str, members: Sequence[str], valid: Mapping[str, frozenset[str]]
) -> Baseline:
    """범위 하나의 기준월과 물린 지표를 낸다.

    후보 월은 **지표별 최신 유효월 중 최대**다. 기준월(교집합의 최대)이 후보 월과 같으면
    아무도 물리지 않았다는 뜻이고, 다르거나 기준월이 없으면 후보 월에 값이 없는 지표들이
    물린 것이다(data-model §3.3).
    """
    if not members:
        # 그 분야의 지표가 전부 중단된 경우다. 오류가 아니라 자료 없음이다 — 분야가 사라진
        # 것이 아니라 지금 볼 값이 없는 것이고, 화면은 그 사실을 보여야 한다(FR-016b).
        return Baseline(scope_id=scope_id, reference_month=None, held_back_by=())

    month_sets = [valid[indicator_id] for indicator_id in members]
    common = frozenset.intersection(*month_sets) if all(month_sets) else frozenset()
    reference_month = max(common) if common else None

    latest = [max(months) for months in month_sets if months]
    candidate = max(latest) if latest else None
    if candidate is None or candidate == reference_month:
        held_back: tuple[str, ...] = ()
    else:
        held_back = tuple(
            indicator_id for indicator_id in members if candidate not in valid[indicator_id]
        )
    return Baseline(scope_id=scope_id, reference_month=reference_month, held_back_by=held_back)


def baselines(
    indicators: Sequence[IndicatorDefinition],
    observations: Mapping[str, Sequence[NormalizedObservation]],
) -> tuple[Baseline, ...]:
    """분야별 `T_g` 일곱과 전체 `T_all` 하나를 낸다 (FR-013).

    순서는 :data:`~data_pipeline.macro.registry.FIELD_IDS` 를 따르고 마지막이 전체다.
    """
    # 중단된 지표는 새 자료 버전에 들어가지 않으므로 기준월에도 세지 않는다(FR-016b).
    # 세면 이미 빠진 지표가 기준월을 뒤로 물려 멀쩡한 분야까지 과거로 끌어내린다.
    live = collectible(indicators)
    valid = _valid_by_indicator(live, observations)

    members_by_field: dict[str, list[str]] = {field_id: [] for field_id in FIELD_IDS}
    for indicator in live:
        if indicator.field_id not in members_by_field:
            raise PublishError(
                f"{indicator.indicator_id}의 분야가 7개 밖이다: {indicator.field_id}"
            )
        members_by_field[indicator.field_id].append(indicator.indicator_id)

    result = [
        _baseline_for_scope(field_id, members_by_field[field_id], valid) for field_id in FIELD_IDS
    ]
    result.append(_baseline_for_scope(SCOPE_ALL, [i.indicator_id for i in live], valid))
    return tuple(result)


def plan_publication(
    indicators: Sequence[IndicatorDefinition],
    observations: Mapping[str, Sequence[NormalizedObservation]],
) -> PublicationDecision:
    """발행 판정과 기준월을 한 번에 낸다. 보류면 기준월을 달지 않는다.

    아래의 원자적 발행이 이 결과를 받아 파일을 봉인하고 한 트랜잭션에 쓴다. 판정과 기록을
    나눈 이유는, 판정만 따로 시험할 수 있어야 하고 기록이 실패해도 판정이 달라지지 않기
    때문이다.
    """
    blockers = gate(indicators, observations)
    counts = {
        indicator.indicator_id: len(valid_months(observations.get(indicator.indicator_id, ())))
        for indicator in indicators
    }
    return PublicationDecision(
        blockers=blockers,
        baselines=() if blockers else baselines(indicators, observations),
        valid_month_counts=counts,
        suspended=tuple(
            (i.indicator_id, i.license_suspended_reason or "")
            for i in indicators
            if i.license_status == LICENSE_SUSPENDED
        ),
    )


@dataclass(frozen=True)
class PublicationPointer:
    """발행 시작 때 고정한 세 버전. 비교 후 교체가 동시 발행을 직렬화한다."""

    dataset_id: int | None
    registry_id: int
    display_id: int


@dataclass(frozen=True)
class SealedManifest:
    path: Path
    sha256: str


@dataclass(frozen=True)
class AtomicPublicationRequest:
    version_key: str
    attempt_key: str
    pointer: PublicationPointer
    indicators: tuple[IndicatorDefinition, ...]
    observations: Mapping[str, Sequence[NormalizedObservation]]
    raw_files: tuple[Path, ...]
    processed_files: tuple[Path, ...]
    data_root: Path
    is_sample_data: bool = False
    target_registry_id: int | None = None
    target_display_id: int | None = None
    weights: Mapping[str, Decimal] | None = None


class ConcurrentPublication(PublishError):
    """시작할 때 본 포인터가 다른 발행으로 이미 바뀌었다."""


_SAFE_VERSION_KEY: Final[re.Pattern[str]] = re.compile(r"^[A-Za-z0-9_-]{1,40}$")


def seal_manifest(
    data_root: Path, version_key: str, kind: str, files: Sequence[Path]
) -> SealedManifest:
    """완성된 파일 목록·크기·해시를 결정적 JSON으로 봉인한다.

    같은 버전 키의 재시도는 정확히 같은 매니페스트일 때만 허용한다. 실패한 DB 발행의
    미참조 매니페스트는 남아 재시도/정리 대상으로 보이되 current 포인터로 노출되지 않는다.
    """
    if not _SAFE_VERSION_KEY.fullmatch(version_key) or kind not in {"raw", "processed"}:
        raise PublishError("매니페스트 식별자가 유효하지 않다")
    root = data_root.resolve(strict=True)
    if not files:
        raise PublishError(f"{kind} 매니페스트에 파일이 없다")
    entries: list[dict[str, object]] = []
    for file in files:
        resolved = file.resolve(strict=True)
        if not resolved.is_relative_to(root) or not resolved.is_file():
            raise PublishError(f"자료 루트 밖이거나 파일이 아니다: {file}")
        relative = resolved.relative_to(root)
        if relative.parts[:2] != (kind, "macro"):
            raise PublishError(f"{kind} 자료 경로가 아니다: {file}")
        sealed = resolved
        if kind == "processed":
            # storage.processed_path()의 part.parquet는 다음 수집에서 교체될 수 있다.
            # 공개 버전은 그 가변 파일이 아니라 버전별 불변 사본을 참조해야 한다.
            sealed = (
                root / "processed" / "macro" / "versions" / version_key
                / Path(*relative.parts[2:])
            )
            sealed.parent.mkdir(parents=True, exist_ok=True)
            if not sealed.parent.resolve().is_relative_to(root):
                raise PublishError("봉인 대상이 자료 루트 밖이다")
            try:
                with resolved.open("rb") as source, sealed.open("xb") as target:
                    for chunk in iter(lambda: source.read(1024 * 1024), b""):
                        target.write(chunk)
                    target.flush()
                    os.fsync(target.fileno())
            except FileExistsError:
                pass
        digest = hashlib.sha256()
        with sealed.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if kind == "processed":
            source_digest = hashlib.sha256()
            with resolved.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    source_digest.update(chunk)
            source_hash = source_digest.hexdigest()
            if source_hash != digest.hexdigest():
                raise PublishError(f"봉인된 processed 파일과 입력이 다르다: {file}")
        entries.append(
            {"path": sealed.relative_to(root).as_posix(), "bytes": sealed.stat().st_size,
             "sha256": digest.hexdigest()}
        )
    if len({entry["path"] for entry in entries}) != len(entries):
        raise PublishError("매니페스트에 중복 파일이 있다")
    payload = json.dumps(sorted(entries, key=lambda e: str(e["path"])), sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    manifest = root / "processed" / "macro" / "manifests" / f"{version_key}.{kind}.json"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    if not manifest.parent.resolve().is_relative_to(root):
        raise PublishError("매니페스트 대상이 자료 루트 밖이다")
    try:
        with manifest.open("xb") as target:
            target.write(payload)
            target.flush()
            os.fsync(target.fileno())
    except FileExistsError:
        if manifest.read_bytes() != payload:
            raise PublishError(f"이미 봉인된 매니페스트와 내용이 다르다: {manifest}") from None
    return SealedManifest(manifest, hashlib.sha256(payload).hexdigest())


def _month_date(period: str) -> date:
    try:
        month = date.fromisoformat(f"{period}-01")
    except ValueError as error:
        raise PublishError(f"대상월이 YYYY-MM 형식이 아니다: {period}") from error
    if period != month.strftime("%Y-%m"):
        raise PublishError(f"대상월이 YYYY-MM 형식이 아니다: {period}")
    return month


def _assert_full_history(
    indicators: Sequence[IndicatorDefinition],
    observations: Mapping[str, Sequence[NormalizedObservation]],
    carried: Mapping[str, set[date]] | None = None,
) -> None:
    """시작월부터 최신월까지 결측을 포함한 모든 월이 한 번씩 있어야 한다.

    ``carried`` 는 앞 자료 버전에서 그대로 이어받을 달이다. **정기 수집은 최근 N개월만 받으므로**
    이번에 받은 것만 세면 전 구간이 늘 비어 보이고 발행이 영영 막힌다(FR-017). 공개될 자료는 둘의
    합집합이므로 판정도 합집합으로 한다.
    """
    expected_ids = {indicator.indicator_id for indicator in indicators}
    if set(observations) != expected_ids:
        raise PublishError("전체 이력의 지표 ID가 등록표와 다르다")
    for indicator in indicators:
        months = observations[indicator.indicator_id]
        indices: set[int] = set()
        start = _month_date(indicator.series_start)
        start_index = start.year * 12 + start.month
        for observation in months:
            if observation.indicator_id != indicator.indicator_id:
                raise PublishError("관측값의 지표 ID가 묶음과 다르다")
            month = _month_date(observation.period)
            index = month.year * 12 + month.month
            if index in indices:
                raise PublishError(f"{indicator.indicator_id}에 중복 대상월이 있다")
            indices.add(index)
        for month in (carried or {}).get(indicator.indicator_id, ()):
            indices.add(month.year * 12 + month.month)
        if (
            not indices
            or min(indices) != start_index
            or len(indices) != max(indices) - start_index + 1
        ):
            raise PublishError(f"{indicator.indicator_id}의 전체 가용 구간에 빠진 월이 있다")


def _reusable_previous(
    connection: Connection, dataset_id: int | None, fingerprint: str
) -> int | None:
    """앞 자료 버전에서 달을 이어받아도 되는가 (FR-003·FR-017).

    **값 규칙이 같을 때만 이어받는다.** 규칙이 바뀌면 전 구간을 다시 만들어야 하므로 옛 규칙으로
    낸 값이 새 버전에 섞이면 안 된다 — 한 버전 안에서 달마다 다른 규칙으로 계산된 값이 나란히
    놓이고, 그 사실은 값만 봐서는 드러나지 않는다.
    """
    if dataset_id is None:
        return None
    previous = connection.execute(
        text(
            "SELECT value_rule_fingerprint FROM macro_dataset_version"
            " WHERE id = :id AND status = 'PUBLISHED'"
        ),
        {"id": dataset_id},
    ).scalar_one_or_none()
    return dataset_id if previous == fingerprint else None


def _months_of(connection: Connection, dataset_id: int | None) -> dict[str, set[date]]:
    """앞 자료 버전이 담고 있는 지표별 대상월."""
    if dataset_id is None:
        return {}
    rows = connection.execute(
        text(
            "SELECT indicator_id, target_month FROM macro_observation"
            " WHERE dataset_version_id = :id"
        ),
        {"id": dataset_id},
    ).all()
    months: dict[str, set[date]] = {}
    for indicator_id, month in rows:
        # SQLite 는 날짜를 문자열로 돌려준다. 두 저장소에서 같은 판정이 나오게 맞춘다.
        parsed = date.fromisoformat(month) if isinstance(month, str) else month
        months.setdefault(indicator_id, set()).add(parsed)
    return months


def _carry_forward(connection: Connection, previous: int | None, dataset_id: int) -> int:
    """앞 버전의 관측 중 이번에 받지 않은 달을 새 버전으로 그대로 옮긴다 (FR-017).

    **값을 다시 만들지 않고 행을 복사한다.** 정기 수집이 받지 않은 달은 우리가 계산할 입력이 없고,
    앞 버전에 있는 값이 그 달에 대해 아는 전부다. 다시 계산하는 척하면 그 달의 수신 시각·발표일이
    이번 실행의 것으로 바뀌어, 언제 받은 자료인지가 조용히 틀어진다.
    """
    if previous is None:
        return 0
    result = connection.execute(
        text(
            "INSERT INTO macro_observation"
            " (dataset_version_id, indicator_id, target_month, raw_value, transformed_value,"
            "  released_at, received_at, break_segment, missing_reason)"
            " SELECT :new, o.indicator_id, o.target_month, o.raw_value, o.transformed_value,"
            "        o.released_at, o.received_at, o.break_segment, o.missing_reason"
            " FROM macro_observation o"
            " WHERE o.dataset_version_id = :old"
            "   AND NOT EXISTS ("
            "     SELECT 1 FROM macro_observation n"
            "     WHERE n.dataset_version_id = :new"
            "       AND n.indicator_id = o.indicator_id"
            "       AND n.target_month = o.target_month)"
        ),
        {"new": dataset_id, "old": previous},
    )
    return result.rowcount or 0


def _assert_registered_calculation(
    connection: Connection,
    registry_id: int,
    indicators: Sequence[IndicatorDefinition],
    weights: Mapping[str, Decimal] | None,
) -> None:
    """기준월을 계산할 입력이 저장된 등록표 정의와 정확히 같은지 확인한다."""
    expected = {indicator.indicator_id: indicator.field_id for indicator in indicators}
    expected_weights = dict(weights) if weights is not None else nominal_weights(indicators)
    if set(expected_weights) != set(expected):
        raise PublishError("명목 비중의 지표 ID가 등록표와 다르다")
    rows = connection.execute(
        text("SELECT indicator_id, field_id, nominal_weight FROM macro_indicator_definition "
             "WHERE registry_version_id=:id"),
        {"id": registry_id},
    ).mappings().all()
    if {row["indicator_id"] for row in rows} != set(expected) or len(rows) != len(expected):
        raise PublishError("저장된 계산 등록표의 지표 구성이 입력과 다르다")
    for row in rows:
        raw_weight = row["nominal_weight"]
        stored_weight = Decimal(
            str(json.loads(raw_weight, parse_float=Decimal)
                if isinstance(raw_weight, str) else raw_weight)
        )
        indicator_id = row["indicator_id"]
        if (
            row["field_id"] != expected[indicator_id]
            or stored_weight != expected_weights[indicator_id]
        ):
            raise PublishError("저장된 계산 등록표의 분야·가중이 입력과 다르다")


def publish_atomically(
    engine: Engine,
    request: AtomicPublicationRequest,
    *,
    before_pointer_swap: Callable[[], None] | None = None,
) -> int:
    """매니페스트를 먼저 봉인한 뒤 스냅샷·기준월·포인터를 한 DB 트랜잭션에 쓴다.

    등록표/표시 버전과 singleton 포인터는 선행 등록 단계에서 존재해야 한다. 함수가
    받은 observations는 전체 가용 구간이며 최근 N개월 증분만 넘겨서는 안 된다.
    """
    if not _SAFE_VERSION_KEY.fullmatch(request.version_key):
        raise PublishError("자료 버전 키가 유효하지 않다")
    decision = plan_publication(request.indicators, request.observations)
    now = datetime.now(UTC)
    with engine.begin() as connection:
        connection.execute(
            text("INSERT INTO macro_collection_attempt "
                 "(attempt_key, mode, started_at, status) "
                 "VALUES (:key, :mode, :started, 'RUNNING')"),
            {"key": request.attempt_key,
             "mode": "INITIAL" if request.pointer.dataset_id is None else "REGULAR",
             "started": now},
        )
    if not decision.can_publish:
        with engine.begin() as connection:
            connection.execute(
                text("UPDATE macro_collection_attempt SET status='PARTIAL', finished_at=:now "
                     "WHERE attempt_key=:key"), {"now": now, "key": request.attempt_key}
            )
        raise PublishError(f"발행 보류: {decision.blockers}")

    fingerprint = value_rule_fingerprint(request.indicators)
    try:
        # 앞 버전이 담고 있는 달을 먼저 읽는다. 정기 수집이 받지 않은 달은 그대로 이어받으므로
        # 전 구간 판정도 둘의 합집합으로 해야 한다(FR-017).
        with engine.begin() as connection:
            reusable = _reusable_previous(connection, request.pointer.dataset_id, fingerprint)
            carried = _months_of(connection, reusable)
        _assert_full_history(request.indicators, request.observations, carried)
        raw_manifest = seal_manifest(
            request.data_root, request.version_key, "raw", request.raw_files
        )
        processed_manifest = seal_manifest(
            request.data_root, request.version_key, "processed", request.processed_files
        )
        target_registry = request.target_registry_id or request.pointer.registry_id
        target_display = request.target_display_id or request.pointer.display_id
        json_cast = "CAST(:value AS JSONB)" if engine.dialect.name == "postgresql" else ":value"
        with engine.begin() as connection:
            registry = connection.execute(
                text("SELECT value_rule_fingerprint FROM macro_calculation_registry_version "
                     "WHERE id=:id AND status='PUBLISHED'"),
                {"id": target_registry},
            ).scalar_one_or_none()
            if registry != fingerprint:
                raise PublishError("계산 등록표와 자료 값 규칙 지문이 다르다")
            _assert_registered_calculation(
                connection, target_registry, request.indicators, request.weights
            )
            if request.pointer.dataset_id is not None:
                previous_fingerprint = connection.execute(
                    text("SELECT value_rule_fingerprint FROM macro_dataset_version "
                         "WHERE id=:id AND status='PUBLISHED'"),
                    {"id": request.pointer.dataset_id},
                ).scalar_one_or_none()
                if previous_fingerprint is None:
                    raise PublishError("기존 공개 자료 버전이 없다")
            if connection.execute(
                text("SELECT id FROM macro_display_version WHERE id=:id"),
                {"id": target_display},
            ).scalar_one_or_none() is None:
                raise PublishError("표시 버전이 없다")
            dataset_id = connection.execute(
                text("INSERT INTO macro_dataset_version "
                     "(version_key, processing_registry_version_id, value_rule_fingerprint, "
                     "status, complete_indicator_count, is_sample_data, raw_manifest_hash, "
                     "processed_manifest_hash, published_at, created_at) VALUES "
                     "(:key, :registry, :fingerprint, 'PUBLISHED', 17, :sample, :raw_hash, "
                     ":processed_hash, :now, :now) RETURNING id"),
                {"key": request.version_key, "registry": target_registry,
                 "fingerprint": fingerprint, "sample": request.is_sample_data,
                 "raw_hash": raw_manifest.sha256,
                 "processed_hash": processed_manifest.sha256, "now": now},
            ).scalar_one()
            for indicator in request.indicators:
                for observation in request.observations[indicator.indicator_id]:
                    if observation.indicator_id != indicator.indicator_id:
                        raise PublishError("관측값의 지표 ID가 묶음과 다르다")
                    raw_value = json.dumps(
                        [[period, None if value is None else str(value)]
                         for period, value in observation.raw_values]
                    )
                    transformed = (
                        None if observation.transformed_value is None
                        else json.dumps(str(observation.transformed_value))
                    )
                    raw_expression = json_cast.replace(":value", ":raw_value")
                    transformed_expression = json_cast.replace(":value", ":transformed_value")
                    connection.execute(
                        text("INSERT INTO macro_observation "
                             "(dataset_version_id, indicator_id, target_month, raw_value, "
                             "transformed_value, released_at, received_at, break_segment, "
                             "missing_reason) VALUES (:dataset, :indicator, :month, "
                             f"{raw_expression}, {transformed_expression}, :released, :received, "
                             ":segment, :missing)"),
                        {"dataset": dataset_id, "indicator": indicator.indicator_id,
                         "month": _month_date(observation.period), "raw_value": raw_value,
                         "transformed_value": transformed,
                         "released": observation.released_at,
                         "received": observation.received_at or now,
                         "segment": observation.break_segment,
                         "missing": observation.missing_reason},
                    )
            # 이번에 받지 않은 달을 앞 버전에서 옮긴다. 이 한 줄이 빠지면 공개된 과거가
            # 정기 수집을 돌릴 때마다 최근 N개월로 줄어든다.
            _carry_forward(connection, reusable, dataset_id)
            # **결과**가 앞 버전을 덮는지 본다. 입력을 보면 이어받기로 채워질 달까지 요구하게
            # 되어 정기 수집이 영영 발행되지 않는다. 공개될 자료가 줄지 않았는지가 물음이다.
            if reusable is not None:
                lost = connection.execute(
                    text(
                        "SELECT count(*) FROM macro_observation o"
                        " WHERE o.dataset_version_id = :old"
                        "   AND NOT EXISTS ("
                        "     SELECT 1 FROM macro_observation n"
                        "     WHERE n.dataset_version_id = :new"
                        "       AND n.indicator_id = o.indicator_id"
                        "       AND n.target_month = o.target_month)"
                    ),
                    {"old": reusable, "new": dataset_id},
                ).scalar_one()
                if lost:
                    raise PublishError(f"새 자료 버전에서 기존 가용 월 {lost}개가 빠졌다")
            for baseline in decision.baselines:
                expression = json_cast.replace(":value", ":held_back")
                connection.execute(
                    text("INSERT INTO macro_baseline "
                         "(dataset_version_id, registry_version_id, scope_id, reference_month, "
                         "reference_month_held_back_by, computed_at) VALUES "
                         f"(:dataset, :registry, :scope, :month, {expression}, :now)"),
                    {"dataset": dataset_id, "registry": target_registry,
                     "scope": baseline.scope_id,
                     "month": None if baseline.reference_month is None
                     else _month_date(baseline.reference_month),
                     "held_back": json.dumps(baseline.held_back_by), "now": now},
                )
            connection.execute(
                text("UPDATE macro_collection_attempt SET status='SUCCEEDED', finished_at=:now "
                     "WHERE attempt_key=:key"), {"now": now, "key": request.attempt_key}
            )
            if before_pointer_swap is not None:
                before_pointer_swap()
            expected = request.pointer
            dataset_clause = (
                "dataset_version_id IS NULL" if expected.dataset_id is None
                else "dataset_version_id=:expected_dataset"
            )
            updated = connection.execute(
                text("UPDATE macro_current_publication SET dataset_version_id=:new_dataset, "
                     "registry_version_id=:registry, display_version_id=:display, "
                     "switched_at=:now WHERE singleton_key='X' AND " + dataset_clause +
                     " AND registry_version_id=:expected_registry "
                     "AND display_version_id=:expected_display"),
                {"new_dataset": dataset_id, "registry": target_registry,
                 "display": target_display, "now": now,
                 "expected_dataset": expected.dataset_id,
                 "expected_registry": expected.registry_id,
                 "expected_display": expected.display_id},
            )
            if updated.rowcount != 1:
                raise ConcurrentPublication("다른 작업이 현재 발행 포인터를 먼저 바꿨다")
        return dataset_id
    except Exception:
        with engine.begin() as connection:
            connection.execute(
                text("UPDATE macro_collection_attempt SET status='FAILED', finished_at=:now "
                     "WHERE attempt_key=:key"),
                {"now": datetime.now(UTC), "key": request.attempt_key},
            )
        raise


def switch_display_version(
    engine: Engine, expected: PublicationPointer, target_display_id: int
) -> None:
    """화면 문구 변경은 자료·계산 등록표를 건드리지 않고 포인터만 바꾼다."""
    with engine.begin() as connection:
        if connection.execute(
            text("SELECT id FROM macro_display_version WHERE id=:id"),
            {"id": target_display_id},
        ).scalar_one_or_none() is None:
            raise PublishError("표시 버전이 없다")
        dataset_clause = (
            "dataset_version_id IS NULL" if expected.dataset_id is None
            else "dataset_version_id=:expected_dataset"
        )
        updated = connection.execute(
            text("UPDATE macro_current_publication SET display_version_id=:target, "
                 "switched_at=:now WHERE singleton_key='X' AND " + dataset_clause +
                 " AND registry_version_id=:registry AND display_version_id=:display"),
            {"target": target_display_id, "now": datetime.now(UTC),
             "expected_dataset": expected.dataset_id,
             "registry": expected.registry_id, "display": expected.display_id},
        )
        if updated.rowcount != 1:
            raise ConcurrentPublication("다른 작업이 현재 발행 포인터를 먼저 바꿨다")


def switch_calculation_registry(
    engine: Engine,
    expected: PublicationPointer,
    target_registry_id: int,
    indicators: tuple[IndicatorDefinition, ...],
    *,
    weights: Mapping[str, Decimal] | None = None,
) -> None:
    """같은 값 지문의 기존 관측 시계열을 재사용해 8개 기준월만 다시 계산한다."""
    if expected.dataset_id is None:
        raise PublishError("자료 버전이 없으면 기준월을 재계산할 수 없다")
    fingerprint = value_rule_fingerprint(indicators)
    with engine.begin() as connection:
        dataset_fingerprint = connection.execute(
            text("SELECT value_rule_fingerprint FROM macro_dataset_version "
                 "WHERE id=:id AND status='PUBLISHED'"),
            {"id": expected.dataset_id},
        ).scalar_one_or_none()
        target_fingerprint = connection.execute(
            text("SELECT value_rule_fingerprint FROM macro_calculation_registry_version "
                 "WHERE id=:id AND status='PUBLISHED'"),
            {"id": target_registry_id},
        ).scalar_one_or_none()
        if fingerprint != dataset_fingerprint or fingerprint != target_fingerprint:
            raise PublishError("값 규칙 지문이 달라 기존 시계열을 재사용할 수 없다")
        _assert_registered_calculation(connection, target_registry_id, indicators, weights)
        rows = connection.execute(
            text("SELECT indicator_id, target_month, transformed_value, missing_reason "
                 "FROM macro_observation WHERE dataset_version_id=:dataset"),
            {"dataset": expected.dataset_id},
        ).mappings()
        by_id: dict[str, list[NormalizedObservation]] = {
            indicator.indicator_id: [] for indicator in indicators
        }
        definition_by_id = {indicator.indicator_id: indicator for indicator in indicators}
        for row in rows:
            indicator = definition_by_id.get(row["indicator_id"])
            if indicator is None:
                raise PublishError("자료 버전에 등록표 밖의 지표가 있다")
            target_month = row["target_month"]
            period = (
                target_month.strftime("%Y-%m")
                if isinstance(target_month, date)
                else str(target_month)[:7]
            )
            by_id[indicator.indicator_id].append(
                NormalizedObservation(
                    indicator_id=indicator.indicator_id,
                    period=period,
                    transformed_value=(
                        None if row["transformed_value"] is None else Decimal(1)
                    ),
                    missing_reason=row["missing_reason"],
                    raw_values=(), transform_rule=indicator.transform_rule,
                    raw_unit=indicator.raw_unit,
                    transformed_unit=indicator.transformed_unit,
                    source_updated_at=None,
                )
            )
        decision = plan_publication(indicators, by_id)
        if not decision.can_publish:
            raise PublishError(f"기존 자료가 발행 게이트를 통과하지 못한다: {decision.blockers}")
        json_expression = (
            "CAST(:held_back AS JSONB)" if engine.dialect.name == "postgresql"
            else ":held_back"
        )
        for baseline in decision.baselines:
            connection.execute(
                text("INSERT INTO macro_baseline "
                     "(dataset_version_id, registry_version_id, scope_id, reference_month, "
                     "reference_month_held_back_by, computed_at) VALUES "
                     f"(:dataset, :registry, :scope, :month, {json_expression}, :now)"),
                {"dataset": expected.dataset_id, "registry": target_registry_id,
                 "scope": baseline.scope_id,
                 "month": None if baseline.reference_month is None
                 else _month_date(baseline.reference_month),
                 "held_back": json.dumps(baseline.held_back_by), "now": datetime.now(UTC)},
            )
        updated = connection.execute(
            text("UPDATE macro_current_publication SET registry_version_id=:target, "
                 "switched_at=:now WHERE singleton_key='X' AND dataset_version_id=:dataset "
                 "AND registry_version_id=:registry AND display_version_id=:display"),
            {"target": target_registry_id, "now": datetime.now(UTC),
             "dataset": expected.dataset_id, "registry": expected.registry_id,
             "display": expected.display_id},
        )
        if updated.rowcount != 1:
            raise ConcurrentPublication("다른 작업이 현재 발행 포인터를 먼저 바꿨다")
