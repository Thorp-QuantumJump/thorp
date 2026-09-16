"""분배 기업행위 입력 파일을 만드는 진입점 (spec 001 T014, FR-004·FR-006).

``scripts/build_distributions.py``가 정하는 것은 **무엇을 열쇠로 두 원천을 붙이는가**와
**붙지 않은 것이 어떻게 드러나는가**다. 계산 자체는 ``data_pipeline``이 이미 갖고 있다.

여기서 고정하는 성질은 셋이다.

1. **락일로 붙는다.** 공시의 락일은 지급기준일 직전 거래일이다. 금액으로 붙이지 않는 까닭은
   두 금액이 같지 않기 때문이다(아래 2번).
2. **공시 금액과 관측 금액의 차이는 호가단위 안이어야 한다.** 거래소 기준가가 5원의 배수라
   공시 금액이 5원의 배수가 아니면 그만큼 깎인 값이 관측된다. 차이가 그보다 크면 락일 대응이
   틀렸다는 뜻이므로 붙이지 않는다.
3. **붙지 않은 것이 결과에서 사라지지 않는다.** 공시를 못 찾은 관측은 지급일이 없어 적재할 수
   없고, 관측이 없는 공시는 락일을 확인하지 못한 것이다. 둘 다 조용히 버리면 "분배가 이게
   전부다"라는 말이 근거 없이 서게 된다.
"""

from __future__ import annotations

import argparse
import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

import build_distributions as build
from data_pipeline import krx
from data_pipeline.corporate_actions import EVENT_DISTRIBUTION, ObservedDistribution
from data_pipeline.kind_distributions import (
    MATCH_BY_ISIN,
    DisclosureRef,
    DistributionRecord,
    FundNameFileError,
    collect_distribution_histories,
    load_fund_isins,
)

INSTRUMENT_ID = "139230.XKRX"
ISIN = "KR7139230007"
OTHER_INSTRUMENT_ID = "228790.XKRX"
OTHER_ISIN = "KR7228790002"

#: 거래일 다섯이다. 2026-07-30이 락일, 2026-07-31이 지급기준일이 된다.
CALENDAR: list[date] = [
    date(2026, 7, 28),
    date(2026, 7, 29),
    date(2026, 7, 30),
    date(2026, 7, 31),
    date(2026, 8, 3),
]


def observed(day: date, amount: str, instrument_id: str = INSTRUMENT_ID) -> ObservedDistribution:
    return ObservedDistribution(
        instrument_id=instrument_id,
        ex_date=day,
        amount=Decimal(amount),
        previous_close=Decimal("10000"),
        close=Decimal("9900"),
        change=Decimal("-100") + Decimal(amount),
    )


def disclosed(
    record_date: date,
    pay_date: date,
    amount: str,
    *,
    isin: str = ISIN,
    acpt_no: str = "20260729000622",
) -> DistributionRecord:
    return DistributionRecord(
        isin=isin,
        short_name="TIGER 200 중공업",
        record_date=record_date,
        pay_date=pay_date,
        amount=Decimal(amount),
        acpt_no=acpt_no,
        matched_by=MATCH_BY_ISIN,
    )


# ---------------------------------------------------------------------------
# 직전 거래일
# ---------------------------------------------------------------------------


def test_직전_거래일은_달력에서_읽는다() -> None:
    assert build.previous_trading_day(CALENDAR, date(2026, 7, 31)) == date(2026, 7, 30)
    # 주말을 건너뛴다. 달력에 없는 날짜를 물어도 그보다 앞선 거래일을 준다.
    assert build.previous_trading_day(CALENDAR, date(2026, 8, 1)) == date(2026, 7, 31)


def test_달력_앞의_날짜는_직전_거래일이_없다() -> None:
    assert build.previous_trading_day(CALENDAR, date(2026, 7, 28)) is None


# ---------------------------------------------------------------------------
# 락일로 붙이기
# ---------------------------------------------------------------------------


def test_지급기준일_직전_거래일이_락일이다() -> None:
    paired, unpaired = build.pair_by_ex_date(
        INSTRUMENT_ID,
        [observed(date(2026, 7, 30), "85")],
        [disclosed(date(2026, 7, 31), date(2026, 8, 4), "85")],
        CALENDAR,
    )
    assert unpaired == []
    assert len(paired) == 1
    assert paired[0].ex_date == date(2026, 7, 30)
    assert paired[0].pay_date == date(2026, 8, 4)


