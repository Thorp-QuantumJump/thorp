"""공통 척도 — 연속 구간 판정과 중앙값·산포 (spec FR-005·FR-006).

척도는 질의 구간 직전 t-122~t-3의 최대 120개월에서 구한다. **연속 구간은 t-3에서 과거로
거슬러 세어 처음 결측을 만나는 곳까지다** — 구멍 건너편에 자료가 더 있어도 끌어오지 않는다.
그 구간이 60개월 미만이면 그 지표가 속한 분야(종합이면 종합)를 계산 불가로 처리한다.

산포 s는 MAD ÷ Φ⁻¹(0.75)다. MAD가 0이면 IQR ÷ (2·Φ⁻¹(0.75))를 쓰고(IQR은 선형 보간의
75·25백분위 차이), 그것도 0이면 계산 불가다. 보정 상수는 근사값을 적지 않고 실제로 계산한다
(research R5).

**이 함수가 만든 척도 하나를 질의와 모든 후보에 똑같이 적용한다**(FR-005, 원칙 IV). t-122보다
오래된 후보에도 마찬가지다 — 후보마다 제 척도로 재면 서로 다른 자로 잰 값을 견주게 된다.
후보월로 이 함수를 부르는 곳은 `era.py`의 **보조 설명**뿐이고(FR-017), 그 결과는 순위·거리에
들어가지 않는다.

**실패를 예외로 던지지 않고 값으로 돌려준다.** 부르는 쪽이 둘로 갈리기 때문이다 — 공통 척도
실패는 그 분야·종합을 계산 불가로 만들지만(FR-005·FR-006), 시대별 보조 척도 실패는 그 지표의
설명만 제한하고 사례·순위·거리는 그대로 둔다(FR-017, SC-011).

임의 epsilon·묵시적 클리핑·지표 몰래 제외를 하지 않는다(FR-007).
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from typing import Final

import numpy as np
from scipy.stats import norm

from data_pipeline.regime.macro.types import MonthlyValue, format_month, require_month

#: 척도 구간은 관측 종료월 기준 `[end-122, end-3]`이다 (spec FR-005).
WINDOW_START_OFFSET: Final[int] = 122
WINDOW_END_OFFSET: Final[int] = 3

#: 최대 120개월, 최소 60개월 연속 (spec FR-005).
WINDOW_MAX_MONTHS: Final[int] = 120
WINDOW_MINIMUM_MONTHS: Final[int] = 60

#: Φ⁻¹(0.75). **근사값 1.4826·1.349를 적지 않는다**(spec FR-005, research R5).
#: 근사값과는 다섯째 자리에서 갈리는데, 그 자리는 저장 자릿수(12)보다 위라 순위에 닿는다.
NORMAL_QUARTILE: Final[float] = float(norm.ppf(0.75))

#: 척도를 만들지 못한 사유. 값은 data-model.md §2.1의 `status_reason` 어휘를 그대로 쓴다.
SCALE_WINDOW_TOO_SHORT: Final[str] = "SCALE_WINDOW_TOO_SHORT"
SCALE_ZERO: Final[str] = "SCALE_ZERO"

#: 산포를 무엇으로 구했는가. 대체가 일어난 사실은 화면·기록에 남는다(spec FR-028).
METHOD_MAD: Final[str] = "MAD"
METHOD_IQR: Final[str] = "IQR"


def shift_month(month: date, back: int) -> date:
    """그 달에서 ``back``개월 전. 월은 그 달 1일로 나타낸다."""
    total = month.year * 12 + (month.month - 1) - back
    if total < 0:
        raise ValueError(f"달을 너무 많이 거슬렀다: {format_month(month)} - {back}")
    return date(total // 12, total % 12 + 1, 1)


@dataclass(frozen=True)
class Scale:
    """지표 하나의 공통 척도.

    ``window_end``·``window_start``는 실제로 쓴 연속 구간이다. 요청한 구간이 아니라 **쓴**
    구간을 남기는 이유는, 구멍 때문에 짧아진 구간으로 계산했다는 사실이 기록에 있어야
    나중에 값을 대조할 수 있기 때문이다(spec FR-028).
    """

    indicator_id: str
    observation_end: date
    median: float
    spread: float
    method: str
    window_start: date
    window_end: date
    window_length: int

    @property
    def available(self) -> bool:
        return True

    def standardize(self, value: float) -> float:
        """``(x − m) / s``. 질의와 모든 후보가 **이 하나의 척도**를 쓴다 (spec FR-005·FR-009).

        후보의 관측월이 이 척도 구간 밖이어도 그대로 적용한다 — 구간은 척도를 만든 자리이지
        후보를 고르는 범위가 아니다(FR-012).
        """
        return (value - self.median) / self.spread


@dataclass(frozen=True)
class ScaleUnavailable:
    """척도를 만들지 못했다. ``reason``은 data-model §2.1의 사유다.

    부르는 쪽이 이 값을 어떻게 다룰지는 자리마다 다르다 — 공통 척도면 그 분야·종합이 계산
    불가이고(FR-005·FR-006), 시대별 보조 척도면 그 지표의 설명만 빠진다(FR-017).
    """

    indicator_id: str
    observation_end: date
    reason: str
    window_length: int

    @property
    def available(self) -> bool:
        return False


ScaleResult = Scale | ScaleUnavailable


def continuous_window(
    series: Mapping[date, MonthlyValue], observation_end: date
) -> list[tuple[date, float]]:
    """``end-3``에서 과거로 거슬러 **처음 결측을 만나는 곳까지** (spec FR-005).

    최대 120개월에서 멈춘다. 구멍 건너편에 자료가 더 있어도 끌어오지 않는다 — 통계 개편
    전후처럼 성격이 다른 값이 한 분포에 섞이고, 척도를 "질의 구간 직전"에서 구하라는 규칙의
    뜻이 사라진다.

    오래된 달이 앞에 오도록 뒤집어 돌려준다. 중앙값·분위수는 순서와 무관하지만, 구간의
    시작·끝을 기록할 때 뒤집힌 목록을 다시 읽는 실수를 없앤다.
    """
    require_month(observation_end, "관측 종료월")
    collected: list[tuple[date, float]] = []
    month = shift_month(observation_end, WINDOW_END_OFFSET)
    while len(collected) < WINDOW_MAX_MONTHS:
        entry = series.get(month)
        if entry is None:
            break
        collected.append((month, entry.value))
        month = shift_month(month, 1)
    collected.reverse()
    return collected


def _median_and_spread(values: list[float]) -> tuple[float, float, str]:
    """중앙값 m과 산포 s, 그리고 s를 무엇으로 구했는지.

    s가 0이면 대체할 것이 더 없다는 뜻이다 — 부르는 쪽이 `SCALE_ZERO`로 처리한다.
    """
    array = np.asarray(values, dtype=float)
    median = float(np.median(array))
    mad = float(np.median(np.abs(array - median)))
    if mad != 0.0:
        return median, mad / NORMAL_QUARTILE, METHOD_MAD
    # MAD가 0이면 절반 넘는 값이 중앙값과 같다는 뜻이다. 그래도 사분위가 벌어져 있으면
    # 그 폭으로 잰다(spec FR-006). 선형 보간은 numpy 기본이며 research R5가 지정했다.
    q1, q3 = np.percentile(array, [25, 75])
    iqr = float(q3 - q1)
    return median, iqr / (2 * NORMAL_QUARTILE), METHOD_IQR


def scale_for(
    indicator_id: str,
    observation_end: date,
    series: Mapping[date, MonthlyValue],
) -> ScaleResult:
    """지표 하나의 척도를 관측 종료월 직전 구간에서 만든다 (spec FR-005·FR-006).

    거리 계산에는 **질의 기준월 t로 만든 척도만** 쓴다. 후보월 c로 부르는 것은 `era.py`의
    보조 설명에만 허용한다(FR-017).
    """
    window = continuous_window(series, observation_end)
    if len(window) < WINDOW_MINIMUM_MONTHS:
        return ScaleUnavailable(
            indicator_id=indicator_id,
            observation_end=observation_end,
            reason=SCALE_WINDOW_TOO_SHORT,
            window_length=len(window),
        )

    median, spread, method = _median_and_spread([value for _, value in window])
    if spread == 0.0:
        # MAD도 IQR도 0이다. 여기서 임의 epsilon을 넣으면 순위가 만들어지지만 그 순위는
        # 자료가 아니라 상수가 만든 것이다(spec FR-007).
        return ScaleUnavailable(
            indicator_id=indicator_id,
            observation_end=observation_end,
            reason=SCALE_ZERO,
            window_length=len(window),
        )

    return Scale(
        indicator_id=indicator_id,
        observation_end=observation_end,
        median=median,
        spread=spread,
        method=method,
        window_start=window[0][0],
        window_end=window[-1][0],
        window_length=len(window),
    )
