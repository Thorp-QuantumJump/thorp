"""저장·비교 직전의 12자리 반올림 (research R4).

double로 계산한 값을 그대로 정렬하면 같은 입력의 재검색이 부동소수점 끝자리 때문에 다른
순위를 낼 수 있다. 저장 자릿수까지 반올림한 값으로 순위와 **정확한 동률**을 판정한다
(spec FR-012 — 근사 동률을 동률로 보지 않는다).

**한 번만 적용한다.** 중간 계산마다 반올림하면 오차가 반올림 경계에서 누적되고, 어느 단계의
값이 저장값인지도 흐려진다. 적용 자리는 두 곳뿐이다 — 순위·동률을 판정하기 직전(M4)과
저장하기 직전(M5)이다.

12자리인 이유는 거리 값의 크기(z 차이의 평균, 대개 0~5)에 비해 충분히 아래이고 double의
유효자릿수(약 15~17자리) 안이기 때문이다(research R4).

반올림 방식은 **사사오입**(`ROUND_HALF_UP`)이다. 파이썬 내장 `round()`는 짝수로 붙이는
방식이라 수기 대조(SC-003)에서 사람이 계산한 값과 갈릴 수 있다. 경계에 정확히 걸리는 일은
드물지만, 드문 일이 순위를 뒤집으면 원인을 찾기 어렵다.
"""

from __future__ import annotations

import math
from decimal import ROUND_HALF_UP, Decimal
from typing import Final

#: 저장 자릿수 (research R4). 바꾸면 순위·동률 판정이 달라지므로 **새 모델 버전**이다.
STORAGE_DECIMALS: Final[int] = 12

_QUANTUM: Final[Decimal] = Decimal(1).scaleb(-STORAGE_DECIMALS)


class RoundingError(ValueError):
    """반올림할 수 없는 값이다."""


def _quantize(value: float) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise RoundingError(f"수가 아니다: {value!r}")
    number = float(value)
    if math.isnan(number) or math.isinf(number):
        # 계산 실패를 값으로 바꾸지 않는다. 여기서 막지 않으면 NaN이 저장값이 되고,
        # 정렬에서 조용히 뒤로 밀려 "후보가 있는데 안 보인다"가 된다(원칙 VI).
        raise RoundingError(f"NaN·무한대는 저장할 수 없다: {value!r}")
    # Decimal(float)은 그 double의 정확한 2진 전개다. repr을 거치지 않는 이유는 경계 판정을
    # 실제 값으로 하기 위해서다 — 짧은 표기로 바꾸면 없던 경계가 생긴다.
    return Decimal(number).quantize(_QUANTUM, rounding=ROUND_HALF_UP)


def round_for_storage(value: float) -> float:
    """순위·동률 판정에 쓸 12자리 반올림 값."""
    return float(_quantize(value))


def to_storage_text(value: float) -> str:
    """저장·응답에 실을 문자열 decimal (research R4).

    `NUMERIC` 컬럼을 쓰지 않는 이유는 scale을 임의로 정하고 넘는 값을 조용히 반올림하기
    때문이다. 문자열로 두면 저장된 자릿수가 눈에 보인다.
    """
    return str(_quantize(value))


def equal_at_storage(left: float, right: float) -> bool:
    """저장 자릿수까지 같은가 — **정확한 동률** 판정 (spec FR-012).

    근사 동률을 동률로 보지 않는다. 눈으로 같아 보이는 두 거리에 '오래된 월 우선'을 적용하면
    그 규칙이 언제 적용되는지 아무도 예측할 수 없다.
    """
    return _quantize(left) == _quantize(right)
