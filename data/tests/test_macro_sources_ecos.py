"""한국은행 어댑터 계약 테스트 (T018).

표본은 2026-09-18에 실제로 받아 `tests/fixtures/macro/ecos/`에 저장한 응답이다. 네트워크를
쓰지 않으므로 인증키 없는 환경에서도 돈다(FR-022).
"""

from __future__ import annotations

import json
import pathlib
from decimal import Decimal

import pytest

from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.sources import ecos
from data_pipeline.macro.sources.base import SourceContractError

FIXTURES = pathlib.Path(__file__).parent / "fixtures" / "macro" / "ecos"

BY_ID = {indicator.indicator_id: indicator for indicator in INDICATORS}


def load(name: str) -> object:
    return json.loads((FIXTURES / f"{name}.json").read_text(encoding="utf-8"))


class TestTreasuryBond3y:
    """국고채 3년 — 일별 계열(817Y002 / 010200000)."""

    def test_일별_행을_날짜로_읽는다(self) -> None:
        observations = ecos.parse(BY_ID["treasury_bond_3y"], load("treasury_bond_3y_daily"))

        assert len(observations) == 174
        assert observations[0].period == "2026-01-02"
        assert observations[0].value == Decimal("2.935")
        assert observations[-1].period == "2026-09-16"
        assert observations[-1].value == Decimal("4.053")

    def test_단위는_원천이_준_값을_쓴다(self) -> None:
        observations = ecos.parse(BY_ID["treasury_bond_3y"], load("treasury_bond_3y_daily"))

        assert {o.unit for o in observations} == {"연%"}

    def test_휴장일은_행_자체가_오지_않는다(self) -> None:
        """값을 0으로 채워 보내지 않는다. 월 기말·월 평균이 이 사실에 기댄다(T028)."""
        observations = ecos.parse(BY_ID["treasury_bond_3y"], load("treasury_bond_3y_daily"))
        periods = {o.period for o in observations}

        assert "2026-01-01" not in periods  # 신정
        assert "2026-01-03" not in periods  # 토요일
        assert all(o.value is not None for o in observations)

    def test_발표일을_수신_시각으로_채우지_않는다(self) -> None:
        observations = ecos.parse(BY_ID["treasury_bond_3y"], load("treasury_bond_3y_daily"))

        assert all(o.source_updated_at is None for o in observations)


class TestUsdKrwRate:
    """원/달러 환율 — 월별 표의 말일자료(731Y004 / 0000001 · 0000200)."""

    def test_월별_행을_대상월로_읽는다(self) -> None:
        observations = ecos.parse(BY_ID["usd_krw_rate"], load("usd_krw_monthly"))

        assert len(observations) == 32
        assert observations[0].period == "2024-01"
        assert observations[-1].period == "2026-08"
        assert observations[-1].value == Decimal("1376.5")

    def test_항목이_둘인_계열을_둘로_확인한다(self) -> None:
        assert ecos.item_codes(BY_ID["usd_krw_rate"]) == ("0000001", "0000200")

    def test_평균자료가_섞이면_멈춘다(self) -> None:
        """말일자료(0000200) 자리에 평균자료(0000100)가 오면 그냥 넘기지 않는다."""
        payload = load("usd_krw_monthly")
        payload["StatisticSearch"]["row"][3]["ITEM_CODE2"] = "0000100"  # type: ignore[index]

        with pytest.raises(SourceContractError, match="다른 계열이 섞였는지"):
            ecos.parse(BY_ID["usd_krw_rate"], payload)


class TestDubaiCrude:
    """두바이유 — 원천이 월값을 직접 준다(902Y003 / 010102)."""

    def test_월값을_그대로_읽는다(self) -> None:
        observations = ecos.parse(BY_ID["dubai_crude"], load("dubai_crude_monthly"))

        assert len(observations) == 32
        assert observations[0].period == "2024-01"
        assert observations[0].value == Decimal("79.58")
        assert observations[-1].period == "2026-08"
        assert observations[-1].value == Decimal("87.5")
        assert observations[-1].unit == "달러/배럴"


class TestCycle:
    def test_국고채만_일별이다(self) -> None:
        assert ecos.cycle_for(BY_ID["treasury_bond_3y"]) == "D"
        assert ecos.cycle_for(BY_ID["usd_krw_rate"]) == "M"
        assert ecos.cycle_for(BY_ID["dubai_crude"]) == "M"

    def test_조회_경로에_인증키를_끼워_넣지_않는다(self) -> None:
        path = ecos.build_path(BY_ID["usd_krw_rate"], "202401", "202608")

        assert "{key}" in path
        assert path.endswith("/731Y004/M/202401/202608/0000001/0000200")


