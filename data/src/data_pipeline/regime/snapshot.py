"""기준월 단위 조립과 검색 상태 (spec FR-029).

사전 계산 단위는 (등록표 버전, 모델 버전) × 기준월 t × 모드(분야 7 + 종합 1)다. 한 snapshot
안의 모든 후보가 같은 m·s와 같은 비교 시점을 쓴다 — 같은 조건 비교(원칙 IV)를 스키마로 고정한다.

검색 상태는 **저장 값**이고 조회 시 재판정하지 않는다(research R6). 문턱이 교정되기 전에는
`NEAREST_ONLY`로 고정하며 문턱 값을 코드에 두지 않는다.

`content_hash`의 입력에 `computed_at` 같은 시각 값을 넣지 않는다 — 넣으면 같은 결과가 매번
새 revision으로 쌓여 SC-002가 깨진다.
"""

from __future__ import annotations

import hashlib
import json
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from typing import Any, Final

from data_pipeline.regime.candidates import (
    EXCLUSION_REASONS,
    CandidateSelection,
    select_candidates,
)
from data_pipeline.regime.comparison import QUERY_INPUT_MISSING, comparison_months_for
from data_pipeline.regime.distance import (
    CandidateDistance,
    DistanceModel,
    IndicatorDistance,
    ModelUnavailable,
    build_model,
)
from data_pipeline.regime.era import EraPositionCache
from data_pipeline.regime.explain import explanation_for
from data_pipeline.regime.macro.types import (
    IndicatorEntry,
    IndicatorRegistry,
    MacroSourceError,
    MonthlyValue,
    Publication,
    format_month,
    require_month,
)
from data_pipeline.regime.rounding import to_storage_text
from data_pipeline.regime.scale import SCALE_WINDOW_TOO_SHORT, SCALE_ZERO, Scale

#: 010 모델 버전 (data-model §6). 배치가 상수로 들고 다니며 식별 키와 저장 행에 실린다.
#:
#: **아래가 바뀌면 값을 올린다.** 올리면 워터마크가 계열 시작월이 되어 전 구간이 다시 돌고,
#: 내용이 달라진 항목만 새 `revision`이 된다(data-model §6).
#:
#: - 지표 집합·변환(FR-004)
#: - 척도 규칙 — 산출 구간·구간 길이·연속 판정·분위수 방법·보정 상수(FR-005·FR-006)
#: - 비교 시점 규칙 — 결측 달 제외, 지표 단위 적용(FR-001)
#: - 거리식·가중(FR-009·FR-010)
#: - 시대별 위치 판정 경계값 — `era.SIMILAR_THRESHOLD`(FR-017)
#: - 후보 제한(12개월·±6개월·동률), 표시 개수(FR-012·FR-013)
#: - 문턱(FR-019·FR-020), decimal 반올림 자릿수(research R4)
#:
#: ⚠ d21-amendment의 v2 검증 제안은 **기본 계산 경로에 들어가지 않는다**(PD-08 미확정).
#: 제안을 여기에 미리 반영하면 검증 전 모델이 운영 값이 된다.
MODEL_VERSION: Final[str] = "REGIME-V1"

MODE_FIELD: Final[str] = "FIELD"
MODE_COMPOSITE: Final[str] = "COMPOSITE"
MODES: Final[tuple[str, ...]] = (MODE_FIELD, MODE_COMPOSITE)

#: 종합 검색의 범위 id. `field_id`가 NULL인 자리를 식별 키·기준월 표에서 채운다.
COMPOSITE_SCOPE: Final[str] = "ALL"

STATUS_NOT_COMPUTABLE: Final[str] = "NOT_COMPUTABLE"
STATUS_NEAREST_ONLY: Final[str] = "NEAREST_ONLY"
#: PD-09(문턱)이 교정된 뒤에 생기는 상태. **지금 이 값을 만드는 경로는 없다**(research R6).
#: 어휘를 미리 두는 것은 저장 값의 목록이고, 문턱 값을 코드에 두는 것과 다르다.
STATUS_THRESHOLD_APPLIED: Final[str] = "THRESHOLD_APPLIED"

