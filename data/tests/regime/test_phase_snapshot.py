"""v3 종합 비교 snapshot (spec 010 v3 개정안 §3.5·§5, S15P21E205-442).

대역 등록표와 그에 맞춘 블록 구성으로 `SnapshotBuilder`의 종합 경로를 돌린다. 분야 검색이 v1 그대로
남는지도 여기서 본다.
"""

from __future__ import annotations

import json
import math
from datetime import date
from pathlib import Path

import pytest
from conftest import make_registry, make_series, write_phase_config

from data_pipeline.macro.registry import INDICATORS
from data_pipeline.regime.context import ContextEvent, events_within
from data_pipeline.regime.macro.types import format_month
from data_pipeline.regime.phase import DEFAULT_CONFIG, build_phase_model, load_config, phase_inputs
from data_pipeline.regime.phase_snapshot import (
    CASE_UNUSUAL_SENTENCE,
    format_phase_value,
    residual_threshold,
    subject_of,
)
from data_pipeline.regime.snapshot import MODEL_VERSION, SearchScope, SnapshotBuilder, Versions

LAYOUT = {"A": ["A1", "A2"], "B": ["B1"], "C": ["C1", "C2"]}
MONTHS = 240


def _values(index: int) -> list[float]:
    # 주기가 서로 다른 파동에 느린 추세를 더한다 — 상수도 완전 주기도 아니라 순위가 갈린다.
    period = 11 + 4 * index
    return [
        round(10 + index + 3 * math.sin(2 * math.pi * i / period) + 0.01 * i, 4)
        for i in range(MONTHS)
    ]


@pytest.fixture
def builder(tmp_path: Path) -> SnapshotBuilder:
    registry = make_registry(LAYOUT)
    series = {
        entry.id: make_series((2000, 1), _values(index))
        for index, entry in enumerate(registry.indicators)
    }
    config = load_config(
        write_phase_config(tmp_path, registry),
        indicator_ids=[entry.id for entry in registry.indicators],
    )
    return SnapshotBuilder(
        registry,
        series,
        lambda indicator_id, month: None,
        Versions(data="D1", registry=registry.version),
        phase_config=config,
    )


BASELINE = date(2019, 12, 1)


class TestVersions:
    def test_종합은_설정의_모델_버전으로_식별된다(self, builder: SnapshotBuilder) -> None:
        snapshot = builder.build(SearchScope.composite(), BASELINE)
        assert snapshot.versions.model == "REGIME-V3-TEST"
        assert snapshot.key.endswith(":REGIME-V3-TEST")

    def test_분야_검색은_v1_그대로다(self, builder: SnapshotBuilder) -> None:
        snapshot = builder.build(SearchScope.field("A"), BASELINE)
        assert snapshot.versions.model == MODEL_VERSION
        assert "byField" in snapshot.cases[0].distance
        assert snapshot.cases[0].distance["byField"]


