"""KIND 공시에서 ETF 분배금 **지급일**을 받아 온다 (spec 001 FR-004, 004 계약).

KIND(Korea Investor's Network for Disclosure system)는 거래소가 운영하는 상장법인 공시 시스템이다.

## 왜 공시를 읽어야 하는가

일봉만으로도 **분배락일**(권리가 떨어지는 날)과 **주당 분배금**은 되살릴 수 있다
(:func:`data_pipeline.corporate_actions.observe_distributions`). 그러나 **지급일**은 가격에
흔적을 남기지 않으므로 일봉에서 얻을 수 없다. 저장 스키마와 워커는 지급일을 요구한다.

- 마이그레이션 ``ck_corporate_action_pay_date``: ``DISTRIBUTION``이면
  ``pay_date IS NOT NULL AND pay_date >= ex_date``
- :class:`data_pipeline.corporate_actions.CorporateAction` 도 같은 조건을 건다
- ``worker/src/worker/market_data.py`` 는 ``pay_date``가 빈 분배 행을 만나면 실행을 멈춘다

지급일은 기준일로부터 **2~12일** 뒤이고 연휴에 따라 달라져 추정할 수 없다. 그래서 공시에서 받는다.

## 원천의 성질 — 2026-09-16에 실제 응답을 받아 확인한 것이다

1. 목록 응답은 JSON이 아니라 **HTML 조각**이다. ``<tbody>``의 ``<tr>`` 다섯 칸이
   ``번호 | 시각 | 종목명 | 공시제목 | 제출인``이다.
2. **제목으로 걸러 주는 인자가 없다.** ``reportNm``에 제목을 넣으면 0건이 온다. 기간으로만 받아
   제목을 코드에서 거른다. 그래서 :func:`find_distribution_disclosures` 가 목록을 다 훑는다.
3. **반드시 전 페이지를 훑는다.** 목록은 공시 시각 내림차순이라 분배 공시가 뒤쪽 페이지에만
   있을 수 있다. 2026-07-28~08-05 9일 구간은 4,363건 중 402건이 분배 공시였는데 앞 3페이지에는
   한 건도 없었다. 총 페이지 수는 응답의 ``전체 <em>4,363</em>건 : <strong>1</strong>/44``에서
   읽는다(:func:`parse_paging`).
4. **일괄공시라 402행이 접수번호 5개로 묶인다.** 접수번호 단위로 중복을 없애고 본문을 한 번만
   받는다.
5. 본문 표의 종목코드 칸에는 단축코드가 아니라 **ISIN**(국제증권식별번호, ``KR7139230007``
   꼴)이 들어 있다. 그래서 이 모듈은 **ISIN으로 거른다.** 목록에 「종목명 변경」 공시가 실제로
   있어 이름은 바뀌므로 종목명 문자열로 맞추지 않는다.
6. 우리가 쓸 값은 **지급예정일**이다. 같은 표의 **지급기준일**과 혼동하면 안 된다.

## 본문이 두 가지 모양이라는 것

공시 제목이 2020년을 경계로 바뀌었고 **본문 표의 모양도 함께 바뀌었다**(2026-09-16 실측).

- 2020년 이후 ``ETF이익금분배신고(분배금안내)(일괄공시)`` — 가로 표다. 한 공시가 여러 종목을
  담고 **행마다 ISIN이 있다.** 이 모듈이 노리는 모양이다.
- 2019년 이전 ``ETF이익금분배신고(분배금안내)`` — 세로 표다. 한 공시가 한 펀드만 담고
  **ISIN 칸이 아예 없다.** 펀드 정식명(``우리 KOSEF 블루칩 상장지수증권투자신탁[주식]``)뿐이라
  ISIN으로 맞출 수 없다.

옛 모양을 조용히 버리면 빠진 분배를 아무도 모른다. 그래서 :func:`parse_distribution_rows` 는
옛 모양도 읽되 ``isin``을 ``None``으로 둔다.

## 옛 공시를 우리 종목에 어떻게 붙이는가

ISIN이 없으니 남은 단서는 ``종목`` 칸의 **펀드 정식명**뿐이다.
:func:`collect_distribution_history` 에 ``fund_names`` (``{펀드 정식명: ISIN}``) 를 주면
:func:`key_distribution_rows` 가 **갈래를 갈라** 붙인다 — ISIN이 있는 행은 ISIN으로,
없는 행은 이름으로. 붙은 건은 :attr:`DistributionRecord.matched_by` 에 어느 갈래였는지 남는다.

이름으로도 못 붙인 행은 버리지 않고 :class:`UnkeyedDisclosure` 로 **결과에 담아 드러낸다.**
목록이 비었다는 것과 읽지 못했다는 것은 :attr:`DistributionHistory.is_complete` 가 가른다.
왜 부분 일치가 아니라 완전 일치인지는 「6단계」 절의 주석에 실측 근거와 함께 적었다.

## 인코딩

문서(step 4)의 껍데기는 ``charset=euc-kr``을 선언하지만 **실제 바이트는 UTF-8이다**(2026-09-16
확인). 선언을 믿지 않고 UTF-8 → EUC-KR 차례로 해독한다(:func:`decode_kind_bytes`).
"""

from __future__ import annotations

import json
import re
import time
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from html.parser import HTMLParser
from pathlib import Path
from typing import Any, Final, Protocol

import requests

KIND_BASE_URL: Final[str] = "https://kind.krx.co.kr"

#: 공시 목록 조각. ``method``가 ETF 묶음을 고른다.
LIST_PATH: Final[str] = "/disclosure/disclosurebystocktype.do"
LIST_METHOD: Final[str] = "searchDisclosureByStockTypeEtfSub"

#: 문서 선택(step 3)과 본문 경로(step 4)를 모두 이 경로가 ``method``로 가른다.
VIEWER_PATH: Final[str] = "/common/disclsviewer.do"

#: 거를 공시 제목의 머리말. 2019년까지는 ``(분배금안내)``, 2020년부터는 ``(분배금안내)(일괄공시)``가
#: 뒤에 붙으므로 **머리말로만** 맞춘다. 비슷하지만 다른
#: ``ETF이익금분배 수익자 확정을 위한 설정/환매접수 일시중지 안내(일괄공시)``는 이 머리말로
#: 시작하지 않아 걸러진다 — ``ETF이익금분배`` 뒤가 ``신고``가 아니라 공백이기 때문이다.
DISTRIBUTION_TITLE_PREFIX: Final[str] = "ETF이익금분배신고"

