"""공시 대조와 원인별 처리 (spec 001 FR-016·FR-017, SC-002).

## 표본을 왜 세 날짜로 한정하는가

거래소가 원본인 값을 거래소와 대조하는 일은 실익이 없다. 그래서 표본은 **값이 틀어지기 쉬운
날**만이다: 상장 첫날, 모든 분배금 **지급일**, 모든 분할일(락일)이다. 평범한 거래일은 넣지
않는다 (FR-016).

분배금이 지급일인 까닭은 FR-016이 "모든 분배금 지급일"이라고 적었고, 004 계약이 락일
(``ex_date``)은 조정 계수 산출에, 지급일(``pay_date``)은 현금 반영에 쓰는 **다른 날**로
구분해 두었기 때문이다. 두 날짜를 섞으면 공시에서 찾아볼 날이 달라져 대조가 헛돈다.

## 불일치를 만나도 값을 고치지 않는다 (FR-017)

이 모듈에는 개별 값을 고치는 경로가 없다. 원인에 따라 처리가 갈리고 조합은 네 쌍뿐이다.

| ``cause`` | ``resolution`` | 뜻 |
|---|---|---|
| ``MATCH`` | ``NONE`` | 일치했다 |
| ``PIPELINE_ERROR`` | ``REPUBLISHED`` | 수집·가공을 바로잡아 **새 자료 버전으로 재발행**한다 |
| ``DEFINITION_DIFF`` | ``RECORDED_ONLY`` | 값의 정의가 달라 생긴 차이라 기록만 남긴다 |
| ``UNKNOWN`` | ``RANGE_EXCLUDED`` | 원인을 못 찾았으니 그 구간을 분석 범위에서 뺀다 |

``cause`` 하나에서 ``resolution``을 유도하는 이유(:func:`resolution_for`): 두 값을 각각 받으면
"원인 불명인데 재발행" 같은 어긋난 조합을 만들 여지가 생기고, 그 조합을 막는 검사와 유도가 같은
지식을 두 곳에 두게 된다. 마이그레이션의 ``ck_reconciliation_record_cause_resolution``이 같은
네 쌍을 강제하지만, DB가 돌려주는 것은 제약 이름뿐이라 여기서 먼저 뜻이 분명한 오류를 낸다.

## 분류하지 않은 불일치의 기본값은 ``UNKNOWN``이다

원인 판정은 자동으로 되지 않는다. 사람이 "이 항목은 정의 차이다"·"이 항목은 수집 오류다"라고
분류해 :func:`reconcile` 에 넘긴 것만 그렇게 기록하고, 나머지는 **가장 보수적인** ``UNKNOWN``
(구간 제외)이다. 분류하지 않은 불일치를 ``DEFINITION_DIFF``로 넘기면 "정의 차이라서 괜찮다"고
조용히 넘어가게 된다.

## 대조하지 않은 표본을 건너뛰지 않는다 (SC-002)

공시값을 못 구한 표본은 "대조를 하지 않은" 것이다. 조용히 건너뛰면 SC-002 판정의 분모가 줄어
"전부 일치"로 보인다. 그래서 :class:`ReconciliationOutcome` 이 그 표본을 따로 담고,
:func:`sc002_verdict` 는 하나라도 있으면 판정을 거짓으로 둔다.
"""

from __future__ import annotations

import numbers
import re
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from math import isfinite
from typing import Any, Final

import numpy as np

from data_pipeline.corporate_actions import CorporateAction
from data_pipeline.gaps import REASON_RECONCILIATION_UNKNOWN, validate_gap_rows
from data_pipeline.krx import ListingWindow, windows_by_instrument

#: 종목 식별자. ``$``는 끝에 붙은 줄바꿈 앞에서도 맞으므로 ``\A...\Z``로 닫는다.
INSTRUMENT_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9]{6}\.XKRX\Z")

#: ``reconciliation_record.sample_reason``. 마이그레이션의 CHECK와 같은 세 값뿐이다 (FR-016).
SAMPLE_LISTING: Final[str] = "LISTING"
SAMPLE_DISTRIBUTION: Final[str] = "DISTRIBUTION"
SAMPLE_SPLIT: Final[str] = "SPLIT"
SAMPLE_REASONS: Final[tuple[str, ...]] = (SAMPLE_LISTING, SAMPLE_DISTRIBUTION, SAMPLE_SPLIT)

#: ``reconciliation_record.cause``.
CAUSE_MATCH: Final[str] = "MATCH"
CAUSE_PIPELINE_ERROR: Final[str] = "PIPELINE_ERROR"
CAUSE_DEFINITION_DIFF: Final[str] = "DEFINITION_DIFF"
CAUSE_UNKNOWN: Final[str] = "UNKNOWN"