class TestContractViolations:
    def test_원천이_거절하면_원천_문구를_그대로_올린다(self) -> None:
        payload = {"RESULT": {"CODE": "INFO-200", "MESSAGE": "해당하는 데이터가 없습니다."}}

        with pytest.raises(SourceContractError, match="INFO-200"):
            ecos.parse(BY_ID["dubai_crude"], payload)

    def test_자료가_없으면_빈_목록이지_실패가_아니다(self) -> None:
        assert ecos.parse(BY_ID["dubai_crude"], {"StatisticSearch": {"list_total_count": 0}}) == []

    def test_봉투가_바뀌면_멈춘다(self) -> None:
        with pytest.raises(SourceContractError, match="구조가 바뀌었는지"):
            ecos.parse(BY_ID["dubai_crude"], {"Statistics": {"row": []}})

    def test_필드가_사라지면_멈춘다(self) -> None:
        payload = load("dubai_crude_monthly")
        del payload["StatisticSearch"]["row"][0]["UNIT_NAME"]  # type: ignore[index]

        with pytest.raises(SourceContractError, match="UNIT_NAME"):
            ecos.parse(BY_ID["dubai_crude"], payload)

    def test_주기와_다른_기간_표기는_멈춘다(self) -> None:
        """월별 계열에 일별 표기가 오면 대상월을 잘라 맞추지 않는다."""
        payload = load("dubai_crude_monthly")
        payload["StatisticSearch"]["row"][0]["TIME"] = "20240115"  # type: ignore[index]

        with pytest.raises(SourceContractError, match="대상 기간 표기"):
            ecos.parse(BY_ID["dubai_crude"], payload)

    @pytest.mark.parametrize("special", ["NaN", "sNaN", "Infinity", "-Infinity"])
    def test_유한하지_않은_수를_값으로_받지_않는다(self, special: str) -> None:
        """Decimal 은 NaN·Infinity 를 정상적으로 받으므로 그냥 두면 '값 있는 관측'이 된다.

        결측도 계약 위반도 아닌 세 번째 상태가 되어 증가율이 NaN 인데 결측 사유는 비고,
        NaN 과의 비교는 모두 거짓이라 010 의 거리·순위가 조용히 어긋난다 (원칙 VI).
        """
        payload = load("dubai_crude_monthly")
        payload["StatisticSearch"]["row"][0]["DATA_VALUE"] = special  # type: ignore[index]

        with pytest.raises(SourceContractError, match="유한한 수가 아니다"):
            ecos.parse(BY_ID["dubai_crude"], payload)

    def test_등록표가_모르는_항목_자리가_생기면_멈춘다(self) -> None:
        """항목이 하나뿐인 계열은 예전에 ITEM_CODE2 를 아예 확인하지 않았다."""
        payload = load("dubai_crude_monthly")
        payload["StatisticSearch"]["row"][0]["ITEM_CODE2"] = "S1"  # type: ignore[index]

        with pytest.raises(SourceContractError, match="다른 계열이 섞였는지"):
            ecos.parse(BY_ID["dubai_crude"], payload)

    def test_같은_기간이_두_번_오면_멈춘다(self) -> None:
        """겹친 채로 내보내면 월 기말·증가율이 임의의 한 행을 골라 값이 틀린다."""
        payload = load("dubai_crude_monthly")
        rows = payload["StatisticSearch"]["row"]  # type: ignore[index]
        duplicate = dict(rows[0])
        duplicate["DATA_VALUE"] = "999"
        payload["StatisticSearch"]["row"] = [rows[0], duplicate]  # type: ignore[index]

        with pytest.raises(SourceContractError, match="같은 기간이 여러 번"):
            ecos.parse(BY_ID["dubai_crude"], payload)

    def test_숫자가_아닌_값은_결측으로_묻지_않는다(self) -> None:
        payload = load("dubai_crude_monthly")
        payload["StatisticSearch"]["row"][0]["DATA_VALUE"] = "8x.5"  # type: ignore[index]

        with pytest.raises(SourceContractError, match="숫자로 읽지 못했다"):
            ecos.parse(BY_ID["dubai_crude"], payload)