#: 유효 후보가 0건이다 (data-model §2.1).
NO_CANDIDATE: Final[str] = "NO_CANDIDATE"
#: 문턱 미교정 — `NEAREST_ONLY`의 사유다 (spec FR-020, research R6).
THRESHOLD_NOT_CALIBRATED: Final[str] = "THRESHOLD_NOT_CALIBRATED"

#: 계산 불가 사유 넷 (data-model §2.1). 이 밖의 값을 만들지 않는다.
NOT_COMPUTABLE_REASONS: Final[tuple[str, ...]] = (
    SCALE_WINDOW_TOO_SHORT,
    SCALE_ZERO,
    QUERY_INPUT_MISSING,
    NO_CANDIDATE,
)


class SnapshotError(RuntimeError):
    """snapshot을 조립할 수 없다."""


@dataclass(frozen=True)
class SearchScope:
    """검색 하나의 범위 — 분야 g이거나 종합이다.

    `field_ids`가 `None`인 것이 종합이며, 그 값이 그대로 `build_model`에 넘어간다. 모드를
    문자열로 비교하는 분기를 계산 쪽에 두지 않기 위해서다(spec FR-010).
    """

    mode: str
    field_id: str | None

    def __post_init__(self) -> None:
        if self.mode not in MODES:
            raise MacroSourceError(f"모르는 검색 모드다: {self.mode!r}")
        if self.mode == MODE_FIELD and not self.field_id:
            raise MacroSourceError("분야 검색에는 분야 id가 있어야 한다")
        if self.mode == MODE_COMPOSITE and self.field_id is not None:
            raise MacroSourceError("종합 검색에는 분야 id를 두지 않는다")

    @classmethod
    def field(cls, field_id: str) -> SearchScope:
        return cls(mode=MODE_FIELD, field_id=field_id)

    @classmethod
    def composite(cls) -> SearchScope:
        return cls(mode=MODE_COMPOSITE, field_id=None)

    @property
    def scope_id(self) -> str:
        """식별 키·기준월 표에 쓰는 범위 id. 종합은 ``ALL``이다."""
        return self.field_id if self.field_id is not None else COMPOSITE_SCOPE

    @property
    def field_ids(self) -> tuple[str, ...] | None:
        return None if self.field_id is None else (self.field_id,)


@dataclass(frozen=True)
class Versions:
    """식별 키를 이루는 버전 셋 (data-model §2)."""

    data: str
    registry: str
    model: str = MODEL_VERSION


@dataclass(frozen=True)
class SearchStatus:
    """검색 상태와 사유 (data-model §2.1, §8).

    **저장 값이다.** 조회가 다시 판정하면 문턱이 교정된 날 이전 검색의 표시가 한꺼번에 바뀌는데,
    그때 사용자가 예전에 본 화면은 어디에도 남지 않는다(research R6, spec Edge Cases).
    """

    status: str
    reason: str | None

    def __post_init__(self) -> None:
        if self.status == STATUS_NOT_COMPUTABLE:
            if self.reason not in NOT_COMPUTABLE_REASONS:
                raise SnapshotError(
                    f"계산 불가 사유가 data-model §2.1의 넷이 아니다: {self.reason!r}"
                )
        elif self.status == STATUS_NEAREST_ONLY:
            if self.reason != THRESHOLD_NOT_CALIBRATED:
                raise SnapshotError(f"NEAREST_ONLY의 사유가 아니다: {self.reason!r}")
        else:
            raise SnapshotError(f"배치가 만들 수 있는 상태가 아니다: {self.status!r}")

    @property
    def computable(self) -> bool:
        return self.status != STATUS_NOT_COMPUTABLE


def not_computable(reason: str) -> SearchStatus:
    return SearchStatus(status=STATUS_NOT_COMPUTABLE, reason=reason)


