"""원천 어댑터 공통 규약 — 재시도·포기·요청 분할·응답 구조 검증 (spec 008 FR-011b).

네 원천은 인증도 응답 형식도 한도도 다르지만 **실패를 다루는 방식은 같아야 한다.** 어댑터마다
제각각 재시도하면 한 곳은 영영 매달리고 다른 곳은 한 번 만에 포기한다.

**다시 걸어 볼 가치가 있는 실패와 그렇지 않은 실패를 가른다.** 끊긴 연결이나 5xx 는 기다렸다
다시 걸면 되지만, 인증 거부와 없는 경로는 몇 번을 걸어도 같은 답이 온다 — 거기에 재시도를 붙이면
원천에 부담만 주고 실패를 늦게 알게 된다. 관세청에서 옛 주소를 부르면 인증키가 멀쩡해도
``SERVICE_KEY_IS_NOT_REGISTERED_ERROR`` 가 오는데(pd11 §4), 이걸 일시 오류로 읽고 되풀이하면
원인이 주소라는 사실이 영영 드러나지 않는다.

**응답 구조가 등록표와 다르면 그 자리에서 멈춘다.** 원천이 항목 코드나 필드 이름을 바꾸면 값이
비거나 다른 계열이 조용히 섞이는데, 그건 결측보다 나쁘다 — 결측은 드러나지만 다른 계열의 값은
그럴듯해 보인다.
"""

from __future__ import annotations

import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any, Final, TypeVar

from data_pipeline.macro.config import SourceConfig

T = TypeVar("T")


class SourceError(RuntimeError):
    """원천 호출이 실패했다."""


class TransientSourceError(SourceError):
    """다시 걸어 볼 만한 실패 — 끊긴 연결, 시간 초과, 5xx."""


class PermanentSourceError(SourceError):
    """다시 걸어도 같은 답이 오는 실패 — 인증 거부, 없는 경로, 잘못된 인자."""


class QuotaExhaustedError(SourceError):
    """호출 한도에 닿았다. 재시도하지 않고 그 시점까지를 남긴다 (FR-011)."""


class SourceContractError(SourceError):
    """응답 구조가 등록표가 아는 모양과 다르다 (FR-011b)."""


def fetch_with_retry(
    call: Callable[[], T],
    config: SourceConfig,
    *,
    sleep: Callable[[float], None] = time.sleep,
) -> T:
    """``call`` 을 설정된 횟수만큼 다시 시도한다.

    간격은 :meth:`SourceConfig.backoff_for` 가 정한다(기본 1·2·4초). 마지막 시도가 실패하면
    마지막 오류를 그대로 올린다 — 삼키고 빈 값을 돌려주면 수집이 성공한 것처럼 보인다.
    """
    last_error: TransientSourceError | None = None
    for attempt in range(1, config.max_attempts + 1):
        try:
            return call()
        except TransientSourceError as error:
            last_error = error
            if attempt == config.max_attempts:
                break
            sleep(config.backoff_for(attempt))
    raise SourceError(
        f"{config.source_id} 호출이 {config.max_attempts}회 모두 실패했다"
    ) from last_error


@dataclass(frozen=True)
class SourceObservation:
    """원천이 준 관측값 하나. **변환 전이다.**

    ``period`` 는 원천의 주기를 그대로 쓴다 — 일별 계열은 ``YYYY-MM-DD``, 월별 계열은
    ``YYYY-MM`` 이다. 일별을 여기서 월로 접지 않는 이유는 월 기말·월 평균이 어느 날을 골랐는지
    되짚을 수 있어야 하기 때문이다(FR-006a, T029 계보).

    ``value`` 가 ``None`` 이면 원천이 그 시점을 비워 보냈다는 뜻이다. 0 으로 채우지 않는다 —
    없는 값과 0 은 다르고, 섞이면 증가율이 조용히 틀린다.

    ``source_updated_at`` 은 원천이 밝힌 **통계표 갱신일**이지 관측치별 발표일이 아니다
    (FR-007). 이 자리를 수신 시각으로 채우지 않는다.
    """

    indicator_id: str
    period: str
    value: Decimal | None
    unit: str | None = None
    #: 원천이 관측치별로 밝힌 발표 시각. 제공하지 않으면 ``None`` 이다.
    released_at: datetime | None = None
    source_updated_at: str | None = None


@dataclass(frozen=True)
class MonthWindow:
    """요청 하나가 담는 기간. ``start`` 와 ``end`` 는 그 달 1일이다."""

    start: date
    end: date


#: 요청 하나에 담을 수 있는 개월 수. 관세청은 조회 기간이 1년으로 제한된다(pd11 §4).
MAX_MONTHS_CUSTOMS: Final[int] = 12


