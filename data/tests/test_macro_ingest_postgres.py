"""수집 실행이 DB에 무엇을 남기는지 실제 PostgreSQL 로 본다 (tasks.md T080·T082).

표본을 읽는 테스트는 조각이 이어지는지만 본다. 여기서 보는 것은 **그 결과가 실제로 기록되는가**다
— 원본 이력과 지표별 수집 상태는 008 조회가 읽는 자리라, 쓰이지 않으면 화면이 늘 "실패 없음"으로
보인다.

Docker 가 없으면 건너뛴다. `THORP_REQUIRE_DOCKER=true` 면 건너뛰지 않고 실패한다(conftest).
"""

from __future__ import annotations

from dataclasses import replace
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import text

from data_pipeline.macro import ingest
from data_pipeline.macro.registry import INDICATORS
from data_pipeline.macro.sources.base import SourceObservation, TransientSourceError
from data_pipeline.macro.versioning import (
    initialize_current_publication,
    register_calculation_version,
)

# 표본을 직접 만들지 않고 관측을 바로 돌려주는 대역을 쓴다. 어댑터 파싱은 다른 파일이 본다.
_MONTHS = ("2026-05", "2026-06", "2026-07")


class StubFetcher:
    """모든 요청에 같은 본문을 돌려준다. 원본 저장 경로를 보기 위한 최소 대역."""

    def __init__(self, body: str = '{"ok": true}') -> None:
        self._body = body

    def get(self, url: str, params: dict[str, str] | None = None) -> str:
        return self._body


@pytest.fixture
def dummy_keys(monkeypatch):
    """원천에 닿는 경로를 보려면 키가 있어야 한다. 값은 쓰이지 않는다 — 대역이 받는다.

    키를 넣지 않으면 대부분의 지표가 fetcher 에 닿기도 전에 KEY_NOT_ISSUED 로 갈린다.
    그것도 맞는 동작이지만, 여기서 보려는 것은 원천 실패가 어떻게 기록되는가다.
    """
    for name in ("ECOS_API_KEY", "KOSIS_API_KEY", "DATA_GO_KR_SERVICE_KEY"):
        monkeypatch.setenv(name, "test-key")


@pytest.fixture
def seeded_indicators():
    """전 구간을 짧게 줄인 17개. 실제 시작월을 쓰면 표본 없이 수백 달을 요구한다."""
    return tuple(replace(indicator, series_start="2026-05") for indicator in INDICATORS)


def _prepare_registry(engine, indicators) -> None:
    registry_id = register_calculation_version(
        engine, "calc-ingest", "ingest 통합 테스트", indicators
    )
    with engine.begin() as connection:
        display_id = connection.execute(
            text(
                "INSERT INTO macro_display_version (version_key, change_reason, created_at)"
                " VALUES ('display-ingest', 'ingest 통합 테스트', CURRENT_TIMESTAMP)"
                " RETURNING id"
            )
        ).scalar_one()
    initialize_current_publication(engine, registry_id, display_id)


def _outcome(indicator_id: str, *, values: bool = True) -> ingest.IndicatorOutcome:
    observations = (
        [
            SourceObservation(
                indicator_id=indicator_id, period=month, value=Decimal("101.2")
            )
            for month in _MONTHS
        ]
        if values
        else []
    )
    return ingest.IndicatorOutcome(
        indicator_id=indicator_id,
        source="kosis",
        status=ingest.STATUS_SUCCEEDED if values else ingest.STATUS_FAILED,
        observations=observations,
        error_class=None if values else "TransientSourceError",
    )