#: ``reconciliation_record.resolution``.
RESOLUTION_NONE: Final[str] = "NONE"
RESOLUTION_REPUBLISHED: Final[str] = "REPUBLISHED"
RESOLUTION_RECORDED_ONLY: Final[str] = "RECORDED_ONLY"
RESOLUTION_RANGE_EXCLUDED: Final[str] = "RANGE_EXCLUDED"

#: FR-017의 처리 규칙. 이 매핑이 원본이고 다른 조합은 만들 수 없다.
CAUSE_RESOLUTION: Final[Mapping[str, str]] = {
    CAUSE_MATCH: RESOLUTION_NONE,
    CAUSE_PIPELINE_ERROR: RESOLUTION_REPUBLISHED,
    CAUSE_DEFINITION_DIFF: RESOLUTION_RECORDED_ONLY,
    CAUSE_UNKNOWN: RESOLUTION_RANGE_EXCLUDED,
}

#: 대조 항목 이름. :func:`build_queried_values` 가 채우는 것과 공시 파일이 써야 하는 이름이 같다.
FIELD_LISTED_ON: Final[str] = "listed_on"
FIELD_AMOUNT: Final[str] = "amount"
FIELD_PAY_DATE: Final[str] = "pay_date"
FIELD_EX_DATE: Final[str] = "ex_date"

#: ``reconciliation_record``에서 이 모듈이 채우는 열. ``id``는 IDENTITY이고
#: ``dataset_version_id``·``etf_id``는 적재 계층이 식별자를 찾아 채운다
#: (:meth:`data_pipeline.repository.MarketDataRepository.load_reconciliation_records`).
RECONCILIATION_RECORD_VALUE_COLUMNS: Final[tuple[str, ...]] = (
    "target_date",
    "sample_reason",
    "field_name",
    "queried_value",
    "disclosed_value",
    "difference",
    "cause",
    "resolution",
    "checked_at",
)

#: 표본 하나를 가리키는 키. 사유까지 넣는 이유는 같은 종목·같은 날짜에 사유가 둘일 수 있고
#: (분배금 지급일과 분할일이 겹칠 수 있다) 사유마다 대조할 항목이 다르기 때문이다.
#: 표본 하나를 가리키는 키. 마지막 칸은 **그 표본을 만든 기업행위의 배당락일**이며 상장
#: 첫날 표본에는 없다(``None``). 이 칸이 없으면 배당락일이 다르고 지급일이 같은 분배금 둘이
#: 한 표본으로 합쳐진다. 금액까지 같으면 조용히 합쳐져 기업행위 하나가 대조되지 않은 채
#: "표본을 모두 대조했다"는 판정이 나오고, 금액이 다르면 대조 자체가 멈춘다. 둘 다 FR-016의
#: "모든 분배금 지급일"을 어긴다.
SampleKey = tuple[str, date, str, date | None]


class ReconciliationError(ValueError):
    """공시 대조 규칙 위반. 값이나 분류를 고쳐 다시 대조해야 한다."""


def resolution_for(cause: str) -> str:
    """원인에서 처리를 유도한다 (FR-017). 아는 원인이 아니면 거부한다."""
    if not isinstance(cause, str) or cause not in CAUSE_RESOLUTION:
        raise ReconciliationError(f"cause는 {sorted(CAUSE_RESOLUTION)} 중 하나여야 한다: {cause!r}")
    return CAUSE_RESOLUTION[cause]


# ---------------------------------------------------------------------------
# 값 다루기
# ---------------------------------------------------------------------------


def _as_decimal(text: str) -> Decimal | None:
    """수로 읽을 수 있으면 :class:`Decimal` 로 돌려준다. 아니면 ``None``이다.

    ``Decimal("nan")``·``Decimal("Infinity")``는 문자열로는 읽히지만 비교할 수 있는 수가
    아니므로 수로 보지 않는다.
    """
    try:
        value = Decimal(text.strip())
    except (InvalidOperation, ValueError):
        return None
    return value if value.is_finite() else None


def value_text(value: object) -> str:
    """대조에 쓸 표준 문자열. 저장 열이 ``text``라 조회값·공시값을 문자열로 둔다.

    ``numpy`` 스칼라도 받는다 — ``numpy.int64``는 ``int``의 하위형이 아니어서 형만 보면
    거부되는데, 값은 같고 상자만 다르다. ``Decimal``은 지수 표기를 쓰지 않는다
    (``1E+2``로 적히면 사람이 공시와 눈으로 맞춰 볼 수 없다).
    """
    if value is None:
        raise ReconciliationError("대조할 값이 없다. 없는 값은 지어내지 않는다")
    if isinstance(value, bool | np.bool_):
        return "true" if bool(value) else "false"
    if isinstance(value, Decimal):
        if not value.is_finite():
            raise ReconciliationError(f"유한하지 않은 값은 대조할 수 없다: {value!r}")
        return format(value, "f")
    if isinstance(value, datetime):
        return value.isoformat()
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, str):
        text = value.strip()
        if not text:
            raise ReconciliationError("대조할 값이 비어 있다")
        return text
    if isinstance(value, numbers.Integral):
        return str(int(value))
    if isinstance(value, float):
        if not isfinite(value):
            raise ReconciliationError(f"유한하지 않은 값은 대조할 수 없다: {value!r}")
        return repr(value)
    raise ReconciliationError(f"대조할 값의 형을 알 수 없다: {type(value).__name__}")


