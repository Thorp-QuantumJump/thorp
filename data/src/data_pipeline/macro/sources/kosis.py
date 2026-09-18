"""국가통계포털 어댑터 — 생산·소비·투자·노동·물가 등 10개 표 (spec 008, pd11 §2).

**표 하나를 특정하는 데 네 가지가 필요하다.** 기관(``orgId``)·통계표(``tblId``)·항목(``itmId``)·
분류(``objL1``·``objL2``)다. 항목까지만 주면 통계표 안의 여러 계열이 한꺼번에 오고, 대상월이
같은 행이 여러 개 생긴다. 등록표가 넷을 모두 들고 있다(FR-005).

요청의 분류 이름과 응답의 분류 이름이 다르다 — 보낼 때는 ``objL1``·``objL2`` 인데 돌아올 때는
``C1``·``C2`` 다. 같은 것을 가리키는 두 이름이라 여기서 짝지어 확인한다(FR-011b).

**``LST_CHN_DE`` 는 통계표 갱신일이지 관측치별 발표일이 아니다**(FR-007). 개정이 있었는지를
가리는 데는 쓸 수 있지만 "이 달 값이 언제 발표됐다"로 읽으면 안 된다. 그래서 발표일 자리가 아니라
갱신일 자리에 담는다.

**단위는 원천이 준 문자열을 그대로 둔다.** 국가통계포털은 기준 표기에 전각 등호(``2020＝100``)를
쓰는데 등록표의 ``raw_unit`` 은 반각(``2020=100``)이다. 등록표 쪽은 우리가 화면과 문서에 쓰려고
정한 표기이고 여기 있는 것은 원천이 실제로 보낸 글자라, 둘을 같게 만들려고 어느 한쪽을 고치지
않는다 — 원천 표기를 우리 표기로 덮으면 원천이 단위를 진짜 바꿨을 때 그 변화가 묻힌다.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from typing import Any, Final

from data_pipeline.macro.registry import IndicatorDefinition
from data_pipeline.macro.sources.base import (
    SourceContractError,
    SourceObservation,
    parse_decimal,
    require_keys,
    require_mapping,
)

#: 행마다 있어야 하는 필드.
_ROW_KEYS: Final[tuple[str, ...]] = ("PRD_DE", "DT", "ITM_ID", "TBL_ID", "ORG_ID", "UNIT_NM")

#: 단위를 뺀 필수 필드. 옛 통계표는 ``UNIT_NM`` 을 주지 않는다 — 아래 `parse_series` 참고.
_ROW_KEYS_WITHOUT_UNIT: Final[tuple[str, ...]] = tuple(k for k in _ROW_KEYS if k != "UNIT_NM")

#: 요청의 분류 이름 → 응답의 분류 이름. 같은 것을 가리키는 두 이름이다.
_RESPONSE_DIMENSION: Final[Mapping[str, str]] = {"objL1": "C1", "objL2": "C2"}

#: 월별 자료만 받는다. 등록표의 17개가 모두 월 단위다.
PERIOD_MONTHLY: Final[str] = "M"


def _reject_link_chain(indicator: IndicatorDefinition) -> None:
    """개편으로 나뉜 계열을 표 하나로 받으려 하면 멈춘다 (FR-010).

    거절하는 이유는 **실패가 성공처럼 보이기 때문이다.** 사슬의 첫 표만 받아도 응답은
    정상이고 값도 멀쩡하다. 다만 계열이 등록표가 말하는 구간보다 짧을 뿐인데, 그건 값을
    들여다봐서는 알 수 없다. 주택가격이면 2000-01 부터라고 적혀 있는 자료가 실제로는
    2021-06 부터가 되고, 그 상태로 발행하면 경제환경 검색이 볼 수 있는 과거가 조용히
    5년으로 줄어든다.
    """
    if indicator.link_chain:
        raise SourceContractError(
            f"{indicator.indicator_id}: 개편으로 나뉜 계열이라 표 하나로 받을 수 없다."
            f" 등록표의 사슬에 표가 {len(indicator.link_chain)}개 있다"
            " — data_pipeline.macro.sources.housing 의 build_params/parse_table 로 표마다"
            " 받은 뒤 link() 로 이어야 한다. 이 경로로 받으면 계열이"
            f" {indicator.link_chain[0].coverage_start} 부터만 와서"
            f" 등록표의 시작월 {indicator.series_start} 와 어긋난다"
        )


def build_series_params(
    org_id: str,
    table_code: str,
    item_code: str,
    classification: tuple[tuple[str, str], ...],
    start: str,
    end: str,
) -> dict[str, str]:
    """통계표 하나를 받을 조회 인자. **인증키는 넣지 않는다** — 부르는 쪽이 그때 붙인다.

    국가통계포털은 항목·분류 코드 끝에 ``+`` 를 붙여 "이 코드만"을 뜻한다. 빠뜨리면 하위
    분류가 모두 딸려 온다.

    지표가 아니라 통계표를 인자로 받는 이유는 개편으로 나뉜 계열 때문이다. 주택가격은 표가
    셋이고 각 표의 기관·항목·분류가 달라, 지표 하나에 매인 함수로는 부를 수 없다.
    """
    params = {
        "method": "getList",
        "orgId": org_id,
        "tblId": table_code,
        "itmId": f"{item_code}+",
        "format": "json",
        "jsonVD": "Y",
        "prdSe": PERIOD_MONTHLY,
        "startPrdDe": start.replace("-", ""),
        "endPrdDe": end.replace("-", ""),
    }
    for dimension, code in classification:
        params[dimension] = f"{code}+"
    return params


def build_params(indicator: IndicatorDefinition, start: str, end: str) -> dict[str, str]:
    """지표 하나를 표 하나로 받을 조회 인자. 사슬이 있는 지표는 거절한다."""
    _reject_link_chain(indicator)
    if not indicator.table_code or not indicator.item_code:
        raise SourceContractError(f"{indicator.indicator_id}: 등록표에 통계표나 항목이 없다")
    if not indicator.org_id:
        raise SourceContractError(
            f"{indicator.indicator_id}: 등록표에 기관 코드가 없다."
            " 통계표 id 만으로는 표가 특정되지 않는다"
        )
    if not indicator.classification:
        raise SourceContractError(
            f"{indicator.indicator_id}: 등록표에 분류가 없다."
            " 항목 코드만으로는 계열이 하나로 좁혀지지 않는다"
        )

    return build_series_params(
        indicator.org_id,
        indicator.table_code,
        indicator.item_code,
        indicator.classification,
        start,
        end,
    )


def _rows(payload: Any, context: str) -> Sequence[Mapping[str, Any]]:
    """응답을 목록으로 만든다. 오류는 매핑으로 오므로 거기서 갈린다."""
    if isinstance(payload, Mapping):
        if "err" in payload:
            raise SourceContractError(
                f"{context}: 국가통계포털이 거절했다 [{payload.get('err')}]"
                f" {payload.get('errMsg')}"
            )
        raise SourceContractError(
            f"{context}: 응답이 목록이 아닌데 오류 표기도 없다. 구조가 바뀌었는지 본다"
        )
    if not isinstance(payload, Sequence) or isinstance(payload, str):
        raise SourceContractError(f"{context}: 응답이 목록이 아니다({type(payload).__name__})")
    return payload


def _check_classification(
    classification: tuple[tuple[str, str], ...], row: Mapping[str, Any], context: str
) -> None:
    """등록표가 고른 계열이 맞는지 행마다 본다.

    걸러 내지 않고 멈추는 이유는, 다른 분류가 섞였다는 것은 요청이 아니라 **표의 구조가
    바뀌었다**는 뜻이기 때문이다. 조용히 버리면 계열이 짧아진 이유가 남지 않는다.
    """
    for dimension, code in classification:
        field = _RESPONSE_DIMENSION[dimension]
        actual = row.get(field)
        if actual is None:
            raise SourceContractError(
                f"{context}: 등록표는 {dimension}={code} 를 고르는데 응답에 {field} 가 없다"
            )
        if str(actual) != code:
            raise SourceContractError(
                f"{context}: 등록표의 {dimension} 는 {code} 인데 {actual} 이(가) 왔다."
                " 다른 계열이 섞였는지 본다"
            )


def _check_identity(
    org_id: str, table_code: str, item_code: str, row: Mapping[str, Any], context: str
) -> None:
    for field, expected in (
        ("ORG_ID", org_id),
        ("TBL_ID", table_code),
        ("ITM_ID", item_code),
    ):
        if str(row[field]) != str(expected):
            raise SourceContractError(
                f"{context}: 등록표의 {field} 는 {expected} 인데 {row[field]} 이(가) 왔다"
            )


def _check_period(period: str, context: str) -> str:
    if len(period) != 6 or not period.isdigit():
        raise SourceContractError(
            f"{context}: 대상월 표기가 YYYYMM 이 아니다({period!r}). 월별 자료만 받는다"
        )
    return f"{period[:4]}-{period[4:]}"


def parse_series(
    payload: Any,
    *,
    indicator_id: str,
    org_id: str,
    table_code: str,
    item_code: str,
    classification: tuple[tuple[str, str], ...],
    require_unit: bool = True,
) -> list[SourceObservation]:
    """통계표 하나의 응답을 읽는다. 개편으로 나뉜 계열의 각 표도 이 경로를 쓴다.

    ``require_unit`` 을 끄는 자리는 **연결 사슬의 옛 표**다. 2011.6 기준 주택가격 표
    (``DT_304N_04_00001``)는 ``UNIT_NM`` 을 아예 주지 않고, 2021.6 표는 자기 기준월인
    ``2021.6＝100.0`` 을 준다. 어느 쪽이든 이어 붙인 뒤에는 쓸 수 없는 값이다 — 연결이
    끝나면 모든 달이 현행 기준월로 옮겨지므로 계열의 단위는 현행 표의 것 하나이고, 옛 표의
    기준월을 그 자리에 두면 같은 계열 안에 기준이 셋 섞인다. 그래서 사슬의 표에서는 단위를
    읽지 않고 등록표의 ``raw_unit`` 을 쓴다.
    """
    required = _ROW_KEYS if require_unit else _ROW_KEYS_WITHOUT_UNIT
    context = f"kosis {indicator_id} {table_code}"

    observations: list[SourceObservation] = []
    for index, raw_row in enumerate(_rows(payload, context)):
        where = f"{context} {index}번째 행"
        row = require_mapping(raw_row, where)
        require_keys(row, required, where)
        _check_identity(org_id, table_code, item_code, row, where)
        _check_classification(classification, row, where)

        period = _check_period(str(row["PRD_DE"]), where)
        observations.append(
            SourceObservation(
                indicator_id=indicator_id,
                period=period,
                value=parse_decimal(row["DT"], f"{context} {period}"),
                unit=row.get("UNIT_NM"),
                # 통계표 갱신일이다. 관측치별 발표일이 아니다 (FR-007).
                source_updated_at=row.get("LST_CHN_DE"),
            )
        )
    return observations


def parse(indicator: IndicatorDefinition, payload: Any) -> list[SourceObservation]:
    """지표 하나를 표 하나에서 읽는다. 사슬이 있는 지표는 거절한다."""
    _reject_link_chain(indicator)
    if not indicator.org_id or not indicator.table_code or not indicator.item_code:
        raise SourceContractError(
            f"{indicator.indicator_id}: 등록표에 기관·통계표·항목이 다 있지 않다"
        )
    return parse_series(
        payload,
        indicator_id=indicator.indicator_id,
        org_id=indicator.org_id,
        table_code=indicator.table_code,
        item_code=indicator.item_code,
        classification=indicator.classification,
    )
