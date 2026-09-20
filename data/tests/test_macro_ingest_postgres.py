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

    def get(
        self,
        url: str,
        params: dict[str, str] | None = None,
        headers: dict[str, str] | None = None,
    ) -> str:
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
        def get(self, url, params=None, headers=None):
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
        def get(self, url, params=None, headers=None):
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
            text("SELECT status, hold_reason FROM macro_collection_attempt"
                 " WHERE attempt_key = :key"),
            {"key": report.attempt_key},
        ).all()
        items = connection.execute(
            text("SELECT count(*) FROM macro_collection_item_status")
        ).scalar_one()

    # 아무것도 발행되지 않았으므로 성공이 아니고, 왜 막혔는지가 행에 함께 남는다 (T083).
    assert len(attempts) == 1
    status, hold_reason = attempts[0]
    assert status == "HELD"
    assert hold_reason, "보류했는데 사유가 DB 에 없다. 로그는 지나간다"
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


def _ecos_shaped(indicator):
    """모든 지표를 같은 표본으로 받을 수 있게 원천 정의만 바꾼다.

    발행 성공 경로를 보려면 17개가 **모두** 유효 변환값을 가져야 하는데, 주택가격 연결 사슬처럼
    표본이 갖춰지지 않은 계열이 있다. 여기서 보려는 것은 값의 정확성이 아니라
    **발행까지 이어지는가** 이므로, 원천 정의를 하나로 맞춰 17개가 다 차는 상황을 만든다.
    값 검증은 어댑터·변환 테스트가 한다.
    """
    return replace(
        indicator,
        primary_source="ecos",
        table_code="902Y003",
        item_code="010102",
        classification=(),
        org_id=None,
        source_cycle="M",
        link_chain=(),
        transform_rule="MONTH_AVERAGE",
        raw_unit="달러/배럴",
        transformed_unit="달러/배럴",
        series_start="2024-01",
    )


def test_run_publishes_when_all_seventeen_are_filled(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """17개가 다 차면 자료 버전이 실제로 발행된다.

    지금까지 통합 테스트는 늘 보류로 끝났다. 보류만 검증하면 발행 경로는 한 번도 지나지 않는다.
    """
    from pathlib import Path as _Path

    body = (
        _Path(__file__).parent / "fixtures" / "macro" / "ecos" / "dubai_crude_monthly.json"
    ).read_text(encoding="utf-8")
    indicators = tuple(_ecos_shaped(indicator) for indicator in INDICATORS)
    _prepare_registry(postgres_engine, indicators)

    report = ingest.run(
        postgres_engine,
        indicators,
        fetcher=StubFetcher(body),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 8, 15),  # 표본 마지막 달
    )

    assert report.publication_held is None, report.publication_held
    assert report.published, "17개가 다 찼는데 발행되지 않았다"
    assert report.raw_revisions_written > 0

    with postgres_engine.begin() as connection:
        published = connection.execute(
            text(
                "SELECT status, complete_indicator_count FROM macro_dataset_version"
                " WHERE id = :id"
            ),
            {"id": report.dataset_version_id},
        ).one()
        pointer = connection.execute(
            text("SELECT dataset_version_id FROM macro_current_publication")
        ).scalar_one()

    assert published == ("PUBLISHED", 17)
    assert pointer == report.dataset_version_id, "발행했는데 현재 포인터가 안 움직였다"


