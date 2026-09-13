"""수집 진입점의 입력 파일 규약과 기본값 (spec 001 FR-005·FR-006).

``src/data_pipeline/``이 아니라 ``scripts/ingest_krx.py``가 정하는 두 가지를 고정한다.

1. **기업행위 파일을 주지 않았을 때 무엇이 남는가.** 목록이 없으면 누적 계수가 전부 1이 되어
   조정 종가가 원시 종가와 같아진다. 그 결과는 "기업행위가 없었다"와 똑같이 생겼으므로,
   자료 부족 기록이 없으면 결과만 보아서는 둘을 가릴 수 없다. ``--commit``까지 붙으면 불변
   트리거가 걸려 되돌릴 수도 없다.
2. **입력 파일의 열 이름.** 같은 성격의 파일을 ``ingest_krx.py``와 ``reconcile.py``가 다른
   표기로 읽으면 한 파일을 두 스크립트에 넣을 수 없다. 두 스크립트가 같은 파일을 읽는지를
   여기서 확인한다.
"""

from __future__ import annotations

import json
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

import ingest_krx
import reconcile
from data_pipeline.corporate_actions import (
    ACTIONS_KNOWN_FROM_NEVER,
    EVENT_DISTRIBUTION,
    corporate_actions_from_records,
)
from data_pipeline.gaps import REASON_MISSING_CORPORATE_ACTION, build_instrument_series
from data_pipeline.krx import DailyQuote, ListingWindow
from data_pipeline.reconciliation import listing_windows_from_records

INSTRUMENT_ID = "091160.XKRX"
DAYS: tuple[date, ...] = tuple(date(2026, 9, day) for day in (1, 2, 3, 4))
CLOSES: tuple[str, ...] = ("10000", "10200", "10100", "10400")

LISTING_RECORDS = [
    {"instrument_id": INSTRUMENT_ID, "listed_on": "2006-06-27", "delisted_on": None},
]
ACTION_RECORDS = [
    {
        "instrument_id": INSTRUMENT_ID,
        "ex_date": "2026-09-03",
        "event_type": EVENT_DISTRIBUTION,
        "amount": "120",
        "pay_date": "2026-09-10",
    },
]

#: 고치기 전의 표기. 이 모양을 다시 받아들이면 "두 스크립트가 같은 파일을 읽는다"가 깨진다.
CAMEL_LISTING_RECORDS = [
    {"instrumentId": INSTRUMENT_ID, "listedOn": "2006-06-27", "delistedOn": None},
]
CAMEL_ACTION_RECORDS = [
    {
        "instrumentId": INSTRUMENT_ID,
        "exDate": "2026-09-03",
        "eventType": EVENT_DISTRIBUTION,
        "amount": "120",
        "payDate": "2026-09-10",
    },
]


def write_json(path: Path, records: list[dict[str, object]]) -> Path:
    path.write_text(json.dumps(records, ensure_ascii=False), encoding="utf-8")
    return path


def quotes() -> list[DailyQuote]:
    """체결이 있었던 나흘. 정지·결측이 섞이면 확인하려는 것이 흐려지므로 넣지 않는다."""
    return [
        DailyQuote(
            instrument_id=INSTRUMENT_ID,
            date=day,
            close=Decimal(close),
            open=Decimal(close),
            high=Decimal(close),
            low=Decimal(close),
            volume=1000,
            value=1000 * int(close),
        )
        for day, close in zip(DAYS, CLOSES, strict=True)
    ]


