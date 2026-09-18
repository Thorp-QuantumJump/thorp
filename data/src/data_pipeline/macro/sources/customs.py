"""관세청 수출입총괄 어댑터 — 수출·수입·무역수지 (spec 008, pd11 §4).

응답이 XML 이고 세 지표가 **한 응답에 함께 온다**. 다른 원천은 지표마다 따로 부르지만 여기서는
한 번 받아 셋으로 나누는 편이 호출 수를 3분의 1로 줄인다 — 전 구간이 37년이라 그 차이가 크다.

**기준 주소가 2026-08-12에 바뀌었다**(``1220000/Itemtrade`` → ``1220000/Newtrade``). 폐기된
경로로 부르면 인증키가 멀쩡해도 ``SERVICE_KEY_IS_NOT_REGISTERED_ERROR`` 가 온다(pd11 §4). 이
오류를 키 문제로 읽으면 원인이 주소라는 사실이 드러나지 않으므로, 그렇게 단정하지 않는 문구로
올린다(T047).

**조회 기간이 1년으로 제한된다.** 1989-01 부터 받으려면 연 단위로 37회 넘게 나눠 불러야 한다
(FR-017a). 나누는 일은 `base.split_months` 가 하고 여기서는 상한값만 안다.

**``<year>총계</year>`` 합계 행이 섞여 온다.** 월별 관측값으로 세면 그 해가 한 번 더, 그것도
열두 배 큰 값으로 들어간다. 걸러 내되 **걸러낸 행 수를 세어 함께 돌려준다**(FR-017a) — 수집
기록에 남아야 원천이 합계 행의 모양을 바꿔 걸러지지 않게 됐을 때 그 사실이 드러난다.

**무역수지는 원천이 주는 공식 값이다.** ``balPayments`` 를 그대로 쓰고 수출에서 수입을 빼지
않는다. 지금은 두 값이 정확히 일치하지만, 값이 같다는 것과 출처가 같다는 것은 다르다 — 원천이
정의를 바꾸면 파생식은 조용히 어긋난다(FR-006).
"""

from __future__ import annotations

from collections.abc import Iterator
from dataclasses import dataclass
from typing import Final
from xml.etree import ElementTree

from data_pipeline.macro.registry import IndicatorDefinition
from data_pipeline.macro.sources.base import (
    MAX_MONTHS_CUSTOMS,
    PermanentSourceError,
    SourceContractError,
    SourceObservation,
    parse_decimal,
)

#: 정상 응답의 결과 코드.
_RESULT_OK: Final[str] = "00"

#: 합계 행의 대상월 자리에 들어오는 값. 관측값이 아니다.
TOTAL_ROW_MARKER: Final[str] = "총계"

#: 응답의 세 금액 필드. 등록표의 `item_code` 가 이 중 하나를 가리킨다.
AMOUNT_FIELDS: Final[tuple[str, ...]] = ("expDlr", "impDlr", "balPayments")

#: 요청 하나가 담을 수 있는 개월 수. 더 넓게 부르면 자료가 오지 않는다.
MAX_MONTHS: Final[int] = MAX_MONTHS_CUSTOMS

#: 키가 아니라 주소가 원인일 때 오는 문구 (pd11 §4).
_STALE_PATH_MARKER: Final[str] = "SERVICE_KEY_IS_NOT_REGISTERED_ERROR"


def build_params(start: str, end: str) -> dict[str, str]:
    """조회 인자를 만든다. **인증키는 넣지 않는다** — 부르는 쪽이 그때 붙인다."""
    return {"strtYymm": start, "endYymm": end}


def _root(payload: str | ElementTree.Element, context: str) -> ElementTree.Element:
    if isinstance(payload, ElementTree.Element):
        return payload
    try:
        return ElementTree.fromstring(payload)
    except ElementTree.ParseError as error:
        raise SourceContractError(f"{context}: 응답을 XML 로 읽지 못했다({error})") from error


