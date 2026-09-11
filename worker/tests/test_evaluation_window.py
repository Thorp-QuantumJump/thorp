"""공통 평가 기간 산식을 공유 픽스처로 검증한다.

백엔드 ``EvaluationWindowTest`` 와 **같은 파일**을 읽는다. 테스트 리소스로 복사하지 않는다 —
복사하면 어긋나고, 한쪽만 고쳐도 빌드가 통과해 버린다(contracts/README.md 「계약 변경 순서」).

픽스처를 찾지 못하면 건너뛰지 않고 **실패한다**. 건너뛰게 두면 파일명이 바뀌는 순간 두 구현을 묶는
장치가 조용히 사라진다.
"""

import json
from datetime import date
from pathlib import Path
from typing import Any

import pytest

from worker.evaluation_window import (
    Candidate,
    DataGap,
    EvaluationWindowError,
    ExcludedRange,
    Strategy,
    compute_evaluation_window,
)

FIXTURE = (
    Path(__file__).resolve().parents[2]
    / "specs"
    / "001-etf-market-data"
    / "contracts"
    / "fixtures"
    / "evaluation-window-cases.json"
)

# 계약 「경계 동작」이 요구하는 일곱 가지. 케이스를 지우면 여기서 걸린다.
REQUIRED_BOUNDARY_CASES = frozenset(
    {
        "common-start-after-common-end",
        "single-candidate",
        "empty-candidates",
        "zero-warmup",
        "delisted-candidate-pulls-end",
        "gap-covers-common-start",
        "longest-warmup-wins",
    }
)


def _load_cases() -> list[dict[str, Any]]:
    """수집 단계에서 읽는다. 파일이 없으면 여기서 예외가 나고 pytest는 오류로 끝난다."""
    if not FIXTURE.is_file():
        raise FileNotFoundError(
            f"공유 픽스처가 없다: {FIXTURE}. 워커와 백엔드를 묶는 유일한 파일이므로 건너뛰지 않는다"
        )
    cases = json.loads(FIXTURE.read_text(encoding="utf-8"))["cases"]
    if not cases:
        raise ValueError(f"공유 픽스처에 케이스가 없다: {FIXTURE}")
    return cases


CASES = _load_cases()


def _to_date(value: str) -> date:
    return date.fromisoformat(value)


def _to_candidate(raw: dict[str, Any]) -> Candidate:
    delisted = raw.get("delistedOn")
    return Candidate(
        instrument_id=raw["instrumentId"],
        listed_on=_to_date(raw["listedOn"]),
        delisted_on=_to_date(delisted) if delisted is not None else None,
        data_gaps=tuple(
            DataGap(_to_date(g["start"]), _to_date(g["end"]), g["reason"])
            for g in raw.get("dataGaps", [])
        ),
    )


def _call(case_input: dict[str, Any]):
    return compute_evaluation_window(
        candidates=[_to_candidate(c) for c in case_input["candidates"]],
        strategies=[
            Strategy(s["strategyId"], s["warmupTradingDays"]) for s in case_input["strategies"]
        ],
        data_deadline=_to_date(case_input["dataDeadline"]),
        trading_calendar=[_to_date(d) for d in case_input["tradingCalendar"]],
    )


def test_fixture_covers_every_contract_boundary() -> None:
    """계약 「경계 동작」 일곱 가지가 모두 픽스처에 있다."""
    names = {case["name"] for case in CASES}
    assert REQUIRED_BOUNDARY_CASES <= names, REQUIRED_BOUNDARY_CASES - names


@pytest.mark.parametrize("case", CASES, ids=[c["name"] for c in CASES])
def test_fixture_case(case: dict[str, Any]) -> None:
    has_expected = "expected" in case
    has_error = "error" in case
    # 둘 다 없으면 케이스가 아무것도 검증하지 않는다. 그런 케이스가 조용히 지나가면 안 된다.
    assert has_expected != has_error, f"{case['name']}: expected 와 error 중 정확히 하나여야 한다"

    if has_error:
        with pytest.raises(EvaluationWindowError) as caught:
            _call(case["input"])
        assert caught.value.code == case["error"]
        return

    window = _call(case["input"])
    expected = case["expected"]
    assert window.common_start == _to_date(expected["commonStart"])
    assert window.common_end == _to_date(expected["commonEnd"])
    assert window.warmup_trading_days == expected["warmupTradingDays"]
    assert window.binding_candidate == expected["bindingCandidate"]
    assert window.evaluable is expected["evaluable"]
    assert [
        {"start": r.start.isoformat(), "end": r.end.isoformat(), "reason": r.reason}
        for r in window.excluded_ranges
    ] == expected["excludedRanges"]


def test_data_gap_does_not_push_common_start() -> None:
    """자료 부족 구간이 시작일을 덮어도 시작일을 밀지 않는다 (원칙 VI, 계약 경계 동작).

    픽스처 ``gap-covers-common-start`` 가 같은 규칙을 묶고 있지만, 이 단언은 규칙 자체를 문장으로
    남긴다 — 부족 구간의 끝을 뒤로 밀어도 ``common_start`` 는 꿈쩍하지 않아야 한다.
    """
    calendar = [date(2024, 1, d) for d in (2, 3, 4, 5, 8, 9, 10, 11, 12)]
    gap = DataGap(date(2024, 1, 2), date(2024, 1, 11), "AMBIGUOUS_PRICE")
    candidate = Candidate("091160.XKRX", date(2024, 1, 2), None, (gap,))

    window = compute_evaluation_window(
        candidates=[candidate],
        strategies=[Strategy("SECTOR_MOMENTUM", 2)],
        data_deadline=date(2024, 1, 12),
        trading_calendar=calendar,
    )

    assert window.common_start == date(2024, 1, 4)
    assert window.evaluable is True
    assert window.excluded_ranges == (
        ExcludedRange(date(2024, 1, 4), date(2024, 1, 11), "AMBIGUOUS_PRICE"),
    )
