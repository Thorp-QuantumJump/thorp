"""화면 분류 — 분야·지표의 이름과 순서 (spec 008 FR-003).

**계산 등록표와 따로 둔다.** 화면 문구를 고쳤다고 과거 검색 결과가 달라지면 안 되고, 반대로
분야 매핑을 고쳤다고 이름까지 새 버전이 될 이유도 없다. 두 버전이 서로 다른 속도로 움직이므로
:mod:`~data_pipeline.macro.registry` 와 이 모듈을 나눠 둔다.

여기 있는 이름은 spec FR-001 표와 pd11-source-verification.md 의 표기를 그대로 옮긴 것이다.
**번역하거나 다듬지 않는다** — 화면에 나갈 말은 기획이 정하는 것이고, 코드가 고쳐 쓰면 문서와
화면이 갈라진다.
"""

from __future__ import annotations

from typing import Final

from data_pipeline.macro.registry import (
    FIELD_CONSUMPTION_INVESTMENT,
    FIELD_EXTERNAL_TRADE,
    FIELD_FINANCE_HOUSING,
    FIELD_LABOR,
    FIELD_OVERSEAS,
    FIELD_PRICES_COMMODITIES,
    FIELD_PRODUCTION,
)

#: 분야 표시: ``분야 id -> (이름, 순서, 설명)``. 순서는 FR-001 표의 차례다.
FIELDS: Final[dict[str, tuple[str, int, str | None]]] = {
    FIELD_PRODUCTION: ("생산", 1, "전산업·광공업·서비스업 생산 지수"),
    FIELD_CONSUMPTION_INVESTMENT: ("소비·투자", 2, "소매판매·설비투자·건설기성"),
    FIELD_EXTERNAL_TRADE: ("대외거래", 3, "수출·수입과 무역수지"),
    FIELD_LABOR: ("노동", 4, "취업자 수와 실업률"),
    FIELD_PRICES_COMMODITIES: ("물가·원자재", 5, "소비자물가와 국제 유가"),
    FIELD_FINANCE_HOUSING: ("금융·주택", 6, "금리·환율과 주택 매매가격"),
    FIELD_OVERSEAS: ("해외 경기", 7, "주요국 경기선행지수"),
}

#: 지표 표시: ``지표 id -> (이름, 순서)``. 순서는 분야 차례를 따라 1부터 이어진다.
INDICATORS: Final[dict[str, tuple[str, int]]] = {
    "all_industry_production": ("전산업생산", 1),
    "mining_manufacturing_production": ("광공업생산", 2),
    "service_production": ("서비스업생산", 3),
    "retail_sales_index": ("소매판매액지수", 4),
    "facility_investment_index": ("설비투자지수", 5),
    "construction_completed_constant": ("건설기성액(불변)", 6),
    "exports": ("수출", 7),
    "imports": ("수입", 8),
    "trade_balance": ("무역수지", 9),
    "employed_persons": ("취업자 수", 10),
    "unemployment_rate": ("실업률", 11),
    "consumer_price_index": ("소비자물가", 12),
    "dubai_crude": ("두바이유", 13),
    "treasury_bond_3y": ("국고채 3년", 14),
    "usd_krw_rate": ("원/달러 환율", 15),
    "housing_sale_price": ("주택 매매가격", 16),
    "g20_cli": ("G20 경기선행지수", 17),
}
