"""자료 버전 발행 (spec 001 FR-013·FR-014·FR-017).

자료 버전은 수집이 끝난 뒤에 한 번 발행되고 그 뒤로는 바뀌지 않는다. 자료를 고쳐야 하면
같은 행을 손대지 않고 새 ``dataset_id``로 새 버전을 발행한다(FR-017). 이 모듈은 발행 값
자체의 규칙만 다루고, 실제 저장은 :mod:`data_pipeline.repository`가 맡는다.

불변식을 파이썬에서도 검사하는 이유: 저장 스키마
(``backend/src/main/resources/db/migration/V20260911114500__etf_market_data.sql``)에 같은
제약이 CHECK·UNIQUE로 들어가 있지만, DB에만 맡기면 파이프라인을 돌린 사람이 받는 것은
제약 이름뿐이라 어떤 값을 어떻게 고쳐야 하는지 알 수 없다. DB는 마지막 방어선이고
여기는 뜻이 분명한 오류를 내는 자리다.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any, Final

#: ``KRX_DAILY_{YYYYMMDD}[_R{판}]`` 형식이다 (research.md 결정 4). 옛 표기 ``KRX_DAILY_V1``은
#: 쓰지 않는다. 판 번호는 같은 마감일을 다시 발행할 때만 붙고 2부터 센다 — 까닭은
#: :func:`build_dataset_id` 에 있다.
DATASET_ID_PREFIX: Final[str] = "KRX_DAILY_"
DATASET_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"^KRX_DAILY_(\d{8})(?:_R([2-9]\d*))?$")

#: ``dataset_version.source``에 들어가는 값. 예시 자료는 워커의 기존 표시를 그대로 쓴다
#: (research.md 결정 5, ``worker/src/worker/market_data.py``).
SOURCE_KRX_OPEN_API: Final[str] = "KRX_OPEN_API"
SOURCE_FIXED_TEST_DATA: Final[str] = "FIXED_TEST_DATA"

#: ``dataset_version``의 열 이름. 마이그레이션과 어긋나면 적재가 깨지므로 한 곳에 모은다.
#: ``id``는 IDENTITY라 파이프라인이 채우지 않는다.
DATASET_VERSION_COLUMNS: Final[tuple[str, ...]] = (
    "dataset_id",
    "data_deadline",
    "collected_at",
    "last_success_at",
    "complete",
    "failure_reason",
    "is_sample",
    "source",
    "map_version_id",
)

_SOURCE_MAX_LENGTH: Final[int] = 64


class DatasetVersionError(ValueError):
    """자료 버전 규칙 위반. 값을 고쳐 다시 발행해야 한다."""


class InvalidDatasetIdError(DatasetVersionError):
    """``dataset_id``가 ``KRX_DAILY_{YYYYMMDD}`` 형식이 아니다."""


class IncompleteWithoutReasonError(DatasetVersionError):
    """부분 갱신인데 실패 사유가 없다 (FR-014)."""


class DatasetAlreadyPublishedError(DatasetVersionError):
    """이미 발행된 ``dataset_id``에 다시 발행하려 했다 (FR-017)."""


class DatasetNotPublishedError(DatasetVersionError):
    """자료 버전 행이 없는 ``dataset_id``에 시계열을 적재하려 했다."""


class DatasetAlreadyLoadedError(DatasetVersionError):
    """이미 적재가 끝난 자료 버전에 같은 시계열을 다시 넣으려 했다 (FR-017)."""


def build_dataset_id(data_deadline: datetime | date, revision: int = 1) -> str:
    """데이터 마감 시각에서 ``KRX_DAILY_{YYYYMMDD}[_R{판}]`` 식별자를 만든다.

    ``datetime``을 받으면 그 시각의 날짜를 쓴다. 시간대 변환은 하지 않는다 — 마감 시각을
    어느 시간대로 정할지는 부르는 쪽의 결정이고, 여기서 임의로 바꾸면 하루가 밀린다.

    ``revision``은 **같은 마감일을 다시 발행할 때** 쓴다. 첫 판(1)은 판 번호를 붙이지 않고,
    두 번째부터 ``_R2``·``_R3``이 붙는다. 첫 판에 ``_R1``을 붙이면 이미 발행된 이름들을 모두
    바꿔야 하기 때문이다.

    **판 번호가 필요한 까닭**은 발행본을 고칠 수 없기 때문이다(FR-017). 같은 기간의 자료를
    내용만 바꿔 다시 내려면 이름이 달라야 하는데, 마감일 하나로만 정하면 그 길이 없다.
    구간 끝을 하루 늘려 피할 수도 있으나 장이 열리지 않은 날이나 과거 구간을 고칠 때는 그
    방법이 통하지 않는다 (research.md 결정 4의 2026-09-17 보완).

    판을 올릴지는 **부르는 쪽이 정한다.** 이 함수가 이미 발행된 것을 조회해 스스로 올리면,
    같은 인자로 부른 두 번이 다른 값을 내어 재현이 깨진다.
    """
    if isinstance(data_deadline, datetime):
        day = data_deadline.date()
    elif isinstance(data_deadline, date):
        day = data_deadline
    else:
        raise InvalidDatasetIdError(
            f"데이터 마감 시각은 date 또는 datetime이어야 한다: {type(data_deadline).__name__}"
        )
    if not isinstance(revision, int) or isinstance(revision, bool) or revision < 1:
        raise InvalidDatasetIdError(f"판 번호는 1 이상의 정수여야 한다: {revision!r}")
    suffix = "" if revision == 1 else f"_R{revision}"
    return f"{DATASET_ID_PREFIX}{day:%Y%m%d}{suffix}"


def validate_dataset_id(dataset_id: object) -> str:
    """``dataset_id`` 형식을 검사하고 그대로 돌려준다."""
    if not isinstance(dataset_id, str):
        raise InvalidDatasetIdError(f"dataset_id는 문자열이어야 한다: {type(dataset_id).__name__}")
    matched = DATASET_ID_PATTERN.match(dataset_id)
    if matched is None:
        raise InvalidDatasetIdError(
            f"dataset_id는 {DATASET_ID_PREFIX}{{YYYYMMDD}}[_R{{판}}] 형식이어야 한다: "
            f"{dataset_id!r}"
        )
    try:
        datetime.strptime(matched.group(1), "%Y%m%d")
    except ValueError as exc:
        raise InvalidDatasetIdError(
            f"dataset_id의 날짜 부분이 실제 날짜가 아니다: {dataset_id!r}"
        ) from exc
    return dataset_id


def dataset_revision(dataset_id: str) -> int:
    """``dataset_id``가 몇 번째 판인지 돌려준다. 판 번호가 없으면 1이다."""
    validate_dataset_id(dataset_id)
    matched = DATASET_ID_PATTERN.match(dataset_id)
    assert matched is not None  # validate_dataset_id가 이미 확인했다
    suffix = matched.group(2)
    return 1 if suffix is None else int(suffix)


def dataset_deadline_date(dataset_id: str) -> date:
    """``dataset_id``가 담은 데이터 마감일을 돌려준다. 판 번호는 마감일을 바꾸지 않는다."""
    validate_dataset_id(dataset_id)
    matched = DATASET_ID_PATTERN.match(dataset_id)
    assert matched is not None  # validate_dataset_id가 이미 확인했다
    return datetime.strptime(matched.group(1), "%Y%m%d").date()


def _require_aware(value: object, field: str) -> datetime:
    if not isinstance(value, datetime):
        raise DatasetVersionError(f"{field}은(는) datetime이어야 한다: {type(value).__name__}")
    if value.tzinfo is None or value.tzinfo.utcoffset(value) is None:
        raise DatasetVersionError(
            f"{field}은(는) 시간대가 붙은 datetime이어야 한다 (저장 열이 timestamptz다): {value!r}"
        )
    return value


@dataclass(frozen=True)
class DatasetVersion:
    """발행할 자료 버전 한 건. ``dataset_version`` 한 행에 그대로 대응한다.

    ``map_version_id``는 이 자료가 어느 섹터-ETF 대응표를 대상으로 수집됐는지를 가리키며
    (data-model.md), 저장 스키마에서 NOT NULL이다.
    """

    dataset_id: str
    data_deadline: datetime
    collected_at: datetime
    last_success_at: datetime
    complete: bool
    is_sample: bool
    source: str
    map_version_id: int
    failure_reason: str | None = None

    def __post_init__(self) -> None:
        validate_dataset_id(self.dataset_id)
        _require_aware(self.data_deadline, "data_deadline")
        # research.md 결정 4가 값 규칙을 `KRX_DAILY_{자료마감일}`로 정했으므로 둘은 같은 날을
        # 가리켜야 한다. 어긋나면 식별자가 말하는 날과 data_deadline 열이 말하는 날이 달라지고,
        # 발행된 행은 고칠 수 없어 아무도 바로잡지 못한다.
        derived = build_dataset_id(self.data_deadline)
        if derived != self.dataset_id:
            raise DatasetVersionError(
                f"dataset_id와 data_deadline이 어긋난다: {self.dataset_id} ≠ {derived}"
                f" (data_deadline={self.data_deadline.isoformat()}). 값 규칙은"
                " KRX_DAILY_{자료마감일}이다 (research.md 결정 4)"
            )
        _require_aware(self.collected_at, "collected_at")
        _require_aware(self.last_success_at, "last_success_at")
        if not isinstance(self.complete, bool):
            raise DatasetVersionError(f"complete는 bool이어야 한다: {self.complete!r}")
        if not isinstance(self.is_sample, bool):
            raise DatasetVersionError(f"is_sample은 bool이어야 한다: {self.is_sample!r}")
        if self.failure_reason is not None and not isinstance(self.failure_reason, str):
            raise DatasetVersionError(
                f"failure_reason은 문자열이거나 None이어야 한다: {self.failure_reason!r}"
            )
        # FR-014. 저장 스키마의 ck_dataset_version_incomplete_reason과 같은 조건이며,
        # 공백만 들어간 사유는 DB가 통과시키지만 사유가 아니므로 여기서 막는다.
        if not self.complete and not (self.failure_reason or "").strip():
            raise IncompleteWithoutReasonError(
                f"부분 갱신({self.dataset_id})은 failure_reason 없이 발행할 수 없다 (FR-014)"
            )
        if not isinstance(self.source, str) or not self.source.strip():
            raise DatasetVersionError(f"source는 비어 있을 수 없다: {self.source!r}")
        if len(self.source) > _SOURCE_MAX_LENGTH:
            raise DatasetVersionError(
                f"source는 {_SOURCE_MAX_LENGTH}자를 넘을 수 없다: {self.source!r}"
            )
        if not isinstance(self.map_version_id, int) or isinstance(self.map_version_id, bool):
            raise DatasetVersionError(f"map_version_id는 정수여야 한다: {self.map_version_id!r}")
        if self.map_version_id <= 0:
            raise DatasetVersionError(f"map_version_id는 양수여야 한다: {self.map_version_id!r}")

    @classmethod
    def for_deadline(
        cls,
        data_deadline: datetime,
        *,
        collected_at: datetime,
        last_success_at: datetime,
        complete: bool,
        is_sample: bool,
        source: str,
        map_version_id: int,
        failure_reason: str | None = None,
    ) -> DatasetVersion:
        """데이터 마감 시각에서 ``dataset_id``를 만들어 자료 버전을 구성한다."""
        return cls(
            dataset_id=build_dataset_id(data_deadline),
            data_deadline=data_deadline,
            collected_at=collected_at,
            last_success_at=last_success_at,
            complete=complete,
            is_sample=is_sample,
            source=source,
            map_version_id=map_version_id,
            failure_reason=failure_reason,
        )

    def to_row(self) -> dict[str, Any]:
        """``dataset_version`` INSERT에 쓸 값 묶음. 열 이름은 마이그레이션과 같다."""
        row: dict[str, Any] = {
            "dataset_id": self.dataset_id,
            "data_deadline": self.data_deadline,
            "collected_at": self.collected_at,
            "last_success_at": self.last_success_at,
            "complete": self.complete,
            "failure_reason": self.failure_reason,
            "is_sample": self.is_sample,
            "source": self.source,
            "map_version_id": self.map_version_id,
        }
        if tuple(row) != DATASET_VERSION_COLUMNS:
            raise DatasetVersionError(
                f"dataset_version 열 목록이 DATASET_VERSION_COLUMNS와 어긋난다: {tuple(row)}"
            )
        return row