#: 문턱 교정 전의 유일한 정상 상태 (spec FR-020, research R6).
#:
#: **문턱 값이 코드에 없다.** `NEAREST_ONLY`는 "가까운 순서만 제공하고 유사성은 판정하지
#: 않는다"는 뜻이라 비교할 값이 필요 없다. 임시 문턱을 넣어 두면 그 숫자가 곧 판정 기준이 되고,
#: 교정으로 정해질 값과 다른 결과가 이미 저장된 뒤다(PD-09 미확정).
NEAREST_ONLY: Final[SearchStatus] = SearchStatus(
    status=STATUS_NEAREST_ONLY, reason=THRESHOLD_NOT_CALIBRATED
)


def status_for_model(model: DistanceModel | ModelUnavailable) -> SearchStatus | None:
    """기준월 t로 계산을 시작할 수 있는가. 시작할 수 있으면 ``None``이다.

    ``None``은 "아직 상태가 정해지지 않았다"는 뜻이다 — 후보를 세어 봐야 `NO_CANDIDATE`인지
    `NEAREST_ONLY`인지 갈린다.
    """
    if isinstance(model, ModelUnavailable):
        return not_computable(model.reason)
    return None


def status_for_selection(selection: CandidateSelection) -> SearchStatus:
    """후보를 센 뒤의 상태 (data-model §8)."""
    if not selection.ranked:
        return not_computable(NO_CANDIDATE)
    return NEAREST_ONLY


def propagate_to_composite(
    field_statuses: Mapping[str, SearchStatus], composite: SearchStatus
) -> SearchStatus:
    """분야 하나가 계산 불가면 **종합도 계산 불가**다 (spec FR-039, US3 시나리오 6).

    6개 분야로 계산하지 않는다 — 종합은 등록표의 모든 분야를 같은 조건으로 묶은 값이라, 하나를
    빼고 낸 값은 이름만 같은 다른 지표다.

    **반대 방향으로는 전파하지 않는다.** 종합만 계산 불가일 수 있고(후보 0건이거나 T_all이
    없을 때), 그때 분야 검색은 그대로 제공한다(FR-039). 여기서 되돌려 전파하면 시작 연도가 늦은
    지표 하나가 나머지 여섯 분야의 검색까지 끈다.

    사유는 **분야가 낸 것을 그대로** 쓴다(등록표 순서로 먼저 걸린 분야). 새 사유를 만들면
    data-model §2.1에 없는 값이 화면과 기록으로 새어 나간다.
    """
    for status in field_statuses.values():
        if not status.computable:
            return status
    return composite


def snapshot_key(scope: SearchScope, baseline_month: date, versions: Versions) -> str:
    """식별 키 (spec FR-029, data-model §2).

    ``{mode}:{fieldId|ALL}:{baselineMonth}:{dataVersion}:{registryVersion}:{modelVersion}``

    **검색 경로와 `baseline_origin`은 넣지 않는다.** 경로가 달라도 계산이 같고, 같은 월을 T_g로
    얻었든 사용자가 지정했든 결과가 같다 — 넣으면 같은 결과가 여러 벌 저장되어 "같은 식별이면
    같은 결과"(SC-002)가 흐려진다. 게다가 요청을 모르는 배치는 근거를 정할 수도 없다.
    """
    require_month(baseline_month, "기준월")
    return ":".join(
        (
            scope.mode,
            scope.scope_id,
            baseline_month.isoformat(),
            versions.data,
            versions.registry,
            versions.model,
        )
    )


