"""지표 등록표 — 17개 지표가 어디서 오고 무엇으로 바뀌는지의 단일 출처 (spec 008 FR-002).

값의 근거는 [pd11-source-verification.md]다. 2026-09-17에 17개 전부를 실제로 조회해 통계표·항목·
단위·확보 구간·작성기관을 확인했고, 여기 있는 것은 그 결과를 옮긴 것이다. **추정으로 채운 칸이
없다** — 확인하지 못한 항목은 값을 지어내지 않고 ``None`` 으로 두며 그것을 채우는 작업을 적어 둔다.

**어댑터는 여기서 읽을 뿐 스스로 고르지 않는다**(FR-005). 한국은행과 국가통계포털이 같은 지표를
줄 때 주 공급원을 고르는 것은 등록표이고, 어댑터가 고르면 같은 지표가 실행마다 다른 원천에서 와도
값이 왜 달라졌는지 되짚을 곳이 없다.

작성기관과 제공기관이 다를 수 있다(FR-016). 두바이유는 한국은행이 제공하지만 작성기관은
국제통화기금이고, 주택가격은 국가통계포털이 제공하지만 작성기관은 한국부동산원이다. 이용 조건은
**작성기관 쪽을 먼저 본다** — 제공 창구가 어디든 저작권은 작성기관에 있다.

아직 비워 둔 칸과 그것을 채울 작업::

    published_precision      T069 — 공표 정밀도
    minimum_effective_scale  010 소유 (PD-10). 008은 채우지 않는다 (FR-015)
    trading_calendar         거래일 달력 출처가 PD-11에서 확정되지 않았다
    refresh_months_n         T073 — 정기 수집을 몇 달 관찰한 뒤 실제 개정 관행에 맞춘다
"""

from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Final

from data_pipeline.macro.config import SOURCE_CUSTOMS, SOURCE_ECOS, SOURCE_KOSIS, SOURCE_OECD

#: 7개 분야. 009 계약과 같은 식별자다(data-model §1).
FIELD_PRODUCTION: Final[str] = "production"
FIELD_CONSUMPTION_INVESTMENT: Final[str] = "consumption_investment"
FIELD_EXTERNAL_TRADE: Final[str] = "external_trade"
FIELD_LABOR: Final[str] = "labor"
FIELD_PRICES_COMMODITIES: Final[str] = "prices_commodities"
FIELD_FINANCE_HOUSING: Final[str] = "finance_housing"
FIELD_OVERSEAS: Final[str] = "overseas"

FIELD_IDS: Final[tuple[str, ...]] = (
    FIELD_PRODUCTION,
    FIELD_CONSUMPTION_INVESTMENT,
    FIELD_EXTERNAL_TRADE,
    FIELD_LABOR,
    FIELD_PRICES_COMMODITIES,
    FIELD_FINANCE_HOUSING,
    FIELD_OVERSEAS,
)

#: 변환 여섯 (FR-001 표). 앞의 넷은 계산하고 뒤의 둘은 원천 값을 그대로 쓴다.
TRANSFORM_YOY: Final[str] = "YOY_GROWTH"
TRANSFORM_MOM: Final[str] = "MOM_GROWTH"
TRANSFORM_MONTH_END: Final[str] = "MONTH_END"
TRANSFORM_MONTH_AVERAGE: Final[str] = "MONTH_AVERAGE"
TRANSFORM_OFFICIAL_AMOUNT: Final[str] = "OFFICIAL_AMOUNT"
TRANSFORM_SEASONAL_RATE: Final[str] = "SEASONAL_RATE"

#: 권리 판정 상태 (data-model §1). UNVERIFIED 만 발행 보류 대상이다 (FR-016a).
REVIEW_VERIFIED: Final[str] = "VERIFIED"
REVIEW_PENDING_RIGHTS: Final[str] = "PENDING_RIGHTS"
REVIEW_UNVERIFIED: Final[str] = "UNVERIFIED"