def test_적재하는_금액은_공시_금액이다() -> None:
    """관측 금액은 기준가를 호가단위에 맞추며 깎인 값이다. 투자자가 받는 것은 공시 금액이다."""
    paired, unpaired = build.pair_by_ex_date(
        INSTRUMENT_ID,
        [observed(date(2026, 7, 30), "55")],
        [disclosed(date(2026, 7, 31), date(2026, 8, 4), "59")],
        CALENDAR,
    )
    assert unpaired == []
    assert paired[0].amount == Decimal("59")
    assert paired[0].observed_amount == Decimal("55")
    assert paired[0].to_record() == {
        "instrument_id": INSTRUMENT_ID,
        "ex_date": "2026-07-30",
        "event_type": EVENT_DISTRIBUTION,
        "amount": "59",
        "pay_date": "2026-08-04",
    }


def test_호가단위를_벗어난_차이는_붙이지_않는다() -> None:
    """차이가 5원 이상이면 반올림으로 설명되지 않는다. 락일 대응이 틀렸을 가능성이 더 크다."""
    paired, unpaired = build.pair_by_ex_date(
        INSTRUMENT_ID,
        [observed(date(2026, 7, 30), "55")],
        [disclosed(date(2026, 7, 31), date(2026, 8, 4), "85")],
        CALENDAR,
    )
    assert paired == []
    assert [item.side for item in unpaired] == [build.SIDE_DISCLOSED, build.SIDE_OBSERVED]
    assert "호가단위" in unpaired[0].detail


def test_관측보다_적은_공시_금액도_붙이지_않는다() -> None:
    """관측은 공시를 내림한 값이므로 공시가 더 작을 수 없다. 그렇다면 다른 분배를 본 것이다."""
    paired, _ = build.pair_by_ex_date(
        INSTRUMENT_ID,
        [observed(date(2026, 7, 30), "85")],
        [disclosed(date(2026, 7, 31), date(2026, 8, 4), "84")],
        CALENDAR,
    )
    assert paired == []


def test_공시를_못_찾은_관측이_결과에_남는다() -> None:
    paired, unpaired = build.pair_by_ex_date(
        INSTRUMENT_ID, [observed(date(2026, 7, 30), "85")], [], CALENDAR
    )
    assert paired == []
    assert len(unpaired) == 1
    assert unpaired[0].side == build.SIDE_OBSERVED
    assert unpaired[0].day == date(2026, 7, 30)


def test_관측이_없는_공시가_결과에_남는다() -> None:
    paired, unpaired = build.pair_by_ex_date(
        INSTRUMENT_ID, [], [disclosed(date(2026, 7, 31), date(2026, 8, 4), "85")], CALENDAR
    )
    assert paired == []
    assert [item.side for item in unpaired] == [build.SIDE_DISCLOSED]


def test_달력보다_앞선_기준일은_락일을_정할_수_없다() -> None:
    paired, unpaired = build.pair_by_ex_date(
        INSTRUMENT_ID, [], [disclosed(CALENDAR[0], date(2026, 8, 4), "85")], CALENDAR
    )
    assert paired == []
    assert "락일을 정할 수 없다" in unpaired[0].detail


def test_한_락일을_두_공시가_가리키면_둘_다_붙이지_않는다() -> None:
    """어느 쪽인지 정할 근거가 없다. 하나를 골라 두면 고른 까닭이 남지 않는다."""
    paired, unpaired = build.pair_by_ex_date(
        INSTRUMENT_ID,
        [observed(date(2026, 7, 30), "85")],
        [
            disclosed(date(2026, 7, 31), date(2026, 8, 4), "85", acpt_no="20260729000001"),
            disclosed(date(2026, 7, 31), date(2026, 8, 5), "85", acpt_no="20260729000002"),
        ],
        CALENDAR,
    )
    assert paired == []
    assert sum(item.side == build.SIDE_DISCLOSED for item in unpaired) == 2


# ---------------------------------------------------------------------------
# 대응표 파일 — 종목 식별자와 ISIN
# ---------------------------------------------------------------------------


def fund_names_file(path: Path, funds: list[dict[str, object]]) -> Path:
    path.write_text(json.dumps({"funds": funds}, ensure_ascii=False), encoding="utf-8")
    return path


def fund(instrument_id: str, isin: str, name: str) -> dict[str, object]:
    return {
        "instrument_id": instrument_id,
        "isin": isin,
        "official_names": [
            {
                "name": name,
                "observed": [{"acpt_no": "20110427000489", "disclosed_on": "2011-04-27"}],
            }
        ],
    }


def test_대응표_파일이_종목_식별자와_ISIN을_잇는다(tmp_path: Path) -> None:
    path = fund_names_file(
        tmp_path / "fund-names.json",
        [
            fund(INSTRUMENT_ID, ISIN, "미래에셋 TIGER 200 중공업"),
            fund(OTHER_INSTRUMENT_ID, OTHER_ISIN, "다른 펀드"),
        ],
    )
    assert load_fund_isins(path) == {INSTRUMENT_ID: ISIN, OTHER_INSTRUMENT_ID: OTHER_ISIN}


