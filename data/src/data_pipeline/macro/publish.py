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

발행을 **실제로 기록하는** 일(파일 봉인, 한 트랜잭션 쓰기, 포인터 교체)은 T038·T039 다. 이
모듈은 "발행해도 되는가"와 "기준월이 언제인가"만 정한다.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from typing import Final

from data_pipeline.macro.normalize import NormalizedObservation
from data_pipeline.macro.registry import (
    EXPECTED_INDICATOR_COUNT,
    FIELD_IDS,
    REVIEW_PENDING_RIGHTS,
    REVIEW_VERIFIED,
    IndicatorDefinition,
    RegistryError,
)
from data_pipeline.macro.registry import (
    validate as validate_registry,
)

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

    blockers: list[PublicationBlocker] = []
    for indicator in indicators:
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
        raise PublishError(f"{scope_id}에 속한 지표가 없다")

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
    valid = _valid_by_indicator(indicators, observations)

    members_by_field: dict[str, list[str]] = {field_id: [] for field_id in FIELD_IDS}
    for indicator in indicators:
        if indicator.field_id not in members_by_field:
            raise PublishError(
                f"{indicator.indicator_id}의 분야가 7개 밖이다: {indicator.field_id}"
            )
        members_by_field[indicator.field_id].append(indicator.indicator_id)

    result = [
        _baseline_for_scope(field_id, members_by_field[field_id], valid) for field_id in FIELD_IDS
    ]
    result.append(_baseline_for_scope(SCOPE_ALL, [i.indicator_id for i in indicators], valid))
    return tuple(result)


def plan_publication(
    indicators: Sequence[IndicatorDefinition],
    observations: Mapping[str, Sequence[NormalizedObservation]],
) -> PublicationDecision:
    """발행 판정과 기준월을 한 번에 낸다. 보류면 기준월을 달지 않는다.

    T038 의 원자적 발행이 이 결과를 받아 파일을 봉인하고 한 트랜잭션에 쓴다. 판정과 기록을
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
    )
