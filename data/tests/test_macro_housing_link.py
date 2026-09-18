"""주택가격 연결기 테스트 (T026) — 기준월별 표 5개를 이어 2000-01까지 (FR-010).

표본은 2026-09-18에 실제로 받아 `tests/fixtures/macro/housing/`에 저장한 응답이다.
"""

from __future__ import annotations

import json
import pathlib
from decimal import Decimal

import pytest

from data_pipeline.macro.registry import (
    HOUSING_LINK_CHAIN,
    HOUSING_REVISION_VINTAGES,
    INDICATORS,
)
from data_pipeline.macro.sources.housing import (
    HousingLinkError,
    Segment,
    build_params,
    link,
    parse_table,
    segments_from,
)

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro" / "housing"

#: 전 구간을 덮는 데 필요한 표 셋. 새 기준월부터 늘어놓는다.
CHAIN: tuple[tuple[str, str], ...] = (
    ("2026-06", "base_2026_06_DT_30404_C012"),
    ("2021-06", "base_2021_06_DT_40803_N0001"),
    ("2011-06", "base_2011_06_DT_304N_04_00001"),
)

#: 연결에 쓰이지 않지만 개정 대조용으로 받아 둔 판본 (T052).
REVISION_VINTAGES: tuple[tuple[str, str], ...] = (
    ("2026-01", "base_2026_01_DT_30404_B012"),
    ("2025-03", "base_2025_03_DT_30404_A012"),
)


def monthly_before(period: str) -> str:
    year, month = int(period[:4]), int(period[5:])
    return f"{year - 1}-12" if month == 1 else f"{year}-{month - 1:02d}"


BY_BASE_MONTH = {t.base_month: t for t in (*HOUSING_LINK_CHAIN, *HOUSING_REVISION_VINTAGES)}


def load(name: str) -> dict[str, Decimal]:
    """표본을 **실제 파싱 경로로** 읽는다. 손으로 훑으면 구조 검증이 시험되지 않는다."""
    base_month = next(base for base, fixture in (*CHAIN, *REVISION_VINTAGES) if fixture == name)
    payload = json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))
    return parse_table(BY_BASE_MONTH[base_month], payload)


def segments() -> list[Segment]:
    """등록표의 사슬 순서대로 만든다. 순서를 테스트가 정하지 않는다."""
    return segments_from({base: load(name) for base, name in CHAIN})


class TestCoverage:
    def test_2000년_1월까지_닿는다(self) -> None:
        linked = link(segments())

        assert linked.start == "2000-01"
        assert linked.end == "2026-07"

    def test_빠진_달이_없다(self) -> None:
        linked = link(segments())

        periods = [p.period for p in linked.points]
        assert len(periods) == len(set(periods))
        assert len(periods) == (2026 - 2000) * 12 + 7

    def test_표_하나로는_2021년_6월까지밖에_못_간다(self) -> None:
        """연결을 하지 않으면 경제환경 검색이 볼 수 있는 과거가 5년으로 줄어든다."""
        current = segments()[0]

        assert min(current.values) == "2021-06"


class TestLinkRecord:
    def test_연결_지점과_계수를_남긴다(self) -> None:
        linked = link(segments())

        assert len(linked.links) == 2
        for record in linked.links:
            assert record.splice_period
            assert record.coefficient > 0
            assert record.overlap_months > 0

    def test_현행과_2021년_표는_순수_재기준화다(self) -> None:
        """겹침 구간 어디서 이어도 같은 값이 나온다 (편차 0%)."""
        first = link(segments()).links[0]

        assert first.from_base_month == "2021-06"
        assert first.overlap_months == 46
        assert first.overlap_spread_percent == Decimal("0.0000")
        assert first.ratio_is_stable

    def test_2011년_표_연결은_비율이_일정하지_않다(self) -> None:
        """pd11은 겹침 비율로 이으면 된다고 적었지만 이 연결은 그렇지 않다.

        두 표가 같은 달에 대해 서로 다른 변화를 말한다. 감추지 않고 드러낸다(원칙 VI).
        """
        second = link(segments()).links[1]

        assert second.from_base_month == "2011-06"
        assert second.overlap_months == 110
        assert second.overlap_spread_percent > Decimal("5")
        assert not second.ratio_is_stable

    def test_연결이_모두_안정적이지는_않다(self) -> None:
        assert not link(segments()).all_links_stable