def values_equal(queried: str, disclosed: str) -> bool:
    """조회값과 공시값이 같은 값인지 본다.

    둘 다 수로 읽히면 **수로** 비교한다. ``120``과 ``120.00000000``은 자리수 표기만 다르고
    같은 값인데, 문자열로만 비교하면 불일치가 되어 원인 불명(구간 제외)으로 떨어진다. 수가
    아닌 값(날짜·이름)은 표준 문자열이 같아야 일치다.
    """
    if queried == disclosed:
        return True
    left = _as_decimal(queried)
    right = _as_decimal(disclosed)
    if left is None or right is None:
        return False
    return left == right


def _difference_text(queried: str, disclosed: str) -> str:
    """``difference`` 열에 남길 설명. 수끼리는 차이를, 아니면 두 값을 적는다."""
    left = _as_decimal(queried)
    right = _as_decimal(disclosed)
    if left is not None and right is not None:
        return f"{queried} - {disclosed} = {format(left - right, 'f')}"
    return f"{queried} ≠ {disclosed}"


def _is_missing(value: object) -> bool:
    """값이 없는가. 공백만 든 문자열도 없는 것으로 본다(공시를 못 옮긴 칸이다)."""
    if value is None:
        return True
    return isinstance(value, str) and not value.strip()


def _require_instrument_id(value: object) -> str:
    if not isinstance(value, str) or INSTRUMENT_ID_PATTERN.match(value) is None:
        raise ReconciliationError(f"종목 식별자가 {{6자리}}.XKRX 형식이 아니다: {value!r}")
    return value


def _require_sample_date(value: object, field_name: str) -> date:
    """표본 날짜는 거래일이라 시각을 담지 않는다 (저장 열이 ``date``다)."""
    if isinstance(value, datetime) or not isinstance(value, date):
        raise ReconciliationError(f"{field_name}은(는) 시각 없는 date여야 한다: {value!r}")
    return value


def _require_aware(value: object, field_name: str) -> datetime:
    if not isinstance(value, datetime):
        raise ReconciliationError(f"{field_name}은(는) datetime이어야 한다: {value!r}")
    if value.tzinfo is None or value.tzinfo.utcoffset(value) is None:
        raise ReconciliationError(
            f"{field_name}은(는) 시간대가 붙은 datetime이어야 한다 (저장 열이 timestamptz다): "
            f"{value!r}"
        )
    return value


# ---------------------------------------------------------------------------
# 표본 구성
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ReconciliationTarget:
    """대조 표본 하나. 종목·날짜·사유가 표본의 신원이다 (FR-016)."""

    instrument_id: str
    target_date: date
    sample_reason: str
    #: 이 표본을 만든 기업행위의 배당락일. 상장 첫날 표본에는 없다. 저장 열이 아니라
    #: 표본을 구분하는 값이며, ``reconciliation_record``에는 들어가지 않는다.
    event_ex_date: date | None = None

    def __post_init__(self) -> None:
        _require_instrument_id(self.instrument_id)
        _require_sample_date(self.target_date, "target_date")
        if self.sample_reason not in SAMPLE_REASONS:
            raise ReconciliationError(
                f"sample_reason은 {list(SAMPLE_REASONS)} 중 하나여야 한다: {self.sample_reason!r}"
            )
        if self.event_ex_date is not None:
            _require_sample_date(self.event_ex_date, "event_ex_date")
            if self.sample_reason == SAMPLE_LISTING:
                raise ReconciliationError(
                    f"상장 첫날 표본에는 배당락일이 없다: {self.instrument_id} {self.target_date}"
                )
            if self.event_ex_date > self.target_date:
                raise ReconciliationError(
                    f"배당락일이 표본 날짜보다 뒤다: {self.instrument_id} "
                    f"{self.event_ex_date} > {self.target_date}"
                )

    @property
    def key(self) -> SampleKey:
        return (
            self.instrument_id,
            self.target_date,
            self.sample_reason,
            self.event_ex_date,
        )


