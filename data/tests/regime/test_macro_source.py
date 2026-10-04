"""`MacroIndicatorSource` 계약 (contracts/macro-indicator-port.md §1·§3).

**008 구현체가 와도 이 파일이 그대로 돈다.** `conftest.SOURCE_NAMES`에 한 줄을 더하면 같은
시험이 실제 구현체에도 걸린다 — 계약 §5의 첫 번째 확인 항목이 그것이다. 그래서 여기에는
픽스처에만 있는 값(지표 id·특정 달·개수)을 적지 않고 **모든 구현체가 지켜야 할 규칙**만 적는다.
"""

from __future__ import annotations

import math
from datetime import date

import pytest

from data_pipeline.regime.macro import (
    UnknownMacroSourceError,
    create_source,
)
from data_pipeline.regime.macro.source import MacroIndicatorSource
from data_pipeline.regime.macro.types import (
    BaselineMonths,
    IndicatorRegistry,
    MacroSourceError,
    MonthlyValue,
    Publication,
)


class TestRegistry:
    def test_프로토콜을_만족한다(self, macro_source: MacroIndicatorSource) -> None:
        assert isinstance(macro_source, MacroIndicatorSource)

    def test_등록표에_지표와_분야가_함께_온다(self, macro_source: MacroIndicatorSource) -> None:
        registry = macro_source.registry()
        assert isinstance(registry, IndicatorRegistry)
        assert registry.fields and registry.indicators

    def test_분야에_이름과_순서가_있다(self, macro_source: MacroIndicatorSource) -> None:
        """화면이 분야 이름을 보여야 하고, 순서는 등록표가 정한다 (008 FR-002·FR-003).

        010이 분야 이름 문자열을 갖지 않으므로(spec FR-004) 여기서 오지 않으면 화면에 붙일
        이름이 아예 없다.
        """
        registry = macro_source.registry()
        for field in registry.fields:
            assert field.name.strip()
        orders = [field.display_order for field in registry.ordered_fields()]
        assert orders == sorted(orders)

    def test_모든_지표가_등록된_분야에_속한다(self, macro_source: MacroIndicatorSource) -> None:
        registry = macro_source.registry()
        field_ids = {field.id for field in registry.fields}
        assert {indicator.field_id for indicator in registry.indicators} <= field_ids

    def test_분야마다_지표가_하나_이상_있다(self, macro_source: MacroIndicatorSource) -> None:
        """지표가 없는 분야는 거리를 빈 평균으로 만든다 — 0으로 나누기 전에 드러나야 한다."""
        registry = macro_source.registry()
        for field in registry.fields:
            assert registry.indicators_of(field.id), f"{field.id}에 지표가 없다"


class TestSeries:
    def test_결측월은_키가_없다(self, macro_source: MacroIndicatorSource) -> None:
        """계약 §3 — 빠진 달은 키가 없다. 0·직전 값·NaN 어느 것으로도 채워 오지 않는다.

        구멍을 값으로 채워 오면 척도 연속 구간 판정(spec FR-005)이 구멍을 못 보고 지나간다.
        """
        registry = macro_source.registry()
        holed = 0
        for indicator in registry.indicators:
            series = macro_source.monthly_series(indicator.id)
            assert series, f"{indicator.id}의 계열이 비어 있다"
            for value in series.values():
                assert not math.isnan(value.value)
                assert not math.isnan(value.raw)
            months = sorted(series)
            span = (months[-1].year * 12 + months[-1].month) - (
                months[0].year * 12 + months[0].month
            ) + 1
            if len(months) < span:
                holed += 1
        assert holed, "결측이 있는 계열이 하나도 없다 — 결측 처리 경로가 시험되지 않는다"

    def test_원값과_변환값이_함께_온다(self, macro_source: MacroIndicatorSource) -> None:
        """계약 §3 — 설명은 원값과 단위로, 거리는 변환값으로 쓴다. 하나만 오면 지어내야 한다."""
        for indicator in macro_source.registry().indicators:
            series = macro_source.monthly_series(indicator.id)
            raws = macro_source.raw_series(indicator.id)
            assert set(series) == set(raws), f"{indicator.id}의 변환값·원값 달이 어긋난다"
            for month, value in series.items():
                assert isinstance(value, MonthlyValue)
                assert raws[month] == value.raw

    def test_모르는_지표는_거부한다(self, macro_source: MacroIndicatorSource) -> None:
        """조회 실패를 대체하지 않는다 (계약 §2). 빈 계열을 돌려주면 그 지표가 조용히 빠진다."""
        with pytest.raises(MacroSourceError):
            macro_source.monthly_series("NO_SUCH_INDICATOR")


