"""유사성 문턱 교정 실험 (spec 010 threshold-calibration.md, S15P21E205-448).

조정 규칙과 판정 기준은 `specs/010-economic-regime-search/threshold-calibration.md` §1~§6이
**실험 전에** 고정했다. 이 스크립트는 그 정의대로 계산만 한다. 기준을 여기서 바꾸지 않는다.

    .venv/bin/python scripts/calibrate_regime_threshold.py \\
        --out ../specs/010-economic-regime-search/threshold-calibration-result.json

운영 배치와 저장소에 아무것도 쓰지 않는다. 지표는 `DATABASE_URL`의 008 적재분을 읽는다.
"""

from __future__ import annotations

import argparse
import json
import math
import sys
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Final

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from data_pipeline.regime.candidates import select_candidates  # noqa: E402
from data_pipeline.regime.distance import ModelUnavailable, build_model  # noqa: E402
from data_pipeline.regime.macro import create_source  # noqa: E402
from data_pipeline.regime.macro.types import (  # noqa: E402
    IndicatorRegistry,
    MonthlyValue,
    format_month,
)
from data_pipeline.regime.phase import (  # noqa: E402
    PhaseConfig,
    build_phase_model,
    load_config,
    phase_inputs,
)
from experiment_regime_v3 import phase_label  # noqa: E402

#: threshold-calibration.md §3
DEV_END: Final[date] = date(2012, 12, 1)
EXTERNAL: Final[tuple[date, date]] = (date(2013, 1, 1), date(2016, 12, 1))
FINAL: Final[tuple[date, date]] = (date(2017, 1, 1), date(2020, 5, 1))

#: §4 격자
TAU_PERCENTILES: Final[tuple[int, ...]] = (10, 20, 30, 40, 50)
DELTAS: Final[tuple[float, ...]] = (1.0, 1.5, 2.0, math.inf)
MIN_COVERAGE: Final[float] = 0.20
MIN_PAIRS_DEV: Final[int] = 30

#: §5 판정
MIN_LIFT: Final[float] = 0.05
MIN_PAIRS_EVAL: Final[int] = 20

#: §1 최소 근거량
COMPOSITE_MONTHS: Final[int] = 3
FIELD_MIN_MONTHS: Final[int] = 2


@dataclass(frozen=True)
class Pair:
    """(질의, 표시 후보) 한 쌍 — 판정에 필요한 값만 담는다.

    ``scope``는 검색 범위(종합이면 ``ALL``, 분야 검색이면 분야 id)다. 질의 단위는
    (범위, 기준월)이다 — 분야 검색에서 기준월만으로 세면 어느 한 분야에 충족 사례가 있어도 그 달
    전체가 포괄된 것이 된다.
    """

    scope: str
    query: date
    case: date
    distance: float
    max_gap: float
    evidence: bool
    same_phase: bool


def composite_pairs(
    config: PhaseConfig, series: Mapping[str, Mapping[date, MonthlyValue]], months: Sequence[date]
) -> list[Pair]:
    inputs = phase_inputs(config, series)
    pairs: list[Pair] = []
    for t in months:
        query_phase = phase_label(t)
        if query_phase is None:
            continue
        model = build_phase_model(config, t, inputs)
        if isinstance(model, ModelUnavailable):
            continue
        evidence = len(model.months) >= COMPOSITE_MONTHS
        for item in select_candidates(model).displayed:
            case_phase = phase_label(item.end_month)
            if case_phase is None:
                continue
            pairs.append(
                Pair(
                    scope="ALL",
                    query=t,
                    case=item.end_month,
                    distance=item.value,
                    max_gap=max(block.gap for block in item.blocks),
                    evidence=evidence,
                    same_phase=case_phase == query_phase,
                )
            )
    return pairs


def field_pairs(
    registry: IndicatorRegistry,
    series: Mapping[str, Mapping[date, MonthlyValue]],
    months: Sequence[date],
) -> list[Pair]:
    pairs: list[Pair] = []
    for field in registry.ordered_fields():
        entries = registry.indicators_of(field.id)
        scoped = {entry.id: series[entry.id] for entry in entries}
        for t in months:
            query_phase = phase_label(t)
            if query_phase is None:
                continue
            model = build_model(registry, t, scoped, field_ids=(field.id,))
            if isinstance(model, ModelUnavailable):
                continue
            for item in select_candidates(model).displayed:
                case_phase = phase_label(item.end_month)
                if case_phase is None:
                    continue
                indicators = [i for f in item.fields for i in f.indicators]
                pairs.append(
                    Pair(
                        scope=field.id,
                        query=t,
                        case=item.end_month,
                        distance=item.value,
                        max_gap=max(i.e for i in indicators),
                        evidence=all(len(i.months) >= FIELD_MIN_MONTHS for i in indicators),
                        same_phase=case_phase == query_phase,
                    )
                )
    return pairs


def meets(pair: Pair, tau: float, delta: float) -> bool:
    return pair.evidence and pair.distance <= tau and pair.max_gap <= delta