#: 한 번에 받을 목록 행 수. 원천이 더 큰 값도 받지만(5000 확인) 기본은 온건하게 둔다.
DEFAULT_PAGE_SIZE: Final[int] = 100

#: 호출 간격(초). 거래소 서버에 부담을 주지 않기 위한 것이며 HTTP 구현에만 둔다 —
#: 순수 함수는 기다리지 않아야 테스트가 빠르다.
DEFAULT_REQUEST_INTERVAL: Final[float] = 0.2

DEFAULT_TIMEOUT_SECONDS: Final[float] = 60.0

#: ISIN(국제증권식별번호). 국내 상장분은 ``KR`` + 10글자다.
ISIN_PATTERN: Final[re.Pattern[str]] = re.compile(r"\AKR[0-9A-Z]{10}\Z")

#: 종목 식별자. :data:`data_pipeline.krx.INSTRUMENT_ID_PATTERN` 과 같은 모양이며, 대응표
#: 파일이 ISIN과 함께 들고 있는 값을 검사하는 데만 쓴다. 이 모듈이 :mod:`data_pipeline.krx`
#: 를 끌어오지 않으려고 여기에 따로 둔다 — KIND 수집은 원천 API와 아무 관계가 없다.
INSTRUMENT_ID_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A[0-9A-Z]{6}\.XKRX\Z")

#: 목록 행의 공시 링크에서 접수번호를 읽는다. 14글자이되 **숫자만은 아니다** — 2006~2009년
#: 공시는 ``20061227U00003`` 처럼 날짜 여덟 자리 뒤에 영문 한 글자가 온다(2026-09-16 실측).
#: 숫자로만 못 박으면 그 시기 목록에서 접수번호를 하나도 읽지 못해 수집이 멈춘다.
_VIEWER_CALL: Final[re.Pattern[str]] = re.compile(r"openDisclsViewer\(\s*'([0-9A-Za-z]{14})'")
_PAGING: Final[re.Pattern[str]] = re.compile(
    r"전체\s*<em>([\d,]+)</em>\s*건\s*:\s*<strong>(\d+)</strong>\s*/\s*(\d+)"
)
_OPTION: Final[re.Pattern[str]] = re.compile(
    r"<option\b[^>]*?\bvalue\s*=\s*'([^']*)'([^>]*)>", re.IGNORECASE
)
_SET_PATH: Final[re.Pattern[str]] = re.compile(r"parent\.setPath\(\s*'[^']*'\s*,\s*'([^']+)'")
_DOC_NO_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A\d{14}\Z")

#: 가로 표(2020년 이후)의 열 이름. 공백을 지운 뒤 맞춘다.
_COL_ISIN: Final[str] = "종목코드"
_COL_RECORD_DATE: Final[str] = "투자신탁분배금지급기준일"
_COL_PAY_DATE: Final[str] = "투자신탁분배금지급예정일"
_COL_AMOUNT: Final[str] = "분배금(원)"

#: 종목 이름 열의 이름은 해에 따라 다르다. 둘 중 **하나만** 있으면 된다.
#: ``종목약명``은 2020년 이후, ``종목명``은 2019년의 일괄공시에서 관측했다
#: (2019-10-29 접수 ``20191029000131`` 실측). 하나로 못 박으면 2019년 일괄공시 22건이
#: 통째로 :class:`KindParseError` 가 되어 그 해 수집이 멈춘다.
_COL_SHORT_NAME_ALIASES: Final[tuple[str, ...]] = ("종목약명", "종목명")

#: 열 자리표에서 종목 이름 열을 가리키는 내부 열쇠. 실제 열 이름이 둘이라 따로 둔다.
_SHORT_NAME_KEY: Final[str] = "_short_name"

#: 세로 표(2019년 이전)의 항목 이름. 금액 항목 이름이 가로 표와 다르다.
_LEGACY_NAME: Final[str] = "종목"
_LEGACY_AMOUNT: Final[str] = "1좌당예상분배금(원)"


class KindError(RuntimeError):
    """KIND 공시 수집 과정의 오류."""


class KindParseError(KindError):
    """응답이 약속한 모양이 아니다. 값을 지어내거나 조용히 버리지 않고 알린다."""


# ---------------------------------------------------------------------------
# 값
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Paging:
    """목록 응답이 알려 주는 쪽수. ``total_pages``를 모르면 전 페이지를 돌 수 없다."""

    total_count: int
    page_index: int
    total_pages: int


@dataclass(frozen=True)
class DisclosureRef:
    """공시 한 건의 가리킴. ``acpt_no``는 접수번호 14자리다."""

    acpt_no: str
    disclosed_on: date
    issue_name: str
    title: str


@dataclass(frozen=True)
class DistributionRow:
    """본문 표의 한 행.

    ``isin``이 ``None``인 것은 값을 못 읽은 것이 아니라 **그 모양의 공시에 ISIN 칸이 없다**는
    뜻이다(2019년 이전 세로 표). 이 행은 ISIN으로 맞출 수 없으므로 걸러 내지 않고
    :class:`UnkeyedDisclosure` 로 드러낸다.
    """

    isin: str | None
    short_name: str
    record_date: date
    pay_date: date
    amount: Decimal


#: 종목코드(ISIN) 칸으로 맞춘 것. 2020년 이후 일괄공시가 이 갈래다.
MATCH_BY_ISIN: Final[str] = "isin"

#: 펀드 정식명으로 맞춘 것. 2019년 이전 공시가 이 갈래다.
MATCH_BY_FUND_NAME: Final[str] = "fund_name"


@dataclass(frozen=True)
class DistributionRecord:
    """어떤 종목의 분배 한 건. ``pay_date``가 이 모듈을 만든 이유다.

    ``matched_by``는 이 건을 **어느 갈래로 맞췄는지** 남긴다
    (:data:`MATCH_BY_ISIN` 또는 :data:`MATCH_BY_FUND_NAME`). 이름으로 맞춘 건은 대응표가
    맞다는 전제 위에 서 있으므로, 읽는 쪽이 둘을 구별할 수 있어야 한다.
    """

    isin: str
    short_name: str
    record_date: date
    pay_date: date
    amount: Decimal
    acpt_no: str
    matched_by: str = MATCH_BY_ISIN


@dataclass(frozen=True)
class UnkeyedDisclosure:
    """어느 종목의 것인지 맞추지 못해 판정을 보류한 공시.

    ISIN 칸도 없고 펀드 정식명도 대응표에 없을 때 남는다. ``row_count``와 ``names``는
    **맞추지 못한 행만** 센다 — 같은 공시의 나머지 행이 붙었어도 못 붙은 행은 드러나야 한다.

    비어 있지 않다면 **"분배가 없다"고 말할 수 없다.** 결과가 이 사실을 담고 다닌다.
    """

    acpt_no: str
    disclosed_on: date
    row_count: int
    names: tuple[str, ...]
    reason: str