class TestCaseBody:
    def test_사례에_블록_거리가_담기고_분야_거리는_빈다(self, builder: SnapshotBuilder) -> None:
        snapshot = builder.build(SearchScope.composite(), BASELINE)
        assert snapshot.status.computable
        distance = snapshot.cases[0].distance
        assert distance["byField"] == {}
        assert [block["id"] for block in distance["blocks"]] == ["A", "B", "C"]
        assert {"gap", "name", "spread"} <= set(distance["blocks"][0])
        shares = [float(value) for value in distance["blockContribution"].values()]
        assert sum(shares) == pytest.approx(1.0, abs=1e-9)

    def test_순위는_국면_거리_순서다(self, builder: SnapshotBuilder) -> None:
        snapshot = builder.build(SearchScope.composite(), BASELINE)
        values = [float(case.distance["value"]) for case in snapshot.cases]
        assert values == sorted(values)

    def test_지표_값은_모든_지표를_같은_달로_담는다(self, builder: SnapshotBuilder) -> None:
        snapshot = builder.build(SearchScope.composite(), BASELINE)
        values = snapshot.cases[0].indicator_values
        assert set(values) == {"A1", "A2", "B1", "C1", "C2"}
        months = {tuple(body["comparisonMonths"]) for body in values.values()}
        assert len(months) == 1
        # 블록이 셋이고 A 블록에 둘이 있으면 명목 비중은 1/2 × 1/3이다.
        assert float(values["A1"]["nominalWeight"]) == pytest.approx(1 / 6)

    def test_설명은_블록_이름과_국면_입력_값을_말한다(self, builder: SnapshotBuilder) -> None:
        snapshot = builder.build(SearchScope.composite(), BASELINE)
        explanation = snapshot.cases[0].explanation
        assert explanation["similar"] and explanation["different"]
        assert all("분야 흐름은 비슷했습니다" in s for s in explanation["similar"])
        assert isinstance(explanation["unusual"], bool)
        assert (CASE_UNUSUAL_SENTENCE in explanation["different"]) is explanation["unusual"]

    def test_한_사례에서_같은_지표를_두_번_인용하지_않는다(self, builder: SnapshotBuilder) -> None:
        snapshot = builder.build(SearchScope.composite(), BASELINE)
        for case in snapshot.cases:
            sentences = case.explanation["similar"] + case.explanation["different"]
            for indicator_id in ("A1", "A2", "B1", "C1", "C2"):
                assert sum(f"{indicator_id} 지표" in s for s in sentences) <= 1

    def test_같은_입력이면_같은_본문이다(self, builder: SnapshotBuilder) -> None:
        first = builder.build(SearchScope.composite(), BASELINE)
        second = builder.build(SearchScope.composite(), BASELINE)
        assert first.content_hash == second.content_hash


class TestUnavailable:
    def test_척도_구간이_모자라면_v3_버전으로_계산_불가를_저장한다(
        self, builder: SnapshotBuilder
    ) -> None:
        snapshot = builder.build(SearchScope.composite(), date(2003, 6, 1))
        assert not snapshot.status.computable
        assert snapshot.status.reason == "SCALE_WINDOW_TOO_SHORT"
        assert snapshot.versions.model == "REGIME-V3-TEST"
        assert snapshot.cases == ()


class TestResidual:
    def test_문턱은_척도_구간_잔차_크기의_90번째_백분위다(
        self, builder: SnapshotBuilder, tmp_path: Path
    ) -> None:
        registry = builder.registry
        config = load_config(
            write_phase_config(tmp_path, registry),
            indicator_ids=[entry.id for entry in registry.indicators],
        )
        series = {
            entry.id: make_series((2000, 1), _values(index))
            for index, entry in enumerate(registry.indicators)
        }
        model = build_phase_model(config, BASELINE, phase_inputs(config, series))
        threshold = residual_threshold(model)
        assert threshold is not None
        norms = []
        month = date(2019, 9, 1)
        while len(norms) < 120 and all(month in model.series[i] for i in config.indicator_ids):
            norms.append(model.residual_norm(month))
            month = date(month.year - (month.month == 1), (month.month - 2) % 12 + 1, 1)
        below = sum(1 for value in norms if value <= threshold)
        assert below / len(norms) == pytest.approx(0.9, abs=0.02)
        assert format_month(model.months[-1]) == "2019-12"


class TestFormat:
    @pytest.mark.parametrize(
        ("value", "text"),
        [(0.30000000000000004, "0.3"), (12.3456, "12.35"), (-0.004, "0"), (2.0, "2")],
    )
    def test_값은_소수_둘째_자리까지다(self, value: float, text: str) -> None:
        assert format_phase_value(value) == text

    @pytest.mark.parametrize(
        ("name", "label", "subject"),
        [
            ("건설기성액(불변)", "", "건설기성액(불변)은"),
            ("실업률", " 1년 변화", "실업률 1년 변화는"),
            ("원/달러 환율", " 1년 변화율", "원/달러 환율 1년 변화율은"),
            ("G20 경기선행지수", "", "G20 경기선행지수는"),
        ],
    )
    def test_주어의_조사는_괄호_앞_글자로_고른다(self, name: str, label: str, subject: str) -> None:
        assert subject_of(name, label) == subject


