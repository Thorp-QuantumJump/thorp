"""⚠ [임시] `indicators.json`을 만드는 생성기 — 008 완성 시 이 디렉터리와 함께 지운다.

`indicators.json`은 17개 지표 × 390개월이라 손으로 쓸 수 없고, 손으로 쓸 수 없는 파일을
출처 없이 커밋하면 나중에 "이 값이 왜 이런가"에 아무도 답하지 못한다. 그래서 생성기를 같은
디렉터리에 둔다 — 폐기할 때 함께 사라지고, 그 전까지는 값의 근거가 코드로 남는다.

**값은 지어낸 것이다**(008 FR-022). 실제 경제 자료가 아니므로 "2014년이 지금과 가까웠다"
같은 결론을 여기서 읽으면 안 된다. 이 파일이 담는 것은 값이 아니라 **모양**이다 —
contracts/macro-indicator-port.md §4.4의 열 가지를 일부러 심는다. 그 모양이 없으면
`NOT_COMPUTABLE`·동률·건너뛰기 경로가 조용히 검증 밖으로 나간다.

다시 만들기::

    .venv/Scripts/python -m data_pipeline.regime.macro.fixture.build_indicators

같은 씨앗을 쓰므로 몇 번을 돌려도 같은 파일이 나온다.
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass
from pathlib import Path
from typing import Final

OUTPUT_PATH: Final[Path] = Path(__file__).resolve().parent / "indicators.json"

#: 자료 버전·등록표 버전은 `FIXTURE-`로 시작한다 (계약 §4.2). 이 접두사가 식별 키·API 응답·
#: 기록에 실려 다니므로 값이 어느 경로로 흘러가도 가짜임이 드러나고, 폐기가 조건 한 줄로 끝난다.
DATA_VERSION: Final[str] = "FIXTURE-20260915"
REGISTRY_VERSION: Final[str] = "FIXTURE-REG-1"

FIRST_MONTH: Final[tuple[int, int]] = (1994, 1)
LAST_MONTH: Final[tuple[int, int]] = (2026, 6)

#: 008 FR-001의 7개 분야. 이름과 순서는 화면 분류이며 계산 가중과 별개다 (008 FR-003).
FIELDS: Final[tuple[tuple[str, str, int], ...]] = (
    ("PRODUCTION", "생산", 1),
    ("CONSUMPTION_INVESTMENT", "소비·투자", 2),
    ("EXTERNAL_TRADE", "대외거래", 3),
    ("LABOR", "노동", 4),
    ("PRICES_COMMODITIES", "물가·원자재", 5),
    ("FINANCE_HOUSING", "금융·주택", 6),
    ("GLOBAL_ECONOMY", "해외 경기", 7),
)


@dataclass(frozen=True)
class IndicatorSpec:
    """지표 하나를 어떻게 지어낼지.

    ``center``·``spread``는 값의 크기만 정한다. 규칙 검증에 쓰이는 것은 아래 defect 함수들이
    심는 **모양**이지 이 숫자가 아니다.
    """

    id: str
    name: str
    field_id: str
    unit: str
    transform: str
    display_order: int
    center: float
    spread: float
    decimals: int
    publication_lag_days: int
    base_level: float
    first_month: tuple[int, int] = FIRST_MONTH
    last_month: tuple[int, int] = LAST_MONTH


#: 008 FR-001이 고정한 17개 지표. 변환 이름은 그 표의 "변환" 열이다.
INDICATORS: Final[tuple[IndicatorSpec, ...]] = (
    IndicatorSpec(
        "ALL_INDUSTRY_PRODUCTION", "전산업생산", "PRODUCTION", "%", "YOY_GROWTH_RATE",
        1, 3.1, 2.6, 2, 30, 100.0,
    ),
    IndicatorSpec(
        "MINING_MANUFACTURING_PRODUCTION", "광공업생산", "PRODUCTION", "%", "YOY_GROWTH_RATE",
        2, 3.4, 4.8, 2, 30, 100.0,
    ),
    IndicatorSpec(
        "SERVICE_PRODUCTION", "서비스업생산", "PRODUCTION", "%", "YOY_GROWTH_RATE",
        3, 2.9, 2.1, 2, 30, 100.0,
    ),
    IndicatorSpec(
        "RETAIL_SALES_INDEX", "소매판매액지수", "CONSUMPTION_INVESTMENT", "%", "YOY_GROWTH_RATE",
        1, 2.4, 3.3, 2, 30, 100.0,
    ),
    IndicatorSpec(
        "FACILITY_INVESTMENT_INDEX", "설비투자지수", "CONSUMPTION_INVESTMENT", "%",
        "YOY_GROWTH_RATE", 2, 3.0, 9.4, 2, 30, 100.0,
    ),
    IndicatorSpec(
        "CONSTRUCTION_COMPLETED", "건설기성액(불변)", "CONSUMPTION_INVESTMENT", "%",
        "YOY_GROWTH_RATE", 3, 1.8, 6.7, 2, 30, 100.0,
    ),
    IndicatorSpec(
        "EXPORTS", "수출", "EXTERNAL_TRADE", "%", "YOY_GROWTH_RATE", 1, 6.2, 11.5, 2, 15, 300.0,
    ),
    IndicatorSpec(
        "IMPORTS", "수입", "EXTERNAL_TRADE", "%", "YOY_GROWTH_RATE", 2, 6.0, 12.1, 2, 15, 290.0,
    ),
    IndicatorSpec(
        "TRADE_BALANCE", "무역수지", "EXTERNAL_TRADE", "억달러", "OFFICIAL_AMOUNT",
        3, 28.0, 22.0, 1, 15, 28.0,
    ),
    IndicatorSpec(
        "EMPLOYED_PERSONS", "취업자 수", "LABOR", "%", "YOY_GROWTH_RATE",
        1, 1.3, 1.1, 2, 14, 2200.0,
    ),
    IndicatorSpec(
        "UNEMPLOYMENT_RATE", "실업률", "LABOR", "%", "SEASONALLY_ADJUSTED_RATE",
        2, 3.5, 0.6, 1, 14, 3.5,
    ),
    IndicatorSpec(
        "CONSUMER_PRICES", "소비자물가", "PRICES_COMMODITIES", "%", "YOY_GROWTH_RATE",
        1, 2.3, 1.4, 2, 5, 80.0,
    ),
    IndicatorSpec(
        "DUBAI_CRUDE", "두바이유", "PRICES_COMMODITIES", "달러/배럴", "MONTHLY_AVERAGE",
        2, 62.0, 24.0, 2, 3, 62.0,
    ),
    IndicatorSpec(
        "TREASURY_BOND_3Y", "국고채 3년", "FINANCE_HOUSING", "%", "MONTH_END",
        1, 3.4, 1.7, 2, 1, 3.4,
    ),
    IndicatorSpec(
        "USD_KRW", "원/달러 환율", "FINANCE_HOUSING", "원", "MONTH_END",
        2, 1140.0, 95.0, 1, 1, 1140.0,
    ),
    IndicatorSpec(
        "HOUSING_PRICES", "주택가격", "FINANCE_HOUSING", "%", "MOM_GROWTH_RATE",
        3, 0.21, 0.42, 2, 2, 70.0,
    ),
    IndicatorSpec(
        "G20_LEADING_INDEX", "G20 경기선행지수", "GLOBAL_ECONOMY", "%", "YOY_GROWTH_RATE",
        1, 0.2, 1.5, 2, 45, 100.0,
        first_month=(2005, 1),
        last_month=(2026, 4),
    ),
)

#: 변환값이 곧 공표값인 지표들. 원값과 변환값이 같다.
_LEVEL_TRANSFORMS: Final[frozenset[str]] = frozenset(
    {"OFFICIAL_AMOUNT", "SEASONALLY_ADJUSTED_RATE", "MONTHLY_AVERAGE", "MONTH_END"}
)


def month_index(month: tuple[int, int]) -> int:
    """(연, 월)을 통월 번호로."""
    return month[0] * 12 + (month[1] - 1)


def month_label(index: int) -> str:
    """통월 번호를 ``YYYY-MM``으로."""
    return f"{index // 12:04d}-{index % 12 + 1:02d}"


def months_between(first: tuple[int, int], last: tuple[int, int]) -> list[int]:
    return list(range(month_index(first), month_index(last) + 1))


def _walk(spec: IndicatorSpec, months: list[int]) -> dict[int, float]:
    """평균으로 돌아오는 난수 걸음. 씨앗이 지표 id라 몇 번을 돌려도 같은 값이 나온다."""
    rng = random.Random(f"thorp-010-fixture:{spec.id}")
    values: dict[int, float] = {}
    current = spec.center
    for index in months:
        current = spec.center + (current - spec.center) * 0.82 + rng.gauss(0.0, spec.spread * 0.45)
        values[index] = round(current, spec.decimals)
    return values


# --------------------------------------------------------------------------------------
# 계약 §4.4의 열 가지 모양. 어느 것도 "정상 계열에 잡음을 더한 것"이 아니라 **판정 경로 하나씩**을
# 겨냥한다. 심는 자리는 전부 과거의 특정 구간이라 기본 기준월(T_g·T_all)의 계산은 멀쩡하다 —
# 실패 경로는 사용자가 과거 기준월을 지정했을 때 드러난다.
# --------------------------------------------------------------------------------------

#: 모양 2 — 척도 구간 한가운데 뚫는 구멍. t-3에서 거슬러 센 연속 구간이 60개월 미만이 되는
#: 기준월(2012-08 ~ 2017-04쯤)에서 그 분야가 계산 불가가 된다 (spec FR-005).
SCALE_HOLE_MONTH: Final[tuple[int, int]] = (2012, 5)
SCALE_HOLE_INDICATOR: Final[str] = "TRADE_BALANCE"

#: 모양 3 — MAD=0인데 IQR≠0. 120개월 창에서 61개월이 같은 값, 30개월이 그보다 작고 29개월이
#: 크면 중앙값 편차의 중앙값은 0이고 25백분위는 중앙값보다 작다. 기준월 2018-06의 창
#: (2008-04 ~ 2018-03)을 겨냥한다 (spec FR-006의 IQR 대체).
MAD_ZERO_INDICATOR: Final[str] = "CONSUMER_PRICES"
MAD_ZERO_WINDOW: Final[tuple[tuple[int, int], tuple[int, int]]] = ((2008, 4), (2018, 3))
MAD_ZERO_VALUE: Final[float] = 2.10

#: 모양 4 — MAD도 IQR도 0인 완전 상수 구간. 기준월 2010-03 ~ 2012-03의 창이 통째로 이 안에
#: 들어가 `SCALE_ZERO`가 된다 (spec FR-006).
FLAT_INDICATOR: Final[str] = "HOUSING_PRICES"
FLAT_WINDOW: Final[tuple[tuple[int, int], tuple[int, int]]] = ((2000, 1), (2011, 12))
FLAT_VALUE: Final[float] = 0.15

#: 모양 8 — 질의 3개월 중 한 달만 결측. 기본 기준월 2026-06의 t-1이라 **기본 경로에서** 지표별
#: 비교 시점과 빠진 지표·월 표기가 드러난다 (spec FR-001·FR-031).
QUERY_ONE_MISSING_INDICATOR: Final[str] = "ALL_INDUSTRY_PRODUCTION"
QUERY_ONE_MISSING_MONTH: Final[tuple[int, int]] = (2026, 5)

#: 모양 9 — 질의 3개월이 전부 결측. 기준월 2015-03이면 이 지표의 비교 시점이 하나도 남지
#: 않아 그 분야가 `QUERY_INPUT_MISSING`이 된다 (spec FR-007).
QUERY_ALL_MISSING_INDICATOR: Final[str] = "CONSTRUCTION_COMPLETED"
QUERY_ALL_MISSING_MONTHS: Final[tuple[tuple[int, int], ...]] = ((2015, 1), (2015, 2), (2015, 3))

#: 모양 6 — 인접 월이 서로 가까운 구간. 이 구간의 후보들이 나란히 상위에 올라 ±6개월
#: 건너뛰기가 실제로 후보를 걸러낸다 (spec FR-012·FR-014).
CLUSTER_WINDOW: Final[tuple[tuple[int, int], tuple[int, int]]] = ((2014, 2), (2014, 10))

#: 모양 7 — 거리가 **정확히** 같은 두 후보. 두 3개월 블록의 값을 그대로 복사해 저장 자릿수까지
#: 같게 만든다. 근사 동률이 아니라 정확한 동률이어야 '오래된 월 우선'이 시험된다 (spec FR-012).
TIE_SOURCE_END: Final[tuple[int, int]] = (2010, 11)
TIE_TARGET_END: Final[tuple[int, int]] = (2003, 6)

#: 모양 6·7이 LABOR에서 성립하려면 질의 3개월이 서로 가까워야 한다. 그래야 인접한 후보들이
#: 함께 상위에 오른다.
LABOR_QUERY_VALUES: Final[dict[str, tuple[float, float, float]]] = {
    "EMPLOYED_PERSONS": (1.42, 1.43, 1.44),
    "UNEMPLOYMENT_RATE": (3.2, 3.2, 3.3),
}
LABOR_CLUSTER_VALUES: Final[dict[str, tuple[float, ...]]] = {
    # 질의 값 언저리에서 조금씩 다른 값. 똑같이 두면 이 안에서도 동률이 생겨 모양 7과 섞인다.
    "EMPLOYED_PERSONS": (1.40, 1.41, 1.45, 1.46, 1.47, 1.48, 1.49, 1.50, 1.51),
    "UNEMPLOYMENT_RATE": (3.2, 3.2, 3.3, 3.2, 3.2, 3.3, 3.2, 3.3, 3.2),
}


def _apply_shapes(spec: IndicatorSpec, values: dict[int, float]) -> dict[int, float]:
    """계약 §4.4의 모양을 심는다. 결측은 키를 지워 만든다 — 값으로 표시하지 않는다."""
    if spec.id == SCALE_HOLE_INDICATOR:
        values.pop(month_index(SCALE_HOLE_MONTH), None)

    if spec.id == QUERY_ONE_MISSING_INDICATOR:
        values.pop(month_index(QUERY_ONE_MISSING_MONTH), None)

    if spec.id == QUERY_ALL_MISSING_INDICATOR:
        for month in QUERY_ALL_MISSING_MONTHS:
            values.pop(month_index(month), None)

    if spec.id == FLAT_INDICATOR:
        start, end = (month_index(bound) for bound in FLAT_WINDOW)
        for index in range(start, end + 1):
            values[index] = FLAT_VALUE

    if spec.id == MAD_ZERO_INDICATOR:
        start, end = (month_index(bound) for bound in MAD_ZERO_WINDOW)
        window = list(range(start, end + 1))
        below, equal = 30, 61
        for position, index in enumerate(window):
            if position < below:
                values[index] = round(MAD_ZERO_VALUE - 0.9 + 0.02 * position, 2)
            elif position < below + equal:
                values[index] = MAD_ZERO_VALUE
            else:
                values[index] = round(MAD_ZERO_VALUE + 0.2 + 0.03 * (position - below - equal), 2)

    if spec.id in LABOR_QUERY_VALUES:
        last = month_index(spec.last_month)
        for offset, value in enumerate(LABOR_QUERY_VALUES[spec.id]):
            values[last - 2 + offset] = value

        cluster_start = month_index(CLUSTER_WINDOW[0])
        for offset, value in enumerate(LABOR_CLUSTER_VALUES[spec.id]):
            values[cluster_start + offset] = value

        # 모양 7은 마지막에 둔다. 앞의 덮어쓰기가 복사 원본을 바꾸면 동률이 깨진다.
        source_end, target_end = month_index(TIE_SOURCE_END), month_index(TIE_TARGET_END)
        for back in range(3):
            values[target_end - back] = values[source_end - back]

    return values


def _raw_series(
    spec: IndicatorSpec, values: dict[int, float], months: list[int]
) -> dict[int, float]:
    """변환값에서 원값을 되돌린다 (008 FR-006 — 둘 다 보존한다).

    변환이 이미 공표값인 지표는 원값과 같다. 증가율 지표는 비교 시점의 값에 증가율을 곱해
    수준을 쌓는다. **결측 달은 원값도 없다** — 같은 관측이 빠진 것이므로 한쪽만 채우면
    "원값은 있는데 변환값이 없다"는 있을 수 없는 상태가 생긴다.
    """
    if spec.transform in _LEVEL_TRANSFORMS:
        return dict(values)

    lag = 12 if spec.transform == "YOY_GROWTH_RATE" else 1
    raw: dict[int, float] = {}
    last_known = spec.base_level
    for index in months:
        if index not in values:
            continue
        base = raw.get(index - lag, last_known)
        level = base * (1.0 + values[index] / 100.0)
        raw[index] = round(level, 2)
        last_known = level
    return raw


def build_indicator(spec: IndicatorSpec) -> dict[str, object]:
    months = months_between(spec.first_month, spec.last_month)
    values = _apply_shapes(spec, _walk(spec, months))
    raw = _raw_series(spec, values, months)
    # 개정 여부를 시험할 자리. 가장 최근 두 달을 개정분으로 둔다 (008 FR-007).
    revised = [month_label(index) for index in months[-2:] if index in values]
    return {
        "id": spec.id,
        "name": spec.name,
        "fieldId": spec.field_id,
        "unit": spec.unit,
        "transform": spec.transform,
        "displayOrder": spec.display_order,
        "publicationLagDays": spec.publication_lag_days,
        "startMonth": month_label(months[0]),
        "value": [values.get(index) for index in months],
        "raw": [raw.get(index) for index in months],
        "revisedMonths": revised,
    }


def build_document() -> dict[str, object]:
    return {
        "note": (
            "⚠ 임시 대역. 값은 지어낸 것이며 008 완성 시 이 디렉터리와 함께 지운다. "
            "담는 것은 값이 아니라 contracts/macro-indicator-port.md §4.4의 열 가지 모양이다. "
            "build_indicators.py 로 다시 만든다."
        ),
        # 만든 날짜를 넣지 않는다. 씨앗이 같아도 날이 바뀌면 파일이 달라져 "다시 만들었더니
        # diff가 생겼다"가 되고, 그 diff는 값이 바뀐 것인지 날짜만 바뀐 것인지 알려 주지 않는다.
        # 언제 자료인지는 `dataVersion`이 이미 담고 있다. snapshot의 content_hash에 computed_at을
        # 넣지 않기로 한 것과 같은 이유다(plan.md 위험 표).
        "dataVersion": DATA_VERSION,
        "registryVersion": REGISTRY_VERSION,
        "fields": [
            {"id": field_id, "name": name, "displayOrder": order}
            for field_id, name, order in FIELDS
        ],
        "indicators": [build_indicator(spec) for spec in INDICATORS],
    }


def main() -> int:
    document = build_document()
    OUTPUT_PATH.write_text(
        json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    months = len(document["indicators"][0]["value"])  # type: ignore[index]
    print(f"{OUTPUT_PATH.name}: 지표 {len(INDICATORS)}개 × 최대 {months}개월")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