class TestBaselineMonths:
    def test_분야별_기준월과_공통_기준월이_함께_온다(
        self, macro_source: MacroIndicatorSource
    ) -> None:
        baseline = macro_source.baseline_months()
        assert isinstance(baseline, BaselineMonths)
        assert baseline.by_field

    def test_기준월은_그_범위_지표가_함께_있는_최신월이다(
        self, macro_source: MacroIndicatorSource
    ) -> None:
        """008 FR-013의 정의 그대로다. 어느 한 지표만 있는 달을 기준월로 삼지 않는다.

        `MAX(baseline_month)`로 대신할 수 없는 이유와 같은 규칙이다(data-model §3).
        """
        registry = macro_source.registry()
        series = {
            indicator.id: set(macro_source.monthly_series(indicator.id))
            for indicator in registry.indicators
        }
        baseline = macro_source.baseline_months()

        for field_id, month in baseline.by_field.items():
            common: set[date] = set.intersection(
                *(series[indicator.id] for indicator in registry.indicators_of(field_id))
            )
            assert month == max(common), f"{field_id}의 T_g가 공통 최신월이 아니다"

        if baseline.composite is not None:
            common = set.intersection(*(series[indicator.id] for indicator in registry.indicators))
            assert baseline.composite == max(common)

    def test_공통_기준월은_어느_분야_기준월보다_늦지_않다(
        self, macro_source: MacroIndicatorSource
    ) -> None:
        """T_all은 전체 공통이므로 분야 공통보다 뒤로 갈 수 없다."""
        baseline = macro_source.baseline_months()
        if baseline.composite is None:
            pytest.skip("T_all이 없는 구현체다 (spec FR-039 ②)")
        assert baseline.composite <= min(baseline.by_field.values())


class TestPublicationAndVersions:
    def test_발표일과_개정_여부가_온다(self, macro_source: MacroIndicatorSource) -> None:
        indicator = macro_source.registry().indicators[0]
        month = max(macro_source.monthly_series(indicator.id))
        publication = macro_source.publication(indicator.id, month)
        assert isinstance(publication, Publication)
        assert publication.month == month
        assert publication.published_on >= month

    def test_값이_없는_달은_발표일도_없다(self, macro_source: MacroIndicatorSource) -> None:
        """없는 발표일을 지어내지 않는다. `data_asof`에 복사되는 값이라 더 그렇다."""
        indicator = macro_source.registry().indicators[0]
        series = macro_source.monthly_series(indicator.id)
        missing = date(min(series).year - 5, 1, 1)
        assert macro_source.publication(indicator.id, missing) is None

    def test_버전_셋이_함께_온다(self, macro_source: MacroIndicatorSource) -> None:
        """계약 §3 — 자료 버전과 등록표 버전이 함께 와야 재계산 대상을 정할 수 있다(FR-030)."""
        assert macro_source.data_version().strip()
        assert macro_source.registry().version.strip()

    def test_예시_자료면_자료_버전이_FIXTURE로_시작한다(
        self, macro_source: MacroIndicatorSource
    ) -> None:
        """계약 §4.2 — 이 접두사가 식별 키·응답·기록에 실려 다녀 가짜임이 드러난다.

        008 구현체는 `is_sample_data()`가 거짓이므로 이 시험은 접두사를 요구하지 않는다.
        """
        if macro_source.is_sample_data():
            assert macro_source.data_version().startswith("FIXTURE-")
            assert macro_source.registry().version.startswith("FIXTURE-")
        else:
            assert not macro_source.data_version().startswith("FIXTURE-")

    def test_모르면_None을_돌려준다(self, macro_source: MacroIndicatorSource) -> None:
        """`changed_since()`가 답할 수 없으면 `None`이고 배치는 전 구간으로 물러난다 (계약 §1).

        '바뀐 것 없음'을 뜻하는 값을 돌려주면 낡은 결과가 남는다. 발행한 적 없는 버전 뒤에 무엇이
        바뀌었는지는 어느 구현체도 알 수 없다 — 개정 이력이 있다고 달을 짐작해 돌려주면 배치가
        일부만 다시 계산하고 나머지는 낡은 채로 남는다.
        """
        assert macro_source.changed_since("발행한-적-없는-자료-버전") is None


class TestFactory:
    def test_모르는_소스는_거부한다(self) -> None:
        with pytest.raises(UnknownMacroSourceError):
            create_source("no-such-source")