def build_samples(
    windows: Iterable[ListingWindow],
    actions: Iterable[CorporateAction] = (),
) -> tuple[ReconciliationTarget, ...]:
    """상장 구간과 기업행위에서 대조 표본을 만든다 (FR-016).

    상장 첫날은 ``window.listed_on``, 분배금은 ``pay_date``(지급일), 분할은 ``ex_date``다.
    평범한 거래일은 표본이 아니므로 거래일 달력을 아예 받지 않는다.

    상장 구간이 없는 종목의 기업행위는 조용히 버리지 않고 거부한다. 대표 ETF가 아닌 종목이
    섞였거나 대응표가 빠진 것이고, 버리면 표본이 줄어든 사실이 드러나지 않는다.
    """
    # 한 번만 훑는다. 발생기(generator)를 두 번 돌면 두 번째는 비어 있어 표본이 조용히 준다.
    listed = list(windows)
    for window in listed:
        if not isinstance(window, ListingWindow):
            raise ReconciliationError(
                f"상장 구간은 ListingWindow여야 한다: {type(window).__name__}"
            )
    mapped = windows_by_instrument(listed)

    targets: dict[SampleKey, ReconciliationTarget] = {}
    for window in mapped.values():
        target = ReconciliationTarget(window.instrument_id, window.listed_on, SAMPLE_LISTING)
        targets[target.key] = target

    for action in actions:
        if not isinstance(action, CorporateAction):
            raise ReconciliationError(
                f"기업행위는 CorporateAction이어야 한다: {type(action).__name__}"
            )
        window = mapped.get(action.instrument_id)
        if window is None:
            raise ReconciliationError(
                f"상장 구간이 없는 종목의 기업행위다: {action.instrument_id} {action.ex_date}. "
                "표본을 줄이지 않으려면 대응표의 상장 구간을 함께 넘겨야 한다"
            )
        if action.is_distribution:
            # FR-016이 "모든 분배금 지급일"이라고 적었다. 락일과 지급일은 다른 날이다.
            sample_date = action.pay_date
            sample_reason = SAMPLE_DISTRIBUTION
        else:
            sample_date = action.ex_date
            sample_reason = SAMPLE_SPLIT
        if sample_date is None:  # pragma: no cover - CorporateAction이 이미 막는다
            raise ReconciliationError(f"분배금에 지급일이 없다: {action.instrument_id}")
        if sample_date < window.listed_on:
            raise ReconciliationError(
                f"상장 전 기업행위다: {action.instrument_id} {sample_date} "
                f"< listed_on {window.listed_on}"
            )
        target = ReconciliationTarget(
            action.instrument_id, sample_date, sample_reason, action.ex_date
        )
        targets[target.key] = target

    return tuple(sorted(targets.values(), key=lambda item: item.key))


def build_queried_values(
    windows: Iterable[ListingWindow],
    actions: Iterable[CorporateAction] = (),
) -> dict[SampleKey, dict[str, str]]:
    """표본마다 대조할 **조회값**을 파이프라인 입력에서 뽑는다.

    상장일은 대응표(``representative_etf``)가, 분배금·분할은 운용사 공시를 옮긴 기업행위
    입력이 원본이다. 상장 첫날 **가격**은 원본이 ``daily_price``인데 이 파트의 적재 방향은
    ``raw → processed → PostgreSQL`` 한 방향뿐이라 DB에서 읽어 오지 않는다. 그래서 여기서는
    가격을 채우지 않고, 가격까지 대조하려면 부르는 쪽이 그 항목을 더한다.
    """
    values: dict[SampleKey, dict[str, str]] = {}
    for window in windows:
        if not isinstance(window, ListingWindow):
            raise ReconciliationError(
                f"상장 구간은 ListingWindow여야 한다: {type(window).__name__}"
            )
        key = (window.instrument_id, window.listed_on, SAMPLE_LISTING, None)
        values[key] = {FIELD_LISTED_ON: value_text(window.listed_on)}

    for action in actions:
        if not isinstance(action, CorporateAction):
            raise ReconciliationError(
                f"기업행위는 CorporateAction이어야 한다: {type(action).__name__}"
            )
        if action.is_distribution:
            key = (
                action.instrument_id,
                action.pay_date,
                SAMPLE_DISTRIBUTION,
                action.ex_date,
            )
            entry = {
                FIELD_AMOUNT: value_text(action.amount),
                FIELD_PAY_DATE: value_text(action.pay_date),
                FIELD_EX_DATE: value_text(action.ex_date),
            }
        else:
            key = (action.instrument_id, action.ex_date, SAMPLE_SPLIT, action.ex_date)
            entry = {
                FIELD_AMOUNT: value_text(action.amount),
                FIELD_EX_DATE: value_text(action.ex_date),
            }
        if key in values and values[key] != entry:
            # 표본 하나에 조회값이 둘이면 어느 값을 공시와 맞출지 정해지지 않는다.
            raise ReconciliationError(
                f"같은 표본에 조회값이 두 벌 있다: {key}. 기업행위 입력을 확인해야 한다"
            )
        values[key] = entry
    return values


