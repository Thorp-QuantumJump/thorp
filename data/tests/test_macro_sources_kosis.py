"""국가통계포털 어댑터 계약 테스트 (T019).

표본은 2026-09-18에 실제로 받아 `tests/fixtures/macro/kosis/`에 저장한 응답이다. 10개 표의
항목·분류 코드가 pd11 §2 표와 같은지 확인한다.
"""

from __future__ import annotations

import json
import pathlib
from decimal import Decimal

import pytest

from data_pipeline.macro.config import SOURCE_KOSIS
from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.sources import kosis
from data_pipeline.macro.sources.base import SourceContractError

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro" / "kosis"

BY_ID = {indicator.indicator_id: indicator for indicator in INDICATORS}

#: 10개 표와 pd11 §2가 확인한 최근 값. 표본 파일 이름은 지표 id 와 같다.
#: 주택가격만 현행 기준월 표라 파일 이름이 다르다.
#:
#: 단위는 **원천이 보낸 글자 그대로**다. 국가통계포털은 전각 등호(2020＝100)를 쓰는데 pd11 은
#: 반각으로 옮겨 적었고 등록표도 반각이다. 둘을 같게 맞추지 않는 이유는 kosis 모듈에 적었다.
EXPECTED: dict[str, tuple[str, str, str, str]] = {
    # 지표 id: (표본 이름, 최근 대상월, 최근 값, 단위)
    "all_industry_production": ("all_industry_production", "2026-07", "117.9", "2020＝100"),
    "mining_manufacturing_production": (
        "mining_manufacturing_production", "2026-07", "120.4", "2020＝100",
    ),
    "service_production": ("service_production", "2026-07", "145.1", "2020＝100"),
    "retail_sales_index": ("retail_sales_index", "2026-07", "122.9", "2020＝100"),
    "facility_investment_index": ("facility_investment_index", "2026-07", "143.4", "2020＝100"),
    "construction_completed_constant": (
        "construction_completed_constant", "2026-07", "8820913", "백만원",
    ),
    "employed_persons": ("employed_persons", "2026-08", "29151", "천명"),
    "unemployment_rate": ("unemployment_rate", "2026-08", "2.7", "%"),
    "consumer_price_index": ("cpi", "2026-08", "120.05", "2020＝100"),
}

#: 표 하나로 받지 않는 지표. 개편으로 나뉘어 등록표가 연결 사슬을 든다 (FR-010).
#: 조회·파싱은 `test_macro_housing_link.py` 가 덮는다.
LINKED: frozenset[str] = frozenset({"housing_sale_price"})


def load(name: str) -> object:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


def test_국가통계포털_지표가_열개다() -> None:
    kosis_ids = {i.indicator_id for i in INDICATORS if i.primary_source == SOURCE_KOSIS}

    assert kosis_ids == set(EXPECTED) | LINKED


@pytest.mark.parametrize("indicator_id", sorted(EXPECTED))
def test_열개_표의_항목과_분류를_읽는다(indicator_id: str) -> None:
    fixture, last_period, last_value, unit = EXPECTED[indicator_id]

    observations = kosis.parse(BY_ID[indicator_id], load(fixture))

    assert observations, f"{indicator_id}: 표본에서 관측값이 하나도 나오지 않았다"
    assert observations[-1].period == last_period
    assert observations[-1].value == Decimal(last_value)
    assert observations[-1].unit == unit


@pytest.mark.parametrize("indicator_id", sorted(EXPECTED))
def test_대상월이_겹치지_않는다(indicator_id: str) -> None:
    """분류를 덜 주면 여러 계열이 섞여 같은 대상월이 여러 번 온다. 그걸 잡는 시험이다."""
    fixture = EXPECTED[indicator_id][0]

    periods = [o.period for o in kosis.parse(BY_ID[indicator_id], load(fixture))]

    assert len(periods) == len(set(periods))


@pytest.mark.parametrize("indicator_id", sorted(EXPECTED))
def test_통계표_갱신일을_갱신일_자리에_담는다(indicator_id: str) -> None:
    """LST_CHN_DE 는 관측치별 발표일이 아니다 (FR-007)."""
    fixture = EXPECTED[indicator_id][0]

    observations = kosis.parse(BY_ID[indicator_id], load(fixture))

    assert all(o.source_updated_at for o in observations)


class TestBuildParams:
    def test_분류가_둘인_표는_둘_다_보낸다(self) -> None:
        params = kosis.build_params(BY_ID["mining_manufacturing_production"], "202301", "202608")

        assert params["orgId"] == "101"
        assert params["tblId"] == "DT_1F02001"
        assert params["itmId"] == "T10+"
        assert params["objL1"] == "00+"
        assert params["objL2"] == "0+"

    def test_분류가_objL2_하나뿐인_표는_objL1을_지어내지_않는다(self) -> None:
        params = kosis.build_params(BY_ID["facility_investment_index"], "202301", "202608")

        assert params["objL2"] == "C+"
        assert "objL1" not in params

    def test_사슬이_있는_지표는_표_하나로_받기를_거절한다(self) -> None:
        """사슬의 첫 표만 받아도 응답은 정상이라 짧아진 줄 모른다 (FR-010)."""
        with pytest.raises(SourceContractError, match="표 하나로 받을 수 없다"):
            kosis.build_params(BY_ID["housing_sale_price"], "202106", "202608")

    def test_거절_문구가_옳은_경로를_가리킨다(self) -> None:
        with pytest.raises(SourceContractError, match="sources.housing"):
            kosis.parse(BY_ID["housing_sale_price"], [])

    def test_인증키를_인자에_넣지_않는다(self) -> None:
        params = kosis.build_params(BY_ID["consumer_price_index"], "202301", "202608")

        assert "apiKey" not in params


class TestContractViolations:
    def test_원천이_거절하면_원천_문구를_그대로_올린다(self) -> None:
        payload = {"err": "20", "errMsg": "필수요청변수값이 누락되었습니다. (objL)"}

        with pytest.raises(SourceContractError, match="필수요청변수값"):
            kosis.parse(BY_ID["consumer_price_index"], payload)

    def test_다른_분류가_섞이면_멈춘다(self) -> None:
        payload = load("all_industry_production")
        payload[0]["C1"] = "1"  # type: ignore[index]

        with pytest.raises(SourceContractError, match="다른 계열이 섞였는지"):
            kosis.parse(BY_ID["all_industry_production"], payload)

    def test_다른_통계표가_오면_멈춘다(self) -> None:
        payload = load("cpi")
        payload[0]["TBL_ID"] = "DT_1J22004"  # type: ignore[index]

        with pytest.raises(SourceContractError, match="TBL_ID"):
            kosis.parse(BY_ID["consumer_price_index"], payload)

    def test_분류_필드가_사라지면_멈춘다(self) -> None:
        payload = load("cpi")
        del payload[0]["C1"]  # type: ignore[index]

        with pytest.raises(SourceContractError, match="응답에 C1 가 없다"):
            kosis.parse(BY_ID["consumer_price_index"], payload)

    def test_필드가_사라지면_멈춘다(self) -> None:
        payload = load("cpi")
        del payload[0]["UNIT_NM"]  # type: ignore[index]

        with pytest.raises(SourceContractError, match="UNIT_NM"):
            kosis.parse(BY_ID["consumer_price_index"], payload)

    def test_월별이_아닌_기간_표기는_멈춘다(self) -> None:
        payload = load("cpi")
        payload[0]["PRD_DE"] = "2026"  # type: ignore[index]

        with pytest.raises(SourceContractError, match="YYYYMM"):
            kosis.parse(BY_ID["consumer_price_index"], payload)
