"""KIND 공시에서 분배금 지급일을 받아 오는 경로 (spec 001 FR-004, 004 계약).

이 파일이 고정하는 것은 **놓치지 않는 성질**이다. 이 원천은 제목으로 걸러 주는 인자가 없고
목록이 시각 내림차순이라, 앞 몇 쪽만 보면 분배 공시가 한 건도 없는 것처럼 보인다. 실제로
2026-07-28~08-05 구간은 4,363건 중 402건이 분배 공시였는데 앞 3쪽에는 한 건도 없었다.
그래서 전 페이지를 도는지, 비슷한 제목을 잘못 집지 않는지, 못 읽은 것이 드러나는지를 건다.

픽스처는 2026-09-16에 KIND에서 받은 실제 응답을 줄인 것이다. **파일이 없으면 건너뛰지 않고
실패한다** — 검사 장치가 조용히 꺼지면 빠진 분배를 아무도 모른다.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from data_pipeline.kind_distributions import (
    DISTRIBUTION_TITLE_PREFIX,
    DisclosureRef,
    DistributionRow,
    KindParseError,
    collect_distribution_history,
    decode_kind_bytes,
    fetch_distribution_rows,
    find_distribution_disclosures,
    is_distribution_title,
    iter_year_chunks,
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
        [ref("20110427000489", date(2011, 4, 27))],
        {"20110427000489": fixture("body_legacy_20110427001051.html")},
    )
    history = collect_distribution_history(source, TARGET_ISIN, date(2011, 4, 1), date(2011, 5, 31))
    assert history.records == ()
    assert len(history.unkeyed) == 1
    assert history.unkeyed[0].acpt_no == "20110427000489"
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
