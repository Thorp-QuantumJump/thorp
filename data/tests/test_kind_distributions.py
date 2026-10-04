"""KIND 공시에서 분배금 지급일을 받아 오는 경로 (spec 001 FR-004, 004 계약).

이 파일이 고정하는 것은 **놓치지 않는 성질**이다. 이 원천은 제목으로 걸러 주는 인자가 없고
목록이 시각 내림차순이라, 앞 몇 쪽만 보면 분배 공시가 한 건도 없는 것처럼 보인다. 실제로
2026-07-28~08-05 구간은 4,363건 중 402건이 분배 공시였는데 앞 3쪽에는 한 건도 없었다.
그래서 전 페이지를 도는지, 비슷한 제목을 잘못 집지 않는지, 못 읽은 것이 드러나는지를 건다.

픽스처는 2026-09-16에 KIND에서 받은 실제 응답을 줄인 것이다. **파일이 없으면 건너뛰지 않고
실패한다** — 검사 장치가 조용히 꺼지면 빠진 분배를 아무도 모른다.
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from data_pipeline.kind_distributions import (
    DISTRIBUTION_TITLE_PREFIX,
    MATCH_BY_FUND_NAME,
    MATCH_BY_ISIN,
    DisclosureRef,
    DistributionRow,
    FundNameFileError,
    KindParseError,
    build_fund_name_index,
    collect_distribution_history,
    decode_kind_bytes,
    fetch_distribution_rows,
    find_distribution_disclosures,
    is_distribution_title,
    iter_year_chunks,
    key_distribution_rows,
    load_fund_names,
    match_fund_name,
    normalize_fund_name,
    parse_disclosure_refs,
    parse_distribution_rows,
    parse_document_number,
    parse_document_url,
    parse_paging,
    resolve_document_url,
)

FIXTURES = Path(__file__).parent / "fixtures" / "kind"

#: 실측 대상. TIGER 200 중공업(단축코드 139230)이며 단축코드는 어디에도 박아 두지 않는다.
TARGET_ISIN = "KR7139230007"
BUNDLED_ACPT = "20260729000622"
BUNDLED_DOC = "20260729001455"
BUNDLED_URL = "https://kind.krx.co.kr/external/2026/07/29/000622/20260729001455/68659.htm"
LEGACY_ACPT = "20110427000489"


def fixture(name: str) -> str:
    """픽스처를 읽는다. 없으면 건너뛰지 않고 실패한다."""
    path = FIXTURES / name
    if not path.is_file():
        pytest.fail(f"픽스처가 없다: {path}. 없으면 검사가 사라지므로 건너뛰지 않는다")
    return path.read_text(encoding="utf-8")


class RecordingSource:
    """픽스처만 읽는 원천. 네트워크를 쓰지 않는다."""

    def __init__(
        self,
        pages: list[str],
        documents: dict[str, str] | None = None,
        *,
        viewer: str | None = None,
        shell: str | None = None,
    ) -> None:
        self._pages = pages
        self._documents = documents or {}
        self._viewer = viewer if viewer is not None else fixture(f"viewer_{BUNDLED_ACPT}.html")
        self._shell = shell if shell is not None else fixture(f"contents_shell_{BUNDLED_DOC}.html")
        self.list_calls: list[tuple[date, date, int, int]] = []
        self.document_calls: list[str] = []

    def list_page(self, from_date: date, to_date: date, page_index: int, page_size: int) -> str:
        self.list_calls.append((from_date, to_date, page_index, page_size))
        return self._pages[page_index - 1]

    def viewer_page(self, acpt_no: str) -> str:
        return self._viewer

    def contents_shell(self, doc_no: str) -> str:
        return self._shell

    def document(self, url: str) -> str:
        self.document_calls.append(url)
        return self._documents.get(url, fixture(f"body_bundled_{BUNDLED_DOC}.html"))


# ---------------------------------------------------------------------------
# 1단계 — 목록에서 제목을 거른다
# ---------------------------------------------------------------------------


def test_목록에서_분배신고만_걸러진다() -> None:
    """비슷하지만 다른 제목이 함께 걸리면 본문을 헛되이 받고, 표 모양이 달라 터진다."""
    refs = parse_disclosure_refs(fixture("list_page2.html"))
    titles = {ref.title for ref in refs}
    assert any(title.startswith(DISTRIBUTION_TITLE_PREFIX) for title in titles)
    matched = [ref for ref in refs if is_distribution_title(ref.title)]
    assert matched, "분배 공시를 한 건도 집지 못했다"
    for ref in matched:
        assert ref.title.startswith(DISTRIBUTION_TITLE_PREFIX)


@pytest.mark.parametrize(
    ("title", "expected"),
    [
        ("ETF이익금분배신고(분배금안내)(일괄공시)", True),
        ("ETF이익금분배신고(분배금안내)", True),
        # 「분배」로 시작하지만 다른 공시다. 이것들이 걸리면 안 된다.
        ("ETF이익금분배 수익자 확정을 위한 설정/환매접수 일시중지 안내(일괄공시)", False),
        ("ETF 분배락 기준가격 안내", False),
        ("ETF 추가 ㆍ 변경상장신청서(수량변경)(일괄공시)", False),
        ("ETF변경등록ㆍ집합투자규약 변경", False),
    ],
)
def test_비슷한_제목은_걸러지지_않는다(title: str, expected: bool) -> None:
    assert is_distribution_title(title) is expected


def test_일시중지_공시는_목록에_있어도_집히지_않는다() -> None:
    """1쪽 픽스처에는 「일시중지 안내」가 들어 있다. 이것을 집으면 잘못이다."""
    refs = parse_disclosure_refs(fixture("list_page1.html"))
    assert any("일시중지" in ref.title for ref in refs), "픽스처가 헷갈리는 제목을 담고 있어야 한다"
    assert not [ref for ref in refs if is_distribution_title(ref.title)]


def test_목록_행의_칸과_접수번호를_읽는다() -> None:
    refs = parse_disclosure_refs(fixture("list_page2.html"))
    assert refs
    for ref in refs:
        assert len(ref.acpt_no) == 14 and ref.acpt_no.isdigit()
        assert ref.disclosed_on == date(2026, 7, 29)
        assert ref.issue_name


def test_읽지_못한_목록_행은_조용히_버려지지_않는다() -> None:
    broken = "<table><tbody><tr><td>1</td><td>2026-07-29 15:31</td></tr></tbody></table>"
    with pytest.raises(KindParseError):
        parse_disclosure_refs(broken)


def test_공시_시각을_못_읽으면_알린다() -> None:
    broken = (
        "<table><tbody><tr>"
        "<td>1</td><td>어제</td><td>ACE</td>"
        "<td><a onclick=\"openDisclsViewer('20260729000622','')\" "
        "title='ETF이익금분배신고(분배금안내)(일괄공시)'>x</a></td><td>한국펀드파트너스</td>"
        "</tr></tbody></table>"
    )
    with pytest.raises(KindParseError):
        parse_disclosure_refs(broken)


# ---------------------------------------------------------------------------
# 쪽수 — 전 페이지를 도는지
# ---------------------------------------------------------------------------


def test_총_쪽수를_읽는다() -> None:
    paging = parse_paging(fixture("list_page1.html"))
    assert paging.page_index == 1
    assert paging.total_pages == 2
    assert paging.total_count > 0


def test_쪽수를_못_읽으면_알린다() -> None:
    """못 읽은 채 1쪽만 보고 끝내면 분배 공시를 통째로 놓친다."""
    with pytest.raises(KindParseError):
        parse_paging("<html><body>목록</body></html>")


def test_전_페이지를_돈다() -> None:
    """분배 공시는 2쪽에만 있다. 1쪽에서 멈추면 한 건도 못 찾는다."""
    source = RecordingSource([fixture("list_page1.html"), fixture("list_page2.html")])
    refs = find_distribution_disclosures(source, date(2026, 7, 28), date(2026, 8, 5), page_size=100)
    assert [call[2] for call in source.list_calls] == [1, 2]
    assert len(refs) == 1, "일괄공시 두 행은 접수번호 하나로 묶여야 한다"
    assert refs[0].acpt_no == BUNDLED_ACPT


def test_일괄공시의_여러_행이_접수번호로_묶인다() -> None:
    refs = parse_disclosure_refs(fixture("list_page2.html"))
    matched = [ref for ref in refs if is_distribution_title(ref.title)]
    assert len(matched) == 2, "픽스처는 같은 접수번호를 가리키는 두 행을 담고 있어야 한다"
    assert len({ref.acpt_no for ref in matched}) == 1


def test_응답이_엉뚱한_쪽을_주면_알린다() -> None:
    """같은 쪽만 돌려주면 조용히 무한 반복하거나 뒤쪽을 영영 못 본다."""
    source = RecordingSource([fixture("list_page1.html"), fixture("list_page1.html")])
    with pytest.raises(KindParseError):
        find_distribution_disclosures(source, date(2026, 7, 28), date(2026, 8, 5))


# ---------------------------------------------------------------------------
# 2·3·4단계 — 접수번호 → 문서번호 → 본문 URL
# ---------------------------------------------------------------------------


def test_문서번호를_읽는다() -> None:
    assert parse_document_number(fixture(f"viewer_{BUNDLED_ACPT}.html")) == BUNDLED_DOC


def test_본문선택_빈_option은_문서번호가_아니다() -> None:
    html = (
        "<select><option value=''>본문선택</option>"
        "<option value='20260729001455|Y'>x</option></select>"
    )
    assert parse_document_number(html) == BUNDLED_DOC


def test_정정이_있으면_selected_문서를_고른다() -> None:
    html = (
        "<select><option value=''>본문선택</option>"
        "<option value='20260729001111|Y'>원본</option>"
        "<option value='20260729001455|Y'selected=\"selected\">정정</option></select>"
    )
    assert parse_document_number(html) == BUNDLED_DOC


def test_문서번호를_못_읽으면_알린다() -> None:
    with pytest.raises(KindParseError):
        parse_document_number("<select></select>")


def test_본문_URL을_껍데기에서_읽는다() -> None:
    """URL을 날짜 규칙으로 지어내면 끝의 파일 이름(68659.htm)을 맞힐 수 없다."""
    assert parse_document_url(fixture(f"contents_shell_{BUNDLED_DOC}.html")) == BUNDLED_URL


def test_본문_URL을_못_읽으면_알린다() -> None:
    with pytest.raises(KindParseError):
        parse_document_url("<html><script>parent.close();</script></html>")


def test_접수번호에서_본문_URL까지_이어진다() -> None:
    source = RecordingSource([])
    assert resolve_document_url(source, BUNDLED_ACPT) == BUNDLED_URL


# ---------------------------------------------------------------------------
# 5단계 — 본문 표
# ---------------------------------------------------------------------------


def test_본문_표에서_ISIN으로_찾은_행의_지급예정일이_맞다() -> None:
    """2026-07-31 기준, 2026-08-04 지급, 88원 — 2026-09-16에 KIND에서 직접 확인한 값이다."""
    rows = parse_distribution_rows(fixture(f"body_bundled_{BUNDLED_DOC}.html"))
    matched = [row for row in rows if row.isin == TARGET_ISIN]
    assert len(matched) == 1
    row = matched[0]
    assert row.record_date == date(2026, 7, 31)
    assert row.pay_date == date(2026, 8, 4)
    assert row.amount == Decimal("88")
    assert row.short_name == "TIGER 200 중공업"


def test_지급기준일과_지급예정일을_뒤바꾸지_않는다() -> None:
    rows = parse_distribution_rows(fixture(f"body_bundled_{BUNDLED_DOC}.html"))
    for row in rows:
        assert row.pay_date > row.record_date


def test_가로_표의_모든_행을_읽는다() -> None:
    rows = parse_distribution_rows(fixture(f"body_bundled_{BUNDLED_DOC}.html"))
    assert len(rows) == 3
    assert {row.isin for row in rows} == {
        "KR7227560000",
        "KR7139250005",
        TARGET_ISIN,
    }


def test_세로_표는_ISIN_없이_읽히고_감춰지지_않는다() -> None:
    """2019년 이전 공시에는 종목코드 칸이 없다. 조용히 버리면 빠진 분배를 아무도 모른다."""
    rows = parse_distribution_rows(fixture("body_legacy_20110427001051.html"))
    assert len(rows) == 1
    row = rows[0]
    assert row.isin is None
    assert row.record_date == date(2011, 4, 29)
    assert row.pay_date == date(2011, 5, 11)
    assert row.amount == Decimal("65")
    assert "KOSEF" in row.short_name


def test_정정공시는_앞의_정정표가_아니라_진짜_분배표를_읽는다() -> None:
    """정정공시 본문은 앞에 ``정정항목 | 정정전 | 정정후`` 표를 둔다.

    그 표에 ``종목코드``라는 **항목 이름**이 들어 있어, ``종목코드``만 보고 머리글을 고르면
    엉뚱한 표를 집는다(2025-04-28 접수 20250428000383 실측). 다섯 열이 모두 있는 표만 고른다.
    """
    rows = parse_distribution_rows(fixture("body_corrected_20250428000383.html"))
    assert [(row.isin, row.pay_date, row.amount) for row in rows] == [
        ("KR70004G0002", date(2025, 5, 2), Decimal("28")),
        ("KR7495330003", date(2025, 5, 2), Decimal("23")),
    ]
    assert all(row.record_date == date(2025, 4, 30) for row in rows)


def test_정정표만_있고_분배표가_없으면_알린다() -> None:
    """정정 항목 이름을 분배 표로 착각해 값을 지어내면 안 된다."""
    html = (
        "<table><tr><td>정정항목</td><td>정정전</td><td>정정후</td></tr>"
        "<tr><td>종목코드</td><td>KR70026S0002</td><td>KR70004G0002</td></tr></table>"
    )
    with pytest.raises(KindParseError):
        parse_distribution_rows(html)


def test_모르는_표_모양은_빈_결과가_아니라_오류다() -> None:
    with pytest.raises(KindParseError):
        parse_distribution_rows("<html><body><table><tr><td>가</td></tr></table></body></html>")


def test_표가_없으면_알린다() -> None:
    with pytest.raises(KindParseError):
        parse_distribution_rows("<html><body>본문 없음</body></html>")


def test_종목코드_칸이_ISIN이_아니면_알린다() -> None:
    """단축코드가 들어오면 조용히 안 맞는 것이 아니라 드러나야 한다."""
    html = (
        "<table><tr>"
        "<td>종목코드</td><td>종목약명</td><td>투자신탁 분배금 지급기준일</td>"
        "<td>투자신탁 분배금 지급예정일</td><td>분배금(원)</td></tr>"
        "<tr><td>139230</td><td>TIGER 200 중공업</td><td>2026-07-31</td>"
        "<td>2026-08-04</td><td>88</td></tr></table>"
    )
    with pytest.raises(KindParseError):
        parse_distribution_rows(html)


def test_열_순서가_바뀌어도_이름으로_찾는다() -> None:
    html = (
        "<table><tr>"
        "<td>종목약명</td><td>종목코드</td><td>투자신탁 분배금 지급예정일</td>"
        "<td>투자신탁 분배금 지급기준일</td><td>분배금(원)</td></tr>"
        "<tr><td>TIGER 200 중공업</td><td>KR7139230007</td><td>2026-08-04</td>"
        "<td>2026-07-31</td><td>88</td></tr></table>"
    )
    (row,) = parse_distribution_rows(html)
    assert row.record_date == date(2026, 7, 31)
    assert row.pay_date == date(2026, 8, 4)


def test_본문을_받아_행으로_바꾼다() -> None:
    source = RecordingSource([])
    rows = fetch_distribution_rows(source, BUNDLED_ACPT)
    assert source.document_calls == [BUNDLED_URL]
    assert any(row.isin == TARGET_ISIN for row in rows)


# ---------------------------------------------------------------------------
# 기간 자르기
# ---------------------------------------------------------------------------


def test_기간을_연_단위로_자른다() -> None:
    chunks = list(iter_year_chunks(date(2011, 4, 6), date(2013, 3, 2)))
    assert chunks == [
        (date(2011, 4, 6), date(2011, 12, 31)),
        (date(2012, 1, 1), date(2012, 12, 31)),
        (date(2013, 1, 1), date(2013, 3, 2)),
    ]


def test_한_해_안의_기간은_그대로_둔다() -> None:
    assert list(iter_year_chunks(date(2026, 7, 28), date(2026, 8, 5))) == [
        (date(2026, 7, 28), date(2026, 8, 5))
    ]


def test_뒤집힌_기간은_알린다() -> None:
    with pytest.raises(ValueError):
        list(iter_year_chunks(date(2026, 8, 5), date(2026, 7, 28)))


# ---------------------------------------------------------------------------
# 이력 — 중복 제거와 정렬
# ---------------------------------------------------------------------------


def wide_table(rows: list[tuple[str, str, str, str, str]]) -> str:
    head = (
        "<table><tr><td>종목코드</td><td>종목약명</td>"
        "<td>투자신탁 분배금 지급기준일</td><td>투자신탁 분배금 지급예정일</td>"
        "<td>분배금(원)</td></tr>"
    )
    body = "".join(
        f"<tr><td>{a}</td><td>{b}</td><td>{c}</td><td>{d}</td><td>{e}</td></tr>"
        for a, b, c, d, e in rows
    )
    return head + body + "</table>"


class ScriptedSource:
    """접수번호마다 다른 본문을 돌려주는 원천. 목록은 만들어 넣는다."""

    def __init__(self, refs: list[DisclosureRef], bodies: dict[str, str]) -> None:
        self._refs = refs
        self._bodies = bodies

    def list_page(self, from_date: date, to_date: date, page_index: int, page_size: int) -> str:
        rows = [ref for ref in self._refs if from_date <= ref.disclosed_on <= to_date]
        cells = "".join(
            "<tr>"
            f"<td>{i}</td><td>{ref.disclosed_on:%Y-%m-%d} 15:31</td><td>{ref.issue_name}</td>"
            f"<td><a onclick=\"openDisclsViewer('{ref.acpt_no}','')\" "
            f"title='{ref.title}'>{ref.title}</a></td><td>제출인</td></tr>"
            for i, ref in enumerate(rows, start=1)
        )
        return (
            f"전체 <em>{len(rows)}</em>건 : <strong>{page_index}</strong>/1"
            f"<table><tbody>{cells}</tbody></table>"
        )

    def viewer_page(self, acpt_no: str) -> str:
        return f"<select><option value='{acpt_no}|Y'selected=\"selected\">x</option></select>"

    def contents_shell(self, doc_no: str) -> str:
        return f"<script>parent.setPath('','https://kind.krx.co.kr/doc/{doc_no}.htm','','','');</script>"

    def document(self, url: str) -> str:
        doc_no = url.rsplit("/", 1)[-1].removesuffix(".htm")
        return self._bodies[doc_no]


def ref(acpt_no: str, disclosed_on: date) -> DisclosureRef:
    return DisclosureRef(
        acpt_no=acpt_no,
        disclosed_on=disclosed_on,
        issue_name="TIGER 200 중공업",
        title="ETF이익금분배신고(분배금안내)(일괄공시)",
    )


def test_같은_기준일_지급일은_한_건으로_묶인다() -> None:
    """정정공시가 같은 분배를 다시 알린다. 두 건으로 세면 현금이 두 번 들어간다."""
    original = wide_table([(TARGET_ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "88")])
    source = ScriptedSource(
        [ref("20260729000001", date(2026, 7, 29)), ref("20260730000001", date(2026, 7, 30))],
        {"20260729000001": original, "20260730000001": original},
    )
    history = collect_distribution_history(source, TARGET_ISIN, date(2026, 7, 1), date(2026, 8, 31))
    assert len(history.records) == 1
    assert history.disclosures_read == 2


def test_정정공시가_원본_금액을_대신한다() -> None:
    source = ScriptedSource(
        [ref("20260729000001", date(2026, 7, 29)), ref("20260730000001", date(2026, 7, 30))],
        {
            "20260729000001": wide_table(
                [(TARGET_ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "80")]
            ),
            "20260730000001": wide_table(
                [(TARGET_ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "88")]
            ),
        },
    )
    history = collect_distribution_history(source, TARGET_ISIN, date(2026, 7, 1), date(2026, 8, 31))
    assert [record.amount for record in history.records] == [Decimal("88")]


def test_결과는_지급예정일_오름차순이다() -> None:
    source = ScriptedSource(
        [
            ref("20260729000001", date(2026, 7, 29)),
            ref("20260430000001", date(2026, 4, 30)),
            ref("20260130000001", date(2026, 1, 30)),
        ],
        {
            "20260729000001": wide_table(
                [(TARGET_ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "88")]
            ),
            "20260430000001": wide_table(
                [(TARGET_ISIN, "TIGER 200 중공업", "2026-04-30", "2026-05-07", "50")]
            ),
            "20260130000001": wide_table(
                [(TARGET_ISIN, "TIGER 200 중공업", "2026-01-30", "2026-02-04", "20")]
            ),
        },
    )
    history = collect_distribution_history(
        source, TARGET_ISIN, date(2026, 1, 1), date(2026, 12, 31)
    )
    pay_dates = [record.pay_date for record in history.records]
    assert pay_dates == sorted(pay_dates)
    assert pay_dates == [date(2026, 2, 4), date(2026, 5, 7), date(2026, 8, 4)]


def test_다른_종목은_걸러진다() -> None:
    source = ScriptedSource(
        [ref("20260729000001", date(2026, 7, 29))],
        {
            "20260729000001": wide_table(
                [
                    (TARGET_ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "88"),
                    ("KR7139250005", "TIGER 200 에너지화학", "2026-07-31", "2026-08-04", "50"),
                ]
            )
        },
    )
    history = collect_distribution_history(source, TARGET_ISIN, date(2026, 7, 1), date(2026, 8, 31))
    assert [record.amount for record in history.records] == [Decimal("88")]


def test_ISIN을_못_붙이는_공시는_결과에_드러난다() -> None:
    """빈 결과와 「읽지 못했다」를 가르지 못하면 없는 분배를 없다고 잘못 말하게 된다."""
    source = ScriptedSource(
        [ref(LEGACY_ACPT, date(2011, 4, 27))],
        {LEGACY_ACPT: fixture("body_legacy_20110427001051.html")},
    )
    history = collect_distribution_history(source, TARGET_ISIN, date(2011, 4, 1), date(2011, 5, 31))
    assert history.records == ()
    assert len(history.unkeyed) == 1
    assert history.unkeyed[0].acpt_no == LEGACY_ACPT
    assert history.is_complete is False


def test_모두_읽었으면_완전하다고_말한다() -> None:
    source = ScriptedSource(
        [ref("20260729000001", date(2026, 7, 29))],
        {
            "20260729000001": wide_table(
                [(TARGET_ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "88")]
            )
        },
    )
    history = collect_distribution_history(source, TARGET_ISIN, date(2026, 7, 1), date(2026, 8, 31))
    assert history.is_complete is True


def test_기간을_연으로_나눠_목록을_부른다() -> None:
    source = ScriptedSource([], {})
    calls: list[tuple[date, date]] = []
    original = source.list_page

    def spy(from_date: date, to_date: date, page_index: int, page_size: int) -> str:
        calls.append((from_date, to_date))
        return original(from_date, to_date, page_index, page_size)

    source.list_page = spy  # type: ignore[method-assign]
    collect_distribution_history(source, TARGET_ISIN, date(2011, 4, 6), date(2013, 3, 2))
    assert calls == [
        (date(2011, 4, 6), date(2011, 12, 31)),
        (date(2012, 1, 1), date(2012, 12, 31)),
        (date(2013, 1, 1), date(2013, 3, 2)),
    ]


def test_단축코드는_ISIN으로_받지_않는다() -> None:
    """단축코드를 ISIN 자리에 넣는 실수가 조용히 빈 결과가 되면 안 된다."""
    with pytest.raises(ValueError):
        collect_distribution_history(
            RecordingSource([]), "139230", date(2026, 7, 1), date(2026, 8, 31)
        )


# ---------------------------------------------------------------------------
# 인코딩
# ---------------------------------------------------------------------------


def test_UTF8_선언이_euckr여도_바이트대로_읽는다() -> None:
    """껍데기가 charset=euc-kr을 선언하지만 실제 바이트는 UTF-8이다(2026-09-16 확인)."""
    assert decode_kind_bytes("지급예정일".encode()) == "지급예정일"
    assert decode_kind_bytes("지급예정일".encode("euc-kr")) == "지급예정일"


def test_해독할_수_없으면_알린다() -> None:
    with pytest.raises(KindParseError):
        decode_kind_bytes(b"\xff\xfe\xff\xfe")


def test_DistributionRow는_얼어_있다() -> None:
    row = DistributionRow(
        isin=TARGET_ISIN,
        short_name="TIGER 200 중공업",
        record_date=date(2026, 7, 31),
        pay_date=date(2026, 8, 4),
        amount=Decimal("88"),
    )
    with pytest.raises(AttributeError):
        row.amount = Decimal("1")  # type: ignore[misc]


# ---------------------------------------------------------------------------
# 2019년 이전 — 펀드 정식명으로 맞추기
#
# 아래 픽스처 이름의 숫자는 **접수번호**다(앞선 픽스처는 문서번호를 쓴다). 근거를 보고서와
# 같은 번호로 가리키려고 접수번호로 두었다. 모두 2026-09-16에 KIND에서 실제로 받은 본문이다.
# ---------------------------------------------------------------------------

#: 2019-04-26 접수. 삼성자산운용 공시에서 사람이 따로 확인한 두 건이 여기 들어 있다.
KODEX_SECURITIES_ACPT = "20190426000611"  # 삼성 KODEX 증권주… 2019-05-03 지급, 200원
KODEX_BATTERY_ACPT = "20190426000633"  # 삼성 KODEX 2차전지산업… 2019-05-03 지급, 113원
TIGER_HEAVY_ACPT = "20190426000435"  # 미래에셋 TIGER 200중공업… 2019-05-03 지급, 20원
KBSTAR_HEAVY_ACPT = "20190426000663"  # KB KBSTAR 200중공업… 부분 일치 함정의 실물이다
LEGACY_BUNDLED_ACPT = "20191029000131"  # 2019년 일괄공시. 이름 열이 「종목명」이다

#: 2024-04-26 접수 20240426000498·20240426000640 본문에서 관측한 ISIN이다.
KODEX_SECURITIES_ISIN = "KR7102970001"
KODEX_BATTERY_ISIN = "KR7305720005"
KODEX_BANK_ISIN = "KR7091170001"

#: 2019-04-26 공시 본문에서 관측한 정식명 그대로다. 지어내지 않았다.
FUND_NAMES_2019 = {
    "삼성 KODEX 증권주증권상장지수투자신탁(주식)": KODEX_SECURITIES_ISIN,
    "삼성 KODEX 2차전지산업증권상장지수투자신탁[주식형]": KODEX_BATTERY_ISIN,
    "미래에셋 TIGER 200중공업증권상장지수투자신탁(주식)": TARGET_ISIN,
}


def legacy_table(name: str, record: str, pay: str, amount: str) -> str:
    """2019년 이전 세로 표를 만든다. ISIN 칸이 없는 것이 이 모양의 핵심이다."""
    return (
        "<table>"
        f"<tr><td>종목</td><td>{name}</td></tr>"
        f"<tr><td>투자신탁 분배금 지급기준일</td><td>{record}</td></tr>"
        f"<tr><td>투자신탁 분배금 지급예정일</td><td>{pay}</td></tr>"
        f"<tr><td>1좌당 예상분배금(원)</td><td>{amount}</td></tr>"
        "</table>"
    )


def legacy_ref(acpt_no: str, disclosed_on: date, issue_name: str) -> DisclosureRef:
    return DisclosureRef(
        acpt_no=acpt_no,
        disclosed_on=disclosed_on,
        issue_name=issue_name,
        title="ETF이익금분배신고(분배금안내)",
    )


# --- 이름 다듬기 -------------------------------------------------------------


def test_정식명은_공백만_지워_견준다() -> None:
    """같은 펀드의 정식명이 해마다 띄어쓰기만 달라진다(2011~2019 실측)."""
    assert normalize_fund_name("삼성KODEX건설증권상장지수투자신탁[주식]") == normalize_fund_name(
        "삼성 KODEX 건설 증권상장지수투자신탁[주식]"
    )


def test_괄호_모양은_지우지_않는다() -> None:
    """[주식]과 (주식)은 서로 다른 표기다. 뭉개면 가를 수단이 하나 줄어든다."""
    assert normalize_fund_name("삼성 KODEX 건설증권상장지수투자신탁(주식)") != normalize_fund_name(
        "삼성 KODEX 건설증권상장지수투자신탁[주식]"
    )


# --- 대응표 ------------------------------------------------------------------


def test_대응표는_ISIN_형식을_확인한다() -> None:
    with pytest.raises(ValueError):
        build_fund_name_index({"삼성 KODEX 은행증권상장지수투자신탁(주식)": "091170"})


def test_같은_이름이_두_ISIN을_가리키면_알린다() -> None:
    """조용히 덮어쓰면 분배가 엉뚱한 종목에 붙고 아무도 모른다."""
    with pytest.raises(ValueError):
        build_fund_name_index(
            {
                "삼성 KODEX 은행증권상장지수투자신탁(주식)": KODEX_BANK_ISIN,
                "삼성KODEX은행증권상장지수투자신탁(주식)": KODEX_SECURITIES_ISIN,
            }
        )


def test_같은_ISIN을_여러_이름이_가리켜도_된다() -> None:
    """정식명이 시기마다 달라 한 종목에 여러 줄이 필요하다."""
    index = build_fund_name_index(
        {
            "삼성 KODEX 건설증권상장지수투자신탁(주식)": KODEX_BANK_ISIN,
            "삼성KODEX건설증권상장지수투자신탁[주식]": KODEX_BANK_ISIN,
        }
    )
    assert len(index) == 2


# --- 부분 일치 함정 ----------------------------------------------------------


def test_브랜드명_부분_일치에_걸리지_않는다() -> None:
    """KODEX 은행이 KODEX 은행고배당에도 걸리면 분배가 남의 종목에 붙는다."""
    index = build_fund_name_index({"삼성 KODEX 은행증권상장지수투자신탁(주식)": KODEX_BANK_ISIN})
    assert match_fund_name("삼성 KODEX 은행증권상장지수투자신탁(주식)", index) == KODEX_BANK_ISIN
    assert match_fund_name("삼성 KODEX 은행고배당증권상장지수투자신탁(주식)", index) is None


def test_운용사가_다른_같은_업종에_걸리지_않는다() -> None:
    """2019년 공시에 「미래에셋 TIGER 은행…」이 실제로 함께 있다."""
    index = build_fund_name_index({"삼성 KODEX 은행증권상장지수투자신탁(주식)": KODEX_BANK_ISIN})
    assert match_fund_name("미래에셋 TIGER 은행증권상장지수투자신탁(주식)", index) is None


def test_뒤쪽_조각이_남의_이름에_통째로_들어_있어도_걸리지_않는다() -> None:
    """KB KBSTAR 200중공업…(주식)은 200중공업…(주식)을 통째로 품는다.

    실제 2019-04-26 접수 두 건으로 건다. 뒤쪽 조각으로 맞추는 방법이었다면 여기서 터진다.
    """
    index = build_fund_name_index(FUND_NAMES_2019)
    ours = parse_distribution_rows(fixture(f"body_legacy_acpt{TIGER_HEAVY_ACPT}.html"))
    theirs = parse_distribution_rows(fixture(f"body_legacy_acpt{KBSTAR_HEAVY_ACPT}.html"))
    assert "200중공업증권상장지수투자신탁(주식)" in theirs[0].short_name
    assert match_fund_name(ours[0].short_name, index) == TARGET_ISIN
    assert match_fund_name(theirs[0].short_name, index) is None


def test_브랜드명을_이어붙인_이름으로는_찾을_수_없다() -> None:
    """단축코드 102970의 브랜드명은 KODEX 증권인데 정식명은 KODEX 증권주…다.

    {브랜드명}증권상장지수투자신탁을 만들어 맞추는 방법이면 한 건도 못 찾는다.
    """
    (row,) = parse_distribution_rows(fixture(f"body_legacy_acpt{KODEX_SECURITIES_ACPT}.html"))
    assert row.short_name == "삼성 KODEX 증권주증권상장지수투자신탁(주식)"
    made = build_fund_name_index(
        {"삼성 KODEX 증권증권상장지수투자신탁(주식)": KODEX_SECURITIES_ISIN}
    )
    assert match_fund_name(row.short_name, made) is None


# --- 갈래 가르기 -------------------------------------------------------------


def test_ISIN이_있는_행은_이름표를_보지_않는다() -> None:
    """원천이 준 열쇠를 두고 이름을 보면 대응표의 실수가 원천을 덮어쓴다."""
    rows = parse_distribution_rows(fixture(f"body_bundled_{BUNDLED_DOC}.html"))
    index = build_fund_name_index({"TIGER 200 중공업": KODEX_BANK_ISIN})
    keyed, unmatched = key_distribution_rows(rows, index)
    assert unmatched == []
    assert {isin for _, isin, _ in keyed} == {"KR7227560000", "KR7139250005", TARGET_ISIN}
    assert {how for _, _, how in keyed} == {MATCH_BY_ISIN}


def test_이름으로_맞춘_행은_갈래가_드러난다() -> None:
    rows = parse_distribution_rows(fixture(f"body_legacy_acpt{KODEX_BATTERY_ACPT}.html"))
    keyed, unmatched = key_distribution_rows(rows, build_fund_name_index(FUND_NAMES_2019))
    assert unmatched == []
    assert [(isin, how) for _, isin, how in keyed] == [(KODEX_BATTERY_ISIN, MATCH_BY_FUND_NAME)]


def test_대응표에_없는_행은_버려지지_않고_돌아온다() -> None:
    rows = parse_distribution_rows(fixture(f"body_legacy_acpt{KBSTAR_HEAVY_ACPT}.html"))
    keyed, unmatched = key_distribution_rows(rows, build_fund_name_index(FUND_NAMES_2019))
    assert keyed == []
    assert [row.short_name for row in unmatched] == [
        "KB KBSTAR 200중공업증권상장지수투자신탁(주식)"
    ]


# --- 2019년 일괄공시의 열 이름 ------------------------------------------------


def test_2019년_일괄공시는_열_이름이_종목명이다() -> None:
    """「종목약명」으로 못 박으면 2019년 일괄공시 22건이 통째로 오류가 되어 그 해가 멈춘다."""
    rows = parse_distribution_rows(fixture(f"body_bundled_acpt{LEGACY_BUNDLED_ACPT}.html"))
    assert len(rows) == 4
    assert rows[0].isin == "KR7293180006"
    assert rows[0].short_name == "HANARO 200"
    assert rows[0].record_date == date(2019, 10, 31)
    assert rows[0].pay_date == date(2019, 11, 4)
    assert rows[0].amount == Decimal("88")


# --- 이력 수집 ---------------------------------------------------------------


def test_정식명으로_2019년_분배를_모은다() -> None:
    """삼성자산운용 공시에서 사람이 확인한 두 건이 그대로 나와야 한다."""
    bodies = {
        KODEX_SECURITIES_ACPT: fixture(f"body_legacy_acpt{KODEX_SECURITIES_ACPT}.html"),
        KODEX_BATTERY_ACPT: fixture(f"body_legacy_acpt{KODEX_BATTERY_ACPT}.html"),
    }
    refs = [
        legacy_ref(KODEX_SECURITIES_ACPT, date(2019, 4, 26), "KODEX 증권"),
        legacy_ref(KODEX_BATTERY_ACPT, date(2019, 4, 26), "KODEX 2차전지산업"),
    ]
    source = ScriptedSource(refs, bodies)

    securities = collect_distribution_history(
        source,
        KODEX_SECURITIES_ISIN,
        date(2019, 1, 1),
        date(2019, 12, 31),
        fund_names=FUND_NAMES_2019,
    )
    assert [(r.pay_date, r.amount, r.matched_by) for r in securities.records] == [
        (date(2019, 5, 3), Decimal("200"), MATCH_BY_FUND_NAME)
    ]
    assert securities.records[0].record_date == date(2019, 4, 30)

    battery = collect_distribution_history(
        source,
        KODEX_BATTERY_ISIN,
        date(2019, 1, 1),
        date(2019, 12, 31),
        fund_names=FUND_NAMES_2019,
    )
    assert [(r.pay_date, r.amount, r.matched_by) for r in battery.records] == [
        (date(2019, 5, 3), Decimal("113"), MATCH_BY_FUND_NAME)
    ]
    assert battery.is_complete is True


def test_대응표를_주어도_남의_공시는_결과에_드러난다() -> None:
    """KB 상품 공시는 우리 대응표에 없다. 조용히 사라지면 「분배가 없다」를 잘못 말하게 된다."""
    source = ScriptedSource(
        [legacy_ref(KBSTAR_HEAVY_ACPT, date(2019, 4, 26), "KBSTAR 200중공업")],
        {KBSTAR_HEAVY_ACPT: fixture(f"body_legacy_acpt{KBSTAR_HEAVY_ACPT}.html")},
    )
    history = collect_distribution_history(
        source, TARGET_ISIN, date(2019, 1, 1), date(2019, 12, 31), fund_names=FUND_NAMES_2019
    )
    assert history.records == ()
    assert history.is_complete is False
    assert history.unkeyed[0].names == ("KB KBSTAR 200중공업증권상장지수투자신탁(주식)",)
    assert "대응표에 없다" in history.unkeyed[0].reason


def test_대응표를_주지_않으면_옛_동작_그대로다() -> None:
    source = ScriptedSource(
        [legacy_ref(TIGER_HEAVY_ACPT, date(2019, 4, 26), "TIGER 200중공업")],
        {TIGER_HEAVY_ACPT: fixture(f"body_legacy_acpt{TIGER_HEAVY_ACPT}.html")},
    )
    history = collect_distribution_history(
        source, TARGET_ISIN, date(2019, 1, 1), date(2019, 12, 31)
    )
    assert history.records == ()
    assert history.is_complete is False
    assert "대응표를 받지 않았다" in history.unkeyed[0].reason


def test_은행고배당_함정은_이력에서도_막힌다() -> None:
    """이름이 비슷한 두 공시를 나란히 두고, 우리 것만 집히는지 본다."""
    ours = "삼성 KODEX 은행증권상장지수투자신탁(주식)"
    theirs = "삼성 KODEX 은행고배당증권상장지수투자신탁(주식)"
    source = ScriptedSource(
        [
            legacy_ref("20190426000612", date(2019, 4, 26), "KODEX 은행"),
            legacy_ref("20190426000999", date(2019, 4, 26), "KODEX 은행고배당"),
        ],
        {
            "20190426000612": legacy_table(ours, "2019-04-30", "2019-05-03", "300"),
            "20190426000999": legacy_table(theirs, "2019-04-30", "2019-05-03", "999"),
        },
    )
    history = collect_distribution_history(
        source,
        KODEX_BANK_ISIN,
        date(2019, 1, 1),
        date(2019, 12, 31),
        fund_names={ours: KODEX_BANK_ISIN},
    )
    assert [r.amount for r in history.records] == [Decimal("300")]
    assert history.unkeyed[0].names == (theirs,)


def test_ISIN_매칭은_갈래_표시까지_그대로다() -> None:
    """이름 매칭을 더해도 2020년 이후 경로가 흔들리면 안 된다."""
    source = ScriptedSource(
        [ref("20260729000001", date(2026, 7, 29))],
        {
            "20260729000001": wide_table(
                [(TARGET_ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "88")]
            )
        },
    )
    history = collect_distribution_history(
        source, TARGET_ISIN, date(2026, 7, 1), date(2026, 8, 31), fund_names=FUND_NAMES_2019
    )
    assert [(r.amount, r.matched_by) for r in history.records] == [(Decimal("88"), MATCH_BY_ISIN)]
    assert history.is_complete is True


# ---------------------------------------------------------------------------
# 옛 접수번호 — 2006~2009년은 숫자만이 아니다
# ---------------------------------------------------------------------------


def test_영문이_섞인_접수번호도_읽는다() -> None:
    """2006~2009년 접수번호는 ``20061227U00003``처럼 영문 한 글자를 품는다(2026-09-16 실측).

    숫자 14자리로 못 박으면 그 시기 목록에서 접수번호를 하나도 읽지 못하고 :class:`KindParseError`
    로 멈춘다. 그러면 상장일부터 훑는 수집이 첫 해에서 끝난다.
    """
    refs = parse_disclosure_refs(fixture("list_page_2006.html"))
    assert [ref.acpt_no for ref in refs] == ["20061227U00003", "20061226U00008"]
    assert refs[0].issue_name == "KODEX 200"
    assert refs[0].disclosed_on == date(2006, 12, 27)


# ---------------------------------------------------------------------------
# 펀드 정식명 대응표 파일
# ---------------------------------------------------------------------------

FUND_NAMES_PATH = Path(__file__).resolve().parents[1] / "config" / "fund-names.json"

#: 여덟 섹터 ETF의 ISIN. 대응표 파일이 이 여덟을 모두 담아야 2019년 이전 분배를 붙일 수 있다.
EIGHT_ISINS = {
    "KR7091160002",  # KODEX 반도체
    "KR7091170001",  # KODEX 은행
    "KR7091180000",  # KODEX 자동차
    "KR7102970001",  # KODEX 증권
    "KR7117700005",  # KODEX 건설
    "KR7305720005",  # KODEX 2차전지산업
    "KR7139230007",  # TIGER 200 중공업
    "KR7228790002",  # TIGER 화장품
}


def test_대응표_파일은_여덟_종목을_모두_담는다() -> None:
    """한 종목이라도 빠지면 그 종목의 2019년 이전 분배가 통째로 사라진다."""
    names = load_fund_names(FUND_NAMES_PATH)
    assert set(names.values()) == EIGHT_ISINS


def test_대응표가_2019년_본문의_정식명을_맞춘다() -> None:
    """픽스처는 2019-04-26에 실제로 공시된 본문이다. 대응표가 이것을 못 맞추면 헛것이다."""
    index = build_fund_name_index(load_fund_names(FUND_NAMES_PATH))
    for acpt_no, expected in (
        (KODEX_SECURITIES_ACPT, "KR7102970001"),
        (KODEX_BATTERY_ACPT, "KR7305720005"),
        (TIGER_HEAVY_ACPT, TARGET_ISIN),
    ):
        (row,) = parse_distribution_rows(fixture(f"body_legacy_acpt{acpt_no}.html"))
        assert match_fund_name(row.short_name, index) == expected


def test_대응표는_남의_상품을_맞추지_않는다() -> None:
    """KB KBSTAR 200중공업은 TIGER 것의 뒷조각을 통째로 품는다. 완전 일치라 걸리지 않아야 한다."""
    index = build_fund_name_index(load_fund_names(FUND_NAMES_PATH))
    (row,) = parse_distribution_rows(fixture(f"body_legacy_acpt{KBSTAR_HEAVY_ACPT}.html"))
    assert match_fund_name(row.short_name, index) is None


def test_근거_없는_이름은_받아주지_않는다(tmp_path: Path) -> None:
    """정식명은 지어낼 수 있는 값이 아니다. 근거 없는 줄을 받으면 추측이 섞인다."""
    path = tmp_path / "fund-names.json"
    path.write_text(
        json.dumps(
            {"funds": [{"isin": TARGET_ISIN, "official_names": [{"name": "지어낸 이름"}]}]},
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    with pytest.raises(FundNameFileError, match="관측 근거"):
        load_fund_names(path)


def test_파일이_없으면_조용히_비지_않는다(tmp_path: Path) -> None:
    with pytest.raises(FundNameFileError):
        load_fund_names(tmp_path / "없는파일.json")


def test_대응표_파일도_같은_이름이_두_ISIN을_가리키면_알린다(tmp_path: Path) -> None:
    path = tmp_path / "fund-names.json"
    seen = [{"acpt_no": "20190426000435", "disclosed_on": "2019-04-26"}]
    path.write_text(
        json.dumps(
            {
                "funds": [
                    {
                        "isin": TARGET_ISIN,
                        "official_names": [{"name": "같은 이름", "observed": seen}],
                    },
                    {
                        "isin": KODEX_BANK_ISIN,
                        "official_names": [{"name": "같은 이름", "observed": seen}],
                    },
                ]
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    with pytest.raises(FundNameFileError, match="두 ISIN"):
        load_fund_names(path)