@dataclass(frozen=True)
class DistributionHistory:
    """한 종목의 분배 이력. ``records``는 지급예정일 오름차순이다."""

    isin: str
    from_date: date
    to_date: date
    records: tuple[DistributionRecord, ...]
    unkeyed: tuple[UnkeyedDisclosure, ...] = ()
    disclosures_read: int = 0

    @property
    def is_complete(self) -> bool:
        """판정을 보류한 공시가 하나도 없을 때만 참이다.

        거짓이면 ``records``가 비어 있어도 "분배가 없다"는 결론을 내리면 안 된다.
        """
        return not self.unkeyed


# ---------------------------------------------------------------------------
# 원천 — 테스트는 픽스처를 읽는 구현으로 바꿔 끼운다
# ---------------------------------------------------------------------------


class KindPageSource(Protocol):
    """KIND의 네 갈래 응답을 돌려주는 것.

    :class:`data_pipeline.krx.DailyRowSource` 와 같은 뜻이며, 테스트가 네트워크 없이 돌도록
    픽스처를 읽는 구현으로 바꿔 끼운다.
    """

    def list_page(self, from_date: date, to_date: date, page_index: int, page_size: int) -> str:
        """공시 목록 HTML 조각 한 페이지."""

    def viewer_page(self, acpt_no: str) -> str:
        """접수번호의 문서 선택 HTML. 여기서 문서번호를 읽는다."""

    def contents_shell(self, doc_no: str) -> str:
        """문서번호의 껍데기 HTML. 여기서 본문 URL을 읽는다."""

    def document(self, url: str) -> str:
        """본문 HTML."""


def decode_kind_bytes(payload: bytes) -> str:
    """KIND 응답 바이트를 글자로 푼다.

    껍데기가 ``charset=euc-kr``을 선언해도 실제 바이트는 UTF-8인 경우가 있어(2026-09-16 확인)
    선언을 믿지 않고 UTF-8을 먼저 시도한다. 둘 다 실패하면 값을 뭉개지 않고 알린다.
    """
    for encoding in ("utf-8", "euc-kr", "cp949"):
        try:
            return payload.decode(encoding)
        except UnicodeDecodeError:
            continue
    raise KindParseError("응답을 UTF-8·EUC-KR·CP949 어느 것으로도 해독하지 못했다")


@dataclass
class KindHttpSource:
    """KIND에 실제로 묻는 구현. 호출 사이에 :attr:`interval` 만큼 쉰다."""

    session: requests.Session = field(default_factory=requests.Session)
    base_url: str = KIND_BASE_URL
    timeout: float = DEFAULT_TIMEOUT_SECONDS
    interval: float = DEFAULT_REQUEST_INTERVAL
    user_agent: str = "Mozilla/5.0"

    def _pause(self) -> None:
        if self.interval > 0:
            time.sleep(self.interval)

    def _get(self, url: str, params: dict[str, str] | None = None) -> str:
        response = self.session.get(
            url,
            params=params,
            headers={"User-Agent": self.user_agent},
            timeout=self.timeout,
        )
        self._pause()
        if response.status_code != 200:
            raise KindError(f"KIND가 HTTP {response.status_code}을 돌려주었다: {url}")
        return decode_kind_bytes(response.content)

    def list_page(self, from_date: date, to_date: date, page_index: int, page_size: int) -> str:
        response = self.session.post(
            f"{self.base_url.rstrip('/')}{LIST_PATH}",
            data={
                "method": LIST_METHOD,
                "currentPageSize": str(page_size),
                "pageIndex": str(page_index),
                "orderMode": "1",
                "orderStat": "D",
                "fromDate": f"{from_date:%Y-%m-%d}",
                "toDate": f"{to_date:%Y-%m-%d}",
            },
            headers={"User-Agent": self.user_agent, "X-Requested-With": "XMLHttpRequest"},
            timeout=self.timeout,
        )
        self._pause()
        if response.status_code != 200:
            raise KindError(
                f"공시 목록이 HTTP {response.status_code}을 돌려주었다: "
                f"{from_date:%Y-%m-%d}~{to_date:%Y-%m-%d} {page_index}쪽"
            )
        return decode_kind_bytes(response.content)

    def viewer_page(self, acpt_no: str) -> str:
        return self._get(
            f"{self.base_url.rstrip('/')}{VIEWER_PATH}",
            {"method": "search", "acptno": acpt_no},
        )

    def contents_shell(self, doc_no: str) -> str:
        return self._get(
            f"{self.base_url.rstrip('/')}{VIEWER_PATH}",
            {"method": "searchContents", "docNo": doc_no},
        )

    def document(self, url: str) -> str:
        return self._get(url)


# ---------------------------------------------------------------------------
# 1·2단계 — 목록에서 접수번호를 뽑는다
# ---------------------------------------------------------------------------