#: 정기 재조회 구간의 잠정 기본값 (T012, FR-017). 관찰 뒤 T073 이 지표별로 조정한다.
#:
#: 관세청은 **매월 15일경 전월까지의 자료를 정정**한다고 안내한다(pd11 §4). 3개월은 그 주기보다
#: 짧지 않으므로 세 지표 모두 기본값을 그대로 쓴다. 몇 달치가 함께 정정되는지는 갱신을 봐야
#: 알 수 있고, 그것이 T073 의 일이다.
DEFAULT_REFRESH_MONTHS_N: Final[int] = 3


class RegistryError(ValueError):
    """등록표가 지켜야 할 불변식을 어겼다."""


@dataclass(frozen=True)
class IndicatorDefinition:
    """지표 하나의 등록 정보. 컬럼 이름은 `macro_indicator_definition` 과 같다."""

    indicator_id: str
    field_id: str
    primary_source: str
    table_code: str | None
    item_code: str | None
    raw_unit: str
    transformed_unit: str
    transform_rule: str
    agency: str
    attribution: str
    license_status: str
    license_review_status: str
    series_start: str
    revision_history_available: bool
    release_rule: str | None = None
    break_link_rule: str | None = None
    missing_rule: str | None = None
    refresh_months_n: int = DEFAULT_REFRESH_MONTHS_N
    # 아래 셋은 이 작업의 범위가 아니다. 모듈 docstring 참고.
    published_precision: int | None = None
    minimum_effective_scale: str | None = None
    trading_calendar: str | None = None


_KOSIS_ATTRIBUTION: Final[str] = "국가통계포털(KOSIS)"
_ECOS_ATTRIBUTION: Final[str] = "한국은행 경제통계시스템(ECOS)"
_STATISTICS_KOREA: Final[str] = "통계청"

_NO_RELEASE_DATE: Final[str] = (
    "원천이 관측치별 발표일을 주지 않는다. 수신 시각을 발표일로 쓰지 않는다"
)
_TABLE_UPDATED_AT: Final[str] = "LST_CHN_DE 는 통계표 갱신일이며 관측치별 발표일이 아니다"


def _kosis(
    indicator_id: str,
    field_id: str,
    table_code: str,
    item_code: str,
    raw_unit: str,
    transform_rule: str,
    series_start: str,
    *,
    agency: str = _STATISTICS_KOREA,
    transformed_unit: str = "%",
) -> IndicatorDefinition:
    return IndicatorDefinition(
        indicator_id=indicator_id,
        field_id=field_id,
        primary_source=SOURCE_KOSIS,
        table_code=table_code,
        item_code=item_code,
        raw_unit=raw_unit,
        transformed_unit=transformed_unit,
        transform_rule=transform_rule,
        agency=agency,
        attribution=_KOSIS_ATTRIBUTION,
        license_status="ALLOWED",
        license_review_status=REVIEW_VERIFIED,
        series_start=series_start,
        revision_history_available=True,
        release_rule=_TABLE_UPDATED_AT,
    )