def listing_windows_from_records(records: Iterable[Mapping[str, Any]]) -> list[ListingWindow]:
    """사전 목록을 :class:`ListingWindow` 로 바꾼다.

    상장일은 원천에서 받을 수 없어 대응표에서 오고(``data_pipeline.krx`` 문서 5번), 그 값이
    파일로 들어오든 대응표에서 오든 같은 규칙으로 검사하려고 변환을 한 곳에 둔다.
    :func:`data_pipeline.corporate_actions.corporate_actions_from_records` 와 같은 관례다.
    """
    windows: list[ListingWindow] = []
    for position, record in enumerate(records):
        if not isinstance(record, Mapping):
            raise ReconciliationError(
                f"{position}번째 상장 구간이 매핑이 아니다: {type(record).__name__}"
            )
        missing = sorted({"instrument_id", "listed_on"} - set(record))
        if missing:
            raise ReconciliationError(f"{position}번째 상장 구간에 없는 열: {missing}")
        windows.append(
            ListingWindow(
                instrument_id=str(record["instrument_id"]),
                listed_on=record["listed_on"],
                delisted_on=record.get("delisted_on"),
            )
        )
    windows_by_instrument(windows)  # 같은 종목이 두 번 오면 여기서 거부된다
    return windows


def disclosed_values_from_records(
    records: Iterable[Mapping[str, Any]],
) -> dict[SampleKey, dict[str, str]]:
    """운용사 공시를 옮긴 사전 목록을 표본별 공시값으로 바꾼다.

    공시는 API로 받을 수 없어 사람이 옮긴 파일로 들어온다(``data_pipeline.krx`` 문서 5번).
    항목 이름은 조회값의 이름과 같아야 한다 — 이름이 다르면 두 값이 짝지어지지 않고 그 항목은
    "대조하지 않음"으로 남는다.
    """
    values: dict[SampleKey, dict[str, str]] = {}
    for position, record in enumerate(records):
        if not isinstance(record, Mapping):
            raise ReconciliationError(
                f"{position}번째 공시값이 매핑이 아니다: {type(record).__name__}"
            )
        missing = sorted({"instrument_id", "target_date", "sample_reason", "values"} - set(record))
        if missing:
            raise ReconciliationError(f"{position}번째 공시값에 없는 열: {missing}")
        # ``event_ex_date``는 선택이다. 지급일이 같은 분배금이 둘인 종목에서만 필요하고,
        # 없으면 조회값의 구분자와 짝이 맞지 않아 그 표본이 "대조하지 않음"으로 남는다.
        # 빠뜨린 것을 조용히 넘기지 않고 그렇게 드러나게 두는 편이 맞다.
        target = ReconciliationTarget(
            instrument_id=str(record["instrument_id"]),
            target_date=record["target_date"],
            sample_reason=str(record["sample_reason"]),
            event_ex_date=record.get("event_ex_date"),
        )
        raw_values = record["values"]
        if not isinstance(raw_values, Mapping) or not raw_values:
            raise ReconciliationError(
                f"{position}번째 공시값의 values가 비어 있다: {target.key}. "
                "공시값을 못 구했으면 그 표본을 빼지 않고 비운 채로 남긴다"
            )
        if target.key in values:
            raise ReconciliationError(f"같은 표본의 공시값이 두 번 들어 있다: {target.key}")
        values[target.key] = {str(name): value_text(value) for name, value in raw_values.items()}
    return values


