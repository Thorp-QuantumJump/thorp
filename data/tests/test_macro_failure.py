"""수집 실패·재시도·재개 계약 (spec 008 T046·T047·T048·T051)."""

from __future__ import annotations

from dataclasses import replace
from datetime import date
from pathlib import Path

import pytest

from data_pipeline.macro import ingest
from data_pipeline.macro.config import SourceConfig
from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.sources.base import (
    PermanentSourceError,
    QuotaExhaustedError,
    SourceContractError,
    TransientSourceError,
    fetch_with_retry,
)

FIXTURE = Path(__file__).parent / "fixtures" / "macro" / "customs" / "newtrade_2026.xml"


def _config(source_id: str = "customs") -> SourceConfig:
    return SourceConfig(source_id, "https://example.invalid", None, 1, 3, 1, 2)


def test_transient_failure_retries_with_declared_backoff() -> None:
    attempts = 0
    sleeps: list[float] = []

    def call() -> str:
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise TransientSourceError("temporary")
        return "ok"

    assert fetch_with_retry(call, _config(), sleep=sleeps.append) == "ok"
    assert attempts == 3
    assert sleeps == [1, 2]


@pytest.mark.parametrize("error", [PermanentSourceError("auth"), SourceContractError("shape")])
def test_permanent_or_contract_failure_is_not_retried(error: Exception) -> None:
    attempts = 0

    def call() -> str:
        nonlocal attempts
        attempts += 1
        raise error

    with pytest.raises(type(error)):
        fetch_with_retry(call, _config(), sleep=lambda _: None)
    assert attempts == 1


def test_contract_change_stops_only_that_indicator(tmp_path: Path) -> None:
    indicators = [
        i for i in INDICATORS if i.indicator_id in {"dubai_crude", "usd_krw_rate"}
    ]

    class ChangedShape:
        def get(self, url, params=None, headers=None):
            return "{}"

    outcomes = ingest.collect(
        indicators,
        fetcher=ChangedShape(),
        data_root=tmp_path,
        mode="regular",
        today=date(2026, 9, 20),
        configs={"ecos": _config("ecos")},
    )

    assert len(outcomes) == 2
    assert all(outcome.status == ingest.STATUS_FAILED for outcome in outcomes)
    assert all(outcome.error_class == "SourceContractError" for outcome in outcomes)


def test_regular_window_includes_transform_denominator() -> None:
    yoy = replace(
        next(i for i in INDICATORS if i.transform_rule == "YOY_GROWTH"),
        refresh_months_n=2,
    )
    mom = replace(yoy, transform_rule="MOM_GROWTH")
    direct = replace(yoy, transform_rule="OFFICIAL_AMOUNT")

    assert ingest._window(yoy, "regular", date(2026, 9, 20)) == (
        "2025-07", "2026-09"
    )
    assert ingest._window(mom, "regular", date(2026, 9, 20)) == (
        "2026-06", "2026-09"
    )
    assert ingest._window(direct, "regular", date(2026, 9, 20)) == (
        "2026-07", "2026-09"
    )


def test_customs_initial_load_resumes_after_quota_without_refetching(tmp_path: Path) -> None:
    members = tuple(i for i in INDICATORS if i.primary_source == "customs")
    body = FIXTURE.read_text(encoding="utf-8")

    class QuotaAfterTwo:
        def __init__(self) -> None:
            self.calls = 0

        def get(self, url, params=None, headers=None):
            self.calls += 1
            if self.calls > 2:
                raise QuotaExhaustedError("quota")
            return body

    first = QuotaAfterTwo()
    ingest.collect(
        members, fetcher=first, data_root=tmp_path, mode="initial",
        today=date(2026, 9, 20), configs={"customs": _config()},
    )
    assert first.calls == 3

    class Healthy:
        def __init__(self) -> None:
            self.calls = 0

        def get(self, url, params=None, headers=None):
            self.calls += 1
            return body

    resumed = Healthy()
    outcomes = ingest.collect(
        members, fetcher=resumed, data_root=tmp_path, mode="initial",
        today=date(2026, 9, 20), configs={"customs": _config()},
    )

    assert resumed.calls == 36, "이미 받은 첫 두 연도는 다시 호출하지 않아야 한다"
    assert all(outcome.succeeded for outcome in outcomes)
    assert len(outcomes[0].raw_documents) == 38
