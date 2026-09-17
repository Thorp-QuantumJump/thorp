"""지표별 비교 시점 (spec FR-001·FR-007, tasks.md T027~T028).

두 가지를 고정한다 — 한 지표의 구멍이 **그 지표만** 줄이는가, 그리고 어느 지표의 세 달이
모두 비었을 때 **그 지표를 빼고 계산하는 경로가 막혀 있는가**.

둘째가 중요한 이유는 어기기 쉬워서다. 빼고 계산하면 답이 나오고 화면도 멀쩡해 보인다.
틀렸다는 것을 아무도 알려 주지 않으므로 코드 쪽에서 막아야 한다.
"""

from __future__ import annotations

from datetime import date

import pytest
from conftest import make_series

from data_pipeline.regime.comparison import (
    COMPARISON_OFFSETS,
    QUERY_INPUT_MISSING,
    QueryInputMissingError,
    comparison_for_scope,
    comparison_months_for,
)

BASELINE = date(2026, 6, 1)
MONTHS = (date(2026, 4, 1), date(2026, 5, 1), date(2026, 6, 1))


def full_series() -> dict[date, object]:
    return make_series((2026, 4), [1.0, 2.0, 3.0])


class TestPerIndicator:
    """T027 — 비교 시점은 지표마다 따로 정한다 (spec FR-001)."""

    def test_세_달이_다_있으면_셋_다_쓴다(self) -> None:
        result = comparison_months_for("A", BASELINE, full_series())
        assert result.months == MONTHS
        assert result.omitted == ()
        assert not result.is_partial

    def test_한_달이_비면_그_지표만_두_달이_된다(self) -> None:
        holed = make_series((2026, 4), [1.0, None, 3.0])
        scope = comparison_for_scope(
            ["A", "B"], BASELINE, {"A": holed, "B": full_series()}
        )

        assert scope.by_indicator["A"].months == (date(2026, 4, 1), date(2026, 6, 1))
        assert scope.by_indicator["A"].omitted == (date(2026, 5, 1),)
        assert scope.by_indicator["B"].months == MONTHS, "다른 지표는 세 달 그대로다"
        assert scope.computable, "한 지표가 두 달이어도 분야는 계산할 수 있다"

    def test_빠진_달을_버리지_않고_남긴다(self) -> None:
        """화면이 지표·월 단위로 표기해야 한다 (spec FR-031, SC-007)."""
        holed = make_series((2026, 4), [1.0, None, 3.0])
        result = comparison_months_for("A", BASELINE, holed)
        assert result.is_partial
        assert result.omitted == (date(2026, 5, 1),)

    def test_값을_채워_넣지_않는다(self) -> None:
        """평균이나 직전 값으로 메우면 없는 관측이 있는 것처럼 계산에 들어간다 (spec FR-007)."""
        holed = make_series((2026, 4), [1.0, None, 3.0])
        result = comparison_months_for("A", BASELINE, holed)
        assert date(2026, 5, 1) not in result.months
        assert len(result.months) == 2

    def test_비교_대상은_t_2_t_1_t다(self) -> None:
        assert COMPARISON_OFFSETS == (2, 1, 0)
        result = comparison_months_for("A", BASELINE, full_series())
        assert result.months == MONTHS

    def test_모드를_인자로_받지_않는다(self) -> None:
        """규칙이 모드에 따라 달라지지 않는다 (spec FR-001).

        종합도 분야와 똑같이 지표별로 정한다. 17개 공통 달로 좁히면 지표 하나의 구멍이 전체의
        그 달을 날리고, 같은 분야의 D_g가 모드에 따라 두 값이 된다(SC-002).
        """
        import inspect

        for function in (comparison_months_for, comparison_for_scope):
            names = set(inspect.signature(function).parameters)
            assert not names & {"mode", "search_mode", "scope_kind"}, (
                f"{function.__name__}이 모드를 받는다 — 분기가 생길 자리다"
            )

    def test_지표가_많아도_서로_영향을_주지_않는다(self) -> None:
        scope = comparison_for_scope(
            ["A", "B", "C"],
            BASELINE,
            {
                "A": make_series((2026, 4), [1.0, None, 3.0]),
                "B": make_series((2026, 4), [None, 2.0, 3.0]),
                "C": full_series(),
            },
        )
        assert len(scope.by_indicator["A"].months) == 2
        assert len(scope.by_indicator["B"].months) == 2
        assert len(scope.by_indicator["C"].months) == 3
        assert scope.by_indicator["A"].omitted != scope.by_indicator["B"].omitted


class TestQueryInputMissing:
    """T028 — 세 달이 모두 비면 그 분야가 계산 불가다 (spec FR-007·FR-008)."""

    @staticmethod
    def _scope_with_empty_indicator():
        return comparison_for_scope(
            ["A", "B"],
            BASELINE,
            {"A": make_series((2026, 4), [None, None, None]), "B": full_series()},
        )

    def test_세_달이_모두_비면_그_지표가_빈다(self) -> None:
        result = comparison_months_for("A", BASELINE, make_series((2026, 4), [None, None, None]))
        assert result.is_empty
        assert result.omitted == MONTHS

    def test_분야가_계산_불가가_된다(self) -> None:
        scope = self._scope_with_empty_indicator()
        assert not scope.computable
        assert scope.missing_indicators == ("A",)

    def test_사유가_QUERY_INPUT_MISSING이다(self) -> None:
        """척도 사유에 접어 넣지 않는다 — 화면이 "척도가 60개월이 안 된다"고 잘못 말한다."""
        scope = self._scope_with_empty_indicator()
        assert scope.reason == QUERY_INPUT_MISSING

    def test_그_지표를_빼고_계산하는_경로가_막혀_있다(self) -> None:
        """**빼고 계산하면 답은 나온다.** 그 답이 무엇에 대한 것인지 말할 수 없을 뿐이라,
        규칙을 주석으로만 두면 급할 때 한 줄 건너뛰는 것으로 어겨진다 (spec FR-007).
        """
        scope = self._scope_with_empty_indicator()
        with pytest.raises(QueryInputMissingError) as error:
            scope.require_computable()
        assert "A" in str(error.value)

    def test_멀쩡한_지표가_남아_있어도_계산하지_않는다(self) -> None:
        scope = self._scope_with_empty_indicator()
        assert scope.by_indicator["B"].months == MONTHS, "B는 멀쩡하다"
        assert not scope.computable, "그래도 이 분야는 계산 불가다"

    def test_모두_멀쩡하면_통과한다(self) -> None:
        scope = comparison_for_scope(
            ["A", "B"], BASELINE, {"A": full_series(), "B": full_series()}
        )
        assert scope.computable
        assert scope.reason is None
        scope.require_computable()
