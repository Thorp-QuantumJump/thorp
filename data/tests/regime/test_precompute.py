"""사전 계산 배치의 적재 규칙 (spec FR-029·FR-030·FR-034·FR-039, tasks.md T074~T079).

여기서 확인하는 것은 **행이 어떻게 쌓이는가**다. 값이 맞는지는 `test_distance.py`·
`test_candidates.py`가 이미 고정했고, 이 파일은 같은 값을 두 번 넣으면 어떻게 되는지, 자료가
바뀌면 어디까지 다시 도는지, 중간에 실패하면 무엇이 남는지를 본다.

실제 PostgreSQL 없이 돈다 — 적재 순서·멱등·보관 정리는 파이썬 계층의 규칙이라 같은 열로 만든
인메모리 SQLite에서 확인할 수 있다. DB 제약·트리거는 마이그레이션(T086)이 원본이고
`backend`의 통합 시험이 컨테이너로 검증한다.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from datetime import date, timedelta
from pathlib import Path

import pytest
from conftest import make_registry, make_series, write_phase_config
from sqlalchemy import select

import precompute_regime
from data_pipeline.regime.load import (
    RegimeRepository,
    VersionNotBumpedError,
    baseline_record_for,
    regime_search_case_table,
    regime_search_log_table,
    regime_search_snapshot_table,
)
from data_pipeline.regime.macro.types import (
    BaselineMonths,
    IndicatorRegistry,
    MonthlyValue,
    Publication,
    format_month,
)
from data_pipeline.regime.snapshot import (
    COMPOSITE_SCOPE,
    MODEL_VERSION,
    STATUS_NOT_COMPUTABLE,
    SearchScope,
    SnapshotBuilder,
    Versions,
)

FIXTURE_PREFIX = "FIXTURE-"


def wave(count: int, *, start: float = 10.0, step: float = 0.37) -> list[float]:
    """되풀이되지 않는 값 묶음. 상수도 완전 주기도 아니라 척도·순위가 실제로 갈린다."""
    return [
        round(start + ((index * step) % 7) - ((index % 13) * 0.11), 4)
        for index in range(count)
    ]


class StubMacroSource:
    """단위 시험용 지표 소스 — 값을 바꾸고 `changed_since()`를 정할 수 있다.

    운영 픽스처(`macro/fixture/`)를 쓰지 않는 이유는 **모양을 시험이 정해야** 하기 때문이다.
    "M월 값이 바뀌면 t ≥ M만 다시 돈다"를 확인하려면 값을 바꿀 수 있어야 하는데, 운영 픽스처는
    008이 오면 사라지는 고정 파일이다(계약 §4.4).
    """

    def __init__(
        self,
        registry: IndicatorRegistry,
        series: Mapping[str, Mapping[date, MonthlyValue]],
        *,
        data_version: str = f"{FIXTURE_PREFIX}STUB-1",
        changed: date | None = None,
    ) -> None:
        self._registry = registry
        self._series = {key: dict(value) for key, value in series.items()}
        self._data_version = data_version
        self._changed = changed

    def registry(self) -> IndicatorRegistry:
        return self._registry

    def monthly_series(self, indicator_id: str) -> dict[date, MonthlyValue]:
        return dict(self._series[indicator_id])

    def raw_series(self, indicator_id: str) -> dict[date, float]:
        return {month: value.raw for month, value in self._series[indicator_id].items()}

    def baseline_months(self) -> BaselineMonths:
        by_field: dict[str, date] = {}
        for field in self._registry.fields:
            month = self._common(
                entry.id for entry in self._registry.indicators_of(field.id)
            )
            if month is not None:
                by_field[field.id] = month
        return BaselineMonths(
            by_field=by_field,
            composite=self._common(entry.id for entry in self._registry.indicators),
        )

    def _common(self, indicator_ids: Iterable[str]) -> date | None:
        shared: set[date] | None = None
        for indicator_id in indicator_ids:
            months = set(self._series[indicator_id])
            shared = months if shared is None else shared & months
        return max(shared) if shared else None

    def publication(self, indicator_id: str, month: date) -> Publication | None:
        if month not in self._series[indicator_id]:
            return None
        published = date(month.year + month.month // 12, month.month % 12 + 1, 1)
        return Publication(
            indicator_id=indicator_id,
            month=month,
            published_on=published + timedelta(days=14),
            revised=False,
        )

    def data_version(self) -> str:
        return self._data_version

    def changed_since(self, data_version: str) -> date | None:
        return self._changed

    def is_sample_data(self) -> bool:
        return True

    # -- 시험이 쓰는 조작 ---------------------------------------------------------------

    def revise(self, indicator_id: str, month: date, value: float, *, version: str) -> None:
        """그 달의 값을 고치고 자료 버전을 올린다 — 008의 개정을 흉내 낸다."""
        self._series[indicator_id][month] = MonthlyValue(month=month, value=value, raw=value)
        self._data_version = version
        self._changed = month


def stub_source(months: int = 200, *, layout: Mapping[str, Sequence[str]] | None = None):
    registry = make_registry(dict(layout or {"A": ["A1", "A2"], "B": ["B1"]}))
    series = {
        entry.id: make_series((1994, 1), wave(months, start=10.0 + index))
        for index, entry in enumerate(registry.indicators)
    }
    return StubMacroSource(registry, series)


@pytest.fixture
def repository() -> RegimeRepository:
    return RegimeRepository.rehearsal()


def run_batch(
    monkeypatch: pytest.MonkeyPatch,
    repository: RegimeRepository,
    source: object,
    argv: Sequence[str],
    tmp_path: Path,
    *,
    phase_version: str = "REGIME-V3-TEST",
    one_block: bool = False,
) -> int:
    """진입점을 실제로 돌린다 — 적재까지 포함한 경로가 배치의 전부다."""
    monkeypatch.setattr(precompute_regime, "create_source", lambda name: source)
    monkeypatch.setattr(
        precompute_regime.RegimeRepository,
        "from_env",
        classmethod(lambda cls, path=None: repository),
    )
    monkeypatch.setattr(precompute_regime, "PROCESSED_ROOT", tmp_path)
    monkeypatch.setattr(
        precompute_regime,
        "PHASE_CONFIG",
        write_phase_config(tmp_path, source.registry(), version=phase_version, one_block=one_block),
    )
    return precompute_regime.main(["--source", "macro", *argv])


def snapshot_rows(repository: RegimeRepository) -> list[dict[str, object]]:
    table = regime_search_snapshot_table
    with repository.engine.connect() as conn:
        return [dict(row._mapping) for row in conn.execute(select(table)).all()]


def revision_of(rows: Sequence[Mapping[str, object]], scope_id: str, month: date) -> int | None:
    for row in rows:
        if (row["field_id"] or COMPOSITE_SCOPE) == scope_id and row["baseline_month"] == month:
            return int(row["revision"])
    return None


class TestIdempotence:
    """T074 — 배치를 두 번 돌려도 행 수가 늘지 않는다 (SC-002)."""

    def test_두_번_돌려도_행이_늘지_않는다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        source = stub_source()
        first = run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path)
        assert first == 0
        snapshots, cases = repository.count_snapshots(), repository.count_cases()
        assert snapshots > 0

        second = run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path)
        assert second == 0
        assert repository.count_snapshots() == snapshots
        assert repository.count_cases() == cases

    def test_범위를_넓혀_다시_돌려도_이미_있던_기준월은_늘지_않는다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path) == 0
        narrow = {
            (row["field_id"], row["baseline_month"]): row["revision"]
            for row in snapshot_rows(repository)
        }
        assert run_batch(monkeypatch, repository, source, ["--since", "2006-01"], tmp_path) == 0
        widened = {
            (row["field_id"], row["baseline_month"]): row["revision"]
            for row in snapshot_rows(repository)
        }
        assert len(widened) > len(narrow)  # 넓힌 만큼 새 기준월이 생긴다
        for key, revision in narrow.items():
            assert widened[key] == revision  # 이미 있던 것은 그대로다

    def test_같은_기준월을_다시_넣으면_revision이_오르지_않는다(
        self, repository: RegimeRepository
    ) -> None:
        source = stub_source()
        builder = SnapshotBuilder(
            source.registry(),
            {entry.id: source.monthly_series(entry.id) for entry in source.registry().indicators},
            source.publication,
            Versions(data=source.data_version(), registry=source.registry().version),
        )
        scope = SearchScope.field("A")
        month = builder.observable_months(scope)[150]
        first = repository.save(builder.build(scope, month))
        second = repository.save(builder.build(scope, month))
        assert first.inserted is True
        assert second.inserted is False
        assert second.revision == first.revision == 1

    def test_버전을_올리지_않고_내용이_바뀌면_사유를_말하고_멈춘다(
        self, repository: RegimeRepository
    ) -> None:
        """식별 키는 한 내용만 담는다.

        코드·설정만 바꿔 같은 키에 다른 결과가 나오면 DB 오류가 아니라 '모델 버전을 올린다'로
        알린다(data-model §6). 이전 revision은 그대로 남는다.
        """
        source = stub_source()
        series = {
            entry.id: dict(source.monthly_series(entry.id))
            for entry in source.registry().indicators
        }
        versions = Versions(data=source.data_version(), registry=source.registry().version)
        scope = SearchScope.field("A")
        builder = SnapshotBuilder(source.registry(), series, source.publication, versions)
        month = builder.observable_months(scope)[150]
        assert repository.save(builder.build(scope, month)).inserted is True

        # 자료 버전은 그대로 두고 값만 바꾼다 — 계산 규칙을 바꾼 것과 같은 효과다.
        changed = {key: dict(values) for key, values in series.items()}
        for key, value in list(changed["A1"].items()):
            changed["A1"][key] = MonthlyValue(month=key, value=value.value * 3, raw=0.0)
        other = SnapshotBuilder(source.registry(), changed, source.publication, versions)
        with pytest.raises(VersionNotBumpedError):
            repository.save(other.build(scope, month))
        assert repository.latest_revision(scope, month)[0] == 1


class TestWatermarkScope:
    """T075 — M월 값을 바꾸면 `t ≥ M`만 다시 돌고, 내용이 달라진 것만 revision이 오른다."""

    def test_t가_M_이상인_것만_다시_돌고_바뀐_것만_새_revision이다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path) == 0
        before = snapshot_rows(repository)
        assert revision_of(before, "A", date(2010, 1, 1)) == 1

        changed = date(2010, 3, 1)
        source.revise("A1", changed, 42.0, version=f"{FIXTURE_PREFIX}STUB-2")
        assert run_batch(monkeypatch, repository, source, [], tmp_path) == 0
        after = snapshot_rows(repository)

        # t < M — 손대지 않았다. M월 값은 그 달들의 어느 경로에도 들어가지 않는다.
        for month in (date(2010, 1, 1), date(2010, 2, 1)):
            assert revision_of(after, "A", month) == 1
            assert revision_of(after, "B", month) == 1

        # t ≥ M — A는 값이 달라졌으니 새 revision, B는 내용이 같으니 그대로다.
        assert revision_of(after, "A", changed) == 2
        assert revision_of(after, "B", changed) == 1

    def test_모델_버전이_바뀐_범위만_전_구간을_다시_돈다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        """자료가 최근 달만 바뀌어도 종합의 모델 버전이 바뀌면 종합은 과거 기준월까지 새로 돈다.

        안 그러면 과거 기준월의 종합은 옛 모델 결과가 최신인 채로 남는다(data-model §6).
        """
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2008-01"], tmp_path) == 0
        changed = date(2009, 6, 1)
        source.revise("A1", changed, 42.0, version=f"{FIXTURE_PREFIX}STUB-2")
        second = run_batch(
            monkeypatch,
            repository,
            source,
            [],
            tmp_path,
            phase_version="REGIME-V3-NEXT",
            one_block=True,
        )
        assert second == 0
        rows = snapshot_rows(repository)
        old = date(2008, 6, 1)
        composite_models = {
            row["model_version"]
            for row in rows
            if row["mode"] == "COMPOSITE" and row["baseline_month"] == old
        }
        assert "REGIME-V3-NEXT" in composite_models
        # 분야는 모델 버전이 그대로라 M 이전을 다시 돌지 않는다.
        assert revision_of(rows, "A", old) == 1

    def test_내용이_같으면_자료_버전이_올라도_행이_늘지_않는다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        """같은 결과에 버전 이름표만 다른 사본이 쌓이면 재현이 흐려진다 (FR-030)."""
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2008-01"], tmp_path) == 0
        count = repository.count_snapshots()
        source._data_version = f"{FIXTURE_PREFIX}STUB-9"  # noqa: SLF001 - 개정 없는 버전 상승
        assert run_batch(monkeypatch, repository, source, ["--since", "2008-01"], tmp_path) == 0
        assert repository.count_snapshots() == count

    def test_보조_설명이_바뀌면_같은_순위여도_다른_결과다(
        self, repository: RegimeRepository
    ) -> None:
        """`content_hash`에 시대별 보조 설명이 들어간다 (T065, FR-017·FR-030)."""
        source = stub_source()
        builder = SnapshotBuilder(
            source.registry(),
            {entry.id: source.monthly_series(entry.id) for entry in source.registry().indicators},
            source.publication,
            Versions(data=source.data_version(), registry=source.registry().version),
        )
        scope = SearchScope.field("A")
        month = builder.observable_months(scope)[180]
        snapshot = builder.build(scope, month)
        body = snapshot.content()
        with_era = [case for case in body["cases"] if case["eraPosition"]]
        assert with_era, "보조 설명이 있는 사례가 있어야 이 시험이 성립한다"

        from data_pipeline.regime.snapshot import content_hash

        original = content_hash(body)
        first = next(iter(with_era[0]["eraPosition"]))
        with_era[0]["eraPosition"][first]["sentence"] += " (다른 문장)"
        assert content_hash(body) != original

    def test_계산_시각은_해시에_들어가지_않는다(self, repository: RegimeRepository) -> None:
        """넣으면 같은 결과가 매번 새 revision이 되어 SC-002가 깨진다."""
        source = stub_source()
        builder = SnapshotBuilder(
            source.registry(),
            {entry.id: source.monthly_series(entry.id) for entry in source.registry().indicators},
            source.publication,
            Versions(data=source.data_version(), registry=source.registry().version),
        )
        scope = SearchScope.field("A")
        month = builder.observable_months(scope)[150]
        body = builder.build(scope, month).content()
        assert "computedAt" not in body
        assert all(key not in body for key in ("versions", "dataVersion", "revision"))


class TestEraPositionReuse:
    """T076 — 같은 후보월이면 기준월이 달라도 같은 보조 문장이다 (SC-011)."""

    def test_기준월이_달라도_같은_후보월의_보조_설명은_같다(self) -> None:
        """보조 위치는 후보월 c의 `c-122 ~ c-3`에서만 나온다 — t가 들어갈 자리가 없다.

        질의 쪽 z는 정반대로 t마다 달라진다(research R3). 둘을 같은 성질로 다루면 사례 상세에
        서로 다른 기준의 값이 나란히 놓이고, 화면에서는 구분되지 않는다.
        """
        source = stub_source()
        registry = source.registry()
        series = {entry.id: source.monthly_series(entry.id) for entry in registry.indicators}
        versions = Versions(data=source.data_version(), registry=registry.version)
        scope = SearchScope.field("A")

        first = SnapshotBuilder(registry, series, source.publication, versions)
        months = first.observable_months(scope)
        stored = first.build(scope, months[180])
        case = next(item for item in stored.cases if item.era_position)

        # 캐시까지 새로 만든 두 번째 배치 — 다른 기준월로 돌려도 같은 후보월의 답이 같아야 한다.
        second = SnapshotBuilder(registry, series, source.publication, versions)
        second.build(scope, months[190])
        positions = second.era_cache.positions_for(
            second.indicators_of(scope), case.end_month
        )
        assert set(positions) == set(case.era_position)
        for indicator_id, body in case.era_position.items():
            position = positions[indicator_id]
            assert position.sentence == body["sentence"]
            assert position.method == body["method"]
            assert position.months == body["months"]

    def test_설명할_수_없는_지표는_키가_없고_0으로_채우지_않는다(self) -> None:
        source = stub_source()
        builder = SnapshotBuilder(
            source.registry(),
            {entry.id: source.monthly_series(entry.id) for entry in source.registry().indicators},
            source.publication,
            Versions(data=source.data_version(), registry=source.registry().version),
        )
        scope = SearchScope.field("A")
        months = builder.observable_months(scope)
        snapshot = builder.build(scope, months[80])
        limited = [case for case in snapshot.cases if case.era_position is None]
        assert limited, "이력이 모자란 후보가 있어야 이 경로가 시험된다"
        for case in snapshot.cases:
            if case.era_position is None:
                continue
            for body in case.era_position.values():
                assert body["sentence"]
                assert body["months"] > 0
                assert "z" not in body and "percentile" not in body


class TestCompositePropagation:
    """T078 — 계산 불가가 분야 → 종합으로 전파되고, 반대 방향도 있다 (spec FR-039)."""

    def test_한_분야가_계산_불가면_종합도_계산_불가다(self) -> None:
        """한 분야의 지표가 그 달에 비면 그 분야도, 종합도 계산할 수 없다.

        모양을 여기서 만든다. 예전에는 임시 대역의 실제 자료에서 그런 달을 골라 썼는데,
        그러면 대역이 사라질 때 이 경로가 함께 사라진다(계약 §4.4).
        """
        registry = make_registry({"A": ["A1"], "B": ["B1"]})
        months = 160
        source = StubMacroSource(
            registry,
            {
                "A1": make_series((1994, 1), wave(months)),
                # 척도 구간(최소 60개월)을 세울 만큼이 없다. 한 달만 비면 가장 가까운
                # 달로 물러나므로, 물러날 곳조차 없는 모양이어야 계산 불가가 된다.
                "B1": make_series((1994, 1), [None] * (months - 5) + wave(5)),
            },
        )
        # A 는 끝까지 차 있고 B 는 마지막 달이 비었다. 그 마지막 달이 시험할 기준월이다.
        baseline_month = max(source.monthly_series("A1"))
        builder = SnapshotBuilder(
            registry,
            {entry.id: source.monthly_series(entry.id) for entry in registry.indicators},
            source.publication,
            Versions(data=source.data_version(), registry=registry.version),
        )
        broken = builder.build(SearchScope.field("B"), baseline_month)
        composite = builder.build(SearchScope.composite(), baseline_month)
        assert broken.status.status == STATUS_NOT_COMPUTABLE
        assert composite.status.status == STATUS_NOT_COMPUTABLE
        # 사유는 분야가 낸 것을 그대로 쓴다 — 새 어휘를 만들지 않는다.
        assert composite.status.reason == broken.status.reason
        assert composite.cases == ()

    def test_모든_분야가_정상인데_종합만_계산_불가일_수_있다(self) -> None:
        """반대 방향 — T_all 자체가 없는 경우다 (FR-039 ②).

        지표 교체로 두 계열이 겹치는 달이 하나도 없으면 종합 기준월이 서지 않는다. 그때 분야
        검색은 **각자의 T_g에서 그대로 제공**하고, 종합만 계산 불가로 남는다. 종합이 안 된다는
        이유로 분야를 끄면 시작 연도가 다른 지표 하나가 나머지 검색을 전부 막는다.
        """
        registry = make_registry({"A": ["A1"], "B": ["B1"]})
        series = {
            "A1": make_series((1994, 1), wave(160)),
            "B1": make_series((2007, 5), wave(160, start=12.0)),
        }
        source = StubMacroSource(registry, series)
        assert source.baseline_months().composite is None

        builder = SnapshotBuilder(
            registry,
            {key: source.monthly_series(key) for key in series},
            source.publication,
            Versions(data=source.data_version(), registry=registry.version),
        )
        for field_id, latest in source.baseline_months().by_field.items():
            snapshot = builder.build(SearchScope.field(field_id), latest)
            assert snapshot.status.status != STATUS_NOT_COMPUTABLE, field_id

        composite_scope = SearchScope.composite()
        horizon = builder.observable_months(composite_scope)[-1]
        composite = builder.build(composite_scope, horizon)
        assert composite.status.status == STATUS_NOT_COMPUTABLE
        assert composite.cases == ()


class TestSnapshotKey:
    """T079 — 식별 키에 검색 경로와 기준월의 근거가 들어가지 않는다 (spec FR-029)."""

    def test_키는_모드_범위_기준월_버전_셋뿐이다(self) -> None:
        source = stub_source()
        versions = Versions(data=source.data_version(), registry=source.registry().version)
        key = precompute_regime.SearchScope.field("A")
        from data_pipeline.regime.snapshot import snapshot_key

        value = snapshot_key(key, date(2010, 6, 1), versions)
        assert value.split(":") == [
            "FIELD",
            "A",
            "2010-06-01",
            versions.data,
            versions.registry,
            MODEL_VERSION,
        ]
        for absent in ("MARKET_FLOW", "STRATEGY_RESULT", "T_G", "T_ALL", "USER_SPECIFIED"):
            assert absent not in value

    def test_종합_키의_범위는_ALL이다(self) -> None:
        from data_pipeline.regime.snapshot import snapshot_key

        versions = Versions(data="D", registry="R")
        value = snapshot_key(SearchScope.composite(), date(2010, 6, 1), versions)
        assert value.split(":")[1] == COMPOSITE_SCOPE

    def test_T_g로_얻은_달과_지정한_달이_같은_결과다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        """같은 월이면 근거가 달라도 결과가 하나여야 한다 (Clarifications 2026-09-16)."""
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path) == 0
        count = repository.count_snapshots()
        horizon = source.baseline_months().by_field["A"]
        assert (
            run_batch(
                monkeypatch,
                repository,
                source,
                ["--baseline-month", format_month(horizon)],
                tmp_path,
            )
            == 0
        )
        assert repository.count_snapshots() == count
        assert revision_of(snapshot_rows(repository), "A", horizon) == 1


class TestRetention:
    """T069 — 보관 정리는 최신 revision과 기록이 가리키는 것만 남긴다 (FR-029, data-model §7)."""

    def test_최신과_기록이_가리키는_것만_남는다(self, repository: RegimeRepository) -> None:
        source = stub_source()
        registry = source.registry()
        series = {entry.id: source.monthly_series(entry.id) for entry in registry.indicators}
        scope = SearchScope.field("A")
        month = SnapshotBuilder(
            registry, series, source.publication, Versions(data="D1", registry=registry.version)
        ).observable_months(scope)[150]

        ids: list[int] = []
        for index, version in enumerate(("D1", "D2", "D3"), start=1):
            builder = SnapshotBuilder(
                registry,
                {
                    key: (
                        {**value, month: MonthlyValue(month=month, value=1.0 * index, raw=1.0)}
                        if key == "A1"
                        else value
                    )
                    for key, value in series.items()
                },
                source.publication,
                Versions(data=version, registry=registry.version),
            )
            result = builder.build(scope, month)
            assert repository.save(result).revision == index
        with repository.engine.begin() as conn:
            ids = [
                int(row.id)
                for row in conn.execute(
                    select(regime_search_snapshot_table.c.id).order_by(
                        regime_search_snapshot_table.c.revision
                    )
                ).all()
            ]
            conn.execute(
                regime_search_log_table.insert().values(
                    owner_user_id=1,
                    snapshot_id=ids[0],
                    snapshot_key="kept-by-log",
                    entry_path="MARKET_FLOW",
                    baseline_origin="T_G",
                    viewed_at=date(2026, 1, 1),
                )
            )
        assert repository.prune() == 1  # 가운데 revision 2만 지운다
        remaining = {int(row["id"]) for row in snapshot_rows(repository)}
        assert remaining == {ids[0], ids[2]}

    def test_지운_항목의_사례도_함께_사라진다(self, repository: RegimeRepository) -> None:
        source = stub_source()
        registry = source.registry()
        series = {entry.id: source.monthly_series(entry.id) for entry in registry.indicators}
        scope = SearchScope.field("A")
        month = SnapshotBuilder(
            registry, series, source.publication, Versions(data="D1", registry=registry.version)
        ).observable_months(scope)[150]
        for index, version in enumerate(("D1", "D2"), start=1):
            builder = SnapshotBuilder(
                registry,
                {
                    key: (
                        {**value, month: MonthlyValue(month=month, value=2.0 * index, raw=1.0)}
                        if key == "A1"
                        else value
                    )
                    for key, value in series.items()
                },
                source.publication,
                Versions(data=version, registry=registry.version),
            )
            repository.save(builder.build(scope, month))
        before = repository.count_cases()
        assert repository.prune() == 1
        assert repository.count_cases() < before
        with repository.engine.connect() as conn:
            orphans = conn.execute(
                select(regime_search_case_table.c.id).where(
                    regime_search_case_table.c.snapshot_id.notin_(
                        select(regime_search_snapshot_table.c.id)
                    )
                )
            ).all()
        assert orphans == []


class TestBaselineRecord:
    """T068 — 기준월 기록이 적재와 같은 트랜잭션에서 남는다 (data-model §3, 포트 계약 §0.2)."""

    def test_분야와_종합의_기준월이_모두_기록된다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2008-01"], tmp_path) == 0
        recorded = repository.baseline_months()
        baselines = source.baseline_months()
        assert set(recorded) == set(baselines.by_field) | {COMPOSITE_SCOPE}
        for field_id, month in baselines.by_field.items():
            assert recorded[field_id]["baselineMonth"] == month
        assert recorded[COMPOSITE_SCOPE]["baselineMonth"] == baselines.composite

    def test_가장_오래된_기준월이_사전_계산이_있는_달이다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2005-01"], tmp_path) == 0
        recorded = repository.baseline_months()
        rows = snapshot_rows(repository)
        for scope_id, value in recorded.items():
            months = [
                row["baseline_month"]
                for row in rows
                if (row["field_id"] or COMPOSITE_SCOPE) == scope_id
            ]
            assert value["earliestMonth"] == min(months)


class TestFailureIsolation:
    """T070 — 실패한 기준월은 이전 revision을 그대로 두고, 실패를 기록한다 (FR-034, SC-010)."""

    def test_실패해도_부분_적재가_남지_않는다(
        self,
        monkeypatch: pytest.MonkeyPatch,
        repository: RegimeRepository,
        tmp_path: Path,
        capsys: pytest.CaptureFixture[str],
    ) -> None:
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2004-01"], tmp_path) == 0
        before = snapshot_rows(repository)
        cases_before = repository.count_cases()

        original = RegimeRepository.save
        calls = {"count": 0}

        def flaky(self: RegimeRepository, snapshot, **kwargs):  # type: ignore[no-untyped-def]
            calls["count"] += 1
            if calls["count"] == 2:
                raise RuntimeError("적재 중 끊김")
            return original(self, snapshot, **kwargs)

        monkeypatch.setattr(RegimeRepository, "save", flaky)
        source.revise("A1", date(2005, 1, 1), 99.0, version=f"{FIXTURE_PREFIX}STUB-3")
        exit_code = run_batch(monkeypatch, repository, source, [], tmp_path)

        assert exit_code != 0
        error = capsys.readouterr().err
        assert "실패한 기준월" in error
        assert "이전 revision이 그대로 남는다" in error
        after = snapshot_rows(repository)
        assert len(after) >= len(before)
        # 실패한 기준월은 머리만 남지 않는다 — 사례 없는 정상 항목이 생기지 않았는지 본다.
        with repository.engine.connect() as conn:
            headless = conn.execute(
                select(regime_search_snapshot_table.c.id)
                .where(regime_search_snapshot_table.c.status != STATUS_NOT_COMPUTABLE)
                .where(
                    regime_search_snapshot_table.c.id.notin_(
                        select(regime_search_case_table.c.snapshot_id)
                    )
                )
            ).all()
        assert headless == []
        assert repository.count_cases() >= cases_before


class TestWithdraw:
    """T071 — 철회는 사람이 지정할 때만 하고 기록을 남긴다 (spec FR-035, SC-012)."""

    def _two_revisions(self, repository: RegimeRepository) -> tuple[SearchScope, date]:
        source = stub_source()
        registry = source.registry()
        series = {entry.id: source.monthly_series(entry.id) for entry in registry.indicators}
        scope = SearchScope.field("A")
        month = SnapshotBuilder(
            registry, series, source.publication, Versions(data="D1", registry=registry.version)
        ).observable_months(scope)[150]
        for index, version in enumerate(("D1", "D2"), start=1):
            builder = SnapshotBuilder(
                registry,
                {
                    key: (
                        {**value, month: MonthlyValue(month=month, value=3.0 * index, raw=1.0)}
                        if key == "A1"
                        else value
                    )
                    for key, value in series.items()
                },
                source.publication,
                Versions(data=version, registry=registry.version),
            )
            repository.save(
                builder.build(scope, month),
                baseline_record=baseline_record_for(
                    scope, month, Versions(data=version, registry=registry.version)
                ),
            )
        return scope, month

    def test_최신_revision을_지우면_이전_것이_다시_최신이_된다(
        self, repository: RegimeRepository
    ) -> None:
        scope, month = self._two_revisions(repository)
        assert repository.latest_revision(scope, month)[0] == 2
        withdrawal = repository.withdraw(
            scope=scope, baseline_month=month, reason="척도 구간 오산정"
        )
        assert withdrawal.revision == 2
        assert repository.latest_revision(scope, month)[0] == 1

    def test_기록은_행이_남고_참조만_끊긴다(self, repository: RegimeRepository) -> None:
        scope, month = self._two_revisions(repository)
        with repository.engine.begin() as conn:
            target = conn.execute(
                select(regime_search_snapshot_table.c.id).where(
                    regime_search_snapshot_table.c.revision == 2
                )
            ).scalar_one()
            conn.execute(
                regime_search_log_table.insert().values(
                    owner_user_id=7,
                    snapshot_id=target,
                    snapshot_key="보던 것",
                    entry_path="STRATEGY_RESULT",
                    baseline_origin="USER_SPECIFIED",
                    viewed_at=date(2026, 1, 1),
                )
            )
        withdrawal = repository.withdraw(
            scope=scope, baseline_month=month, reason="계산 오류 확인"
        )
        assert withdrawal.detached_logs == 1
        with repository.engine.connect() as conn:
            rows = conn.execute(select(regime_search_log_table)).all()
        assert len(rows) == 1
        assert rows[0].snapshot_id is None
        assert rows[0].snapshot_key == "보던 것"

    def test_사유_없는_철회는_거부한다(self, repository: RegimeRepository) -> None:
        scope, month = self._two_revisions(repository)
        with pytest.raises(Exception, match="사유"):
            repository.withdraw(scope=scope, baseline_month=month, reason="   ")

    def test_철회_기록이_파일로_남는다(self, tmp_path: Path) -> None:
        """표가 없으므로 파일로 남긴다 — 기록 없는 철회가 0건이어야 한다(SC-012)."""
        import json

        log = tmp_path / "withdrawals.jsonl"
        precompute_regime.append_withdrawal({"snapshotKey": "K", "reason": "R"}, log)
        precompute_regime.append_withdrawal({"snapshotKey": "K2", "reason": "R2"}, log)
        lines = log.read_text(encoding="utf-8").strip().splitlines()
        assert [json.loads(line)["snapshotKey"] for line in lines] == ["K", "K2"]

    def test_철회에는_사유와_범위가_모두_있어야_한다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository
    ) -> None:
        monkeypatch.setattr(
            precompute_regime.RegimeRepository,
            "from_env",
            classmethod(lambda cls, path=None: repository),
        )
        assert precompute_regime.main(["--withdraw", "--mode", "field"]) != 0
        assert (
            precompute_regime.main(
                ["--withdraw", "--mode", "field", "--baseline-month", "2010-06"]
            )
            != 0
        )
        assert (
            precompute_regime.main(
                [
                    "--withdraw",
                    "--mode",
                    "field",
                    "--baseline-month",
                    "2010-06",
                    "--reason",
                    "x",
                ]
            )
            != 0
        )  # --field 가 없다


class TestProcessedOutput:
    """T073 — 중간 산출을 `범위/연도` 파티션 Parquet으로 남긴다."""

    def test_파티션_경로가_규약을_따른다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        source = stub_source()
        assert run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path) == 0
        written = sorted(
            path.relative_to(tmp_path).as_posix()
            for path in tmp_path.rglob("part-0.parquet")
        )
        assert written
        for path in written:
            assert path.startswith(f"{precompute_regime.PROCESSED_DATASET}/scope_id=")
            assert "/year=" in path

    def test_다시_써도_파티션이_늘지_않는다(
        self, monkeypatch: pytest.MonkeyPatch, repository: RegimeRepository, tmp_path: Path
    ) -> None:
        source = stub_source()
        run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path)
        first = {path for path in tmp_path.rglob("part-0.parquet")}
        run_batch(monkeypatch, repository, source, ["--since", "2010-01"], tmp_path)
        assert {path for path in tmp_path.rglob("part-0.parquet")} == first
