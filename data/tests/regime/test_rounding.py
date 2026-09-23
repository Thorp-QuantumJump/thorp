"""저장 자릿수 반올림과 정확한 동률 (research R4, tasks.md T014).

tasks.md 머리말이 *"테스트 또는 동작 확인 완료"* 를 Task 완료 조건으로 못 박았다. 이 모듈은
M4의 순위·동률과 M5의 저장값을 결정하므로, 쓰이기 전에 규칙을 고정해 둔다.
"""

from __future__ import annotations

import math

import pytest

from data_pipeline.regime.rounding import (
    STORAGE_DECIMALS,
    RoundingError,
    equal_at_storage,
    round_for_storage,
    to_storage_text,
)


class TestRounding:
    def test_저장_자릿수는_12다(self) -> None:
        """바꾸면 순위·동률 판정이 달라진다 — 새 모델 버전이다 (research R4)."""
        assert STORAGE_DECIMALS == 12

    def test_12자리_아래를_버린다(self) -> None:
        assert round_for_storage(0.4129385710341234) == 0.412938571034

    def test_사사오입이다(self) -> None:
        """파이썬 내장 `round()`는 짝수로 붙인다. 수기 대조(SC-003)에서 갈릴 수 있다.

        경계를 시험하려면 **double로 정확히 표현되는 경계값**이 필요하다. 십진수 0.0000000000005는
        2진수로 떨어지지 않아 실제 double은 경계보다 아주 조금 작고, 그러면 어느 방식으로 반올림해도
        내려가 두 방식을 구분하지 못한다. `2⁻¹³ = 0.0001220703125`는 소수 13자리에서 정확히 끝나
        진짜 경계다.
        """
        boundary = 1 / 8192

        assert to_storage_text(boundary) == "0.000122070313", "사사오입이면 올라간다"
        assert round(boundary, STORAGE_DECIMALS) == 0.000122070312, "내장 round는 짝수로 내린다"

    def test_문자열로도_낼_수_있다(self) -> None:
        """`NUMERIC` 컬럼은 scale을 임의로 정하고 넘는 값을 조용히 반올림한다 (research R4)."""
        assert to_storage_text(0.412938571034) == "0.412938571034"
        assert to_storage_text(1.0) == "1.000000000000"

    def test_음수도_같은_규칙이다(self) -> None:
        assert round_for_storage(-0.4129385710341234) == -0.412938571034


class TestExactTie:
    """spec FR-012 — 정확한 동률만 동률이다. 근사 동률을 동률로 보지 않는다."""

    def test_12자리까지_같으면_동률이다(self) -> None:
        assert equal_at_storage(0.4129385710341111, 0.4129385710342222)

    def test_12자리_안에서_다르면_동률이_아니다(self) -> None:
        assert not equal_at_storage(0.412938571034, 0.412938571035)

    def test_눈으로_같아_보여도_다르면_다르다(self) -> None:
        """이 구분이 없으면 '오래된 월 우선'이 언제 적용되는지 아무도 예측할 수 없다."""
        assert not equal_at_storage(1.0, 1.000000000001)


class TestRejects:
    def test_NaN은_저장할_수_없다(self) -> None:
        """계산 실패를 값으로 바꾸지 않는다. NaN은 정렬에서 조용히 뒤로 밀린다(원칙 VI)."""
        with pytest.raises(RoundingError):
            round_for_storage(math.nan)

    def test_무한대도_저장할_수_없다(self) -> None:
        with pytest.raises(RoundingError):
            round_for_storage(math.inf)

    def test_수가_아니면_거부한다(self) -> None:
        with pytest.raises(RoundingError):
            round_for_storage("0.5")  # type: ignore[arg-type]