def _raw_document(tmp_path: Path, indicator_id: str, body: str) -> ingest.RawDocument:
    from datetime import UTC, datetime

    from data_pipeline.macro import storage

    received_at = datetime.now(UTC)
    path = storage.raw_path(tmp_path, "kosis", indicator_id, received_at, ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return ingest.RawDocument(
        indicator_id=indicator_id,
        source="kosis",
        body=body,
        received_at=received_at,
        path=path,
        sha256=storage.content_hash(body.encode("utf-8")),
    )


def test_raw_revisions_are_written_once_per_body(postgres_engine, tmp_path: Path) -> None:
    """같은 응답을 다시 받으면 이력이 늘지 않는다 (T080, FR-009).

    정기 재조회는 바뀐 것이 없는 달도 함께 받는다. 해시로 거르지 않으면 돌릴 때마다 개정이
    일어난 것처럼 쌓여, 진짜 개정과 구분되지 않는다.
    """
    outcome = _outcome("all_industry_production")
    outcome.raw_documents.append(_raw_document(tmp_path, "all_industry_production", "body-1"))

    first = ingest.record_raw_revisions(postgres_engine, [outcome])
    second = ingest.record_raw_revisions(postgres_engine, [outcome])

    assert first == 1
    assert second == 0, "같은 본문인데 이력이 늘었다"
    with postgres_engine.begin() as connection:
        rows = connection.execute(
            text("SELECT raw_hash, observed_revision FROM macro_raw_revision")
        ).all()
    assert len(rows) == 1
    assert rows[0][1] == 1


def test_changed_body_adds_a_revision(postgres_engine, tmp_path: Path) -> None:
    """본문이 달라지면 새 이력이 쌓인다 — 그것이 개정이다."""
    outcome = _outcome("all_industry_production")
    outcome.raw_documents.append(_raw_document(tmp_path, "all_industry_production", "body-1"))
    ingest.record_raw_revisions(postgres_engine, [outcome])

    revised = _outcome("all_industry_production")
    revised.raw_documents.append(_raw_document(tmp_path, "all_industry_production", "body-2"))
    written = ingest.record_raw_revisions(postgres_engine, [revised])

    assert written == 1
    with postgres_engine.begin() as connection:
        revisions = connection.execute(
            text("SELECT observed_revision FROM macro_raw_revision ORDER BY observed_revision")
        ).scalars().all()
    assert revisions == [1, 2]


def test_run_records_item_status_for_every_indicator(
    postgres_engine, tmp_path: Path, seeded_indicators
) -> None:
    """성공이든 실패든 지표마다 상태가 하나씩 남는다 (T082, FR-011)."""
    _prepare_registry(postgres_engine, seeded_indicators)

    report = ingest.run(
        postgres_engine,
        seeded_indicators,
        fetcher=StubFetcher(),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 7, 15),
    )

    with postgres_engine.begin() as connection:
        rows = connection.execute(
            text(
                "SELECT indicator_id, status FROM macro_collection_item_status"
                " WHERE attempt_id = (SELECT id FROM macro_collection_attempt"
                "                     WHERE attempt_key = :key)"
            ),
            {"key": report.attempt_key},
        ).all()

    assert len(rows) == len(seeded_indicators)
    assert {row[0] for row in rows} == {i.indicator_id for i in seeded_indicators}


def test_failed_indicator_keeps_its_error_class(
    postgres_engine, tmp_path: Path, seeded_indicators, dummy_keys
) -> None:
    """실패 지표의 오류 분류가 그대로 남는다. 로그를 뒤지지 않고 다음 행동을 정할 수 있어야 한다."""
    _prepare_registry(postgres_engine, seeded_indicators)

    class AlwaysFails:
        def get(self, url, params=None):
            raise TransientSourceError("끊김")

    report = ingest.run(
        postgres_engine,
        seeded_indicators,
        fetcher=AlwaysFails(),
        data_root=tmp_path,
        mode="regular",
        today=date(2026, 7, 15),
    )

    assert report.failed, "전부 실패했는데 실패로 안 잡혔다"
    with postgres_engine.begin() as connection:
        statuses = connection.execute(
            text(
                "SELECT DISTINCT status, error_class FROM macro_collection_item_status"
                " WHERE attempt_id = (SELECT id FROM macro_collection_attempt"
                "                     WHERE attempt_key = :key)"
            ),
            {"key": report.attempt_key},
        ).all()

    assert statuses == [(ingest.STATUS_FAILED, "TransientSourceError")]


