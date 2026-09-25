"""종합 비교 v3-eq 채택 실험 (spec 010 v3-experiment.md, S15P21E205-442).

판정 기준은 `specs/010-economic-regime-search/v3-experiment.md` §1~§5가 **실험 전에** 고정했다.
이 스크립트는 그 정의대로 계산만 한다. 기준을 여기서 바꾸지 않는다.

    .venv/bin/python scripts/experiment_regime_v3.py \
        --out ../specs/010-economic-regime-search/v3-experiment-result.json

운영 배치(`precompute_regime.py`)와 저장소에 아무것도 쓰지 않는다.
지표는 `DATABASE_URL`의 008 적재분을 읽는다.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys
from collections.abc import Callable, Iterable, Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Final

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from data_pipeline.regime.candidates import select_candidates  # noqa: E402
from data_pipeline.regime.distance import (  # noqa: E402
    ModelUnavailable,
    build_model,
    months_between,
)
from data_pipeline.regime.macro import create_source  # noqa: E402
from data_pipeline.regime.macro.types import (  # noqa: E402
    IndicatorRegistry,
    MonthlyValue,
    format_month,
    parse_month,
)
from data_pipeline.regime.phase import (  # noqa: E402
    PhaseConfig,
    build_phase_model,
    load_config,
    phase_inputs,
)

#: v3-experiment.md §2 — 국가데이터처 2023-03-02 기준순환일. (전환점 월, 그 달까지의 국면)
TURNING_POINTS: Final[tuple[tuple[str, str], ...]] = (
    ("1998-08", "수축"),
    ("2000-08", "확장"),
    ("2001-07", "수축"),
    ("2002-12", "확장"),
    ("2005-04", "수축"),
    ("2008-01", "확장"),
    ("2009-02", "수축"),
    ("2011-08", "확장"),
    ("2013-03", "수축"),
    ("2017-09", "확장"),
    ("2020-05", "수축"),
)
LABEL_END: Final[date] = date(2020, 5, 1)

#: v3-experiment.md §3 평가 구간.
EVAL_START: Final[date] = date(2013, 3, 1)
EVAL_END: Final[date] = date(2020, 5, 1)

#: v3-experiment.md §5 알려진 국면 (M4, 진단용).
EPISODES: Final[tuple[tuple[str, str, str], ...]] = (
    ("카드 사태", "2003-03", "2003-12"),
    ("금융위기", "2008-09", "2009-03"),
    ("유럽 재정위기", "2011-08", "2012-06"),
    ("코로나 충격", "2020-03", "2020-06"),
    ("금리 급등", "2022-01", "2022-12"),
)

TOP: Final[int] = 3


def phase_label(month: date) -> str | None:
    """그 달의 경기 국면. 2020-06 이후와 첫 전환점 이전은 모른다(``None``)."""
    if month > LABEL_END:
        return None
    previous: str | None = None
    for point, phase_until in TURNING_POINTS:
        if month <= parse_month(point):
            return phase_until if previous is not None or month == parse_month(point) else None
        previous = point
    return None


def episode_of(month: date) -> str | None:
    for name, start, end in EPISODES:
        if parse_month(start) <= month <= parse_month(end):
            return name
    return None


@dataclass(frozen=True)
class QueryResult:
    baseline_month: date
    top: tuple[date, ...]


Ranker = Callable[[date], tuple[date, ...] | None]


def v1_ranker(
    registry: IndicatorRegistry, series: Mapping[str, Mapping[date, MonthlyValue]]
) -> Ranker:
    def rank(t: date) -> tuple[date, ...] | None:
        model = build_model(registry, t, series)
        if isinstance(model, ModelUnavailable):
            return None
        return tuple(c.end_month for c in select_candidates(model).displayed[:TOP])

    return rank


def v3_ranker(config: PhaseConfig, series: Mapping[str, Mapping[date, MonthlyValue]]) -> Ranker:
    inputs = phase_inputs(config, series)

    def rank(t: date) -> tuple[date, ...] | None:
        model = build_phase_model(config, t, inputs)
        if isinstance(model, ModelUnavailable):
            return None
        return tuple(c.end_month for c in select_candidates(model).displayed[:TOP])

    return rank


def registry_without(registry: IndicatorRegistry, indicator_id: str) -> IndicatorRegistry:
    indicators = tuple(e for e in registry.indicators if e.id != indicator_id)
    used = {e.field_id for e in indicators}
    return IndicatorRegistry(
        version=registry.version,
        fields=tuple(f for f in registry.fields if f.id in used),
        indicators=indicators,
    )


def m1(results: Sequence[QueryResult]) -> float | None:
    rates = []
    for result in results:
        query = phase_label(result.baseline_month)
        labels = [phase_label(month) for month in result.top]
        known = [label for label in labels if label is not None]
        if query is None or not known:
            continue
        rates.append(sum(1 for label in known if label == query) / len(known))
    return statistics.fmean(rates) if rates else None


def m2(results: Sequence[QueryResult]) -> float | None:
    gaps = [
        statistics.median(months_between(month, r.baseline_month) for month in r.top)
        for r in results
        if r.top
    ]
    return statistics.median(gaps) if gaps else None


def m3(
    base: Mapping[date, tuple[date, ...]], variants: Iterable[Mapping[date, tuple[date, ...]]]
) -> float | None:
    overlaps = []
    for variant in variants:
        for month, top in base.items():
            other = variant.get(month)
            if other is None or not top:
                continue
            overlaps.append(len(set(top) & set(other)) / TOP)
    return statistics.fmean(overlaps) if overlaps else None


def m4(results: Sequence[QueryResult]) -> float | None:
    hits = []
    for result in results:
        own = episode_of(result.baseline_month)
        if own is None:
            continue
        hits.append(any(episode_of(m) not in (None, own) for m in result.top))
    return sum(hits) / len(hits) if hits else None


def run_model(rank: Ranker, months: Sequence[date]) -> dict[date, tuple[date, ...]]:
    ranked: dict[date, tuple[date, ...]] = {}
    for month in months:
        top = rank(month)
        if top is not None:
            ranked[month] = top
    return ranked


def evaluate(
    registry: IndicatorRegistry,
    series: Mapping[str, Mapping[date, MonthlyValue]],
    config: PhaseConfig,
    months: Sequence[date],
    *,
    stability: bool = True,
) -> dict[str, Any]:
    change_series = {**series, **phase_inputs(config, series)}
    models: dict[str, dict[date, tuple[date, ...]]] = {
        "v1": run_model(v1_ranker(registry, series), months),
        "v3-eq": run_model(v3_ranker(config, series), months),
        "v1-change": run_model(v1_ranker(registry, change_series), months),
    }
    # §3: 두 판정 대상이 모두 계산 가능한 달만 쓴다.
    common = sorted(set(models["v1"]) & set(models["v3-eq"]))
    evaluation = [m for m in common if EVAL_START <= m <= EVAL_END]
    labeled = [m for m in common if m <= LABEL_END]

    def results_of(name: str, subset: Sequence[date]) -> list[QueryResult]:
        return [QueryResult(m, models[name][m]) for m in subset if m in models[name]]

    report: dict[str, Any] = {
        "queryMonths": {
            "evaluation": len(evaluation),
            "evaluationRange": [format_month(evaluation[0]), format_month(evaluation[-1])]
            if evaluation
            else None,
            "labeledAll": len(labeled),
            "all": len(common),
        },
        "models": {},
    }
    for name in models:
        report["models"][name] = {
            "M1_evaluation": m1(results_of(name, evaluation)),
            "M1_labeledAll": m1(results_of(name, labeled)),
            "M2_evaluation": m2(results_of(name, evaluation)),
            "M4_all": m4(results_of(name, common)),
        }

    if stability:
        ids = [entry.id for entry in registry.indicators]
        v1_variants = [
            run_model(v1_ranker(registry_without(registry, i), series), evaluation) for i in ids
        ]
        v3_variants = [run_model(v3_ranker(config.without(i), series), evaluation) for i in ids]
        report["models"]["v1"]["M3_evaluation"] = m3(
            {m: models["v1"][m] for m in evaluation}, v1_variants
        )
        report["models"]["v3-eq"]["M3_evaluation"] = m3(
            {m: models["v3-eq"][m] for m in evaluation}, v3_variants
        )

    report["decision"] = decide(report["models"]["v1"], report["models"]["v3-eq"])
    report["examples"] = {
        name: {
            format_month(m): [format_month(c) for c in models[name][m]]
            for m in (parse_month("2008-12"), parse_month("2020-04"), parse_month("2016-06"))
            if m in models[name]
        }
        for name in models
    }
    return report


def decide(v1: Mapping[str, Any], v3: Mapping[str, Any]) -> dict[str, Any]:
    """v3-experiment.md §5. 세 조건을 모두 만족해야 채택이다."""
    checks = {
        "M1 차이 ≥ 0.05": _ge(v3.get("M1_evaluation"), v1.get("M1_evaluation"), 0.05),
        "M2(v3) ≥ M2(v1)": _ge(v3.get("M2_evaluation"), v1.get("M2_evaluation"), 0.0),
        "M3(v3) ≥ M3(v1) − 0.05": _ge(v3.get("M3_evaluation"), v1.get("M3_evaluation"), -0.05),
    }
    return {"checks": checks, "adopt": all(value is True for value in checks.values())}


def _ge(left: float | None, right: float | None, margin: float) -> bool | None:
    if left is None or right is None:
        return None
    return left - right >= margin - 1e-12


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="결과 JSON을 쓸 곳")
    parser.add_argument("--no-stability", action="store_true", help="M3를 건너뛴다(빠른 확인용)")
    args = parser.parse_args(argv)

    source = create_source("macro")
    registry = source.registry()
    series = {entry.id: source.monthly_series(entry.id) for entry in registry.indicators}
    config = load_config(indicator_ids=[entry.id for entry in registry.indicators])

    months = sorted({m for values in series.values() for m in values})
    report = evaluate(registry, series, config, months, stability=not args.no_stability)
    report["dataVersion"] = source.data_version()
    report["registryVersion"] = registry.version
    report["phaseConfigVersion"] = config.version

    text = json.dumps(report, ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