#: 17개 지표. 순서는 FR-001 표를 따른다.
INDICATORS: Final[tuple[IndicatorDefinition, ...]] = (
    # --- 생산 (pd11 §2) ---
    _kosis("all_industry_production", FIELD_PRODUCTION, "DT_1JH20201", "T1", "2020=100",
           TRANSFORM_YOY, "2000-01"),
    _kosis("mining_manufacturing_production", FIELD_PRODUCTION, "DT_1F02001", "T10", "2020=100",
           TRANSFORM_YOY, "1980-01"),
    _kosis("service_production", FIELD_PRODUCTION, "DT_1KC2020", "T1", "2020=100",
           TRANSFORM_YOY, "2000-01"),
    # --- 소비·투자 (pd11 §2) ---
    _kosis("retail_sales_index", FIELD_CONSUMPTION_INVESTMENT, "DT_1K41012", "T1", "2020=100",
           TRANSFORM_YOY, "1995-01"),
    _kosis("facility_investment_index", FIELD_CONSUMPTION_INVESTMENT, "DT_1F70011", "T3",
           "2020=100", TRANSFORM_YOY, "1995-02"),
    _kosis("construction_completed_constant", FIELD_CONSUMPTION_INVESTMENT, "DT_1G18011", "T10",
           "백만원", TRANSFORM_YOY, "1997-07"),
    # --- 대외거래 (pd11 §4). 무역수지는 원천이 주는 공식 값이며 수출-수입 파생식이 아니다 ---
    IndicatorDefinition(
        indicator_id="exports", field_id=FIELD_EXTERNAL_TRADE, primary_source=SOURCE_CUSTOMS,
        table_code=None, item_code="expDlr", raw_unit="미국 달러", transformed_unit="%",
        transform_rule=TRANSFORM_YOY, agency="관세청", attribution="관세청 수출입총괄",
        license_status="ALLOWED", license_review_status=REVIEW_VERIFIED, series_start="1989-01",
        revision_history_available=False, release_rule=_NO_RELEASE_DATE,
        missing_rule="응답의 <year>총계</year> 합계 행은 관측값으로 세지 않는다",
    ),
    IndicatorDefinition(
        indicator_id="imports", field_id=FIELD_EXTERNAL_TRADE, primary_source=SOURCE_CUSTOMS,
        table_code=None, item_code="impDlr", raw_unit="미국 달러", transformed_unit="%",
        transform_rule=TRANSFORM_YOY, agency="관세청", attribution="관세청 수출입총괄",
        license_status="ALLOWED", license_review_status=REVIEW_VERIFIED, series_start="1989-01",
        revision_history_available=False, release_rule=_NO_RELEASE_DATE,
        missing_rule="응답의 <year>총계</year> 합계 행은 관측값으로 세지 않는다",
    ),
    IndicatorDefinition(
        indicator_id="trade_balance", field_id=FIELD_EXTERNAL_TRADE, primary_source=SOURCE_CUSTOMS,
        table_code=None, item_code="balPayments", raw_unit="미국 달러",
        transformed_unit="미국 달러", transform_rule=TRANSFORM_OFFICIAL_AMOUNT, agency="관세청",
        attribution="관세청 수출입총괄", license_status="ALLOWED",
        license_review_status=REVIEW_VERIFIED, series_start="1989-01",
        revision_history_available=False, release_rule=_NO_RELEASE_DATE,
        missing_rule="원천 제공값이다. 수출에서 수입을 빼 만들지 않는다",
    ),
    # --- 노동 (pd11 §2). 실업률은 원천이 계절조정한 값이라 우리가 다시 조정하지 않는다 ---
    _kosis("employed_persons", FIELD_LABOR, "DT_1DA7001S", "T30", "천명",
           TRANSFORM_YOY, "1999-06"),
    IndicatorDefinition(
        indicator_id="unemployment_rate", field_id=FIELD_LABOR, primary_source=SOURCE_KOSIS,
        table_code="DT_1DA9001S", item_code="T80", raw_unit="%", transformed_unit="%",
        transform_rule=TRANSFORM_SEASONAL_RATE, agency=_STATISTICS_KOREA,
        attribution=_KOSIS_ATTRIBUTION, license_status="ALLOWED",
        license_review_status=REVIEW_VERIFIED, series_start="1999-06",
        revision_history_available=True, release_rule=_TABLE_UPDATED_AT,
        missing_rule="원천이 계절조정한 값을 그대로 쓴다. 우리가 계절조정하지 않는다",
    ),
    # --- 물가·원자재 ---
    _kosis("consumer_price_index", FIELD_PRICES_COMMODITIES, "DT_1J22003", "T", "2020=100",
           TRANSFORM_YOY, "1980-01"),
    IndicatorDefinition(
        indicator_id="dubai_crude", field_id=FIELD_PRICES_COMMODITIES, primary_source=SOURCE_ECOS,
        table_code="902Y003", item_code="010102", raw_unit="달러/배럴",
        transformed_unit="달러/배럴", transform_rule=TRANSFORM_MONTH_AVERAGE,
        agency="국제통화기금(IMF)", attribution=_ECOS_ATTRIBUTION, license_status="ALLOWED",
        # 한국은행 약관은 상업 이용 제한 계열에 작성기관 승인을 요구하는데, 이 계열이 제한
        # 대상인지 물을 창구가 없다(pd11 §1). 조건 자체를 모르는 UNVERIFIED 와 구분해 두고
        # 쓴다 — 하나로 묶으면 발행이 보류되어 나머지 16개까지 멈춘다.
        license_review_status=REVIEW_PENDING_RIGHTS, series_start="1992-01",
        revision_history_available=False, release_rule=_NO_RELEASE_DATE,
    ),
    # --- 금융·주택 ---
    IndicatorDefinition(
        indicator_id="treasury_bond_3y", field_id=FIELD_FINANCE_HOUSING,
        primary_source=SOURCE_ECOS, table_code="817Y002", item_code="010200000", raw_unit="연%",
        transformed_unit="연%", transform_rule=TRANSFORM_MONTH_END, agency="한국은행",
        attribution=_ECOS_ATTRIBUTION, license_status="ALLOWED",
        license_review_status=REVIEW_VERIFIED, series_start="1998-11",
        revision_history_available=False, release_rule=_NO_RELEASE_DATE,
        missing_rule="일별 계열이다. 그 달 마지막 거래일 값을 쓰고 휴장일 값을 만들지 않는다",
    ),
    IndicatorDefinition(
        # 일별 표(731Y001)는 서울외국환중개 작성이라 쓰지 않는다. 월별 표의 말일자료가
        # 한국은행 작성이면서 월 기말 규칙을 그대로 충족한다(pd11 §1).
        indicator_id="usd_krw_rate", field_id=FIELD_FINANCE_HOUSING, primary_source=SOURCE_ECOS,
        table_code="731Y004", item_code="0000001/0000200", raw_unit="원", transformed_unit="원",
        transform_rule=TRANSFORM_MONTH_END, agency="한국은행", attribution=_ECOS_ATTRIBUTION,
        license_status="ALLOWED", license_review_status=REVIEW_VERIFIED, series_start="1964-05",
        revision_history_available=False, release_rule=_NO_RELEASE_DATE,
        missing_rule="항목2 0000200 말일자료다. 평균자료(0000100)와 섞지 않는다",
    ),
    IndicatorDefinition(
        indicator_id="housing_sale_price", field_id=FIELD_FINANCE_HOUSING,
        primary_source=SOURCE_KOSIS, table_code="DT_30404_C012", item_code="sales",
        raw_unit="2026.6=100", transformed_unit="%", transform_rule=TRANSFORM_MOM,
        agency="한국부동산원", attribution=_KOSIS_ATTRIBUTION, license_status="ALLOWED",
        license_review_status=REVIEW_VERIFIED, series_start="2021-06",
        revision_history_available=True, release_rule=_TABLE_UPDATED_AT,
        # 기준월이 바뀔 때마다 표가 새로 만들어지고 지수가 재기준화된다. 겹치는 구간의
        # 비율로 이으면 2000-01 까지 닿는다(pd11 §2). 연결 계수는 T026 이 산출한다.
        break_link_rule="기준월별 표 5개를 겹침 구간 비율로 연결한다 (T026)."
        " 연결 규칙을 정하기 전까지 2021-06 이전은 사용 가능 범위에서 제외한다",
    ),
    # --- 해외 경기 (pd11 §3). 원천이 주는 증가율(TR/GY)은 지수보다 여섯 달 늦어 쓰지 않는다 ---
    IndicatorDefinition(
        indicator_id="g20_cli", field_id=FIELD_OVERSEAS, primary_source=SOURCE_OECD,
        table_code="OECD.SDD.STES,DSD_STES@DF_CLI", item_code="G20.M.LI.AA.IX",
        raw_unit="지수", transformed_unit="%", transform_rule=TRANSFORM_YOY,
        agency="경제협력개발기구(OECD)", attribution="OECD Composite Leading Indicators",
        license_status="ALLOWED", license_review_status=REVIEW_VERIFIED, series_start="1961-01",
        revision_history_available=True,
        release_rule="응답 헤더 Last-Modified 를 데이터셋 갱신일로 쓴다."
        " SDMX 헤더의 Prepared 는 응답 생성 시각이라 개정 식별자로 쓰지 않는다",
        missing_rule="증가율은 지수에서 직접 계산한다. 원천의 TR/GY 계열을 쓰지 않는다 (FR-019)",
    ),
)