# ---------------------------------------------------------------------------
# 대조 한 건
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ReconciliationRecord:
    """대조 기록 한 건. ``reconciliation_record`` 한 행에 대응한다.

    ``resolution``은 넘기지 않으면 ``cause``에서 유도한다. 인자로 받는 길을 남긴 이유는
    DB에서 읽어 온 값을 다시 담을 수 있어야 하기 때문이고, 넘긴 값이 유도값과 다르면 거부한다.
    """

    instrument_id: str
    target_date: date
    sample_reason: str
    field_name: str
    queried_value: str
    disclosed_value: str
    difference: str | None
    cause: str
    checked_at: datetime
    # 기본값이 있는 열을 뒤에 두면 ``checked_at``이 먼저 와야 하는데, 저장 열 순서는
    # resolution → checked_at이다. 키워드 전용으로 두면 선언 순서를 스키마에 맞출 수 있다.
    resolution: str | None = field(default=None, kw_only=True)

    def __post_init__(self) -> None:
        _require_instrument_id(self.instrument_id)
        _require_sample_date(self.target_date, "target_date")
        if self.sample_reason not in SAMPLE_REASONS:
            raise ReconciliationError(
                f"sample_reason은 {list(SAMPLE_REASONS)} 중 하나여야 한다: {self.sample_reason!r}"
            )
        if not isinstance(self.field_name, str) or not self.field_name.strip():
            raise ReconciliationError(f"field_name은 비어 있을 수 없다: {self.field_name!r}")
        for name in ("queried_value", "disclosed_value"):
            value = getattr(self, name)
            if not isinstance(value, str) or not value.strip():
                raise ReconciliationError(
                    f"{name}은(는) 비어 있을 수 없다 (저장 열이 NOT NULL이다): {value!r}. "
                    "값을 못 구한 표본은 기록을 만들지 않고 '대조하지 않음'으로 남긴다"
                )
        if self.difference is not None and not isinstance(self.difference, str):
            raise ReconciliationError(
                f"difference는 문자열이거나 None이어야 한다: {self.difference!r}"
            )
        expected = resolution_for(self.cause)
        if self.resolution is None:
            object.__setattr__(self, "resolution", expected)
        elif self.resolution != expected:
            raise ReconciliationError(
                f"cause와 resolution의 조합이 허용되지 않는다: {self.cause} → {self.resolution}. "
                f"{self.cause}의 처리는 {expected}뿐이다 (FR-017)"
            )
        # 일치·불일치와 값이 어긋나면 SC-002 판정이 조용히 틀린다. 양쪽을 다 막는다.
        same_value = values_equal(self.queried_value, self.disclosed_value)
        if self.cause == CAUSE_MATCH and not same_value:
            raise ReconciliationError(
                f"MATCH인데 조회값과 공시값이 다르다: {self.instrument_id} {self.target_date} "
                f"{self.field_name} 조회 {self.queried_value} ≠ 공시 {self.disclosed_value}"
            )
        if self.cause != CAUSE_MATCH and same_value:
            raise ReconciliationError(
                f"불일치로 기록되었는데 조회값과 공시값이 같다: {self.instrument_id} "
                f"{self.target_date} {self.field_name} {self.queried_value}"
            )
        _require_aware(self.checked_at, "checked_at")

    @property
    def target(self) -> ReconciliationTarget:
        return ReconciliationTarget(self.instrument_id, self.target_date, self.sample_reason)

    @property
    def matched(self) -> bool:
        return self.cause == CAUSE_MATCH

    def to_row(self) -> dict[str, Any]:
        """적재용 값 묶음. ``instrument_id``는 적재 계층이 ``etf_id``로 바꾼다."""
        row: dict[str, Any] = {
            "instrument_id": self.instrument_id,
            "target_date": self.target_date,
            "sample_reason": self.sample_reason,
            "field_name": self.field_name,
            "queried_value": self.queried_value,
            "disclosed_value": self.disclosed_value,
            "difference": self.difference,
            "cause": self.cause,
            "resolution": self.resolution,
            "checked_at": self.checked_at,
        }
        if tuple(row)[1:] != RECONCILIATION_RECORD_VALUE_COLUMNS:
            raise ReconciliationError(
                "reconciliation_record 열 목록이 RECONCILIATION_RECORD_VALUE_COLUMNS와 어긋난다: "
                f"{tuple(row)[1:]}"
            )
        return row


@dataclass(frozen=True)
class UnreconciledSample:
    """대조하지 못한 표본. 조회값이나 공시값이 없어 기록을 만들 수 없었던 것이다.

    건너뛰지 않고 남기는 이유: 공시값을 못 구한 것은 "일치"가 아니라 "대조하지 않음"이며,
    빼 버리면 SC-002 판정의 분모가 조용히 줄어든다.
    """

    target: ReconciliationTarget
    fields: tuple[str, ...]
    reason: str


