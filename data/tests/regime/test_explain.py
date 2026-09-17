"""비슷한 점·다른 점 문장 (spec FR-015·FR-022, research R8, tasks.md T056·T057).

여기서 고정하는 것은 셋이다 — 문장에 **실제 값과 단위**가 들어가는가, 그 값이 **변환 후 값**인가,
그리고 spec FR-022 ①의 금지 표현이 0건인가.

**금지 목록을 여기에 옮겨 적지 않는다.** 시험이 제 목록을 들고 있으면 spec이 한 줄 늘어도 시험은
옛 목록으로 계속 통과한다. 그래서 spec.md의 표를 읽어 그대로 쓰고, 줄 수가 달라지면 실패한다.
"""

from __future__ import annotations

import ast
import re
from datetime import date
from pathlib import Path

from conftest import make_registry, make_series

from data_pipeline.regime import explain as explain_module
from data_pipeline.regime.distance import CandidateDistance, DistanceModel, build_model
from data_pipeline.regime.explain import MAX_SENTENCES, explanation_for, format_value
from data_pipeline.regime.macro.types import MonthlyValue

REPO_ROOT = Path(__file__).resolve().parents[3]
SPEC = REPO_ROOT / "specs" / "010-economic-regime-search" / "spec.md"
REGIME_PACKAGE = REPO_ROOT / "data" / "src" / "data_pipeline" / "regime"

START = (2015, 4)
MONTHS = 123
BASELINE = date(2025, 6, 1)
END_MONTH = date(2015, 6, 1)


def ramp(period: int, step: float, base: float = 0.0) -> list[float]:
    return [base + (index % period) * step for index in range(MONTHS)]


def banned_rows() -> list[str]:
    """spec FR-022 ①의 표 줄. 목록의 주인은 spec이다."""
    text = SPEC.read_text(encoding="utf-8")
    table = re.search(r"\*\*① 금지 표현 목록\*\*.*?((?:\s*\|.*\n)+)", text, re.S)
    assert table is not None, "spec에서 금지 표현 표를 찾지 못했다"
    return [line for line in table.group(1).splitlines() if re.match(r"\s*\|\s*\d+\s*\|", line)]


def banned_tokens() -> list[str]:
    return [token for row in banned_rows() for token in re.findall(r"`([^`]+)`", row)]


def explain_for(values: dict[str, list[float]], layout: dict[str, list[str]]):
    registry = make_registry(layout)
    series = {key: make_series(START, series) for key, series in values.items()}
    model = build_model(registry, BASELINE, series)
    assert isinstance(model, DistanceModel)
    entries = {entry.id: entry for entry in registry.indicators}
    return explanation_for(model.distance_for(END_MONTH), entries, series)


FOUR = {
    "FIELD_A": ["A1", "A2"],
    "FIELD_B": ["B1", "B2"],
}
FOUR_VALUES = {
    "A1": ramp(7, 0.5, 1.0),
    "A2": ramp(9, 1.25, 10.0),
    "B1": ramp(11, 0.2),
    "B2": ramp(13, 2.0, 5.0),
}


class TestSentences:
    """실제 값과 단위로 서술하고 템플릿을 벗어나지 않는다 (spec FR-015, research R8)."""

    def test_양쪽_모두_최대_두_문장이다(self) -> None:
        explanation = explain_for(FOUR_VALUES, FOUR)
        assert len(explanation.similar) == MAX_SENTENCES
        assert len(explanation.different) == MAX_SENTENCES

    def test_같은_지표가_양쪽에_들어가지_않는다(self) -> None:
        explanation = explain_for(FOUR_VALUES, FOUR)
        names = [sentence.split()[0] for sentence in explanation.similar + explanation.different]
        assert len(set(names)) == len(names)

    def test_값과_단위가_문장에_있다(self) -> None:
        explanation = explain_for(FOUR_VALUES, FOUR)
        for sentence in explanation.similar + explanation.different:
            assert "%" in sentence
            assert re.search(r"\d", sentence)

    def test_다른_점은_높거나_낮다고만_말한다(self) -> None:
        explanation = explain_for(FOUR_VALUES, FOUR)
        for sentence in explanation.different:
            assert sentence.endswith("높습니다.") or sentence.endswith("낮습니다.")

    def test_지표가_적으면_문장도_적다(self) -> None:
        explanation = explain_for({"A1": ramp(7, 0.5, 1.0)}, {"FIELD_A": ["A1"]})
        assert len(explanation.different) == 1
        assert explanation.similar == ()

    def test_모두_같으면_다른_점이_없다(self) -> None:
        same = {key: ramp(12, 0.5, 1.0) for key in ("A1", "A2", "B1", "B2")}
        explanation = explain_for(same, FOUR)
        assert explanation.different == ()
        assert explanation.similar

    def test_받침에_맞는_조사를_쓴다(self) -> None:
        assert explain_module.topic_particle("실업률") == "은"
        assert explain_module.topic_particle("소비자물가") == "는"


