"""공통 평가 가능 기간 산식 (specs/001-etf-market-data/contracts/evaluation-window.md).

계약이 기준이고, 백엔드
``com.ssafy.thorp.sector.application.service.EvaluationWindowService`` 가 같은 산식을 자바로
구현한다. 두 구현은 같은 픽스처
``specs/001-etf-market-data/contracts/fixtures/evaluation-window-cases.json`` 을 읽어 검증하므로
한쪽만 고치면 다른 쪽 빌드가 깨진다. 계산 위치를 한쪽으로 옮기지 않고 산식을 계약으로 공유하는
것이 결정이다(research.md 결정 3).

산식::

    warmup = max(strategies[].warmupTradingDays)

    각 후보 c에 대해
      firstEvaluable(c) = 거래일 달력에서 c.listedOn 이후 warmup 번째 거래일
      lastEvaluable(c)  = c.delistedOn ?? dataDeadline

    commonStart = max over c of firstEvaluable(c)
    commonEnd   = min(dataDeadline, min over c of lastEvaluable(c))

    excludedRanges = 모든 후보의 dataGaps를 합집합한 뒤 [commonStart, commonEnd]로 자른 것

**검증용 잠금 구간은 넣지 않는다**(FR-008). 반환값은 자료로 평가할 수 있는 전체 범위이고, 이를
탐색 구간과 잠금 구간으로 나누는 일은 spec 007의 평가 분할이 맡는다. 여기서 미리 나누면 007이 쓸
수 없는 값이 된다.

**자료 부족 구간이 ``commonStart`` 를 포함해도 시작일을 밀지 않는다.** 범위는 그대로 두고
``excluded_ranges`` 로 알린다 — 조용히 밀면 화면이 왜 기간이 짧아졌는지 설명할 수 없다(원칙 VI).

이 모듈은 pandas도 DB도 쓰지 않는다. 순수 날짜 조합 연산이라 시계열을 훑지 않는다.
"""

from __future__ import annotations

import re
from bisect import bisect_left
from dataclasses import dataclass
from datetime import date

__all__ = [
    "Candidate",
    "DataGap",
    "EvaluationWindow",
    "EvaluationWindowError",
    "ExcludedRange",
    "Strategy",
    "compute_evaluation_window",
]

# ``representative_etf`` · ``data_gap`` 의 CHECK 제약, 백엔드 ``RepresentativeEtf`` 와 같은
# 형식이다. ``$`` 를 쓰지 않는다 — 파이썬의 ``$`` 는 끝에 붙은 줄바꿈 앞에서도 맞아서 자바
# ``String.matches()`` 보다 넓어진다. ``fullmatch`` 는 그 차이가 없다.
_INSTRUMENT_ID_PATTERN = re.compile(r"[0-9]{6}\.XKRX")