def test_quota_stops_collection_and_keeps_what_arrived(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """한도에 닿으면 그 시점에서 멈추고, 받은 구간은 남긴다 (FR-011).

    관세청은 전 구간이 37회 넘는 분할 호출이라 중간에 한도에 걸리는 일이 실제로 일어난다. 그때
    남은 구간을 계속 부르면 한도를 더 깎고 차단으로 이어질 수 있다. 받은 만큼은 버리지 않는다 —
    다음 실행이 그만큼 덜 불러도 되기 때문이다.
    """
    from pathlib import Path as _Path

    from data_pipeline.macro.sources.base import QuotaExhaustedError

    body = (
        _Path(__file__).parent / "fixtures" / "macro" / "customs" / "newtrade_2026.xml"
    ).read_text(encoding="utf-8")
    allowed = 3

    class QuotaAfter:
        def __init__(self) -> None:
            self.calls = 0

        def get(self, url, params=None, headers=None):
            self.calls += 1
            if self.calls > allowed:
                raise QuotaExhaustedError("한도 도달")
            return body

    fetcher = QuotaAfter()
    members = tuple(
        indicator for indicator in INDICATORS if indicator.primary_source == "customs"
    )

    outcomes = ingest.collect(
        members,
        fetcher=fetcher,
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 9, 20),
    )

    assert fetcher.calls == allowed + 1, (
        f"한도 뒤에도 계속 불렀다: {fetcher.calls}회"
    )
    for outcome in outcomes:
        assert outcome.status == ingest.STATUS_QUOTA
        assert outcome.observations, "한도 전에 받은 구간까지 버렸다"


