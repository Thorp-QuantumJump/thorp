"""한국은행 경제통계시스템 어댑터 — 국고채 3년·원/달러 환율·두바이유 (spec 008, pd11 §1).

세 계열이 주기도 항목 구조도 다르다. 국고채는 일별이고 항목이 하나, 환율은 월별이면서 항목이
둘(통화와 평균/말일 구분), 두바이유는 월별에 항목이 하나다. 등록표가 그 차이를 들고 있고 여기서는
읽어 쓴다(FR-005).

**항목2를 빠뜨리면 다른 계열이 조용히 섞인다.** 환율의 731Y004 는 같은 통화에 대해 평균자료
(``0000100``)와 말일자료(``0000200``)를 함께 담는다. 항목2 없이 부르면 두 계열이 같은 대상월에
두 번 들어오고, 둘 다 그럴듯한 환율이라 눈으로는 구분되지 않는다. 그래서 응답을 받은 뒤에도
등록표가 정한 항목과 같은지 행마다 확인한다(FR-011b).

**발표일은 이 원천에서 얻을 수 없다.** ``StatisticSearch`` 응답에는 발표일도 개정 식별자도 없다
(pd11 §1). 수신 시각을 발표일로 대신 적지 않고 비워 둔다(FR-007).
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

#: 응답 본문이 들어 있는 키. 오류일 때는 대신 ``RESULT`` 가 온다.
_ENVELOPE: Final[str] = "StatisticSearch"
_ERROR_ENVELOPE: Final[str] = "RESULT"

#: 행마다 있어야 하는 필드. 하나라도 없으면 구조가 바뀐 것이다.
_ROW_KEYS: Final[tuple[str, ...]] = ("TIME", "DATA_VALUE", "ITEM_CODE1", "UNIT_NAME")

#: 주기별 대상 기간 표기 길이. 일별은 ``YYYYMMDD``, 월별은 ``YYYYMM`` 이다.
_CYCLE_LENGTH: Final[Mapping[str, int]] = {"D": 8, "M": 6}

#: 응답이 담을 수 있는 항목 자리 전부. **등록표가 언급하지 않은 자리도 본다** — 언급한
#: 수만큼만 확인하면 원천이 항목을 *더했을* 때 검증을 빠져나간다.
_MAX_ITEM_LEVELS: Final[int] = 4


def cycle_for(indicator: IndicatorDefinition) -> str:
    """등록표의 확보 구간 표기로 주기를 가른다.

    국고채만 일별이다. 월별 표(721Y001)는 항목2 구분이 없는 평균값이라 월 기말을 얻을 수
    없어서 일별 표를 쓴다(pd11 §1).
    """
    return "D" if indicator.table_code == "817Y002" else "M"


def item_codes(indicator: IndicatorDefinition) -> tuple[str, ...]:
    """등록표의 항목 코드를 조회 경로에 넣을 순서대로 나눈다.

    환율만 ``0000001/0000200`` 처럼 둘이다. 나머지는 하나다.
    """
    if not indicator.item_code:
        raise SourceContractError(f"{indicator.indicator_id}: 등록표에 항목 코드가 없다")
    return tuple(indicator.item_code.split("/"))


def build_path(
    indicator: IndicatorDefinition,
    start: str,
    end: str,
    *,
    start_row: int = 1,
    end_row: int = 100000,
) -> str:
    """인증키를 뺀 조회 경로를 만든다. 키는 부르는 쪽이 앞에 붙인다.

    키를 여기서 끼워 넣지 않는 이유는 이 문자열이 그대로 로그와 오류 메시지에 실리기
    때문이다(config 모듈 docstring 참고).
    """
    codes = "/".join(item_codes(indicator))
    return (
        f"StatisticSearch/{{key}}/json/kr/{start_row}/{end_row}/"
        f"{indicator.table_code}/{cycle_for(indicator)}/{start}/{end}/{codes}"
    )


def _rows(payload: Any, context: str) -> Sequence[Mapping[str, Any]]:
    """응답 봉투를 벗긴다. 오류 봉투면 원천이 준 문구를 그대로 올린다."""
    body = require_mapping(payload, context)
    if _ERROR_ENVELOPE in body:
        result = require_mapping(body[_ERROR_ENVELOPE], f"{context} {_ERROR_ENVELOPE}")
        code = result.get("CODE")
        message = result.get("MESSAGE")
        raise SourceContractError(f"{context}: 한국은행이 거절했다 [{code}] {message}")
    if _ENVELOPE not in body:
        raise SourceContractError(
            f"{context}: 응답에 {_ENVELOPE} 도 {_ERROR_ENVELOPE} 도 없다. 구조가 바뀌었는지 본다"
        )
    envelope = require_mapping(body[_ENVELOPE], f"{context} {_ENVELOPE}")
    rows = envelope.get("row")
    if rows is None:
        # 조회 구간에 자료가 없을 때 온다. 실패가 아니라 빈 결과다.
        return ()
    if not isinstance(rows, Sequence) or isinstance(rows, str):
        raise SourceContractError(f"{context}: row 가 목록이 아니다({type(rows).__name__})")
    return rows


def _check_period(period: str, cycle: str, context: str) -> str:
    expected = _CYCLE_LENGTH[cycle]
    if len(period) != expected or not period.isdigit():
        raise SourceContractError(
            f"{context}: 대상 기간 표기가 {cycle} 주기와 맞지 않다({period!r})."
            f" {expected}자리 숫자를 기대한다"
        )
    if cycle == "D":
        return f"{period[:4]}-{period[4:6]}-{period[6:]}"
    return f"{period[:4]}-{period[4:]}"


def parse(indicator: IndicatorDefinition, payload: Any) -> list[SourceObservation]:
    """저장된 응답이든 방금 받은 응답이든 같은 규칙으로 읽는다.

    **등록표가 정한 항목과 다른 행은 버리지 않고 멈춘다.** 걸러 내면 계열이 조용히 짧아지고,
    남은 값만 보면 아무 문제가 없어 보인다.
    """
    context = f"ecos {indicator.indicator_id}"
    cycle = cycle_for(indicator)
    expected = item_codes(indicator)

    observations: list[SourceObservation] = []
    for index, raw_row in enumerate(_rows(payload, context)):
        row = require_mapping(raw_row, f"{context} {index}번째 행")
        require_keys(row, _ROW_KEYS, f"{context} {index}번째 행")

        # 등록표가 언급한 자리 뒤까지 본다. 두바이유는 항목이 하나뿐이라 예전에는
        # ITEM_CODE2 를 아예 확인하지 않았고, 원천이 항목2 구분을 더하면 같은 대상월에
        # 서로 다른 값의 두 행이 들어와도 통과했다.
        actual = tuple(
            str(row[f"ITEM_CODE{level}"])
            for level in range(1, _MAX_ITEM_LEVELS + 1)
            if row.get(f"ITEM_CODE{level}") is not None
        )
        if actual != expected:
            raise SourceContractError(
                f"{context} {index}번째 행: 등록표의 항목은 {'/'.join(expected)} 인데"
                f" {'/'.join(actual) or '없음'} 이 왔다. 다른 계열이 섞였는지 본다"
            )

        period = _check_period(str(row["TIME"]), cycle, f"{context} {index}번째 행")
        observations.append(
            SourceObservation(
                indicator_id=indicator.indicator_id,
                period=period,
                value=parse_decimal(row["DATA_VALUE"], f"{context} {period}"),
                unit=row["UNIT_NAME"],
                # 한국은행은 발표일을 주지 않는다. 수신 시각으로 대신하지 않는다 (FR-007).
                source_updated_at=None,
            )
        )

    # 항목 검사가 놓친 갈래가 있으면 여기서 드러난다. 겹친 채로 내보내면 뒤의 월 기말·
    # 증가율이 같은 기간의 여러 행 중 하나를 임의로 골라 값이 조용히 틀린다.
    periods = [o.period for o in observations]
    duplicated = sorted({p for p in periods if periods.count(p) > 1})
    if duplicated:
        raise SourceContractError(
            f"{context}: 같은 기간이 여러 번 왔다({', '.join(duplicated[:5])})."
            " 계열이 하나로 좁혀지지 않았다"
        )
    return observations