class TestLinkedValues:
    def test_현행_표_구간은_원천_값을_그대로_둔다(self) -> None:
        current = load(CHAIN[0][1])

        linked = link(segments())

        values = linked.values
        for period, value in current.items():
            assert values[period] == value

    def test_이어_붙인_구간임이_값에_남는다(self) -> None:
        linked = link(segments())

        by_period = {p.period: p for p in linked.points}
        assert by_period["2026-07"].is_linked is False
        assert by_period["2026-07"].source_base_month == "2026-06"
        assert by_period["2000-01"].is_linked is True
        assert by_period["2000-01"].source_base_month == "2011-06"

    def test_연결_지점에서_수준이_이어진다(self) -> None:
        """계수를 곱한 값이 연결 지점에서 새 계열의 값과 맞아야 한다."""
        linked = link(segments())
        first = linked.links[0]
        older = load(CHAIN[1][1])

        moved = older[first.splice_period] * first.coefficient
        assert abs(moved - linked.values[first.splice_period]) < Decimal("0.000001")

    def test_옛_표가_새_표_구간을_덮어쓰지_않는다(self) -> None:
        """새 기준월의 표가 그 달에 대해 더 최근의 개정을 담고 있다."""
        linked = link(segments())

        overlap = [p for p in linked.points if "2021-06" <= p.period <= "2025-03"]
        assert overlap
        assert all(p.source_base_month == "2026-06" for p in overlap)

    def test_달마다의_변화율이_각_표_안에서_보존된다(self) -> None:
        """연결은 수준을 옮길 뿐 표 안의 변화를 건드리지 않는다. 주택가격은 전월 대비를 쓴다.

        이웃한 두 달이 **같은 표에서 왔을 때만** 견준다. 표가 바뀌는 지점에서는 두 표가 서로
        다른 변화를 말하므로(2011.6 연결의 편차 5.7%) 보존될 변화율이라는 것이 없다.
        """
        linked = link(segments())
        by_period = {p.period: p for p in linked.points}
        sources = {base: load(name) for base, name in CHAIN}

        compared = 0
        for point in linked.points:
            previous_period = monthly_before(point.period)
            previous = by_period.get(previous_period)
            if previous is None or previous.source_base_month != point.source_base_month:
                continue
            table = sources.get(point.source_base_month)
            if table is None:
                continue
            source_growth = table[point.period] / table[previous_period]
            linked_growth = point.value / previous.value
            assert abs(source_growth - linked_growth) < Decimal("0.000001"), point.period
            compared += 1

        assert compared > 300, f"견준 달이 {compared}개뿐이다"


class TestRegistryChain:
    """연결 사슬은 등록표가 든다 (FR-003·FR-005).

    표 목록이 코드나 테스트에만 있으면 연결 규칙이 등록표 버전에 매이지 않는다. 표를 하나
    갈아끼웠을 때 자료 버전이 왜 달라졌는지 되짚을 곳이 없다.
    """

    def test_표본_사슬이_등록표_사슬과_같다(self) -> None:
        assert [t.base_month for t in HOUSING_LINK_CHAIN] == [base for base, _ in CHAIN]
        assert [t.table_code for t in HOUSING_LINK_CHAIN] == [
            name.split("_", 3)[-1] for _, name in CHAIN
        ]

    def test_사슬이_새_기준월부터_늘어선다(self) -> None:
        base_months = [t.base_month for t in HOUSING_LINK_CHAIN]

        assert base_months == sorted(base_months, reverse=True)

    def test_2011년_표는_분류_체계가_다르다(self) -> None:
        """앞의 표들은 objL1 이 유형·objL2 가 지역인데 이 표는 반대다. 같은 코드면 거절된다."""
        oldest = HOUSING_LINK_CHAIN[-1]

        assert dict(oldest.classification)["objL1"] == "1530430404A"
        assert oldest.item_code == "16304304_01"

    def test_등록표의_시작월이_사슬_끝까지_닿는다(self) -> None:
        housing = next(i for i in INDICATORS if i.indicator_id == "housing_sale_price")

        assert housing.series_start == HOUSING_LINK_CHAIN[-1].coverage_start == "2000-01"

    def test_연결_규칙에_실측_계수와_편차가_적혀_있다(self) -> None:
        housing = next(i for i in INDICATORS if i.indicator_id == "housing_sale_price")

        assert "1.014008342" in housing.break_link_rule
        assert "0.822651718" in housing.break_link_rule
        assert "5.6788" in housing.break_link_rule

    def test_표가_덜_오면_건너뛰지_않고_멈춘다(self) -> None:
        """빠진 표를 건너뛰고 이으면 그 구간만큼 과거가 조용히 짧아진다."""
        partial = {base: load(name) for base, name in CHAIN[:2]}

        with pytest.raises(HousingLinkError, match="연결 사슬의 표가 덜 왔다"):
            segments_from(partial)

    def test_조회_인자에_인증키를_넣지_않는다(self) -> None:
        params = build_params(HOUSING_LINK_CHAIN[-1], "2000-01", "2012-12")

        assert "apiKey" not in params
        assert params["orgId"] == "408"
        assert params["tblId"] == "DT_304N_04_00001"
        assert params["startPrdDe"] == "200001"
        assert params["objL2"] == "15304A304_04_021+"


class TestRevisionVintages:
    def test_개정_대조용_판본은_연결에_쓰이지_않는다(self) -> None:
        """구간이 현행 표와 겹치기만 해 전 구간을 넓히지 못한다."""
        current = load(CHAIN[0][1])

        for _, name in REVISION_VINTAGES:
            vintage = load(name)
            assert min(vintage) >= min(current)

    def test_등록표가_대조용_판본을_사슬과_따로_둔다(self) -> None:
        chain_tables = {t.table_code for t in HOUSING_LINK_CHAIN}
        vintage_tables = {t.table_code for t in HOUSING_REVISION_VINTAGES}

        assert chain_tables.isdisjoint(vintage_tables)
        assert {t.base_month for t in HOUSING_REVISION_VINTAGES} == {
            base for base, _ in REVISION_VINTAGES
        }


class TestErrors:
    def test_겹치지_않는_표는_잇지_않는다(self) -> None:
        detached = [
            Segment("2026-06", "cur", {"2026-01": Decimal(100)}),
            Segment("2011-06", "old", {"2000-01": Decimal(50)}),
        ]

        with pytest.raises(HousingLinkError, match="겹치는 달이 없다"):
            link(detached)

    def test_이을_표가_없으면_멈춘다(self) -> None:
        with pytest.raises(HousingLinkError, match="이을 표가 없다"):
            link([])

    def test_기준이_될_표가_비면_멈춘다(self) -> None:
        with pytest.raises(HousingLinkError, match="기준이 될 표가 비어 있다"):
            link([Segment("2026-06", "cur", {})])