class _ListTableParser(HTMLParser):
    """목록 ``<tbody>``의 행을 칸 글자와 공시 링크로 나눈다."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._in_tbody = False
        self._in_row = False
        self._in_cell = False
        self._cells: list[str] = []
        self._chunks: list[str] = []
        self._link: tuple[str, str] | None = None
        self.rows: list[tuple[list[str], tuple[str, str] | None]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "tbody":
            self._in_tbody = True
        elif tag == "tr" and self._in_tbody:
            self._in_row = True
            self._cells = []
            self._link = None
        elif tag == "td" and self._in_row:
            self._in_cell = True
            self._chunks = []
        elif tag == "a" and self._in_row and self._link is None:
            attributes = dict(attrs)
            matched = _VIEWER_CALL.search(attributes.get("onclick") or "")
            if matched is not None:
                self._link = (matched.group(1), (attributes.get("title") or "").strip())

    def handle_endtag(self, tag: str) -> None:
        if tag == "tbody":
            self._in_tbody = False
        elif tag == "td" and self._in_cell:
            self._in_cell = False
            self._cells.append("".join(self._chunks).strip())
        elif tag == "tr" and self._in_row:
            self._in_row = False
            if self._cells:
                self.rows.append((self._cells, self._link))

    def handle_data(self, data: str) -> None:
        if self._in_cell:
            self._chunks.append(data)


def parse_paging(html: str) -> Paging:
    """목록 응답에서 ``전체 N건 : 현재/전체쪽``을 읽는다.

    이 값을 못 읽으면 전 페이지를 돌 수 없어 조용히 앞부분만 보게 되므로 알린다.
    """
    matched = _PAGING.search(html)
    if matched is None:
        raise KindParseError(
            "목록 응답에서 쪽수를 읽지 못했다. 앞 몇 쪽만 보고 판단하면 분배 공시를 놓친다"
        )
    return Paging(
        total_count=int(matched.group(1).replace(",", "")),
        page_index=int(matched.group(2)),
        total_pages=int(matched.group(3)),
    )


def parse_disclosure_refs(html: str) -> list[DisclosureRef]:
    """목록 한 페이지의 공시를 모두 돌려준다. 제목으로 거르지 않는다.

    읽지 못한 행은 버리지 않고 :class:`KindParseError` 로 알린다. 조용히 버리면 빠진 분배를
    아무도 모른다.
    """
    parser = _ListTableParser()
    parser.feed(html)
    parser.close()
    refs: list[DisclosureRef] = []
    for cells, link in parser.rows:
        if link is None:
            raise KindParseError(f"목록 행에서 접수번호를 읽지 못했다: {cells!r}")
        if len(cells) != 5:
            raise KindParseError(
                f"목록 행의 칸이 5개가 아니다(번호·시각·종목명·공시제목·제출인): {cells!r}"
            )
        refs.append(
            DisclosureRef(
                acpt_no=link[0],
                disclosed_on=_parse_disclosed_on(cells[1]),
                issue_name=cells[2].strip(),
                title=link[1],
            )
        )
    return refs


def _parse_disclosed_on(text: str) -> date:
    stamp = text.strip()
    for pattern in ("%Y-%m-%d %H:%M", "%Y-%m-%d"):
        try:
            return datetime.strptime(stamp, pattern).date()
        except ValueError:
            continue
    raise KindParseError(f"공시 시각을 읽지 못했다: {stamp!r}")


def is_distribution_title(title: str) -> bool:
    """분배금 안내 공시인지. 머리말로만 맞춘다(2019년 이전·이후 제목이 다르다)."""
    return title.replace(" ", "").startswith(DISTRIBUTION_TITLE_PREFIX)


def find_distribution_disclosures(
    source: KindPageSource,
    from_date: date,
    to_date: date,
    *,
    page_size: int = DEFAULT_PAGE_SIZE,
) -> list[DisclosureRef]:
    """기간의 분배금 안내 공시를 ``(접수번호, 공시일)`` 단위로 돌려준다.

    **전 페이지를 돈다.** 목록이 공시 시각 내림차순이라 분배 공시가 뒤쪽에만 있을 수 있다.
    일괄공시는 여러 행이 한 접수번호를 가리키므로 접수번호로 묶는다.
    """
    found: dict[str, DisclosureRef] = {}
    page_index = 1
    while True:
        html = source.list_page(from_date, to_date, page_index, page_size)
        paging = parse_paging(html)
        if paging.page_index != page_index:
            raise KindParseError(
                f"요청한 쪽과 응답한 쪽이 다르다: {page_index}쪽을 청했는데 "
                f"{paging.page_index}쪽이 왔다. 남은 쪽을 돌 수 없다"
            )
        if paging.total_count == 0:
            return []
        for ref in parse_disclosure_refs(html):
            if is_distribution_title(ref.title):
                found.setdefault(ref.acpt_no, ref)
        if page_index >= paging.total_pages:
            break
        page_index += 1
    return sorted(found.values(), key=lambda ref: (ref.disclosed_on, ref.acpt_no))


# ---------------------------------------------------------------------------
# 3·4단계 — 접수번호에서 본문 URL로
# ---------------------------------------------------------------------------


def parse_document_number(html: str) -> str:
    """문서 선택 HTML의 ``<option>``에서 문서번호 14자리를 읽는다.

    정정이 있으면 ``<option>``이 여러 개다. ``selected``가 붙은 것을 고르고, 없으면 첫
    비어 있지 않은 값을 쓴다.
    """
    candidates: list[tuple[str, bool]] = []
    for value, trailing in _OPTION.findall(html):
        head = value.split("|", 1)[0].strip()
        if head:
            candidates.append((head, "selected" in trailing.lower()))
    if not candidates:
        raise KindParseError("문서 선택 HTML에서 문서번호를 읽지 못했다")
    chosen = next((value for value, selected in candidates if selected), candidates[0][0])
    if _DOC_NO_PATTERN.match(chosen) is None:
        raise KindParseError(f"문서번호가 14자리 숫자가 아니다: {chosen!r}")
    return chosen


def parse_document_url(html: str) -> str:
    """껍데기 HTML의 ``parent.setPath`` 두 번째 인자에서 본문 URL을 읽는다.

    **URL을 날짜 규칙으로 지어내지 않는다.** 접수번호·문서번호만으로는 끝의 파일 이름
    (``68659.htm``)을 알 수 없다.
    """
    matched = _SET_PATH.search(html)
    if matched is None:
        raise KindParseError("껍데기 HTML에서 본문 URL(setPath)을 읽지 못했다")
    return matched.group(1)


def resolve_document_url(source: KindPageSource, acpt_no: str) -> str:
    """접수번호 → 문서번호 → 본문 URL."""
    doc_no = parse_document_number(source.viewer_page(acpt_no))
    return parse_document_url(source.contents_shell(doc_no))


# ---------------------------------------------------------------------------
# 5단계 — 본문 표
# ---------------------------------------------------------------------------


class _BodyTableParser(HTMLParser):
    """본문 ``<table>``마다 행을 칸 글자로 나눈다."""

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self._depth = 0
        self._in_row = False
        self._in_cell = False
        self._cells: list[str] = []
        self._chunks: list[str] = []
        self._current: list[list[str]] = []
        self.tables: list[list[list[str]]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "table":
            self._depth += 1
            if self._depth == 1:
                self._current = []
        elif tag == "tr" and self._depth >= 1:
            self._in_row = True
            self._cells = []
        elif tag in ("td", "th") and self._in_row:
            self._in_cell = True
            self._chunks = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "table" and self._depth >= 1:
            self._depth -= 1
            if self._depth == 0:
                self.tables.append(self._current)
                self._current = []
        elif tag in ("td", "th") and self._in_cell:
            self._in_cell = False
            self._cells.append(" ".join("".join(self._chunks).split()))
        elif tag == "tr" and self._in_row:
            self._in_row = False
            if self._cells:
                self._current.append(self._cells)

    def handle_data(self, data: str) -> None:
        if self._in_cell:
            self._chunks.append(data)


def _squeeze(text: str) -> str:
    return text.replace(" ", "").replace(" ", "")


def _parse_body_date(text: str, label: str) -> date:
    try:
        return datetime.strptime(text.strip(), "%Y-%m-%d").date()
    except ValueError as error:
        raise KindParseError(f"{label}을 읽지 못했다: {text!r}") from error


def _parse_amount(text: str) -> Decimal:
    cleaned = text.strip().replace(",", "")
    try:
        return Decimal(cleaned)
    except InvalidOperation as error:
        raise KindParseError(f"분배금을 읽지 못했다: {text!r}") from error


def _has_short_name_column(labels: set[str]) -> bool:
    """종목 이름 열이 어느 이름으로든 있는가."""
    return any(alias in labels for alias in _COL_SHORT_NAME_ALIASES)


def _bundled_header_index(table: list[list[str]]) -> int | None:
    """가로 표의 머리글 줄 자리. 다섯 열이 **모두** 있어야 머리글로 본다.

    ``종목코드``만 보고 고르면 정정공시에 걸린다. 정정공시는 본문 앞에
    ``정정항목 | 정정전 | 정정후`` 표를 두는데 그 안에 ``종목코드``라는 **항목 이름**이 들어
    있다(2025-04-28 접수 ``20250428000383`` 실측). 그 표를 머리글로 착각하면 진짜 분배 표를
    영영 읽지 못한다.

    종목 이름 열만 이름이 해마다 달라 :data:`_COL_SHORT_NAME_ALIASES` 중 하나면 인정한다.
    """
    required = (_COL_ISIN, _COL_RECORD_DATE, _COL_PAY_DATE, _COL_AMOUNT)
    for index, cells in enumerate(table):
        labels = {_squeeze(cell) for cell in cells}
        if all(name in labels for name in required) and _has_short_name_column(labels):
            return index
    return None


def _parse_bundled_table(table: list[list[str]]) -> list[DistributionRow]:
    """가로 표(2020년 이후). 열 자리는 이름으로 찾는다 — 순서가 바뀌어도 어긋나지 않는다."""
    header_index = _bundled_header_index(table)
    if header_index is None:
        raise KindParseError(f"가로 표의 머리글({_COL_ISIN})을 찾지 못했다")
    header = [_squeeze(cell) for cell in table[header_index]]
    columns: dict[str, int] = {}
    for name in (_COL_ISIN, _COL_RECORD_DATE, _COL_PAY_DATE, _COL_AMOUNT):
        if name not in header:
            raise KindParseError(f"가로 표에 {name!r} 열이 없다: {header!r}")
        columns[name] = header.index(name)
    short_name_alias = next((alias for alias in _COL_SHORT_NAME_ALIASES if alias in header), None)
    if short_name_alias is None:
        raise KindParseError(
            f"가로 표에 종목 이름 열({_COL_SHORT_NAME_ALIASES!r})이 하나도 없다: {header!r}"
        )
    columns[_SHORT_NAME_KEY] = header.index(short_name_alias)
    width = max(columns.values()) + 1
    rows: list[DistributionRow] = []
    for cells in table[header_index + 1 :]:
        if not any(cell.strip() for cell in cells):
            continue
        if len(cells) < width:
            raise KindParseError(
                f"가로 표의 행이 짧다(칸 {len(cells)}개, {width}개 필요): {cells!r}"
            )
        isin = cells[columns[_COL_ISIN]].strip()
        if ISIN_PATTERN.match(isin) is None:
            raise KindParseError(f"종목코드 칸이 ISIN 형식이 아니다: {isin!r}")
        rows.append(
            DistributionRow(
                isin=isin,
                short_name=cells[columns[_SHORT_NAME_KEY]].strip(),
                record_date=_parse_body_date(cells[columns[_COL_RECORD_DATE]], "지급기준일"),
                pay_date=_parse_body_date(cells[columns[_COL_PAY_DATE]], "지급예정일"),
                amount=_parse_amount(cells[columns[_COL_AMOUNT]]),
            )
        )
    if not rows:
        raise KindParseError("가로 표에서 한 행도 읽지 못했다")
    return rows


def _parse_legacy_table(table: list[list[str]]) -> list[DistributionRow]:
    """세로 표(2019년 이전). ISIN 칸이 없어 ``isin``은 ``None``이다."""
    items = {_squeeze(cells[0]): cells[1].strip() for cells in table if len(cells) >= 2}
    missing = [
        name
        for name in (_LEGACY_NAME, _COL_RECORD_DATE, _COL_PAY_DATE, _LEGACY_AMOUNT)
        if name not in items
    ]
    if missing:
        raise KindParseError(f"세로 표에 {missing!r} 항목이 없다: {sorted(items)!r}")
    return [
        DistributionRow(
            isin=None,
            short_name=items[_LEGACY_NAME],
            record_date=_parse_body_date(items[_COL_RECORD_DATE], "지급기준일"),
            pay_date=_parse_body_date(items[_COL_PAY_DATE], "지급예정일"),
            amount=_parse_amount(items[_LEGACY_AMOUNT]),
        )
    ]


def parse_distribution_rows(html: str) -> list[DistributionRow]:
    """본문 표의 모든 행을 돌려준다. 두 모양을 다 읽고, 못 읽으면 알린다.

    정정공시는 본문 앞에 ``정정일자``·``정정사유``·``정정항목`` 표를 여러 개 두고 **맨 뒤에**
    진짜 분배 표를 둔다. 그래서 다섯 열 이름이 모두 있는 표만 고른다.
    """
    parser = _BodyTableParser()
    parser.feed(html)
    parser.close()
    tables = [table for table in parser.tables if table]
    if not tables:
        raise KindParseError("본문에서 표를 찾지 못했다")
    for table in tables:
        if _bundled_header_index(table) is not None:
            return _parse_bundled_table(table)
    for table in tables:
        labels = {_squeeze(cells[0]) for cells in table if cells}
        if _LEGACY_NAME in labels and _COL_PAY_DATE in labels:
            return _parse_legacy_table(table)
    raise KindParseError("본문 표가 아는 두 모양(가로·세로) 어느 쪽도 아니다")


def fetch_distribution_rows(source: KindPageSource, acpt_no: str) -> list[DistributionRow]:
    """접수번호 하나의 본문 표를 읽는다."""
    return parse_distribution_rows(source.document(resolve_document_url(source, acpt_no)))


# ---------------------------------------------------------------------------
# 6단계 — 펀드 정식명으로 맞추기 (2019년 이전)
# ---------------------------------------------------------------------------
#
# ## 왜 이름으로 맞추는가
#
# 2019년 이전 공시에는 종목코드(ISIN) 칸이 아예 없다. 남은 단서는 ``종목`` 칸의
# **펀드 정식명**(``삼성 KODEX 은행증권상장지수투자신탁(주식)``)뿐이다.
#
# ## 왜 「브랜드명으로 시작하는가」로 맞추지 않는가 — 2026-09-16 실측 근거
#
# 브랜드명(``KODEX 은행``)이나 ``{브랜드명}증권상장지수투자신탁`` 같은 **부분 일치**는 셋 다
# 실제로 틀린다. 아래는 2019년 공시 232건을 모두 받아 본 결과다.
#
# 1. 브랜드명이 정식명에 그대로 들어 있지 않다. 단축코드 ``102970``의 브랜드명은
#    ``KODEX 증권``인데 정식명은 ``삼성 KODEX 증권주증권상장지수투자신탁(주식)``으로
#    **``증권주``** 다(2019-04-26 접수 ``20190426000611``). ``KODEX 증권증권상장지수투자신탁``
#    으로 만들어 맞추면 한 건도 못 찾는다.
# 2. 운용사를 빼면 다른 운용사의 같은 업종 상품에 걸린다. 같은 해에
#    ``미래에셋 TIGER 은행증권상장지수투자신탁(주식)``이 함께 있어 ``KODEX 은행``과 ``TIGER 은행``
#    을 ``은행증권상장지수투자신탁(주식)``으로는 가를 수 없다.
# 3. 뒤까지 포함해도 부분 일치는 위험하다. ``KB KBSTAR 200중공업증권상장지수투자신탁(주식)``은
#    ``200중공업증권상장지수투자신탁(주식)``을 **통째로 품는다.** 그래서 미래에셋의
#    ``TIGER 200중공업``을 찾으려고 뒤쪽 조각으로 맞추면 KB 상품까지 걸린다.
#
# ## 그래서 고른 방법 — 정식명 **전체가 똑같을 때만** 맞춘다
#
# 공백만 지우고(아래 :func:`normalize_fund_name`) **완전 일치**로 사전을 찾는다. 부분 일치를
# 아예 쓰지 않으므로 ``KODEX 은행``이 ``KODEX 은행고배당``에 걸릴 길이 구조적으로 없다.
# 대응표는 코드에 박지 않고 인자로 받는다 — 정식명은 관측으로만 확정할 수 있고, 뒤에서 보듯
# 해마다 바뀌기 때문이다.
#
# 공백만 지우는 까닭도 관측이다. 같은 펀드의 정식명이 해마다 띄어쓰기만 달라진다.
# ``삼성KODEX건설증권상장지수투자신탁[주식]``(2011~2015) →
# ``삼성 KODEX 건설증권상장지수투자신탁(주식)``(2016~2019). 대괄호와 소괄호는 **지우지 않는다.**
# ``[주식]``과 ``(주식)``은 서로 다른 시기의 서로 다른 표기이며, 둘을 같게 뭉개면 이름이 실제로
# 다른 두 펀드를 가를 수단이 하나 줄어든다. 표기가 바뀌었으면 대응표에 **관측한 문자열을 모두**
# 적는 것이 맞다.

#: 공백으로 볼 글자. 일반 공백 말고도 줄바꿈·무너지지 않는 공백(NBSP)·전각 공백이 실제로 섞인다.
_WHITESPACE: Final[re.Pattern[str]] = re.compile(r"[\s 　]+")


def normalize_fund_name(name: str) -> str:
    """펀드 정식명을 견줄 수 있는 꼴로 바꾼다. **공백만** 지운다.

    괄호·대괄호는 손대지 않는다(위 주석의 까닭). 비교는 언제나 이 꼴끼리 한다.
    """
    return _WHITESPACE.sub("", name)


def build_fund_name_index(fund_names: Mapping[str, str]) -> dict[str, str]:
    """``{펀드 정식명: ISIN}`` 대응표를 견줌용 사전으로 바꾼다.

    ``fund_names``는 **관측한 정식명**을 열쇠로 둔다. 한 종목의 이름이 시기마다 다르면 그만큼
    여러 줄을 둔다(같은 ISIN을 여러 이름이 가리켜도 된다).

    같은 이름이 서로 다른 ISIN을 가리키면 조용히 덮어쓰지 않고 알린다. 덮어쓰면 어느 쪽이
    남았는지 아무도 모른 채 분배가 엉뚱한 종목에 붙는다.
    """
    index: dict[str, str] = {}
    for name, isin in fund_names.items():
        if ISIN_PATTERN.match(isin) is None:
            raise ValueError(f"대응표의 ISIN이 KR로 시작하는 12글자가 아니다: {name!r} -> {isin!r}")
        key = normalize_fund_name(name)
        if not key:
            raise ValueError("대응표에 빈 펀드 정식명이 있다")
        previous = index.get(key)
        if previous is not None and previous != isin:
            raise ValueError(
                f"같은 펀드 정식명이 두 ISIN을 가리킨다: {name!r} -> {previous!r}, {isin!r}"
            )
        index[key] = isin
    return index


def match_fund_name(name: str, index: Mapping[str, str]) -> str | None:
    """정식명으로 ISIN을 찾는다. **완전 일치**만 인정하며 못 찾으면 ``None``이다."""
    return index.get(normalize_fund_name(name))


# ---------------------------------------------------------------------------
# 대응표 파일
# ---------------------------------------------------------------------------


class FundNameFileError(KindError):
    """펀드 정식명 대응표 파일이 약속한 모양이 아니다."""


#: 대응표 파일의 종목 목록을 담은 키. ``$``로 시작하는 키는 주석이라 읽지 않는다
#: (``data/config/sector-map-2026-09.json``과 같은 방식이다).
_FUNDS_KEY: Final[str] = "funds"


def _require_mapping(value: Any, where: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise FundNameFileError(f"{where}이 객체가 아니다: {value!r}")
    return value


def _require_text(value: Any, where: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise FundNameFileError(f"{where}이 비어 있지 않은 글자가 아니다: {value!r}")
    return value


def load_fund_names(path: Path) -> dict[str, str]:
    """펀드 정식명 대응표 파일을 읽어 ``{정식명: ISIN}``으로 돌려준다.

    :func:`collect_distribution_history` 의 ``fund_names`` 인자에 그대로 넣는 꼴이다.

    **이름마다 관측 근거(``observed``)를 요구한다.** 근거가 없으면 읽지 않고 알린다. 정식명은
    지어낼 수 있는 값이 아니라 공시에서 본 그대로여야 하며, 근거 없는 줄을 조용히 받아 주면
    아무도 확인할 수 없는 추측이 대응표에 섞인다.

    파일이 없으면 빈 대응표로 넘어가지 않고 알린다 — 조용히 비면 2019년 이전 분배가 통째로
    사라지고 아무도 모른다.
    """
    if not path.is_file():
        raise FundNameFileError(f"펀드 정식명 대응표 파일이 없다: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise FundNameFileError(f"대응표 파일이 JSON이 아니다: {path} ({error})") from error
    root = _require_mapping(payload, f"대응표 파일 {path}의 최상위")
    funds = root.get(_FUNDS_KEY)
    if not isinstance(funds, list) or not funds:
        raise FundNameFileError(f"대응표 파일에 {_FUNDS_KEY!r} 목록이 없거나 비었다: {path}")

    names: dict[str, str] = {}
    for entry in funds:
        fund = _require_mapping(entry, "종목 항목")
        isin = _require_text(fund.get("isin"), "종목의 isin")
        official = fund.get("official_names")
        if not isinstance(official, list) or not official:
            raise FundNameFileError(f"{isin}에 official_names가 없거나 비었다")
        for item in official:
            record = _require_mapping(item, f"{isin}의 official_names 항목")
            name = _require_text(record.get("name"), f"{isin}의 정식명")
            observed = record.get("observed")
            if not isinstance(observed, list) or not observed:
                raise FundNameFileError(f"{isin}의 정식명 {name!r}에 관측 근거(observed)가 없다")
            for sighting in observed:
                seen = _require_mapping(sighting, f"{name!r}의 관측 근거")
                _require_text(seen.get("acpt_no"), f"{name!r} 관측 근거의 acpt_no")
                _require_text(seen.get("disclosed_on"), f"{name!r} 관측 근거의 disclosed_on")
            if name in names and names[name] != isin:
                raise FundNameFileError(
                    f"같은 정식명이 두 ISIN을 가리킨다: {name!r} -> {names[name]}, {isin}"
                )
            names[name] = isin
    build_fund_name_index(names)
    return names


def load_fund_isins(path: Path) -> dict[str, str]:
    """같은 대응표 파일에서 ``{종목 식별자: ISIN}``을 읽는다.

    :func:`collect_distribution_history` 는 ISIN으로 묻고 ``corporate_action``은 종목 식별자로
    적재하므로, 둘을 잇는 대응이 어딘가에는 있어야 한다. 그 대응을 **관측 근거를 이미 요구하는
    이 파일**이 함께 갖게 하면 짝이 어긋날 자리가 생기지 않는다. 파일을 따로 두면 한쪽만 고친
    날 분배가 엉뚱한 종목에 붙는다.

    ``official_names``는 읽지 않는다. 이름 대응이 필요한 쪽은 :func:`load_fund_names` 다.
    """
    if not path.is_file():
        raise FundNameFileError(f"펀드 정식명 대응표 파일이 없다: {path}")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        raise FundNameFileError(f"대응표 파일이 JSON이 아니다: {path} ({error})") from error
    root = _require_mapping(payload, f"대응표 파일 {path}의 최상위")
    funds = root.get(_FUNDS_KEY)
    if not isinstance(funds, list) or not funds:
        raise FundNameFileError(f"대응표 파일에 {_FUNDS_KEY!r} 목록이 없거나 비었다: {path}")

    isins: dict[str, str] = {}
    for entry in funds:
        fund = _require_mapping(entry, "종목 항목")
        isin = _require_text(fund.get("isin"), "종목의 isin")
        if ISIN_PATTERN.match(isin) is None:
            raise FundNameFileError(f"ISIN이 KR로 시작하는 12글자가 아니다: {isin!r}")
        instrument_id = _require_text(fund.get("instrument_id"), f"{isin}의 instrument_id")
        if INSTRUMENT_ID_PATTERN.match(instrument_id) is None:
            raise FundNameFileError(
                f"종목 식별자가 {{6글자}}.XKRX 형식이 아니다: {instrument_id!r}"
            )
        if instrument_id in isins and isins[instrument_id] != isin:
            raise FundNameFileError(
                f"같은 종목 식별자가 두 ISIN을 가리킨다: {instrument_id} -> "
                f"{isins[instrument_id]}, {isin}"
            )
        isins[instrument_id] = isin
    reversed_map: dict[str, str] = {}
    for instrument_id, isin in isins.items():
        if isin in reversed_map:
            raise FundNameFileError(
                f"같은 ISIN을 두 종목 식별자가 가리킨다: {isin} -> "
                f"{reversed_map[isin]}, {instrument_id}"
            )
        reversed_map[isin] = instrument_id
    return isins


# ---------------------------------------------------------------------------
# 분배 이력
# ---------------------------------------------------------------------------


def iter_year_chunks(from_date: date, to_date: date) -> Iterator[tuple[date, date]]:
    """기간을 연 단위로 자른다.

    상장일부터 오늘까지를 한 번에 부르면 응답이 수십만 건이 되어 쪽수가 감당되지 않는다.
    """
    if from_date > to_date:
        raise ValueError(f"from_date가 to_date보다 뒤다: {from_date} > {to_date}")
    year = from_date.year
    while year <= to_date.year:
        start = max(from_date, date(year, 1, 1))
        end = min(to_date, date(year, 12, 31))
        yield start, end
        year += 1


def key_distribution_rows(
    rows: list[DistributionRow],
    fund_name_index: Mapping[str, str],
) -> tuple[list[tuple[DistributionRow, str, str]], list[DistributionRow]]:
    """본문 행을 **갈래를 갈라** 종목에 붙인다.

    두 갈래를 한 덩어리로 섞지 않는다. 행에 종목코드(ISIN) 칸이 있으면 그것을 쓰고
    (:data:`MATCH_BY_ISIN`), 없으면 펀드 정식명 대응표를 찾는다
    (:data:`MATCH_BY_FUND_NAME`). ISIN이 있는 행에는 이름 대응표를 **아예 쓰지 않는다** —
    원천이 준 열쇠를 두고 이름을 보면 대응표의 실수가 원천을 덮어쓴다.

    돌려주는 것은 ``(붙은 행, 붙인 ISIN, 갈래)`` 목록과 **어디에도 붙지 못한 행** 목록이다.
    붙지 못한 행을 버리지 않고 돌려주는 것이 이 함수의 요점이다.
    """
    keyed: list[tuple[DistributionRow, str, str]] = []
    unmatched: list[DistributionRow] = []
    for row in rows:
        if row.isin is not None:
            keyed.append((row, row.isin, MATCH_BY_ISIN))
            continue
        matched = match_fund_name(row.short_name, fund_name_index)
        if matched is None:
            unmatched.append(row)
            continue
        keyed.append((row, matched, MATCH_BY_FUND_NAME))
    return keyed, unmatched


def _unmatched_reason(has_index: bool) -> str:
    if not has_index:
        return "공시 본문에 종목코드(ISIN) 칸이 없고 펀드 정식명 대응표를 받지 않았다"
    return "공시 본문에 종목코드(ISIN) 칸이 없고 펀드 정식명이 대응표에 없다"


def collect_distribution_history(
    source: KindPageSource,
    isin: str,
    from_date: date,
    to_date: date,
    *,
    page_size: int = DEFAULT_PAGE_SIZE,
    fund_names: Mapping[str, str] | None = None,
) -> DistributionHistory:
    """한 종목(ISIN)의 분배 이력을 기간에서 모은다.

    단축코드를 ISIN으로 옮기는 일은 이 모듈이 하지 않는다. 대응표를 가진 쪽이 ISIN을 넘긴다.

    ``fund_names``는 ``{펀드 정식명: ISIN}`` 대응표다. 2019년 이전 공시에는 종목코드 칸이 없어
    이 대응표가 있어야 맞출 수 있다. **코드에 박지 않고 인자로 받는다** — 정식명은 관측으로만
    확정되고 시기에 따라 달라지기 때문이다. 주지 않으면 옛 공시는 모두
    :class:`UnkeyedDisclosure` 로 드러난다(지금까지의 동작 그대로다).

    ``(ISIN, 지급기준일, 지급예정일)`` 이 같으면 한 건으로 본다 — 정정공시가 같은 분배를 다시
    알리기 때문이다. 겹치면 **나중에 공시된 것**을 남긴다(정정이 원본을 대신한다).

    여러 종목을 한 번에 모을 때는 :func:`collect_distribution_histories` 를 쓴다. 이 함수를
    종목마다 부르면 같은 목록을 종목 수만큼 다시 훑는다.
    """
    return collect_distribution_histories(
        source, [isin], from_date, to_date, page_size=page_size, fund_names=fund_names
    )[isin]


def collect_distribution_histories(
    source: KindPageSource,
    isins: Iterable[str],
    from_date: date,
    to_date: date,
    *,
    page_size: int = DEFAULT_PAGE_SIZE,
    fund_names: Mapping[str, str] | None = None,
) -> dict[str, DistributionHistory]:
    """여러 종목(ISIN)의 분배 이력을 **목록을 한 번만 훑어** 모은다.

    공시 목록에는 제목으로 걸러 주는 인자가 없어 기간의 전 페이지를 돌아야 하고, 그 비용은
    찾는 종목 수와 무관하다(:func:`find_distribution_disclosures`). 종목마다
    :func:`collect_distribution_history` 를 부르면 같은 목록과 같은 본문을 종목 수만큼 다시
    받게 되므로, 여덟 종목의 전 이력을 모으는 일이 실무상 끝나지 않는다.

    돌려주는 것은 ``{ISIN: 이력}`` 이며 **요청한 ISIN은 하나도 빠지지 않는다.** 분배가 한 건도
    없는 종목도 빈 ``records``로 들어 있다. 빠뜨리면 부르는 쪽이 "없음"과 "묻지 않음"을 가릴 수
    없다.

    판정을 보류한 공시(:class:`UnkeyedDisclosure`)는 **요청한 모든 종목의 이력에 똑같이 담는다.**
    어느 종목의 것인지 모르는 공시라 특정 종목에만 붙일 근거가 없고, 한 종목에만 붙이면 나머지
    종목의 ``is_complete``가 근거 없이 참이 된다.
    """
    wanted = list(dict.fromkeys(isins))
    if not wanted:
        raise ValueError("ISIN을 하나도 주지 않았다")
    for isin in wanted:
        if ISIN_PATTERN.match(isin) is None:
            raise ValueError(f"ISIN은 KR로 시작하는 12글자여야 한다: {isin!r}")
    wanted_set = set(wanted)
    fund_name_index = build_fund_name_index(fund_names) if fund_names else {}

    refs: dict[str, DisclosureRef] = {}
    for chunk_from, chunk_to in iter_year_chunks(from_date, to_date):
        for ref in find_distribution_disclosures(source, chunk_from, chunk_to, page_size=page_size):
            refs.setdefault(ref.acpt_no, ref)

    chosen: dict[tuple[str, date, date], tuple[date, DistributionRecord]] = {}
    unkeyed: list[UnkeyedDisclosure] = []
    for ref in sorted(refs.values(), key=lambda r: (r.disclosed_on, r.acpt_no)):
        rows = fetch_distribution_rows(source, ref.acpt_no)
        keyed, unmatched = key_distribution_rows(rows, fund_name_index)
        if unmatched:
            unkeyed.append(
                UnkeyedDisclosure(
                    acpt_no=ref.acpt_no,
                    disclosed_on=ref.disclosed_on,
                    row_count=len(unmatched),
                    names=tuple(row.short_name for row in unmatched),
                    reason=_unmatched_reason(bool(fund_name_index)),
                )
            )
        for row, row_isin, matched_by in keyed:
            if row_isin not in wanted_set:
                continue
            record = DistributionRecord(
                isin=row_isin,
                short_name=row.short_name,
                record_date=row.record_date,
                pay_date=row.pay_date,
                amount=row.amount,
                acpt_no=ref.acpt_no,
                matched_by=matched_by,
            )
            key = (row_isin, row.record_date, row.pay_date)
            previous = chosen.get(key)
            if previous is None or previous[0] <= ref.disclosed_on:
                chosen[key] = (ref.disclosed_on, record)

    by_isin: dict[str, list[DistributionRecord]] = {isin: [] for isin in wanted}
    for _, record in chosen.values():
        by_isin[record.isin].append(record)
    return {
        isin: DistributionHistory(
            isin=isin,
            from_date=from_date,
            to_date=to_date,
            records=tuple(
                sorted(by_isin[isin], key=lambda item: (item.pay_date, item.record_date))
            ),
            unkeyed=tuple(unkeyed),
            disclosures_read=len(refs),
        )
        for isin in wanted
    }