def measure(pairs: Sequence[Pair], tau: float, delta: float) -> dict[str, Any]:
    """§4의 A_충족·A_미충족·설명력 차이·포괄률·n_충족. 질의는 (범위, 기준월) 단위다."""
    met = [p for p in pairs if meets(p, tau, delta)]
    rest = [p for p in pairs if not meets(p, tau, delta)]
    queries = {(p.scope, p.query) for p in pairs}
    covered = {(p.scope, p.query) for p in met}
    a_met = sum(p.same_phase for p in met) / len(met) if met else None
    a_rest = sum(p.same_phase for p in rest) / len(rest) if rest else None
    return {
        "aMet": a_met,
        "aRest": a_rest,
        "lift": None if a_met is None or a_rest is None else a_met - a_rest,
        "coverage": len(covered) / len(queries) if queries else None,
        "nMet": len(met),
        "nPairs": len(pairs),
        "nQueries": len(queries),
    }


def percentile(values: Sequence[float], q: int) -> float:
    ordered = sorted(values)
    position = q / 100 * (len(ordered) - 1)
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def tune(dev: Sequence[Pair]) -> dict[str, Any]:
    """§4. 제약을 만족하는 조합 중 설명력 차이가 가장 큰 것. 동률이면 τ 큰 쪽, 그다음 δ 큰 쪽."""
    distances = [p.distance for p in dev]
    grid = []
    for q in TAU_PERCENTILES:
        tau = percentile(distances, q)
        for delta in DELTAS:
            result = measure(dev, tau, delta)
            ok = (
                result["coverage"] is not None
                and result["coverage"] >= MIN_COVERAGE
                and result["nMet"] >= MIN_PAIRS_DEV
                and result["lift"] is not None
            )
            grid.append({"tauPercentile": q, "tau": tau, "delta": delta, "eligible": ok, **result})
    eligible = [g for g in grid if g["eligible"]]
    chosen = (
        max(eligible, key=lambda g: (round(g["lift"], 12), g["tau"], g["delta"]))
        if eligible
        else None
    )
    return {"grid": grid, "chosen": chosen}


def judge(result: Mapping[str, Any]) -> dict[str, Any]:
    """§5 한 구간의 세 조건."""
    checks = {
        "설명력 차이 ≥ 0.05": result["lift"] is not None and result["lift"] >= MIN_LIFT - 1e-12,
        "포괄률 ≥ 0.20": result["coverage"] is not None
        and result["coverage"] >= MIN_COVERAGE - 1e-12,
        "n_충족 ≥ 20": result["nMet"] >= MIN_PAIRS_EVAL,
    }
    return {"checks": checks, "pass": all(checks.values())}


def split(pairs: Sequence[Pair]) -> tuple[list[Pair], list[Pair], list[Pair]]:
    dev = [p for p in pairs if p.query <= DEV_END]
    external = [p for p in pairs if EXTERNAL[0] <= p.query <= EXTERNAL[1]]
    final = [p for p in pairs if FINAL[0] <= p.query <= FINAL[1]]
    return dev, external, final


def calibrate(pairs: Sequence[Pair]) -> dict[str, Any]:
    dev, external, final = split(pairs)
    tuned = tune(dev)
    report: dict[str, Any] = {
        "periods": {
            name: {"pairs": len(part), "queries": len({(p.scope, p.query) for p in part})}
            for name, part in (("dev", dev), ("external", external), ("final", final))
        },
        "tuning": tuned,
    }
    chosen = tuned["chosen"]
    if chosen is None:
        report["decision"] = {
            "calibrated": False,
            "reason": "내부 개발 구간에서 제약을 만족하는 조합이 없다",
        }
        return report
    tau, delta = chosen["tau"], chosen["delta"]
    ext = measure(external, tau, delta)
    fin = measure(final, tau, delta)
    report["external"] = {**ext, **judge(ext)}
    report["final"] = {**fin, **judge(fin)}
    report["decision"] = {
        "calibrated": report["external"]["pass"] and report["final"]["pass"],
        "tau": tau,
        "delta": None if math.isinf(delta) else delta,
        "tauPercentile": chosen["tauPercentile"],
    }
    return report


def _json_default(value: Any) -> Any:
    if isinstance(value, float) and math.isinf(value):
        return "inf"
    if isinstance(value, date):
        return format_month(value)
    raise TypeError(type(value))


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="결과 JSON을 쓸 곳")
    args = parser.parse_args(argv)

    source = create_source("macro")
    registry = source.registry()
    series = {entry.id: source.monthly_series(entry.id) for entry in registry.indicators}
    config = load_config(indicator_ids=[entry.id for entry in registry.indicators])
    months = sorted({m for values in series.values() for m in values})

    report = {
        "dataVersion": source.data_version(),
        "registryVersion": registry.version,
        "phaseConfigVersion": config.version,
        "composite": calibrate(composite_pairs(config, series, months)),
        "field": calibrate(field_pairs(registry, series, months)),
    }
    text = json.dumps(report, ensure_ascii=False, indent=2, default=_json_default)
    text = text.replace("Infinity", '"inf"')
    decisions = {k: report[k].get("decision") for k in ("composite", "field")}
    print(json.dumps(decisions, ensure_ascii=False))
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