def split_months(start: date, end: date, max_months: int) -> list[MonthWindow]:
    """``start``~``end`` 를 요청 상한에 맞춰 나눈다 (FR-017a).

    상한이 있는 원천에서 전 구간을 한 번에 부르면 자료가 오지 않는다. 관세청은 1989-01 부터라
    연 단위로 37회 이상 나눠 불러야 한다.
    """
    if max_months < 1:
        raise ValueError(f"요청당 개월 수는 1 이상이어야 한다: {max_months}")
    if start > end:
        raise ValueError(f"시작이 끝보다 늦다: {start} > {end}")

    windows: list[MonthWindow] = []
    cursor = date(start.year, start.month, 1)
    final = date(end.year, end.month, 1)
    while cursor <= final:
        months_ahead = max_months - 1
        year = cursor.year + (cursor.month - 1 + months_ahead) // 12
        month = (cursor.month - 1 + months_ahead) % 12 + 1
        window_end = min(date(year, month, 1), final)
        windows.append(MonthWindow(start=cursor, end=window_end))
        cursor = date(
            window_end.year + (1 if window_end.month == 12 else 0),
            1 if window_end.month == 12 else window_end.month + 1,
            1,
        )
    return windows


#: 원천이 "값 없음"을 적는 방식. 네 곳이 제각각이라 한자리에 모은다.
_BLANK_VALUES: Final[frozenset[str]] = frozenset({"", "-", "...", "..", "X", "NA", "N/A", "null"})


def parse_decimal(raw: object, context: str) -> Decimal | None:
    """원천의 값 문자열을 :class:`~decimal.Decimal` 로 바꾼다. 빈 값은 ``None`` 이다.

    **float 을 거치지 않는다.** 주택가격 지수는 소수 열째 자리까지 오는데(``100.3539736``)
    float 으로 받으면 연결 계수를 곱하는 과정에서 끝자리가 흔들리고, 같은 자료로 다시 돌려도
    값이 미세하게 달라진다. 재현성(SC-004)은 그런 데서 깨진다.

    숫자로 읽히지 않는 값은 ``None`` 으로 넘기지 않고 멈춘다 — 원천이 형식을 바꾼 것이라
    결측으로 조용히 묻으면 계열이 줄어든 이유를 나중에 되짚지 못한다.

    **``NaN`` 과 ``Infinity`` 도 멈춘다.** :class:`~decimal.Decimal` 은 이 둘을 정상적으로
    받아들이므로 그냥 두면 "값이 있는 관측"으로 통과해 버린다. 그러면 결측도 계약 위반도
    아닌 세 번째 상태가 생기는데, 그것이 가장 나쁘다 — 증가율 계산에 들어가면 결과도 ``NaN``
    이 되고 결측 사유는 비어 있으므로, 값이 있다고 적힌 관측이 실제로는 비교할 수 없는 값이
    된다. ``NaN`` 과의 비교는 모두 거짓이라 010 의 거리·순위가 조용히 어긋난다(원칙 VI).
    ``sNaN`` 은 더 나쁘다 — 산술에 닿는 순간 예외가 터져 파싱과 무관한 자리에서 실패한다.
    """
    if raw is None:
        return None
    text = str(raw).strip().replace(",", "")
    if text in _BLANK_VALUES:
        return None
    try:
        value = Decimal(text)
    except InvalidOperation:
        raise SourceContractError(
            f"{context}: 값을 숫자로 읽지 못했다({raw!r}). 원천이 형식을 바꿨는지 본다"
        ) from None
    if not value.is_finite():
        raise SourceContractError(
            f"{context}: 값이 유한한 수가 아니다({raw!r})."
            " 결측으로 두지 않는다 — 원천이 이 자리에 무엇을 뜻하려 했는지 확인해야 한다"
        )
    return value


def require_mapping(payload: Any, context: str) -> Mapping[str, Any]:
    """응답이 매핑인지 본다. 아니면 구조가 바뀐 것이다."""
    if not isinstance(payload, Mapping):
        raise SourceContractError(
            f"{context}: 응답이 매핑이 아니다({type(payload).__name__})."
            " 원천 구조가 바뀌었는지 본다"
        )
    return payload


def require_keys(payload: Mapping[str, Any], keys: Sequence[str], context: str) -> None:
    """등록표가 아는 필드가 응답에 다 있는지 본다 (FR-011b).

    빠진 필드를 기본값으로 채우지 않는다. 그렇게 채운 값은 원천이 준 값과 구분되지 않는다.
    """
    missing = [key for key in keys if key not in payload]
    if missing:
        raise SourceContractError(
            f"{context}: 응답에 {', '.join(missing)} 이(가) 없다."
            f" 등록표가 아는 필드는 {', '.join(keys)} 다"
        )