def _check_header(root: ElementTree.Element, context: str) -> None:
    code = root.findtext("./header/resultCode")
    message = (root.findtext("./header/resultMsg") or "").strip()
    if code is None:
        raise SourceContractError(f"{context}: 응답에 resultCode 가 없다. 구조가 바뀌었는지 본다")
    if code == _RESULT_OK:
        return
    if _STALE_PATH_MARKER in message or _STALE_PATH_MARKER in (code or ""):
        raise PermanentSourceError(
            f"{context}: 관세청이 [{code}] {message} 로 거절했다."
            " 인증키 문제로 단정하지 않는다 — 2026-08-12 에 기준 주소가"
            " 1220000/Itemtrade 에서 1220000/Newtrade 로 바뀌었고, 폐기된 경로로"
            " 부르면 키가 정상이어도 같은 오류가 온다"
        )
    raise PermanentSourceError(f"{context}: 관세청이 거절했다 [{code}] {message}")


def _periods(root: ElementTree.Element) -> Iterator[ElementTree.Element]:
    return iter(root.findall("./body/items/item"))


def _month(raw: str, context: str) -> str:
    """``2026.06`` 을 ``2026-06`` 으로 바꾼다."""
    year, _, month = raw.partition(".")
    if not year.isdigit() or len(year) != 4 or not month.isdigit() or len(month) != 2:
        raise SourceContractError(
            f"{context}: 대상월 표기가 YYYY.MM 이 아니다({raw!r})"
        )
    return f"{year}-{month}"


def parse(
    indicator: IndicatorDefinition, payload: str | ElementTree.Element
) -> list[SourceObservation]:
    """지표 하나 몫만 뽑는다. 세 지표는 같은 응답을 세 번 읽는다."""
    return parse_all(payload, (indicator,))[indicator.indicator_id]


@dataclass(frozen=True)
class TradeResponse:
    """한 응답에서 뽑은 것 전부. 관측값과 **걸러낸 것의 수**를 함께 든다 (FR-017a).

    걸러낸 행 수를 세어 두는 이유는, 응답에서 뺀 행이 몇 개인지가 수집 기록에 남아야 하기
    때문이다. 조용히 건너뛰면 "9개월치를 받았다"와 "10행을 받아 1행을 뺐다"가 같은 모양이
    되고, 원천이 합계 행의 모양을 바꿔 걸러지지 않게 됐을 때 그 사실이 드러나지 않는다.
    """

    observations: dict[str, list[SourceObservation]]
    #: ``<year>총계</year>`` 처럼 월별 관측값이 아니어서 뺀 행의 수.
    excluded_total_rows: int

    def __getitem__(self, indicator_id: str) -> list[SourceObservation]:
        return self.observations[indicator_id]


def parse_all(
    payload: str | ElementTree.Element, indicators: tuple[IndicatorDefinition, ...]
) -> TradeResponse:
    """한 응답에서 여러 지표를 한꺼번에 뽑는다. 전 구간 수집이 이 경로를 쓴다."""
    context = "customs"
    root = _root(payload, context)
    _check_header(root, context)

    for indicator in indicators:
        if indicator.item_code not in AMOUNT_FIELDS:
            raise SourceContractError(
                f"{context} {indicator.indicator_id}: 등록표의 항목 {indicator.item_code!r} 은"
                f" 응답 필드가 아니다. 쓸 수 있는 값은 {', '.join(AMOUNT_FIELDS)}"
            )

    result: dict[str, list[SourceObservation]] = {i.indicator_id: [] for i in indicators}
    excluded = 0
    for index, item in enumerate(_periods(root)):
        where = f"{context} {index}번째 행"
        raw_period = (item.findtext("year") or "").strip()
        if not raw_period:
            raise SourceContractError(f"{where}: year 가 없다. 구조가 바뀌었는지 본다")
        if raw_period == TOTAL_ROW_MARKER:
            # 그 해 열두 달의 합이다. 월별 관측값으로 세면 그 해가 열세 번 들어간다.
            excluded += 1
            continue

        period = _month(raw_period, where)
        for indicator in indicators:
            raw_value = item.findtext(str(indicator.item_code))
            if raw_value is None:
                raise SourceContractError(
                    f"{where}: {indicator.item_code} 가 없다. 구조가 바뀌었는지 본다"
                )
            result[indicator.indicator_id].append(
                SourceObservation(
                    indicator_id=indicator.indicator_id,
                    period=period,
                    value=parse_decimal(raw_value, f"{context} {indicator.indicator_id} {period}"),
                    unit=indicator.raw_unit,
                    # 관세청은 발표일도 개정 식별자도 주지 않는다 (FR-007).
                    source_updated_at=None,
                )
            )
    return TradeResponse(observations=result, excluded_total_rows=excluded)