EXPECTED_INDICATOR_COUNT: Final[int] = 17


def nominal_weights() -> dict[str, Decimal]:
    """분야 간 1/7, 분야 안에서 동일가중인 명목 비중 (FR-004).

    **이것은 Thorp 의 설계 선택이지 경제적 중요성이 같다는 뜻이 아니다.** 근거를 등록표에
    남기는 것과 실제 거리 기여를 비교하는 것은 T040 이 이어받는다.
    """
    per_field: dict[str, list[str]] = {field_id: [] for field_id in FIELD_IDS}
    for indicator in INDICATORS:
        per_field[indicator.field_id].append(indicator.indicator_id)
    field_weight = Decimal(1) / Decimal(len(FIELD_IDS))
    weights: dict[str, Decimal] = {}
    for members in per_field.values():
        if not members:
            continue
        share = field_weight / Decimal(len(members))
        for indicator_id in members:
            weights[indicator_id] = share
    return weights


def validate(indicators: tuple[IndicatorDefinition, ...] = INDICATORS) -> None:
    """등록표 단위의 불변식을 본다. 행 단위 제약으로는 표현할 수 없는 것들이다.

    DB 의 ``CHECK`` 는 한 행만 보므로 "17개"와 "7개 분야"와 "주 공급원 하나"를 잡지 못한다.
    """
    if len(indicators) != EXPECTED_INDICATOR_COUNT:
        raise RegistryError(f"지표는 {EXPECTED_INDICATOR_COUNT}개여야 한다: {len(indicators)}개")

    seen: set[str] = set()
    for indicator in indicators:
        if indicator.indicator_id in seen:
            raise RegistryError(f"지표 id 가 겹친다: {indicator.indicator_id}")
        seen.add(indicator.indicator_id)
        if indicator.field_id not in FIELD_IDS:
            raise RegistryError(
                f"{indicator.indicator_id}의 분야가 7개 밖이다: {indicator.field_id}"
            )
        if indicator.refresh_months_n < 1:
            raise RegistryError(f"{indicator.indicator_id}의 재조회 구간이 1보다 작다")

    used_fields = {indicator.field_id for indicator in indicators}
    missing = set(FIELD_IDS) - used_fields
    if missing:
        raise RegistryError(f"지표가 하나도 없는 분야가 있다: {sorted(missing)}")


def by_field() -> dict[str, tuple[IndicatorDefinition, ...]]:
    """분야별 지표. 표시 순서는 `INDICATORS` 의 순서를 따른다."""
    grouped: dict[str, list[IndicatorDefinition]] = {field_id: [] for field_id in FIELD_IDS}
    for indicator in INDICATORS:
        grouped[indicator.field_id].append(indicator)
    return {field_id: tuple(members) for field_id, members in grouped.items()}