class TestContext:
    def test_관측_구간_안의_사건만_고른다(self) -> None:
        events = tuple(
            ContextEvent(kind="BASE_RATE", on=on, title=str(on), url=None, source="한국은행")
            for on in (date(2008, 9, 30), date(2008, 10, 9), date(2008, 12, 31), date(2009, 1, 9))
        )
        picked = events_within(events, [date(2008, 10, 1), date(2008, 11, 1), date(2008, 12, 1)])
        assert [event.on for event in picked] == [date(2008, 10, 9), date(2008, 12, 31)]

    def test_특이_사례에만_당시_맥락이_붙는다(self, tmp_path: Path) -> None:
        registry = make_registry(LAYOUT)
        series = {
            entry.id: make_series((2000, 1), _values(index))
            for index, entry in enumerate(registry.indicators)
        }
        config = load_config(
            write_phase_config(tmp_path, registry),
            indicator_ids=[entry.id for entry in registry.indicators],
        )
        # 매달 1일에 사건 하나 — 어느 관측 구간이든 사건이 있다.
        events = tuple(
            ContextEvent(
                kind="KDI_TRENDS",
                on=date(2000 + i // 12, i % 12 + 1, 1),
                title=f"호 {i}",
                url="https://example.org",
                source="KDI",
            )
            for i in range(MONTHS)
        )
        builder = SnapshotBuilder(
            registry,
            series,
            lambda indicator_id, month: None,
            Versions(data="D1", registry=registry.version),
            phase_config=config,
            context_events=events,
        )
        seen_unusual = False
        for baseline in (date(2017, 6, 1), date(2018, 6, 1), BASELINE):
            for case in builder.build(SearchScope.composite(), baseline).cases:
                unusual = case.explanation["unusual"]
                seen_unusual |= unusual
                assert ("context" in case.explanation) is unusual
                if unusual:
                    assert len(case.explanation["context"]) == 3
        assert seen_unusual


class TestDisplay:
    def test_표시_배율은_문장에만_쓰고_저장_값은_그대로다(self, tmp_path: Path) -> None:
        registry = make_registry(LAYOUT)
        path = write_phase_config(tmp_path, registry)
        raw = json.loads(path.read_text(encoding="utf-8"))
        raw["inputs"]["B1"].update({"displayDivisor": 100, "displayUnit": "백"})
        path.write_text(json.dumps(raw, ensure_ascii=False), encoding="utf-8")
        config = load_config(path, indicator_ids=[entry.id for entry in registry.indicators])
        assert config.display_of("B1", 250.0) == (2.5, "백")
        assert config.display_of("A1", 250.0) == (250.0, "%")

    def test_실제_설정의_무역수지는_억_달러로_말한다(self) -> None:
        config = load_config(DEFAULT_CONFIG, indicator_ids=[i.indicator_id for i in INDICATORS])
        value, unit = config.display_of("trade_balance", 24_137_983_294.0)
        assert unit == "억 달러"
        assert format_phase_value(value) == "241.38"

    @pytest.mark.parametrize(
        ("raw", "text"),
        [
            (-24_137_983_294.0, "-241.38"),
            (0.0, "0"),
            (300_000.0, "0"),  # 0.003억 — 둘째 자리 아래는 0으로 읽힌다
            (-400_000.0, "0"),  # -0이 아니라 0이다
            (100_000_000.0, "1"),
        ],
    )
    def test_표시_배율을_적용한_경계값(self, raw: float, text: str) -> None:
        config = load_config(DEFAULT_CONFIG, indicator_ids=[i.indicator_id for i in INDICATORS])
        value, _ = config.display_of("trade_balance", raw)
        assert format_phase_value(value) == text