def content_hash(body: Mapping[str, Any]) -> str:
    """결과 본문의 해시 (spec FR-030, data-model §2).

    입력은 :func:`Snapshot.content` 가 만든 본문뿐이다 — **계산 시각도 버전 이름표도 넣지
    않는다.** 시각을 넣으면 같은 결과가 매번 새 revision이 되고, 버전을 넣으면 내용이 한 글자도
    안 바뀐 항목이 버전 이름표만 달고 복제된다(research R3).

    **공통 척도와 시대별 보조 설명이 본문에 들어 있다.** 순위가 같아도 보조 설명이 바뀌면 화면에
    나가는 것이 달라지므로 새 결과다(FR-017·FR-030).
    """
    canonical = json.dumps(body, sort_keys=True, ensure_ascii=False, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class SnapshotCase:
    """표시 후보 한 행 (data-model §4)."""

    rank: int
    end_month: date
    distance: dict[str, Any]
    indicator_values: dict[str, Any]
    explanation: dict[str, Any]
    era_position: dict[str, Any] | None

    def content(self) -> dict[str, Any]:
        return {
            "rank": self.rank,
            "endMonth": format_month(self.end_month),
            "distance": self.distance,
            "indicatorValues": self.indicator_values,
            "explanation": self.explanation,
            "eraPosition": self.era_position,
        }


@dataclass(frozen=True)
class Snapshot:
    """기준월 × 모드 하나의 검색 결과 (data-model §2).

    이 객체가 곧 사용자에게 나가는 것이며, 조회는 이것을 읽을 뿐이다(spec FR-030).
    """

    scope: SearchScope
    baseline_month: date
    versions: Versions
    status: SearchStatus
    scale: dict[str, Any]
    comparison_months: dict[str, Any]
    ranked_candidates: list[dict[str, Any]]
    candidate_counts: dict[str, Any]
    data_asof: dict[str, Any]
    cases: tuple[SnapshotCase, ...]

    @property
    def key(self) -> str:
        return snapshot_key(self.scope, self.baseline_month, self.versions)

    def content(self) -> dict[str, Any]:
        """해시에 넣는 결과 본문 — 사용자에게 나가는 것 전부, 시각과 버전은 제외."""
        return {
            "mode": self.scope.mode,
            "scopeId": self.scope.scope_id,
            "baselineMonth": format_month(self.baseline_month),
            "status": self.status.status,
            "statusReason": self.status.reason,
            "scale": self.scale,
            "comparisonMonths": self.comparison_months,
            "rankedCandidates": self.ranked_candidates,
            "candidateCounts": self.candidate_counts,
            "dataAsOf": self.data_asof,
            "cases": [case.content() for case in self.cases],
        }

    @property
    def content_hash(self) -> str:
        return content_hash(self.content())


def _scale_body(scale: Scale) -> dict[str, Any]:
    return {
        "m": to_storage_text(scale.median),
        "s": to_storage_text(scale.spread),
        "method": scale.method,
        "windowStart": format_month(scale.window_start),
        "windowEnd": format_month(scale.window_end),
        "months": scale.window_length,
    }


def _distance_body(distance: CandidateDistance) -> dict[str, Any]:
    body: dict[str, Any] = {
        "value": to_storage_text(distance.value),
        "byField": {
            field.field_id: to_storage_text(field.d) for field in distance.fields
        },
    }
    contribution = distance.contribution
    # 분야가 하나뿐이거나 ΣD_g가 0이면 **키를 두지 않는다**. 0이나 1을 넣으면 화면이 그것을
    # 기여도로 그리는데, 그 숫자는 자료가 아니라 자리를 채운 값이다(data-model §4).
    if contribution is not None:
        body["contribution"] = {
            field_id: to_storage_text(value) for field_id, value in contribution.items()
        }
    return body


def _indicator_values_body(
    item: IndicatorDistance,
    entry: IndicatorEntry,
    series: Mapping[date, MonthlyValue],
) -> dict[str, Any]:
    """지표 하나의 저장 값 (data-model §4의 `indicator_values`).

    ``queryRaw``·``caseRaw``는 **표준화 전 값**이라는 뜻이고 `monthly_series()`가 준 **변환 후
    값**이다(spec FR-015, 포트 계약 §1). `raw_series()`의 지수 원수준이 아니다 — 증가율 지표에서
    그 둘은 다른 값이고, 거리를 변환값으로 재면서 설명만 원수준으로 쓰면 화면의 숫자와 순위가
    서로 다른 것을 가리킨다.

    비교 시점이 여럿이라 값도 시점 수만큼이다. 하나로 줄이면 SC-003의 수기 대조에서 E_i를 다시
    만들 수 없다.
    """
    body: dict[str, Any] = {
        "unit": entry.unit,
        "comparisonMonths": [format_month(month) for month in item.months],
        "caseMonths": [format_month(month) for month in item.case_months],
        "queryRaw": [to_storage_text(series[month].value) for month in item.months],
        "caseRaw": [to_storage_text(series[month].value) for month in item.case_months],
        "queryZ": [to_storage_text(value) for value in item.query_z],
        "caseZ": [to_storage_text(value) for value in item.case_z],
        "e": to_storage_text(item.e),
        "nominalWeight": to_storage_text(item.nominal_weight),
    }
    if item.distance_share is not None:
        body["distanceShare"] = to_storage_text(item.distance_share)
    return body


class SnapshotBuilder:
    """한 자료·등록표 버전에서 기준월 × 모드의 snapshot을 만든다.

    계열·등록표·시대별 보조 설명 캐시를 한 번 받아 두고 기준월마다 부른다. **버전이 바뀌면 이
    객체를 새로 만든다** — `EraPositionCache`의 값이 버전에 묶여 있어서다(era.py).
    """

    def __init__(
        self,
        registry: IndicatorRegistry,
        series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
        publication: Callable[[str, date], Publication | None],
        versions: Versions,
        era_cache: EraPositionCache | None = None,
    ) -> None:
        self._registry = registry
        self._series = series_by_indicator
        self._publication = publication
        self._versions = versions
        self._era = era_cache if era_cache is not None else EraPositionCache(series_by_indicator)
        self._entries = {entry.id: entry for entry in registry.indicators}

    @property
    def registry(self) -> IndicatorRegistry:
        return self._registry

    @property
    def versions(self) -> Versions:
        return self._versions

    @property
    def era_cache(self) -> EraPositionCache:
        return self._era

    def indicators_of(self, scope: SearchScope) -> tuple[IndicatorEntry, ...]:
        if scope.field_id is None:
            return tuple(
                entry
                for field in self._registry.ordered_fields()
                for entry in self._registry.indicators_of(field.id)
            )
        return self._registry.indicators_of(scope.field_id)

    def observable_months(self, scope: SearchScope) -> tuple[date, ...]:
        """그 범위의 지표가 값을 가진 모든 달 — 기준월 후보다.

        **합집합이다.** 교집합으로 좁히면 어느 지표 하나의 구멍이 그 달을 기준월 후보에서
        지우는데, 그 달도 자료 범위 안이라 조회하면 200 + 계산 불가여야 한다(계약 §1: 404는
        자료 범위 밖일 때만).
        """
        months: set[date] = set()
        for entry in self.indicators_of(scope):
            months.update(self._series[entry.id])
        return tuple(sorted(months))

    def build(self, scope: SearchScope, baseline_month: date) -> Snapshot:
        """기준월 하나의 snapshot을 조립한다 (data-model §2·§4)."""
        require_month(baseline_month, "기준월")
        entries = self.indicators_of(scope)
        if not entries:
            raise SnapshotError(f"등록표에 지표가 없는 범위다: {scope.scope_id}")
        scoped_series = {entry.id: self._series[entry.id] for entry in entries}

        model = build_model(
            self._registry, baseline_month, scoped_series, field_ids=scope.field_ids
        )
        data_asof = self._data_asof(entries, baseline_month)

        blocked = status_for_model(model)
        if blocked is not None or not isinstance(model, DistanceModel):
            if blocked is None:  # 방어 — status_for_model이 가려내지 못한 형이 왔다
                raise SnapshotError(f"계산 준비물의 형을 알 수 없다: {type(model).__name__}")
            if scope.field_id is None and isinstance(model, ModelUnavailable):
                # 막힌 지표가 속한 분야는 t에서 계산 불가다. 그 사실을 종합으로 옮기는 것이
                # FR-039의 정방향이며, 사유도 분야가 낸 것을 그대로 쓴다.
                blocked = propagate_to_composite(
                    {
                        self._entries[indicator_id].field_id: blocked
                        for indicator_id in model.indicator_ids
                    },
                    NEAREST_ONLY,
                )
            return self._unavailable(scope, baseline_month, entries, blocked, data_asof)

        selection = select_candidates(model)
        status = status_for_selection(selection)

        cases = self._cases(model, selection.displayed) if status.computable else ()
        return Snapshot(
            scope=scope,
            baseline_month=baseline_month,
            versions=self._versions,
            status=status,
            scale={
                indicator_id: _scale_body(scale)
                for indicator_id, scale in sorted(model.scales.items())
            },
            comparison_months=self._comparison_body(model),
            ranked_candidates=[
                {
                    "endMonth": format_month(item.end_month),
                    "distance": to_storage_text(item.distance),
                    "rank": item.rank,
                }
                for item in selection.ranked
            ],
            candidate_counts={
                "evaluated": selection.counts.evaluated,
                "excluded": dict(sorted(selection.counts.excluded.items())),
            },
            data_asof=data_asof,
            cases=cases,
        )

    # -- 계산 불가 ---------------------------------------------------------------------

    def _unavailable(
        self,
        scope: SearchScope,
        baseline_month: date,
        entries: Sequence[IndicatorEntry],
        status: SearchStatus,
        data_asof: dict[str, Any],
    ) -> Snapshot:
        """계산을 시작하지 못한 기준월의 snapshot.

        **행을 만들지 않는 선택지는 없다.** 계산 불가는 정상 화면 흐름이라(원칙 VI) 조회가
        200으로 답해야 하고, 행이 없으면 404가 되어 "자료가 없다"와 구분되지 않는다(계약 §1).

        `scale`은 비워 둔다 — 계산에 **쓰지 않은** 척도를 저장하면 화면이 그것으로 수기 대조를
        할 수 있다고 읽는다. 비교 시점은 채운다. 어느 지표의 어느 달이 비어 이렇게 됐는지가
        그 값에 드러나고, 그것이 화면이 표기할 내용이다(spec FR-031).
        """
        comparison: dict[str, Any] = {}
        for entry in entries:
            months = self._comparison_of(entry.id, baseline_month)
            comparison[entry.id] = months
        return Snapshot(
            scope=scope,
            baseline_month=baseline_month,
            versions=self._versions,
            status=status,
            scale={},
            comparison_months=dict(sorted(comparison.items())),
            ranked_candidates=[],
            candidate_counts={
                "evaluated": 0,
                "excluded": dict.fromkeys(sorted(EXCLUSION_REASONS), 0),
            },
            data_asof=data_asof,
            cases=(),
        )

    def _comparison_of(self, indicator_id: str, baseline_month: date) -> dict[str, Any]:
        months = comparison_months_for(indicator_id, baseline_month, self._series[indicator_id])
        return {
            "months": [format_month(month) for month in months.months],
            "omitted": [format_month(month) for month in months.omitted],
        }

    def _comparison_body(self, model: DistanceModel) -> dict[str, Any]:
        return {
            indicator_id: {
                "months": [format_month(month) for month in months.months],
                "omitted": [format_month(month) for month in months.omitted],
            }
            for indicator_id, months in sorted(model.comparison.by_indicator.items())
        }

    # -- 사례 ---------------------------------------------------------------------------

    def _cases(
        self, model: DistanceModel, displayed: Sequence[CandidateDistance]
    ) -> tuple[SnapshotCase, ...]:
        """표시 후보를 사례 행으로 편다 (data-model §4).

        ``rank``는 **표시 순서**다. `ranked_candidates`의 원본 순위는 ±6개월 건너뛰기를 적용하지
        않은 값이라 둘이 다를 수 있고, 화면이 세는 것은 보이는 순서다(spec FR-013).
        """
        entries = {entry.id: entry for entry in model.indicators}
        return tuple(
            SnapshotCase(
                rank=rank,
                end_month=distance.end_month,
                distance=_distance_body(distance),
                indicator_values={
                    item.indicator_id: _indicator_values_body(
                        item, entries[item.indicator_id], model.series[item.indicator_id]
                    )
                    for field in distance.fields
                    for item in field.indicators
                },
                explanation=self._explanation_body(distance, entries, model),
                era_position=self._era_body(model.indicators, distance.end_month),
            )
            for rank, distance in enumerate(displayed, start=1)
        )

    def _explanation_body(
        self,
        distance: CandidateDistance,
        entries: Mapping[str, IndicatorEntry],
        model: DistanceModel,
    ) -> dict[str, Any]:
        explanation = explanation_for(distance, entries, model.series)
        return {"similar": list(explanation.similar), "different": list(explanation.different)}

    def _era_body(
        self, indicators: Sequence[IndicatorEntry], end_month: date
    ) -> dict[str, Any] | None:
        """시대별 보조 위치 (spec FR-017, data-model §4).

        만들지 못한 지표는 **키가 없고**, 전부 만들지 못하면 ``None``이다. 0으로 채우지 않는다 —
        채우면 "그 시대의 평균이었다"는 말이 되는데 실제로는 잴 자가 없었다는 뜻이다.

        z·분위수는 담지 않는다. 담아 두면 화면이 언젠가 그것을 내보내고 그 순간 유사도처럼
        읽힌다(FR-017·FR-022). 수기 대조에는 방법과 구간이면 충분하다.
        """
        positions = self._era.positions_for(indicators, end_month)
        if not positions:
            return None
        return {
            indicator_id: {
                "sentence": position.sentence,
                "method": position.method,
                "windowStart": format_month(position.window_start),
                "windowEnd": format_month(position.window_end),
                "months": position.months,
            }
            for indicator_id, position in sorted(positions.items())
        }

    # -- 자료 기준 시점 -----------------------------------------------------------------

    def _data_asof(
        self, entries: Sequence[IndicatorEntry], baseline_month: date
    ) -> dict[str, Any]:
        """대상월·발표일·개정 여부의 **복사본** (spec FR-031, data-model §9).

        나중에 008을 다시 읽으면 그 뒤 개정된 값이 나오므로, 계산 당시의 발표일을 여기 박아
        둔다. 그래야 "이 결과가 언제 자료인가"가 결과와 함께 굳는다.

        `newerReleases`는 **기준월보다 최신인 개별 발표**다(spec FR-016) — 종합에서 T_all이 어느
        분야 T_g보다 이를 때 생긴다. 별도 정보로만 두고 질의값에 섞지 않는다. 이 값도 `data_asof`의
        일부라 **계산 당시의 사실**이며, 나중에 발표가 더 쌓여도 그 기준월을 다시 계산하지 않는 한
        움직이지 않는다 — 워터마크가 `t ≥ M`만 다시 도는 것과 같은 성질이다(data-model §9).
        """
        indicators: dict[str, Any] = {}
        newer: list[dict[str, Any]] = []
        for entry in entries:
            series = self._series[entry.id]
            months = sorted(series)
            if not months:
                continue
            reflected = [month for month in months if month <= baseline_month]
            if reflected:
                target = reflected[-1]
                published = self._publication(entry.id, target)
                body: dict[str, Any] = {"targetMonth": format_month(target)}
                if published is not None:
                    body["publishedAt"] = published.published_on.isoformat()
                    if published.revised:
                        # 개정된 값의 발표일이 곧 개정일이다 — 008이 개정 날짜를 따로 주지
                        # 않으므로 없는 날짜를 지어내지 않고 이 값을 그대로 쓴다.
                        body["revisedAt"] = published.published_on.isoformat()
                indicators[entry.id] = body
            latest = months[-1]
            if latest > baseline_month:
                published = self._publication(entry.id, latest)
                newer.append(
                    {
                        "indicatorId": entry.id,
                        "month": format_month(latest),
                        "publishedAt": (
                            published.published_on.isoformat() if published is not None else None
                        ),
                    }
                )
        return {
            "indicators": dict(sorted(indicators.items())),
            "newerReleases": sorted(newer, key=lambda item: item["indicatorId"]),
        }
