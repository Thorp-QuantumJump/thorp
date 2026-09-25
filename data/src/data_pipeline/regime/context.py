"""당시 맥락 — 잔차가 큰 사례에 붙이는 같은 기간의 공식 발간물·사건 (spec 010 v3 개정안 §4).

표(`config/regime-context-v1.json`)는 `scripts/collect_regime_context.py`가 만든다. 제목·날짜·원문
링크만 있고 본문이 없다.

**검색 입력이 아니다**(원칙 VII). 순위를 정한 뒤 사례 설명에 링크로 붙일 뿐이며, 어느 사례가 특이
표시를 받는지는 잔차 크기가 정한다(phase_snapshot).

붙이는 기간은 **사례의 관측 구간 안**이다 — 012 FR-006이 과거 사례 뉴스를 관측 구간 안 발행분으로
한정한 것과 같은 규칙이다. 구간 밖 사건을 붙이면 "그때 이런 일이 있었다"가 "그 뒤에 이런 일이
있었다"로 새어 사후 정보가 된다.
"""

from __future__ import annotations

import json
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Final

DEFAULT_CONTEXT: Final[Path] = (
    Path(__file__).resolve().parents[3] / "config" / "regime-context-v1.json"
)

KINDS: Final[frozenset[str]] = frozenset({"KDI_TRENDS", "BASE_RATE", "CYCLE_TURN"})


class ContextTableError(ValueError):
    """맥락 표가 형식을 어겼다. 배치를 시작하지 않는다."""


@dataclass(frozen=True)
class ContextEvent:
    kind: str
    on: date
    title: str
    url: str | None
    source: str

    def body(self) -> dict[str, Any]:
        return {
            "kind": self.kind,
            "date": self.on.isoformat(),
            "title": self.title,
            "url": self.url,
            "source": self.source,
        }


def load_context(path: Path = DEFAULT_CONTEXT) -> tuple[ContextEvent, ...]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    events: list[ContextEvent] = []
    for item in raw.get("events", []):
        if item.get("kind") not in KINDS:
            raise ContextTableError(f"모르는 맥락 종류다: {item.get('kind')!r}")
        url = item.get("url")
        if url is not None and not str(url).startswith("https://"):
            raise ContextTableError(f"원문 링크는 https만 쓴다: {url!r}")
        events.append(
            ContextEvent(
                kind=item["kind"],
                on=date.fromisoformat(item["date"]),
                title=item["title"],
                url=url,
                source=item["source"],
            )
        )
    return tuple(sorted(events, key=lambda e: (e.on, e.kind, e.title)))


def _month_end(month: date) -> date:
    following = date(month.year + month.month // 12, month.month % 12 + 1, 1)
    return date.fromordinal(following.toordinal() - 1)


def events_within(
    events: Sequence[ContextEvent], months: Sequence[date]
) -> tuple[ContextEvent, ...]:
    """관측 구간(첫 달 1일 ~ 마지막 달 말일) 안의 사건."""
    if not months:
        return ()
    start, end = min(months), _month_end(max(months))
    return tuple(event for event in events if start <= event.on <= end)