# ---------------------------------------------------------------------------
# 대조와 그 결과
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class ReconciliationOutcome:
    """대조 결과 한 묶음과 그에서 갈라진 처리 (FR-017).

    ``republish_required``가 비어 있지 않으면 이 자료 버전을 그대로 쓸 수 없고 수집·가공을
    바로잡아 **새 ``dataset_id``로 재발행**해야 한다. 개별 값을 고치는 경로는 없다.

    ``excluded_ranges``는 ``RANGE_EXCLUDED``인 기록에서 만든 ``data_gap`` 행이다. 사유는
    ``RECONCILIATION_UNKNOWN``뿐이고 구간은 그 표본 날짜 하루다.
    """

    records: tuple[ReconciliationRecord, ...]
    republish_required: tuple[ReconciliationRecord, ...]
    excluded_ranges: tuple[dict[str, Any], ...]
    unreconciled: tuple[UnreconciledSample, ...] = ()
    targets: tuple[ReconciliationTarget, ...] = ()

    def __post_init__(self) -> None:
        expected_republish = tuple(
            record for record in self.records if record.cause == CAUSE_PIPELINE_ERROR
        )
        if self.republish_required != expected_republish:
            raise ReconciliationError(
                "republish_required는 PIPELINE_ERROR인 기록 전부여야 한다 (FR-017)"
            )
        expected_ranges = {
            (record.instrument_id, record.target_date)
            for record in self.records
            if record.resolution == RESOLUTION_RANGE_EXCLUDED
        }
        actual_ranges = {(row["instrument_id"], row["start_date"]) for row in self.excluded_ranges}
        if len(actual_ranges) != len(self.excluded_ranges):
            raise ReconciliationError(
                "같은 종목·같은 날짜의 자료 부족 구간이 여러 행으로 들어 있다. "
                "data_gap의 PK가 (dataset_id, instrument_id, start_date, reason)이라 적재가 깨진다"
            )
        if actual_ranges != expected_ranges:
            raise ReconciliationError(
                "excluded_ranges가 RANGE_EXCLUDED 기록과 맞지 않는다 (FR-017)"
            )

    @property
    def matched(self) -> tuple[ReconciliationRecord, ...]:
        return tuple(record for record in self.records if record.matched)

    @property
    def mismatched(self) -> tuple[ReconciliationRecord, ...]:
        return tuple(record for record in self.records if not record.matched)

    @property
    def cause_counts(self) -> dict[str, int]:
        counts = dict.fromkeys(CAUSE_RESOLUTION, 0)
        for record in self.records:
            counts[record.cause] += 1
        return counts

    @property
    def sample_counts(self) -> dict[str, int]:
        counts = dict.fromkeys(SAMPLE_REASONS, 0)
        for target in self.targets:
            counts[target.sample_reason] += 1
        return counts

    @property
    def unreconciled_samples(self) -> tuple[ReconciliationTarget, ...]:
        """대조하지 못한 항목이 하나라도 있는 표본. 같은 표본은 한 번만 센다."""
        seen: dict[SampleKey, ReconciliationTarget] = {}
        for item in self.unreconciled:
            seen.setdefault(item.target.key, item.target)
        return tuple(seen.values())


def reconcile(
    targets: Iterable[ReconciliationTarget],
    queried: Mapping[SampleKey, Mapping[str, Any]],
    disclosed: Mapping[SampleKey, Mapping[str, Any]],
    *,
    definition_diff_fields: Iterable[str] = frozenset(),
    pipeline_error_fields: Iterable[str] = frozenset(),
    checked_at: datetime,
) -> ReconciliationOutcome:
    """표본마다 조회값과 공시값을 대조해 기록을 만든다 (FR-016·FR-017).

    분류하지 않은 불일치의 원인은 ``UNKNOWN``이다. 두 분류 집합에 같은 항목이 들어 있으면
    어느 쪽으로 볼지 정해지지 않은 상태이므로 거부한다.
    """
    definition_diff = frozenset(definition_diff_fields)
    pipeline_error = frozenset(pipeline_error_fields)
    overlap = sorted(definition_diff & pipeline_error)
    if overlap:
        raise ReconciliationError(
            f"정의 차이와 수집·가공 오류에 같은 항목이 들어 있다: {overlap}. "
            "어느 쪽으로 분류할지 먼저 정해야 한다"
        )
    _require_aware(checked_at, "checked_at")

    ordered: list[ReconciliationTarget] = []
    seen_keys: set[SampleKey] = set()
    for target in targets:
        if not isinstance(target, ReconciliationTarget):
            raise ReconciliationError(
                f"표본은 ReconciliationTarget이어야 한다: {type(target).__name__}"
            )
        if target.key in seen_keys:
            raise ReconciliationError(f"같은 표본이 두 번 들어 있다: {target.key}")
        seen_keys.add(target.key)
        ordered.append(target)

    records: list[ReconciliationRecord] = []
    unreconciled: list[UnreconciledSample] = []
    for target in ordered:
        queried_values = queried.get(target.key)
        disclosed_values = disclosed.get(target.key)
        if not queried_values or not disclosed_values:
            missing_sides = [
                name
                for name, values in (("조회값", queried_values), ("공시값", disclosed_values))
                if not values
            ]
            unreconciled.append(
                UnreconciledSample(
                    target=target,
                    fields=(),
                    reason=f"{'과 '.join(missing_sides)}이 없어 대조하지 않았다",
                )
            )
            continue

        names = sorted(set(queried_values) | set(disclosed_values))
        unpaired: list[str] = []
        for name in names:
            queried_value = queried_values.get(name)
            disclosed_value = disclosed_values.get(name)
            if _is_missing(queried_value) or _is_missing(disclosed_value):
                unpaired.append(name)
                continue
            queried_text = value_text(queried_value)
            disclosed_text = value_text(disclosed_value)
            if values_equal(queried_text, disclosed_text):
                cause = CAUSE_MATCH
                difference = None
            else:
                cause = _classify(name, definition_diff, pipeline_error)
                difference = _difference_text(queried_text, disclosed_text)
            records.append(
                ReconciliationRecord(
                    instrument_id=target.instrument_id,
                    target_date=target.target_date,
                    sample_reason=target.sample_reason,
                    field_name=name,
                    queried_value=queried_text,
                    disclosed_value=disclosed_text,
                    difference=difference,
                    cause=cause,
                    checked_at=checked_at,
                )
            )
        if unpaired:
            unreconciled.append(
                UnreconciledSample(
                    target=target,
                    fields=tuple(unpaired),
                    reason="조회값과 공시값이 짝지어지지 않아 대조하지 않은 항목이 있다",
                )
            )

    return ReconciliationOutcome(
        records=tuple(records),
        republish_required=tuple(
            record for record in records if record.cause == CAUSE_PIPELINE_ERROR
        ),
        excluded_ranges=excluded_ranges_for(records),
        unreconciled=tuple(unreconciled),
        targets=tuple(ordered),
    )