class EvaluationWindowError(ValueError):
    """산식을 적용할 수 없는 입력. ``code`` 는 픽스처의 ``error`` 값과 같은 문자열이다.

    평가 불가(``commonStart > commonEnd``)는 여기에 해당하지 않는다. 그것은 오류가 아니라
    ``evaluable = False`` 인 정상 결과다.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code


@dataclass(frozen=True)
class DataGap:
    """후보 하나의 자료 부족 구간. ``reason`` 은 ``data_gap.reason`` 값이다(FR-006·FR-017)."""

    start: date
    end: date
    reason: str


@dataclass(frozen=True)
class Candidate:
    """후보 대표 ETF 하나. 출처는 ``representative_etf`` 와 ``data_gap`` 이다."""

    instrument_id: str
    listed_on: date
    delisted_on: date | None = None
    data_gaps: tuple[DataGap, ...] = ()


@dataclass(frozen=True)
class Strategy:
    """요청된 전략 하나. ``warmup_trading_days`` 는 관측 기간 + 최근 제외 구간이다(FR-007).

    001은 이 값을 정하지 않고 받는다. 전략 등록표는 spec 003이 소유한다.
    """

    strategy_id: str
    warmup_trading_days: int


@dataclass(frozen=True)
class ExcludedRange:
    """``[common_start, common_end]`` 안의 자료 부족 구간."""

    start: date
    end: date
    reason: str


@dataclass(frozen=True)
class EvaluationWindow:
    """계약 「출력」 절의 여섯 가지.

    ``binding_candidate`` 는 ``common_start`` 를 결정한 후보다. 003이 "이 섹터를 빼면 기간이
    늘어납니다"를 안내하는 근거가 된다.
    """

    common_start: date
    common_end: date
    warmup_trading_days: int
    binding_candidate: str
    excluded_ranges: tuple[ExcludedRange, ...]
    evaluable: bool


def compute_evaluation_window(
    candidates: list[Candidate],
    strategies: list[Strategy],
    data_deadline: date,
    trading_calendar: list[date],
) -> EvaluationWindow:
    """공통 평가 가능 기간을 산출한다.

    :param candidates: 후보 대표 ETF. 비어 있으면 오류다 — 후보 없이 기간을 산출하지 않는다
    :param strategies: 요청된 전략. 가장 긴 워밍업이 쓰인다
    :param data_deadline: ``dataset_version.data_deadline``
    :param trading_calendar: 거래일 달력. 확보한 지수 자료(``daily_index``)의 날짜 집합이다
    :raises EvaluationWindowError: 산식을 적용할 수 없는 입력일 때. 평가 불가는 오류가 아니다
    """
    warmup = _resolve_warmup(candidates, strategies)
    calendar = _resolve_calendar(trading_calendar)
    # 식별자 검사를 따로 한 바퀴 돈다. ``_first_evaluable`` 안에서 함께 보면 앞선 후보의
    # CALENDAR_TOO_SHORT가 뒤에 있는 형식 위반보다 먼저 터져, 픽스처가 못박은 판정 순서와 어긋난다.
    for candidate in candidates:
        _require_instrument_id(candidate.instrument_id)

    first_evaluable = [_first_evaluable(c, warmup, calendar) for c in candidates]
    last_evaluable = [
        c.delisted_on if c.delisted_on is not None else data_deadline for c in candidates
    ]

    # 동점이면 입력 순서가 앞선 후보다. 계약이 정하지 않아 정했고 픽스처
    # ``binding-candidate-tie`` 가 두 구현을 묶는다. ``max`` 는 첫 최댓값을 돌려주므로
    # 인덱스를 직접 찾는다(자바 구현과 같은 규칙).
    binding_index = first_evaluable.index(max(first_evaluable))
    common_start = first_evaluable[binding_index]
    common_end = min([data_deadline, *last_evaluable])

    evaluable = common_start <= common_end
    # 구간이 뒤집혔으면 자를 대상이 없다.
    excluded = _merge_gaps(candidates, common_start, common_end) if evaluable else ()

    return EvaluationWindow(
        common_start=common_start,
        common_end=common_end,
        warmup_trading_days=warmup,
        binding_candidate=candidates[binding_index].instrument_id,
        excluded_ranges=excluded,
        evaluable=evaluable,
    )


def _resolve_warmup(candidates: list[Candidate], strategies: list[Strategy]) -> int:
    """검증 순서는 픽스처의 ``$validationOrder`` 와 같다. 자바 구현도 같은 순서다."""
    if not candidates:
        raise EvaluationWindowError(
            "EMPTY_CANDIDATES", "후보 없이 공통 평가 기간을 산출하지 않는다"
        )
    if not strategies:
        raise EvaluationWindowError(
            "EMPTY_STRATEGIES", "전략이 없으면 워밍업 최댓값을 정할 수 없다"
        )
    warmup = max(s.warmup_trading_days for s in strategies)
    if warmup < 0:
        raise EvaluationWindowError(
            "NEGATIVE_WARMUP", f"warmupTradingDays는 0 이상이어야 한다: {warmup}"
        )
    return warmup


def _resolve_calendar(trading_calendar: list[date]) -> list[date]:
    """달력을 정렬·중복 제거한다. 원본 순서를 신뢰하지 않는다 — 조회 순서는 호출부 사정이다."""
    if not trading_calendar:
        raise EvaluationWindowError(
            "EMPTY_CALENDAR", "거래일 달력이 비어 있으면 거래일을 셀 수 없다"
        )
    return sorted(set(trading_calendar))


def _require_instrument_id(instrument_id: object) -> None:
    """자바 쪽 ``requireInstrumentId``와 같은 입력에 같은 오류를 낸다.

    문자열이 아닌 값을 먼저 거른다. ``fullmatch``에 ``None``을 넘기면 ``TypeError``가 나는데,
    그것은 부르는 쪽이 잡는 ``EvaluationWindowError``를 빠져나간다. 자바는 ``null``을 명시적으로
    검사해 ``INVALID_INSTRUMENT_ID``를 내므로, 고치지 않으면 **두 구현이 갈라지는 유일한
    입력**이 된다. 같은 픽스처로 묶어 둔 뜻이 없어진다.
    """
    if not isinstance(instrument_id, str) or not _INSTRUMENT_ID_PATTERN.fullmatch(instrument_id):
        raise EvaluationWindowError(
            "INVALID_INSTRUMENT_ID", f"종목 식별자 형식이 아니다: {instrument_id}"
        )


def _first_evaluable(candidate: Candidate, warmup: int, calendar: list[date]) -> date:
    """달력에서 ``listed_on`` 이상인 거래일 중 0-기준 ``warmup`` 번째.

    ``warmup`` 이 0이면 ``listed_on`` 이 그대로 나온다(계약 경계 동작). 달력이 모자라면 마지막
    거래일로 떨어뜨리지 않고 실패한다 — 없는 날짜를 지어내지 않는다(원칙 VI).
    """
    start = bisect_left(calendar, candidate.listed_on)
    index = start + warmup
    if index >= len(calendar):
        raise EvaluationWindowError(
            "CALENDAR_TOO_SHORT",
            f"{candidate.instrument_id}의 상장일 이후 거래일이 워밍업 {warmup}일에 모자란다",
        )
    return calendar[index]


def _merge_gaps(
    candidates: list[Candidate], common_start: date, common_end: date
) -> tuple[ExcludedRange, ...]:
    """모든 후보의 부족 구간을 합집합한 뒤 ``[common_start, common_end]`` 로 자른다.

    사유가 같고 겹치는 구간끼리만 합친다. 사유가 다른 구간을 합치면 ``data_gap.reason`` 을 잃는다.
    정렬은 (start, end, reason) 오름차순이다.
    """
    clipped: dict[str, list[tuple[date, date]]] = {}
    for candidate in candidates:
        for gap in candidate.data_gaps:
            start = max(gap.start, common_start)
            end = min(gap.end, common_end)
            if start <= end:
                clipped.setdefault(gap.reason, []).append((start, end))

    merged: list[ExcludedRange] = []
    for reason, ranges in clipped.items():
        current_start, current_end = None, None
        for start, end in sorted(ranges):
            if current_end is not None and start <= current_end:
                current_end = max(current_end, end)
                continue
            if current_end is not None:
                merged.append(ExcludedRange(current_start, current_end, reason))
            current_start, current_end = start, end
        if current_end is not None:
            merged.append(ExcludedRange(current_start, current_end, reason))

    merged.sort(key=lambda r: (r.start, r.end, r.reason))
    return tuple(merged)