class TestTransformedValue:
    """문장의 값은 **변환 후 값**이다 (spec FR-015, D21, T057)."""

    def series_with_raw(self) -> dict[str, dict[date, MonthlyValue]]:
        """변환값과 원수준이 다른 계열 — 증가율 3.2%와 지수 112.4처럼."""
        series: dict[date, MonthlyValue] = {}
        year, month = START
        for index in range(MONTHS):
            current = date(year, month, 1)
            series[current] = MonthlyValue(
                month=current, value=1.0 + (index % 7) * 0.5, raw=100.0 + index
            )
            year, month = (year + 1, 1) if month == 12 else (year, month + 1)
        return {"A1": series}

    def test_지수_원수준이_문장에_들어가지_않는다(self) -> None:
        registry = make_registry({"FIELD_A": ["A1"]})
        series = self.series_with_raw()
        model = build_model(registry, BASELINE, series)
        assert isinstance(model, DistanceModel)
        entries = {entry.id: entry for entry in registry.indicators}
        explanation = explanation_for(model.distance_for(END_MONTH), entries, series)

        sentence = explanation.different[0]
        raw_now = series["A1"][BASELINE].raw
        assert format_value(series["A1"][BASELINE].value) in sentence
        assert format_value(raw_now) not in sentence

    def test_패키지에서_raw_series를_부르지_않는다(self) -> None:
        """정의·구현이 아니라 **호출**을 센다.

        포트는 원값을 받도록 `raw_series`를 선언하고 대역이 그것을 구현한다(계약 §1) —
        문자열로 세면 그 둘이 걸려서, 정작 막아야 할 호출과 구분되지 않는다.
        """
        callers = []
        for path in REGIME_PACKAGE.rglob("*.py"):
            tree = ast.parse(path.read_text(encoding="utf-8"))
            for node in ast.walk(tree):
                if not isinstance(node, ast.Call):
                    continue
                target = node.func
                name = (
                    target.attr
                    if isinstance(target, ast.Attribute)
                    else getattr(target, "id", None)
                )
                if name == "raw_series":
                    callers.append(f"{path.name}:{node.lineno}")
        assert callers == [], "설명·표시 경로는 변환 후 값만 쓴다(포트 계약 §1)"


class TestBannedExpressions:
    """spec FR-022 ①의 금지 표현이 산출물에 0건이다."""

    def test_목록은_spec이_가진_것이다(self) -> None:
        assert len(banned_rows()) == 13, "spec의 금지 표현 수가 달라졌다 — 시험을 맞춰야 한다"

    def test_문장에_금지_표현이_없다(self) -> None:
        explanation = explain_for(FOUR_VALUES, FOUR)
        for sentence in explanation.similar + explanation.different:
            for token in banned_tokens():
                assert token not in sentence, f"금지 표현이 문장에 있다: {token}"

    def test_템플릿_자체에도_금지_표현이_없다(self) -> None:
        templates = (explain_module.SIMILAR_TEMPLATE, explain_module.DIFFERENT_TEMPLATE)
        for token in banned_tokens():
            assert all(token not in template for template in templates)

    def test_시대별_위치를_기본_설명에_섞지_않는다(self) -> None:
        explanation = explain_for(FOUR_VALUES, FOUR)
        for sentence in explanation.similar + explanation.different:
            assert "당시로서는" not in sentence
        source = Path(explain_module.__file__).read_text(encoding="utf-8")
        assert "regime.era" not in source


def test_지표가_하나도_없으면_문장도_없다() -> None:
    """빈 사례에 문장을 지어내지 않는다."""
    empty = CandidateDistance(end_month=END_MONTH, value=0.0, fields=())
    explanation = explanation_for(empty, {}, {})
    assert explanation.similar == ()
    assert explanation.different == ()