def _classify(
    field_name: str, definition_diff: frozenset[str], pipeline_error: frozenset[str]
) -> str:
    """불일치의 원인을 가린다. 분류되지 않은 항목은 가장 보수적인 ``UNKNOWN``이다."""
    if field_name in definition_diff:
        return CAUSE_DEFINITION_DIFF
    if field_name in pipeline_error:
        return CAUSE_PIPELINE_ERROR
    return CAUSE_UNKNOWN


def excluded_ranges_for(
    records: Iterable[ReconciliationRecord],
) -> tuple[dict[str, Any], ...]:
    """``RANGE_EXCLUDED``인 기록에서 ``data_gap`` 행을 만든다 (FR-017).

    사유는 ``RECONCILIATION_UNKNOWN``뿐이고 구간은 표본 날짜 하루다
    (``start_date == end_date``). ``SUSPENDED_TOO_LONG``은 PD-19·PD-20 확정 전까지 쓰지
    않으므로 이 모듈은 그 값을 만들지 않는다.

    같은 종목·같은 날짜에 불일치가 여러 항목 생기면 행을 **하나로 합친다.** ``data_gap``의 PK가
    ``(dataset_id, instrument_id, start_date, reason)``이라 중복 행을 내놓으면 적재가 PK 위반으로
    깨지고, 그때는 절반만 들어간 표가 남는다.
    """
    merged: dict[tuple[str, date], dict[str, Any]] = {}
    for record in records:
        if record.resolution != RESOLUTION_RANGE_EXCLUDED:
            continue
        key = (record.instrument_id, record.target_date)
        merged.setdefault(
            key,
            {
                "instrument_id": record.instrument_id,
                "start_date": record.target_date,
                "end_date": record.target_date,
                "reason": REASON_RECONCILIATION_UNKNOWN,
            },
        )
    ordered = [merged[key] for key in sorted(merged)]
    return tuple(validate_gap_rows(ordered))


# ---------------------------------------------------------------------------
# SC-002 판정
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Sc002Verdict:
    """SC-002 판정 결과.

    SC-002는 "모든 표본이 공시와 일치하거나, 불일치 건마다 원인 분류와 처리가 대조 기록에
    남는다"다. 뒷조건은 :class:`ReconciliationRecord` 의 불변식상 자동으로 참이므로(조합이
    네 쌍으로 고정되어 있고 그 밖의 기록은 만들 수 없다), 판정의 실질은 **표본이 빠짐없이
    대조되었는가**다.
    """

    satisfied: bool
    sample_count: int
    reconciled_field_count: int
    matched: int
    mismatched: int
    cause_counts: Mapping[str, int]
    unreconciled_sample_count: int
    republish_required: bool
    detail: str


def sc002_verdict(outcome: ReconciliationOutcome) -> Sc002Verdict:
    """대조 결과가 SC-002를 충족하는지 판정한다."""
    if not isinstance(outcome, ReconciliationOutcome):
        raise ReconciliationError(
            f"판정 대상은 ReconciliationOutcome이어야 한다: {type(outcome).__name__}"
        )
    unreconciled = outcome.unreconciled_samples
    satisfied = not unreconciled
    if satisfied:
        detail = (
            f"표본 {len(outcome.targets)}건을 모두 대조했고 불일치 "
            f"{len(outcome.mismatched)}건마다 원인과 처리가 기록에 남았다"
        )
    else:
        shown = ", ".join(
            f"{target.instrument_id} {target.target_date} {target.sample_reason}"
            for target in unreconciled[:5]
        )
        detail = (
            f"대조하지 않은 표본이 {len(unreconciled)}건이다: {shown}. "
            "공시값을 못 구한 표본을 빼면 SC-002 판정의 분모가 줄어든다"
        )
    return Sc002Verdict(
        satisfied=satisfied,
        sample_count=len(outcome.targets),
        reconciled_field_count=len(outcome.records),
        matched=len(outcome.matched),
        mismatched=len(outcome.mismatched),
        cause_counts=outcome.cause_counts,
        unreconciled_sample_count=len(unreconciled),
        republish_required=bool(outcome.republish_required),
        detail=detail,
    )