def test_publication_is_held_but_records_still_land(
    postgres_engine, tmp_path: Path, seeded_indicators, dummy_keys
) -> None:
    """발행이 보류돼도 수집 기록은 남는다 (FR-011).

    보류는 정상적인 상태다. 그때 기록까지 사라지면 다음 실행에서 무엇을 고쳐야 하는지 알 수 없다.
    """
    _prepare_registry(postgres_engine, seeded_indicators)

    class AlwaysFails:
        def get(self, url, params=None):
            raise TransientSourceError("끊김")

    report = ingest.run(
        postgres_engine,
        seeded_indicators,
        fetcher=AlwaysFails(),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 7, 15),
    )

    assert not report.published
    assert report.publication_held is not None
    with postgres_engine.begin() as connection:
        attempts = connection.execute(
            text("SELECT status FROM macro_collection_attempt WHERE attempt_key = :key"),
            {"key": report.attempt_key},
        ).scalars().all()
        items = connection.execute(
            text("SELECT count(*) FROM macro_collection_item_status")
        ).scalar_one()

    assert attempts == ["PARTIAL"]
    assert items == len(seeded_indicators)


def test_run_without_pointer_says_what_to_do(
    postgres_engine, tmp_path: Path, seeded_indicators
) -> None:
    """등록표를 세우지 않고 돌리면 무엇을 먼저 해야 하는지 말한다."""
    with pytest.raises(ValueError, match="현재 포인터가 없다"):
        ingest.run(
            postgres_engine,
            seeded_indicators,
            fetcher=StubFetcher(),
            data_root=tmp_path,
            mode="initial",
            today=date(2026, 7, 15),
        )


def test_registry_bootstrap_lets_ingest_run(postgres_engine, tmp_path: Path, monkeypatch) -> None:
    """등록표 진입점이 세운 상태에서 수집이 돈다 — 둘이 실제로 이어지는지 본다.

    진입점을 만들기 전에는 `ingest.run` 이 "현재 포인터가 없다"로 멈췄다. 등록표를 세우는 자리가
    저장소 어디에도 없었기 때문이다.
    """
    import register_macro_registry as bootstrap

    # str(url) 은 비밀번호를 *** 로 가린다. 그대로 쓰면 인증에 실패한다.
    monkeypatch.setattr(
        bootstrap,
        "database_url_from_env",
        lambda: postgres_engine.url.render_as_string(hide_password=False),
    )

    assert bootstrap.main(["--calculation-key", "calc-boot", "--display-key", "disp-boot"]) == 0

    with postgres_engine.begin() as connection:
        fields = connection.execute(text("SELECT count(*) FROM macro_display_field")).scalar_one()
        names = connection.execute(
            text("SELECT count(*) FROM macro_display_indicator")
        ).scalar_one()
        pointer = connection.execute(
            text("SELECT count(*) FROM macro_current_publication")
        ).scalar_one()

    assert (fields, names, pointer) == (7, 17, 1)

    # 이제 수집이 포인터를 읽고 돈다.
    report = ingest.run(
        postgres_engine,
        tuple(replace(i, series_start="2026-05") for i in INDICATORS),
        fetcher=StubFetcher(),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 7, 15),
    )
    assert report.attempt_key


def test_registry_bootstrap_refuses_to_overwrite(postgres_engine, monkeypatch) -> None:
    """이미 포인터가 있으면 덮지 않는다.

    다른 작업이 세운 버전을 지우면 그 버전을 참조하던 자료가 뜬다.
    """
    import register_macro_registry as bootstrap

    # str(url) 은 비밀번호를 *** 로 가린다. 그대로 쓰면 인증에 실패한다.
    monkeypatch.setattr(
        bootstrap,
        "database_url_from_env",
        lambda: postgres_engine.url.render_as_string(hide_password=False),
    )
    assert bootstrap.main(["--calculation-key", "calc-1", "--display-key", "disp-1"]) == 0

    assert bootstrap.main(["--calculation-key", "calc-2", "--display-key", "disp-2"]) == 1