def test_한_ISIN을_두_종목이_가리키면_알린다(tmp_path: Path) -> None:
    path = fund_names_file(
        tmp_path / "fund-names.json",
        [fund(INSTRUMENT_ID, ISIN, "이름 하나"), fund(OTHER_INSTRUMENT_ID, ISIN, "이름 둘")],
    )
    with pytest.raises(FundNameFileError):
        load_fund_isins(path)


def test_형식이_아닌_종목_식별자는_거부한다(tmp_path: Path) -> None:
    path = fund_names_file(tmp_path / "fund-names.json", [fund("139230", ISIN, "이름")])
    with pytest.raises(FundNameFileError):
        load_fund_isins(path)


def test_대응표에_없는_종목은_알린다() -> None:
    with pytest.raises(build.BuildError):
        build.resolve_instruments(["069500"], {INSTRUMENT_ID: ISIN})


def test_단축코드로도_종목을_고를_수_있다() -> None:
    assert build.resolve_instruments(["139230"], {INSTRUMENT_ID: ISIN}) == {INSTRUMENT_ID: ISIN}


# ---------------------------------------------------------------------------
# 보관본 읽기
# ---------------------------------------------------------------------------


def write_index_day(root: Path, day: date, *, holiday: bool = False) -> None:
    rows = (
        []
        if holiday
        else [
            {
                "BAS_DD": f"{day:%Y%m%d}",
                "IDX_NM": "코스피",
                "OPNPRC_IDX": "3000.00",
                "HGPRC_IDX": "3010.00",
                "LWPRC_IDX": "2990.00",
                "CLSPRC_IDX": "3005.00",
                "ACC_TRDVOL": "1000",
                "ACC_TRDVAL": "2000",
            }
        ]
    )
    path = root / "idx" / "kospi_dd_trd" / f"{day:%Y%m%d}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"OutBlock_1": rows}, ensure_ascii=False), encoding="utf-8")


def write_etf_day(root: Path, day: date, rows: list[dict[str, str]]) -> None:
    path = root / "etp" / "etf_bydd_trd" / f"{day:%Y%m%d}.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"OutBlock_1": rows}, ensure_ascii=False), encoding="utf-8")


def etf_row(code: str, close: str, change: str) -> dict[str, str]:
    return {
        "ISU_CD": code,
        "TDD_CLSPRC": close,
        "TDD_OPNPRC": close,
        "TDD_HGPRC": close,
        "TDD_LWPRC": close,
        "ACC_TRDVOL": "100",
        "ACC_TRDVAL": "1000",
        "CMPPREVDD_PRC": change,
    }


def test_받지_않은_날은_휴장일로_보지_않는다(tmp_path: Path) -> None:
    """보관본이 없는 날을 빈 응답으로 돌려주면 휴장일로 둔갑해 달력에서 조용히 빠진다."""
    write_index_day(tmp_path, date(2026, 7, 28))
    source = krx.ArchiveReadingSource(root=tmp_path)
    with pytest.raises(krx.RawArchiveError):
        build.trading_calendar(source, date(2026, 7, 28), date(2026, 7, 29))


def test_보관본에서_거래일_달력을_만든다(tmp_path: Path) -> None:
    write_index_day(tmp_path, date(2026, 7, 28))
    write_index_day(tmp_path, date(2026, 7, 29), holiday=True)
    write_index_day(tmp_path, date(2026, 7, 30))
    source = krx.ArchiveReadingSource(root=tmp_path)
    assert build.trading_calendar(source, date(2026, 7, 28), date(2026, 7, 30)) == [
        date(2026, 7, 28),
        date(2026, 7, 30),
    ]


# ---------------------------------------------------------------------------
# 처음부터 끝까지
# ---------------------------------------------------------------------------


class ScriptedKindSource:
    """접수번호 하나에 본문 하나를 돌려주는 KIND 원천. 목록은 만들어 넣는다."""

    def __init__(self, refs: list[DisclosureRef], bodies: dict[str, str]) -> None:
        self._refs = refs
        self._bodies = bodies
        self.list_calls = 0

    def list_page(self, from_date: date, to_date: date, page_index: int, page_size: int) -> str:
        self.list_calls += 1
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
        return (
            "<script>parent.setPath('','https://kind.krx.co.kr/doc/"
            f"{doc_no}.htm','','','');</script>"
        )

    def document(self, url: str) -> str:
        return self._bodies[url.rsplit("/", 1)[-1].removesuffix(".htm")]


def bundled_body(rows: list[tuple[str, str, str, str, str]]) -> str:
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


