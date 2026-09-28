"""종합 비교 '당시 맥락' 표를 만든다 (spec 010 v3 개정안 §4, PD-F6 출발안, S15P21E205-442).

세 가지를 한 표로 묶는다. **제목·발간일·원문 링크만** 담고 본문과 요약은 담지 않는다(constitution
"뉴스·거시 데이터", 개정안 §4.2).

- KDI 경제동향(월간): 연도 목록에서 호 번호를 읽고, 호 페이지에서 발간일을 읽는다.
- 한국은행 기준금리 변경일: 기준금리 추이 표 한 장.
- 경기 정점·저점: 국가데이터처 2023-03-02 기준순환일 (v3-experiment.md §2와 같은 표).

    .venv/bin/python scripts/collect_regime_context.py --out config/regime-context-v1.json

검색 입력이 아니다. 배치는 잔차가 큰 사례에 링크로 붙일 뿐이다(원칙 VII).
"""

from __future__ import annotations

import argparse
import html
import json
import re
import sys
import time
from collections.abc import Sequence
from datetime import date
from pathlib import Path
from typing import Any, Final

import requests

KDI_LIST: Final[str] = "https://www.kdi.re.kr/research/monTrends?year={year}"
KDI_ISSUE: Final[str] = "https://www.kdi.re.kr/research/monTrends?pub_no={pub_no}"
BOK_BASE_RATE: Final[str] = (
    "https://www.bok.or.kr/portal/singl/baseRate/list.do?dataSeCd=01&menuNo=200643"
)
USER_AGENT: Final[str] = "Thorp-regime-context/1.0 (+research; titles and links only)"
DELAY_SECONDS: Final[float] = 0.4

#: 국가데이터처 2023-03-02 「최근 경기순환기의 기준순환일 설정」. (월, 종류, 잠정 여부)
TURNING_POINTS: Final[tuple[tuple[str, str, bool], ...]] = (
    ("1998-08", "저점", False),
    ("2000-08", "정점", False),
    ("2001-07", "저점", False),
    ("2002-12", "정점", False),
    ("2005-04", "저점", False),
    ("2008-01", "정점", False),
    ("2009-02", "저점", False),
    ("2011-08", "정점", False),
    ("2013-03", "저점", False),
    ("2017-09", "정점", True),
    ("2020-05", "저점", True),
)

_ISSUE_BUTTON = re.compile(r"monTrends\?pub_no=(\d+)'\"\s*>\s*(\d{1,2})월")
_ISSUE_DATE = re.compile(
    r'<div class="tit-info">\s*<div class="date">(\d{4})\.(\d{2})\.(\d{2})</div>'
)
_ROW = re.compile(r"<tr[^>]*>(.*?)</tr>", re.S)
_CELL = re.compile(r"<t[dh][^>]*>(.*?)</t[dh]>", re.S)
_TAG = re.compile(r"<[^>]+>")


RETRIES: Final[int] = 3


def _get(session: requests.Session, url: str) -> str:
    """한 번 실패로 표 전체를 버리지 않는다 — 원천의 일시 502가 실제로 있었다.

    세 번째에도 실패면 멈춘다. 호 하나가 빠진 표를 조용히 내지 않는다.
    """
    for attempt in range(1, RETRIES + 1):
        try:
            response = session.get(url, timeout=30)
            response.raise_for_status()
        except requests.RequestException:
            if attempt == RETRIES:
                raise
            time.sleep(DELAY_SECONDS * 5 * attempt)
            continue
        time.sleep(DELAY_SECONDS)
        return response.text
    raise AssertionError("도달하지 않는다")


def kdi_issues(session: requests.Session, years: Sequence[int]) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    for year in years:
        listing = _get(session, KDI_LIST.format(year=year))
        for pub_no, month in sorted(set(_ISSUE_BUTTON.findall(listing)), key=lambda m: int(m[1])):
            page = _get(session, KDI_ISSUE.format(pub_no=pub_no))
            found = _ISSUE_DATE.search(page)
            if found is None:
                print(f"발간일 없음: {year}-{month} pub_no={pub_no}", file=sys.stderr)
                continue
            published = date(int(found[1]), int(found[2]), int(found[3]))
            events.append(
                {
                    "kind": "KDI_TRENDS",
                    "date": published.isoformat(),
                    "title": f"KDI 경제동향 {year}년 {int(month)}월호",
                    "url": KDI_ISSUE.format(pub_no=pub_no),
                    "source": "KDI 한국개발연구원",
                }
            )
    return events


def base_rate_changes(session: requests.Session) -> list[dict[str, Any]]:
    page = _get(session, BOK_BASE_RATE)
    rows: list[tuple[date, str]] = []
    for row in _ROW.findall(page):
        cells = [html.unescape(_TAG.sub("", cell)).strip() for cell in _CELL.findall(row)]
        if len(cells) != 3 or not cells[0].isdigit():
            continue
        month_day = re.match(r"(\d{2})월\s*(\d{2})일", cells[1])
        if month_day is None:
            continue
        rows.append((date(int(cells[0]), int(month_day[1]), int(month_day[2])), cells[2]))
    rows.sort()
    events: list[dict[str, Any]] = []
    for index, (changed, rate) in enumerate(rows):
        previous = rows[index - 1][1] if index > 0 else None
        # 2008-02까지는 콜금리 목표였다(한국은행 표의 주석). 이름을 구분해 둔다.
        name = "기준금리" if changed >= date(2008, 3, 1) else "콜금리 목표"
        title = (
            f"한국은행 {name} {previous}% → {rate}%"
            if previous is not None
            else f"한국은행 {name} {rate}%"
        )
        events.append(
            {
                "kind": "BASE_RATE",
                "date": changed.isoformat(),
                "title": title,
                "url": BOK_BASE_RATE,
                "source": "한국은행",
            }
        )
    return events


def turning_points() -> list[dict[str, Any]]:
    return [
        {
            "kind": "CYCLE_TURN",
            "date": f"{month}-01",
            "month": month,
            "title": f"경기 {kind}{' (잠정)' if provisional else ''}",
            "url": None,
            "source": "국가데이터처 기준순환일 (2023-03-02 발표)",
        }
        for month, kind, provisional in TURNING_POINTS
    ]


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--from-year", type=int, default=2000)
    parser.add_argument("--to-year", type=int, default=date.today().year)
    args = parser.parse_args(argv)

    session = requests.Session()
    session.headers["User-Agent"] = USER_AGENT
    events = [
        *kdi_issues(session, range(args.from_year, args.to_year + 1)),
        *base_rate_changes(session),
        *turning_points(),
    ]
    events.sort(key=lambda event: (event["date"], event["kind"], event["title"]))
    table = {
        "version": "REGIME-CONTEXT-V1",
        "collectedOn": date.today().isoformat(),
        "note": "제목·발간일·원문 링크만 담는다. 본문·요약은 담지 않는다(spec 010 v3 개정안 §4).",
        "events": events,
    }
    args.out.write_text(json.dumps(table, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    counts: dict[str, int] = {}
    for event in events:
        counts[event["kind"]] = counts.get(event["kind"], 0) + 1
    print(f"{args.out}: {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