class TestInputFileKeys:
    """파이프라인 입력 파일은 snake_case 한 가지다.

    ``data_pipeline``의 ``*_from_records`` 계약과 이름이 같아 변환 계층을 더 두지 않아도 된다.
    루트 ``CLAUDE.md``의 camelCase 규칙은 API JSON을 대상으로 한 것이라 이 파일에는 해당하지
    않는다.
    """

    def test_listing_file_uses_snake_case(self, tmp_path: Path) -> None:
        path = write_json(tmp_path / "listing.json", LISTING_RECORDS)
        windows = ingest_krx.listing_windows(path)
        assert windows[INSTRUMENT_ID] == ListingWindow(INSTRUMENT_ID, date(2006, 6, 27), None)

    def test_the_same_listing_file_feeds_the_reconcile_script(self, tmp_path: Path) -> None:
        # 두 스크립트가 같은 파일을 읽는지가 이 과제의 본체다. ``reconcile.py``의 읽기를
        # 그대로 써서 확인한다 — 같은 이름을 따로 적어 두면 한쪽만 바뀌어도 통과한다.
        path = write_json(tmp_path / "listing.json", LISTING_RECORDS)
        from_reconcile = listing_windows_from_records(reconcile._read_records(path, "--listing"))
        assert from_reconcile == list(ingest_krx.listing_windows(path).values())

    def test_a_camel_case_listing_file_is_refused(self, tmp_path: Path) -> None:
        # 조용히 둘 다 받아들이면 어느 표기가 규약인지 알 수 없게 되고, 한 파일에 두 표기가
        # 섞여도 드러나지 않는다.
        path = write_json(tmp_path / "listing.json", CAMEL_LISTING_RECORDS)
        with pytest.raises(ingest_krx.IngestError, match="instrument_id"):
            ingest_krx.listing_windows(path)

    def test_corporate_action_file_uses_snake_case(self, tmp_path: Path) -> None:
        path = write_json(tmp_path / "actions.json", ACTION_RECORDS)
        (action,) = ingest_krx.corporate_actions(path)
        assert action.instrument_id == INSTRUMENT_ID
        assert action.ex_date == date(2026, 9, 3)
        assert action.pay_date == date(2026, 9, 10)
        assert action.event_type == EVENT_DISTRIBUTION
        assert action.amount == Decimal("120")

    def test_the_same_action_file_feeds_the_reconcile_script(self, tmp_path: Path) -> None:
        path = write_json(tmp_path / "actions.json", ACTION_RECORDS)
        from_reconcile = corporate_actions_from_records(
            reconcile._read_records(path, "--corporate-actions")
        )
        assert from_reconcile == ingest_krx.corporate_actions(path)

    def test_a_camel_case_action_file_is_refused(self, tmp_path: Path) -> None:
        path = write_json(tmp_path / "actions.json", CAMEL_ACTION_RECORDS)
        with pytest.raises(ingest_krx.IngestError, match="ex_date"):
            ingest_krx.corporate_actions(path)

    def test_the_help_text_shows_the_same_keys(self) -> None:
        # 인자 설명이 사용자가 파일을 만들 때 보는 유일한 안내다. 여기에 옛 표기가 남으면
        # 코드가 받지 않는 파일을 만들게 된다.
        help_text = ingest_krx.build_parser().format_help()
        for camel in ("instrumentId", "listedOn", "delistedOn", "exDate", "payDate", "eventType"):
            assert camel not in help_text, f"인자 설명에 옛 표기가 남아 있다: {camel}"
        assert '"instrument_id"' in help_text
        assert '"ex_date"' in help_text


class TestMissingCorporateActionFile:
    """기업행위 파일이 없으면 그 종목의 전 구간이 자료 부족 구간이 된다 (FR-006).

    인자를 필수로 만들지 않는다. 시험 수집을 막지 않으면서도 없는 것을 있는 것처럼 내보내지
    않는 쪽이 확정된 처리다(2026-09-13).
    """

    def test_no_file_means_the_coverage_is_unknown(self) -> None:
        assert ingest_krx.resolve_actions_known_from(None, None) == ACTIONS_KNOWN_FROM_NEVER

    def test_no_file_ignores_a_declared_coverage_start(self) -> None:
        # 목록이 없는데 "이 날부터 빠짐없다"는 선언만 받으면, 담긴 것이 없는 파일을 담은 것처럼
        # 다루게 된다. 파일이 없다는 사실이 선언보다 세다.
        assert (
            ingest_krx.resolve_actions_known_from(None, date(2020, 1, 1))
            == ACTIONS_KNOWN_FROM_NEVER
        )

    def test_a_file_without_a_declared_start_is_also_unknown(self, tmp_path: Path) -> None:
        # 그 파일이 어느 날부터를 담는지는 파일이 알려 주는 값이다. 없으면 담은 구간을 말할 수
        # 없으므로, 전 구간을 담았다고 보지 않는다.
        path = write_json(tmp_path / "actions.json", ACTION_RECORDS)
        assert ingest_krx.resolve_actions_known_from(path, None) == ACTIONS_KNOWN_FROM_NEVER

    def test_a_declared_start_is_kept(self, tmp_path: Path) -> None:
        path = write_json(tmp_path / "actions.json", ACTION_RECORDS)
        assert ingest_krx.resolve_actions_known_from(path, date(2020, 1, 1)) == date(2020, 1, 1)

    def test_the_whole_series_is_recorded_as_a_gap(self) -> None:
        built = build_instrument_series(
            quotes(),
            DAYS,
            ListingWindow(INSTRUMENT_ID, DAYS[0]),
            ingest_krx.corporate_actions(None),
            actions_known_from=ingest_krx.resolve_actions_known_from(None, None),
        )
        gaps = [row for row in built.gap_rows if row["reason"] == REASON_MISSING_CORPORATE_ACTION]
        assert len(gaps) == 1
        assert (gaps[0]["instrument_id"], gaps[0]["start_date"], gaps[0]["end_date"]) == (
            INSTRUMENT_ID,
            DAYS[0],
            DAYS[-1],
        )

    def test_the_prices_still_look_unadjusted(self) -> None:
        # 기록이 필요한 까닭이 여기 있다. 계수가 전부 1이라 조정 종가가 원시 종가와 같고,
        # 그 모양은 기업행위가 정말 없었을 때와 구분되지 않는다.
        built = build_instrument_series(
            quotes(),
            DAYS,
            ListingWindow(INSTRUMENT_ID, DAYS[0]),
            ingest_krx.corporate_actions(None),
            actions_known_from=ingest_krx.resolve_actions_known_from(None, None),
        )
        assert all(row["adjustment_factor"] == Decimal(1) for row in built.price_rows)
        assert all(row["adjusted_close"] == row["close"] for row in built.price_rows)