def test_여러_종목을_물어도_목록은_한_번만_훑는다() -> None:
    """종목마다 이력을 따로 부르면 같은 목록을 종목 수만큼 다시 받는다."""
    acpt = "20260729000622"
    source = ScriptedKindSource(
        [
            DisclosureRef(
                acpt_no=acpt,
                disclosed_on=date(2026, 7, 29),
                issue_name="일괄공시",
                title="ETF이익금분배신고(분배금안내)(일괄공시)",
            )
        ],
        {
            acpt: bundled_body(
                [
                    (ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "85"),
                    (OTHER_ISIN, "다른 종목", "2026-07-31", "2026-08-04", "20"),
                ]
            )
        },
    )
    histories = collect_distribution_histories(
        source, [ISIN, OTHER_ISIN], date(2026, 7, 1), date(2026, 8, 31)
    )
    assert source.list_calls == 1
    assert set(histories) == {ISIN, OTHER_ISIN}
    assert histories[ISIN].records[0].amount == Decimal("85")
    assert histories[OTHER_ISIN].records[0].amount == Decimal("20")


def test_분배가_없는_종목도_빠지지_않는다() -> None:
    """빠뜨리면 부르는 쪽이 '없음'과 '묻지 않음'을 가릴 수 없다."""
    source = ScriptedKindSource([], {})
    histories = collect_distribution_histories(
        source, [ISIN, OTHER_ISIN], date(2026, 7, 1), date(2026, 8, 31)
    )
    assert histories[OTHER_ISIN].records == ()


def test_보관본과_공시를_합쳐_기업행위_파일을_쓴다(tmp_path: Path) -> None:
    raw_root = tmp_path / "raw"
    days = [date(2026, 7, 28), date(2026, 7, 29), date(2026, 7, 30), date(2026, 7, 31)]
    for day in days:
        write_index_day(raw_root, day)
    # 2026-07-30이 락일이다. 종가는 10,000 → 9,920이지만 전일 대비는 기준가(10,000 - 85를
    # 호가단위에 맞춘 9,915)에 대고 잰 값이라 +5다. 잔차 85가 곧 관측 분배금이다.
    write_etf_day(raw_root, days[0], [etf_row("139230", "10000", "0")])
    write_etf_day(raw_root, days[1], [etf_row("139230", "10000", "0")])
    write_etf_day(raw_root, days[2], [etf_row("139230", "9920", "5")])
    write_etf_day(raw_root, days[3], [etf_row("139230", "9920", "0")])

    acpt = "20260729000622"
    source = ScriptedKindSource(
        [
            DisclosureRef(
                acpt_no=acpt,
                disclosed_on=date(2026, 7, 29),
                issue_name="일괄공시",
                title="ETF이익금분배신고(분배금안내)(일괄공시)",
            )
        ],
        {acpt: bundled_body([(ISIN, "TIGER 200 중공업", "2026-07-31", "2026-08-04", "85")])},
    )

    out = tmp_path / "actions.json"
    args = argparse.Namespace(
        start=days[0],
        until=days[-1],
        out=out,
        instruments=None,
        fund_names=fund_names_file(
            tmp_path / "fund-names.json", [fund(INSTRUMENT_ID, ISIN, "미래에셋 TIGER 200 중공업")]
        ),
        raw_root=raw_root,
        page_size=None,
    )
    assert build.run(args, source=source) == 0
    assert json.loads(out.read_text(encoding="utf-8")) == [
        {
            "instrument_id": INSTRUMENT_ID,
            "ex_date": "2026-07-30",
            "event_type": EVENT_DISTRIBUTION,
            "amount": "85",
            "pay_date": "2026-08-04",
        }
    ]


def test_붙이지_못한_것이_남으면_종료_코드가_0이_아니다(tmp_path: Path) -> None:
    """파일은 쓰되 완전하지 않다는 것을 종료 코드로 알린다. 조용히 통과하면 검사가 꺼진다."""
    raw_root = tmp_path / "raw"
    days = [date(2026, 7, 28), date(2026, 7, 29), date(2026, 7, 30)]
    for day in days:
        write_index_day(raw_root, day)
    write_etf_day(raw_root, days[0], [etf_row("139230", "10000", "0")])
    write_etf_day(raw_root, days[1], [etf_row("139230", "10000", "0")])
    write_etf_day(raw_root, days[2], [etf_row("139230", "9920", "5")])

    out = tmp_path / "actions.json"
    args = argparse.Namespace(
        start=days[0],
        until=days[-1],
        out=out,
        instruments=None,
        fund_names=fund_names_file(
            tmp_path / "fund-names.json", [fund(INSTRUMENT_ID, ISIN, "미래에셋 TIGER 200 중공업")]
        ),
        raw_root=raw_root,
        page_size=None,
    )
    assert build.run(args, source=ScriptedKindSource([], {})) == 1
    assert json.loads(out.read_text(encoding="utf-8")) == []