def test_quota_is_recorded_and_publication_is_held(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """한도 도달이 수집 기록에 남고 발행은 보류된다."""
    from data_pipeline.macro.sources.base import QuotaExhaustedError

    indicators = tuple(replace(i, series_start="2026-05") for i in INDICATORS)
    _prepare_registry(postgres_engine, indicators)

    class AlwaysQuota:
        def get(self, url, params=None, headers=None):
            raise QuotaExhaustedError("한도 도달")

    report = ingest.run(
        postgres_engine,
        indicators,
        fetcher=AlwaysQuota(),
        data_root=tmp_path,
        mode="regular",
        today=date(2026, 7, 15),
    )

    assert not report.published
    with postgres_engine.begin() as connection:
        rows = connection.execute(
            text(
                "SELECT DISTINCT status, quota_exhausted FROM macro_collection_item_status"
                " WHERE attempt_id = (SELECT id FROM macro_collection_attempt"
                "                     WHERE attempt_key = :key)"
            ),
            {"key": report.attempt_key},
        ).all()

    assert rows == [(ingest.STATUS_QUOTA, True)]


def test_partial_run_records_without_attempting_publication(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """지표 하나만 받는 진단 실행이 기록을 남기고 끝난다.

    운영에서 `--indicator` 로 경로를 확인하다 터진 자리다. 발행 판정기는 17개가 함께여야
    한다며 **수집 시도 행을 만들기 전에** 던지고, 그러면 상태를 붙일 자리가 없어 실행 전체가
    죽으면서 받은 것까지 사라졌다.
    """
    indicators = tuple(replace(i, series_start="2026-05") for i in INDICATORS)
    _prepare_registry(postgres_engine, indicators)
    one = (indicators[0],)

    report = ingest.run(
        postgres_engine,
        one,
        fetcher=StubFetcher(),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 7, 15),
    )

    assert not report.published
    assert "부분 수집" in (report.publication_held or "")
    with postgres_engine.begin() as connection:
        rows = connection.execute(
            text(
                "SELECT indicator_id FROM macro_collection_item_status"
                " WHERE attempt_id = (SELECT id FROM macro_collection_attempt"
                "                     WHERE attempt_key = :key)"
            ),
            {"key": report.attempt_key},
        ).scalars().all()

    assert rows == [one[0].indicator_id], "부분 실행인데 수집 기록이 남지 않았다"


def test_item_status_creates_the_attempt_when_publication_never_started(
    postgres_engine, tmp_path: Path
) -> None:
    """발행이 시도 행을 만들기 전에 끝나도 기록은 남는다."""
    outcome = _outcome("all_industry_production", values=False)

    ingest.record_item_status(postgres_engine, "orphan-attempt", [outcome], mode="INITIAL")

    with postgres_engine.begin() as connection:
        attempt = connection.execute(
            text(
                "SELECT mode, status FROM macro_collection_attempt"
                " WHERE attempt_key = 'orphan-attempt'"
            )
        ).one()
        items = connection.execute(
            text("SELECT indicator_id, status FROM macro_collection_item_status")
        ).all()

    assert attempt == ("INITIAL", "PARTIAL")
    assert items == [("all_industry_production", ingest.STATUS_FAILED)]


# ---------------------------------------------------------------------------
# 걸러낸 행 수 (T075, FR-017a)
# ---------------------------------------------------------------------------
def test_excluded_total_rows_land_in_the_collection_record(postgres_engine) -> None:
    """관세청 합계 행을 몇 개 뺐는지가 수집 기록에 남는다.

    남지 않으면 "9개월치를 받았다"와 "10행을 받아 1행을 뺐다"가 같은 모양이 된다 — 원천이
    합계 행의 모양을 바꿔 걸러지지 않게 됐을 때 그 사실이 드러나지 않는다.
    """
    exports = _outcome("exports")
    exports.source = "customs"
    exports.excluded_rows = 37
    imports = _outcome("imports")
    imports.source = "customs"

    ingest.record_item_status(postgres_engine, "attempt-excluded", [exports, imports])

    with postgres_engine.begin() as connection:
        rows = dict(
            connection.execute(
                text(
                    "SELECT indicator_id, excluded_row_count"
                    " FROM macro_collection_item_status"
                    " WHERE attempt_id = (SELECT id FROM macro_collection_attempt"
                    "                     WHERE attempt_key = 'attempt-excluded')"
                )
            ).all()
        )
    assert rows["exports"] == 37
    # 아무것도 걸러내지 않은 지표는 0 이다. NULL 로 두면 "세지 않았다"와 구분되지 않는다.
    assert rows["imports"] == 0


# ---------------------------------------------------------------------------
# 전 구간 적재 완료 판정 (T077, FR-011b(3))
# ---------------------------------------------------------------------------
def test_initial_load_short_of_the_expected_months_is_not_published(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """기대 대상월 수를 못 채우면 발행하지 않는다.

    짧은 계열로 낸 스냅샷은 응답도 값도 멀쩡하다 — 과거가 잘려 있다는 사실이 화면 어디에도
    드러나지 않으므로, 발행 전에 막지 않으면 아무도 알아채지 못한다.
    """
    from pathlib import Path as _Path

    body = (
        _Path(__file__).parent / "fixtures" / "macro" / "ecos" / "dubai_crude_monthly.json"
    ).read_text(encoding="utf-8")
    # 표본은 2024-01 부터인데 등록표는 2000-01 부터라고 말한다. 스무 해가 비는 상황이다.
    indicators = tuple(
        replace(_ecos_shaped(indicator), series_start="2000-01") for indicator in INDICATORS
    )
    _prepare_registry(postgres_engine, indicators)

    report = ingest.run(
        postgres_engine,
        indicators,
        fetcher=StubFetcher(body),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 8, 15),
    )

    assert report.publication_held is not None
    assert "기대에 못 미쳐" in report.publication_held
    assert not report.published
    # 발행은 막았지만 무엇을 받았는지는 남는다 (FR-011).
    with postgres_engine.begin() as connection:
        recorded = connection.execute(
            text(
                "SELECT count(*) FROM macro_collection_item_status"
                " WHERE attempt_id = (SELECT id FROM macro_collection_attempt"
                "                     WHERE attempt_key = :key)"
            ),
            {"key": report.attempt_key},
        ).scalar_one()
    assert recorded == len(indicators)


def test_regular_mode_is_not_judged_by_the_full_range(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """정기 수집은 최근 N개월만 받으므로 전 구간 판정을 걸지 않는다.

    걸면 정기 수집이 매번 미달로 보류되고, 그 보류가 쌓이면 진짜 미달을 구분하지 못한다.
    """
    from pathlib import Path as _Path

    body = (
        _Path(__file__).parent / "fixtures" / "macro" / "ecos" / "dubai_crude_monthly.json"
    ).read_text(encoding="utf-8")
    indicators = tuple(
        replace(_ecos_shaped(indicator), series_start="2000-01") for indicator in INDICATORS
    )
    _prepare_registry(postgres_engine, indicators)

    report = ingest.run(
        postgres_engine,
        indicators,
        fetcher=StubFetcher(body),
        data_root=tmp_path,
        mode="regular",
        today=date(2026, 8, 15),
    )

    assert report.publication_held is None or "기대에 못 미쳐" not in report.publication_held


def test_a_held_load_does_not_look_like_a_successful_one(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """지표가 다 성공해도 발행을 막았으면 시도 행이 성공으로 남지 않는다 (T083).

    적재 미달로 막은 실행은 지표 열일곱이 모두 성공한 것이 사실이다. 그 사실만 적으면 DB 를
    읽는 사람은 아무것도 발행되지 않았다는 것을 알 수 없고, 사유는 로그와 함께 지나간다.
    """
    from pathlib import Path as _Path

    body = (
        _Path(__file__).parent / "fixtures" / "macro" / "ecos" / "dubai_crude_monthly.json"
    ).read_text(encoding="utf-8")
    indicators = tuple(
        replace(_ecos_shaped(indicator), series_start="2000-01") for indicator in INDICATORS
    )
    _prepare_registry(postgres_engine, indicators)

    report = ingest.run(
        postgres_engine,
        indicators,
        fetcher=StubFetcher(body),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 8, 15),
    )

    with postgres_engine.begin() as connection:
        status, hold_reason = connection.execute(
            text("SELECT status, hold_reason FROM macro_collection_attempt"
                 " WHERE attempt_key = :key"),
            {"key": report.attempt_key},
        ).one()
        item_statuses = set(
            connection.execute(
                text("SELECT status FROM macro_collection_item_status"
                     " WHERE attempt_id = (SELECT id FROM macro_collection_attempt"
                     "                     WHERE attempt_key = :key)"),
                {"key": report.attempt_key},
            ).scalars().all()
        )

    assert status == "HELD"
    assert "기대에 못 미쳐" in hold_reason
    # 지표별로는 성공이 맞다. 둘을 함께 읽어야 무슨 일이 있었는지가 드러난다.
    assert item_statuses == {ingest.STATUS_SUCCEEDED}


def test_a_diagnostic_partial_run_is_held_with_its_reason(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """`--indicator` 진단 실행도 보류로 남는다. 발행하지 않았다는 사실은 같다."""
    indicators = tuple(replace(i, series_start="2026-05") for i in INDICATORS)
    _prepare_registry(postgres_engine, indicators)

    report = ingest.run(
        postgres_engine,
        (indicators[0],),
        fetcher=StubFetcher(),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 7, 15),
    )

    with postgres_engine.begin() as connection:
        status, hold_reason = connection.execute(
            text("SELECT status, hold_reason FROM macro_collection_attempt"
                 " WHERE attempt_key = :key"),
            {"key": report.attempt_key},
        ).one()

    assert status == "HELD"
    assert "부분 수집" in hold_reason


def test_a_published_run_keeps_its_successful_attempt(
    postgres_engine, tmp_path: Path, dummy_keys
) -> None:
    """발행에 성공한 실행은 건드리지 않는다 — 보류 기록이 성공까지 덮으면 안 된다."""
    from pathlib import Path as _Path

    body = (
        _Path(__file__).parent / "fixtures" / "macro" / "ecos" / "dubai_crude_monthly.json"
    ).read_text(encoding="utf-8")
    indicators = tuple(_ecos_shaped(indicator) for indicator in INDICATORS)
    _prepare_registry(postgres_engine, indicators)

    report = ingest.run(
        postgres_engine,
        indicators,
        fetcher=StubFetcher(body),
        data_root=tmp_path,
        mode="initial",
        today=date(2026, 8, 15),
    )

    assert report.published, report.publication_held
    with postgres_engine.begin() as connection:
        status, hold_reason = connection.execute(
            text("SELECT status, hold_reason FROM macro_collection_attempt"
                 " WHERE attempt_key = :key"),
            {"key": report.attempt_key},
        ).one()
    assert status == "SUCCEEDED"
    assert hold_reason is None
