"""시대별 평소 대비 위치 — 상세의 보조 설명 (spec FR-017).

후보 종료월 c 직전 c-122~c-3에서 `scale`과 **같은 규칙으로** 그 시대의 중앙값·산포를 구하고,
후보 관측값을 그 자로 다시 재 "당시로서는 어땠는가"를 문장으로 서술한다.

두 가지를 지킨다.

1. **기본 순위·거리와 결합하지 않는다.** 이 값이 거리에 들어가면 같은 조건 비교(원칙 IV)가
   깨진다. 이력이 부족하거나(연속 60개월 미만) MAD·IQR이 모두 0이면 그 지표의 보조 설명만
   제한하고 사례·순위·거리는 그대로 둔다(SC-011).
2. **z 값·분위수·백분율을 숫자로 내보내지 않는다.** 숫자로 놓이면 유사도처럼 읽혀 FR-022가
   금지한 오독을 부른다. 문장은 `explain`과 같은 템플릿 조립이며 자유 생성하지 않는다.

이 척도는 같은 자료·등록표·모델 버전에서 **기준월 t와 무관**하므로 후보월마다 한 번만 구한다.

**t를 인자로 받지 않는다.** 재사용해도 되는 값이라는 말을 주석으로 적는 대신 t가 들어올 자리를
없앴다. 그래서 "같은 후보월의 보조 위치는 기준월이 달라도 같다"(SC-011)가 시험 결과가 아니라
함수 모양으로 성립한다 — 거리 쪽 z가 t마다 달라지는 것과 정반대 성질이며(research R3), 둘이
섞이기 가장 쉬운 자리다.

**돌려주는 값에 z가 없다.** 판정에는 z를 쓰지만 자료구조에 담지 않으므로, 화면이 언젠가
그것을 꺼내 쓸 수 없다. 수기 대조는 `method`·`window_start`·`window_end`·`months`로 한다 —
그 구간과 방법이 있으면 독립 계산으로 같은 판정을 다시 만들 수 있다(data-model §4).
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date
from typing import Final

from data_pipeline.regime.macro.types import IndicatorEntry, MonthlyValue, require_month
from data_pipeline.regime.scale import Scale, scale_for

#: |z|가 이 값보다 작으면 "비슷한"이다 (research R8).
#: **모델 버전 항목이다** — 바꾸면 같은 자료에서 다른 문장이 나오므로 새 버전이다(spec 모델 버전).
SIMILAR_THRESHOLD: Final[float] = 0.5

HIGH: Final[str] = "높은"
LOW: Final[str] = "낮은"
SIMILAR: Final[str] = "비슷한"

#: 문장 틀 (research R8). 값과 단위는 **변환 후 값**이다(spec FR-015).
SENTENCE_TEMPLATE: Final[str] = "{name} {value}{unit}{particle} 당시로서는 {verdict} 편이었습니다."


@dataclass(frozen=True)
class EraPosition:
    """지표 하나의 시대별 보조 위치.

    ``sentence``만 화면에 나간다. 나머지는 수기 대조용 산출 근거이며, 여기에 z나 분위수가
    없는 것이 FR-017을 지키는 방식이다.
    """

    indicator_id: str
    sentence: str
    method: str
    window_start: date
    window_end: date
    months: int


def _topic_particle(text: str) -> str:
    """앞 글자에 받침이 있으면 ``은``, 없으면 ``는``.

    R8의 틀은 ``는``으로 적혀 있고 예시 단위가 ``%``라 그대로 맞지만, 단위가 ``명``·``원``처럼
    받침으로 끝나면 "명는"이 된다. 틀을 바꾸는 것이 아니라 같은 틀을 한국어로 옳게 적는 일이다.
    """
    if not text:
        return "는"
    last = text[-1]
    if "가" <= last <= "힣":
        return "는" if (ord(last) - 0xAC00) % 28 == 0 else "은"
    return "는"


def format_value(value: float) -> str:
    """문장에 넣을 값 표기.

    **자릿수를 임의로 정하지 않는다.** 표시 자릿수 규칙은 spec에 없고, 여기서 "소수 첫째 자리"
    같은 규칙을 만들면 모델 버전에 없는 규칙이 하나 느는 셈이다. 값이 가진 자릿수를 그대로 쓰되
    정수로 떨어지는 값의 꼬리 ``.0``만 덜어 낸다(정보가 줄지 않는다).
    """
    text = repr(float(value))
    return text[:-2] if text.endswith(".0") else text


def verdict_for(scale: Scale, value: float) -> str:
    """그 시대의 자로 잰 위치 — ``높은``·``낮은``·``비슷한`` 셋 중 하나 (research R8).

    z는 여기서만 쓰이고 밖으로 나가지 않는다. "좋다/나쁘다"를 쓰지 않는다 — 실업률이 높은 것과
    생산이 높은 것은 뜻이 반대이고, 그 해석은 010의 몫이 아니다.
    """
    standardized = scale.standardize(value)
    if abs(standardized) < SIMILAR_THRESHOLD:
        return SIMILAR
    return HIGH if standardized > 0 else LOW


def era_position_for(
    indicator: IndicatorEntry,
    end_month: date,
    series: Mapping[date, MonthlyValue],
) -> EraPosition | None:
    """후보 종료월 c 기준의 보조 위치. 만들 수 없으면 ``None``이다 (spec FR-017).

    ``None``이 되는 자리는 셋이며 **모두 그 지표의 설명만 빠진다** — 사례·순위·거리는 그대로다.

    1. 후보월 직전 연속 구간이 60개월 미만이다.
    2. MAD도 IQR도 0이라 그 시대의 산포를 말할 수 없다.
    3. 서술할 값 자체가 없다(종료월 c에 그 지표의 자료가 없다).

    셋째를 같이 두는 이유는 결과가 같기 때문이다 — 말할 근거가 없으면 문장을 짓지 않는다.
    빈 문장이나 "자료 없음" 같은 문구를 만들면 그것이 곧 화면에 나가는 결과가 된다.
    """
    require_month(end_month, "후보 종료월")
    observed = series.get(end_month)
    if observed is None:
        return None
    scale = scale_for(indicator.id, end_month, series)
    if not isinstance(scale, Scale):
        return None

    sentence = SENTENCE_TEMPLATE.format(
        name=indicator.name,
        value=format_value(observed.value),
        unit=indicator.unit,
        particle=_topic_particle(indicator.unit),
        verdict=verdict_for(scale, observed.value),
    )
    return EraPosition(
        indicator_id=indicator.id,
        sentence=sentence,
        method=scale.method,
        window_start=scale.window_start,
        window_end=scale.window_end,
        months=scale.window_length,
    )


def era_positions_for(
    indicators: Iterable[IndicatorEntry],
    end_month: date,
    series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
) -> dict[str, EraPosition]:
    """후보 하나의 지표별 보조 위치. **만들지 못한 지표는 키가 없다**(data-model §4).

    키 없음이 "이 지표는 말하지 않는다"이다. 빈 값을 넣어 두면 화면이 그것을 무언가로 그린다.
    """
    positions = (
        era_position_for(indicator, end_month, series_by_indicator[indicator.id])
        for indicator in indicators
    )
    return {position.indicator_id: position for position in positions if position is not None}


class EraPositionCache:
    """후보월마다 한 번만 구해 여러 snapshot이 나눠 쓰는 자리 (spec FR-017, research R3).

    같은 후보월이 기준월 3,000개 중 여럿에 등장하는데 그때마다 120개월 척도를 다시 만들면
    510,000회가 되고, 월 단위로 묶으면 7,497회다. **값이 t와 무관하다는 성질이 없으면 이
    캐시는 틀린 값을 준다** — 그래서 키에 t가 없다.

    **버전이 바뀌면 이 객체를 버린다.** 무효화 규칙을 캐시 안에 두지 않는 이유는, 자료·등록표·
    모델 버전을 아는 곳이 배치이기 때문이다. 여기에 두면 버전을 모르는 자리에서 낡은 문장이
    조용히 살아남는다.
    """

    def __init__(self, series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]]) -> None:
        self._series = series_by_indicator
        self._cached: dict[date, dict[str, EraPosition]] = {}

    def positions_for(
        self, indicators: Iterable[IndicatorEntry], end_month: date
    ) -> dict[str, EraPosition]:
        cached = self._cached.get(end_month)
        if cached is None:
            cached = era_positions_for(indicators, end_month, self._series)
            self._cached[end_month] = cached
        return cached

    @property
    def months(self) -> tuple[date, ...]:
        """구해 둔 후보월. 배치가 몇 번 계산했는지 기록할 때 쓴다(spec FR-033)."""
        return tuple(sorted(self._cached))
