"""경제 국면 모델 v3-eq — Thorp 종합 비교 전용 (spec 010 v3 개정안, S15P21E205-442).

v1은 지표 **값의 수준**이 가까운 때를 찾는다. v3는 경제가 **같은 방향으로 움직이던 국면**을 찾는다.

1. **국면 입력**: 008 변환값에서 만든다. 증가율 지표는 그대로, 수준 지표는 1년 변화로 바꾼다
   (개정안 §3.1). 수준 지표를 그대로 두면 금리·환율이 높던 옛 시기가 극단 z가 되어 "가까운 연도
   찾기"가 된다(§1.2).
2. **경제 블록**: 지표를 사람이 정한 블록으로 묶고 블록마다 요인 하나를 둔다(§3.2). 요인은 블록 안
   z의 **동일가중 평균**이다(PD-F1 대안, 2026-09-25 팀 결정 — constitution이 기본 검색 모델에서
   PCA를 금지한다).
3. **국면 거리**: 블록 요인 점수를 척도 구간 안 표준편차로 나눠 같은 크기로 맞추고, 비교 시점마다
   네 블록 점수 벡터의 유클리드 거리를 구해 평균한다(§3.4).
4. **잔차**: 블록 요인이 설명하지 못한 부분(부호를 맞춘 z − 블록 평균)을 따로 남긴다. **순위에는
   넣지 않는다**(§3.5).

**지표를 이름으로 알지 않는다.** 블록 구성과 국면 입력 규칙은 설정 파일
(`config/regime-phase-v3.json`)이 갖고, 이 모듈은 그것을 읽을 뿐이다
(spec FR-004, `test_indicator_agnostic`).

**v1 모듈을 바꾸지 않는다.** 분야 검색은 v1 그대로이며, 같은 분야의 D_g가 두 모드에서 같다는
보장(SC-002)도 분야 검색끼리의 것으로 남는다.

**척도는 기준월 t로 한 번만 만든다**(v1과 같은 원칙, FR-005). 국면 입력마다 v1의 공통 척도 규칙
(`scale_for`: t-122~t-3, 최소 60개월 연속, MAD→IQR)을 그대로 쓰고, 블록 점수의 표준편차도 같은
구간에서 구한다. 질의와 모든 후보가 같은 자를 쓴다.
"""

from __future__ import annotations

import json
import math
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Final

from data_pipeline.regime.comparison import COMPARISON_OFFSETS, QUERY_INPUT_MISSING
from data_pipeline.regime.distance import ModelUnavailable, months_between
from data_pipeline.regime.macro.types import MonthlyValue, format_month, require_month
from data_pipeline.regime.rounding import round_for_storage
from data_pipeline.regime.scale import (
    WINDOW_MAX_MONTHS,
    WINDOW_MINIMUM_MONTHS,
    Scale,
    scale_for,
    shift_month,
)

#: 국면 입력 규칙 (개정안 §3.1).
INPUT_KINDS: Final[frozenset[str]] = frozenset({"AS_IS", "YOY_DIFF", "YOY_PCT", "CUM12_FROM_MOM"})

#: 블록 점수 척도가 없을 때의 사유. v1 사유 어휘(data-model §2.1)를 그대로 쓴다.
SCALE_WINDOW_TOO_SHORT: Final[str] = "SCALE_WINDOW_TOO_SHORT"
SCALE_ZERO: Final[str] = "SCALE_ZERO"

DEFAULT_CONFIG: Final[Path] = (
    Path(__file__).resolve().parents[3] / "config" / "regime-phase-v3.json"
)


class PhaseConfigError(ValueError):
    """설정 파일이 등록표와 맞지 않거나 규칙을 어겼다. 배치를 시작하지 않는다."""


@dataclass(frozen=True)
class BlockMember:
    indicator_id: str
    sign: int


@dataclass(frozen=True)
class Block:
    id: str
    name: str
    members: tuple[BlockMember, ...]

    @property
    def indicator_ids(self) -> tuple[str, ...]:
        return tuple(member.indicator_id for member in self.members)


@dataclass(frozen=True)
class Threshold:
    """교정된 유사성 기준 (PD-09). 네 조건을 모두 만족한 사례가 '기준 충족'이다(§1).

    - 국면 거리 ≤ ``tau``
    - 블록 거리가 모두 ≤ ``delta`` (``None``이면 제한 없음)
    - 비교 시점이 ``min_comparison_months``개 이상
    """

    tau: float
    delta: float | None
    min_comparison_months: int

    def meets(self, distance: PhaseDistance, comparison_months: int) -> bool:
        return (
            comparison_months >= self.min_comparison_months
            and distance.value <= self.tau
            and (self.delta is None or all(b.gap <= self.delta for b in distance.blocks))
        )


@dataclass(frozen=True)
class PhaseConfig:
    """블록 구성표와 국면 입력표(PD-F4). 모델 버전의 일부다 — 바꾸면 새 버전이다."""

    version: str
    input_kinds: Mapping[str, str]
    input_units: Mapping[str, str]
    blocks: tuple[Block, ...]
    #: 교정된 유사성 기준(PD-09, threshold-calibration.md §7.1). 없으면 유사성을 판정하지 않는다.
    threshold: Threshold | None = None
    #: 설명 문장에만 쓰는 표시 배율·단위. 없으면 저장 값과 ``input_units`` 그대로다.
    #: 무역수지처럼 달러 단위 1년 변화가 열한 자리 숫자로 문장에 실리는 것을 막는다(S15P21E205-446).
    display: Mapping[str, tuple[float, str]] = field(default_factory=dict)

    def display_of(self, indicator_id: str, value: float) -> tuple[float, str]:
        """문장에 실을 (값, 단위). 계산과 저장에는 쓰지 않는다."""
        default = (1.0, self.input_units.get(indicator_id, ""))
        divisor, unit = self.display.get(indicator_id, default)
        return value / divisor, unit

    @property
    def indicator_ids(self) -> tuple[str, ...]:
        return tuple(member.indicator_id for block in self.blocks for member in block.members)

    def without(self, indicator_id: str) -> PhaseConfig:
        """지표 하나를 뺀 설정 — 순위 안정성 실험(M3)에서만 쓴다.

        블록이 비게 되면 그 블록을 통째로 뺀다. 운영 배치는 이 함수를 부르지 않는다.
        """
        blocks = tuple(
            Block(
                id=block.id,
                name=block.name,
                members=tuple(m for m in block.members if m.indicator_id != indicator_id),
            )
            for block in self.blocks
        )
        return PhaseConfig(
            version=self.version,
            input_kinds={k: v for k, v in self.input_kinds.items() if k != indicator_id},
            input_units={k: v for k, v in self.input_units.items() if k != indicator_id},
            blocks=tuple(block for block in blocks if block.members),
            display={k: v for k, v in self.display.items() if k != indicator_id},
            threshold=self.threshold,
        )


def load_config(path: Path = DEFAULT_CONFIG, *, indicator_ids: Iterable[str]) -> PhaseConfig:
    """설정을 읽고 등록표와 대조한다. 등록표의 지표가 **정확히 한 번씩** 블록에 있어야 한다.

    빠진 지표를 조용히 두면 그 지표 없이 계산한 결과가 종합 비교라는 이름으로 나간다(FR-007).
    """
    raw = json.loads(path.read_text(encoding="utf-8"))
    registered = set(indicator_ids)
    inputs = raw.get("inputs", {})
    kinds = {key: value["kind"] for key, value in inputs.items()}
    units = {key: value.get("unit", "") for key, value in inputs.items()}
    display = {
        key: (float(value["displayDivisor"]), value["displayUnit"])
        for key, value in inputs.items()
        if "displayDivisor" in value
    }
    if any(divisor <= 0 for divisor, _ in display.values()):
        raise PhaseConfigError("표시 배율은 0보다 커야 한다")
    unknown_kinds = sorted(key for key, kind in kinds.items() if kind not in INPUT_KINDS)
    if unknown_kinds:
        raise PhaseConfigError(f"모르는 국면 입력 규칙이다: {unknown_kinds}")

    blocks = tuple(
        Block(
            id=block["id"],
            name=block["name"],
            members=tuple(
                BlockMember(indicator_id=m["indicator"], sign=int(m["sign"]))
                for m in block["members"]
            ),
        )
        for block in raw.get("blocks", [])
    )
    listed = [member.indicator_id for block in blocks for member in block.members]
    if sorted(listed) != sorted(registered):
        raise PhaseConfigError(
            "블록 구성이 등록표와 다르다 — "
            f"빠짐 {sorted(registered - set(listed))}, 모름 {sorted(set(listed) - registered)}, "
            f"중복 {sorted({i for i in listed if listed.count(i) > 1})}"
        )
    if set(kinds) != registered:
        raise PhaseConfigError(f"국면 입력표가 등록표와 다르다: {sorted(set(kinds) ^ registered)}")
    if any(member.sign not in (1, -1) for block in blocks for member in block.members):
        raise PhaseConfigError("부호는 1 또는 -1이다")
    threshold = None
    if "threshold" in raw:
        body = raw["threshold"]
        threshold = Threshold(
            tau=float(body["tau"]),
            delta=None if body.get("delta") is None else float(body["delta"]),
            min_comparison_months=int(body["minComparisonMonths"]),
        )
        if threshold.tau <= 0 or (threshold.delta is not None and threshold.delta <= 0):
            raise PhaseConfigError("유사성 기준 τ·δ는 0보다 커야 한다")
    return PhaseConfig(
        version=raw["version"],
        input_kinds=kinds,
        input_units=units,
        blocks=blocks,
        display=display,
        threshold=threshold,
    )


# --- 국면 입력 (§3.1) ---------------------------------------------------------------------------


def phase_series(kind: str, series: Mapping[date, MonthlyValue]) -> dict[date, MonthlyValue]:
    """지표 하나의 국면 입력. 12개월 전(또는 12개월 연속) 값이 없는 달은 **만들지 않는다**."""
    if kind not in INPUT_KINDS:
        raise PhaseConfigError(f"모르는 국면 입력 규칙이다: {kind}")
    result: dict[date, MonthlyValue] = {}
    for month, observed in series.items():
        value = _phase_value(kind, month, observed, series)
        if value is not None and math.isfinite(value):
            result[month] = MonthlyValue(month=month, value=value, raw=observed.value)
    return result


def _phase_value(
    kind: str, month: date, observed: MonthlyValue, series: Mapping[date, MonthlyValue]
) -> float | None:
    if kind == "AS_IS":
        return observed.value
    if kind == "CUM12_FROM_MOM":
        growth = 1.0
        for back in range(12):
            earlier = series.get(shift_month(month, back))
            if earlier is None:
                return None
            growth *= 1 + earlier.value / 100
        return (growth - 1) * 100
    year_ago = series.get(shift_month(month, 12))
    if year_ago is None:
        return None
    if kind == "YOY_DIFF":
        return observed.value - year_ago.value
    # YOY_PCT
    if year_ago.value == 0:
        return None
    return (observed.value / year_ago.value - 1) * 100


def phase_inputs(
    config: PhaseConfig, series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]]
) -> dict[str, dict[date, MonthlyValue]]:
    """설정의 모든 지표에 국면 입력을 만든다."""
    return {
        indicator_id: phase_series(
            config.input_kinds[indicator_id], series_by_indicator[indicator_id]
        )
        for indicator_id in config.indicator_ids
    }


# --- 모델 --------------------------------------------------------------------------------------


@dataclass(frozen=True)
class BlockScore:
    """블록 하나의 비교 시점별 표준화 점수와 그 차이."""

    block_id: str
    query: tuple[float, ...]
    case: tuple[float, ...]
    gap: float


@dataclass(frozen=True)
class PhaseDistance:
    """후보 하나의 국면 거리와 블록별 내역.

    ``value``·``end_month``는 `candidates.select_candidates`가 쓰는 이름을 v1과 같게 둔다 —
    후보 제한·±6개월 건너뛰기·동률 규칙을 v1과 같은 코드로 적용하기 위해서다(FR-012~FR-014).
    """

    end_month: date
    value: float
    blocks: tuple[BlockScore, ...]
    residual_norm: float

    @property
    def by_block(self) -> dict[str, float]:
        return {block.block_id: block.gap for block in self.blocks}


@dataclass(frozen=True)
class PhaseModel:
    """기준월 t 하나에 고정된 v3 계산 준비물. 모든 후보가 이것 하나를 쓴다."""

    baseline_month: date
    config: PhaseConfig
    months: tuple[date, ...]
    scales: Mapping[str, Scale]
    block_spread: Mapping[str, float]
    series: Mapping[str, Mapping[date, MonthlyValue]]

    @property
    def available(self) -> bool:
        return True

    def case_months_for(self, end_month: date) -> tuple[date, ...]:
        return tuple(
            shift_month(end_month, months_between(month, self.baseline_month))
            for month in self.months
        )

    def has_inputs_for(self, end_month: date) -> bool:
        require_month(end_month, "후보 종료월")
        return all(
            month in self.series[indicator_id]
            for indicator_id in self.config.indicator_ids
            for month in self.case_months_for(end_month)
        )

    def block_raw(self, block: Block, month: date) -> float:
        """블록의 **표준화 전** 요인 점수 — 부호를 맞춘 z의 동일가중 평균."""
        values = [
            member.sign
            * self.scales[member.indicator_id].standardize(
                self.series[member.indicator_id][month].value
            )
            for member in block.members
        ]
        return sum(values) / len(values)

    def vector(self, month: date) -> tuple[float, ...]:
        """그 달의 국면 벡터 — 블록 점수를 척도 구간 표준편차로 나눈 값."""
        return tuple(
            self.block_raw(block, month) / self.block_spread[block.id]
            for block in self.config.blocks
        )

    def residuals(self, month: date) -> dict[str, float]:
        """지표별 잔차 — 부호를 맞춘 z에서 그 블록의 표준화 전 점수를 뺀 값(§3.5)."""
        result: dict[str, float] = {}
        for block in self.config.blocks:
            center = self.block_raw(block, month)
            for member in block.members:
                z = member.sign * self.scales[member.indicator_id].standardize(
                    self.series[member.indicator_id][month].value
                )
                result[member.indicator_id] = z - center
        return result

    def residual_norm(self, month: date) -> float:
        return math.sqrt(sum(value * value for value in self.residuals(month).values()))

    def distance_for(self, end_month: date) -> PhaseDistance:
        require_month(end_month, "후보 종료월")
        case_months = self.case_months_for(end_month)
        query_vectors = [self.vector(month) for month in self.months]
        case_vectors = [self.vector(month) for month in case_months]
        gaps = [
            math.sqrt(sum((q - c) ** 2 for q, c in zip(qv, cv, strict=True)))
            for qv, cv in zip(query_vectors, case_vectors, strict=True)
        ]
        blocks = tuple(
            BlockScore(
                block_id=block.id,
                query=tuple(round_for_storage(v[index]) for v in query_vectors),
                case=tuple(round_for_storage(v[index]) for v in case_vectors),
                gap=round_for_storage(
                    sum(
                        abs(q[index] - c[index])
                        for q, c in zip(query_vectors, case_vectors, strict=True)
                    )
                    / len(query_vectors)
                ),
            )
            for index, block in enumerate(self.config.blocks)
        )
        return PhaseDistance(
            end_month=end_month,
            value=round_for_storage(sum(gaps) / len(gaps)),
            blocks=blocks,
            residual_norm=round_for_storage(
                sum(self.residual_norm(month) for month in case_months) / len(case_months)
            ),
        )


def build_phase_model(
    config: PhaseConfig,
    baseline_month: date,
    phase_series_by_indicator: Mapping[str, Mapping[date, MonthlyValue]],
) -> PhaseModel | ModelUnavailable:
    """기준월 t의 v3 준비물. ``phase_series_by_indicator``는 `phase_inputs`의 결과다.

    - **비교 시점은 달 단위다**(§3.4): t-2·t-1·t 중 설정의 모든 지표에 국면 입력이 있는 달만 쓴다.
      하나도 없으면 계산 불가(`QUERY_INPUT_MISSING`)다.
    - 국면 입력마다 v1과 같은 공통 척도를 만든다. 하나라도 없으면 계산 불가이며 그 지표를 빼고
      계산하지 않는다(FR-007).
    - 블록 점수의 표준편차는 t-3에서 거슬러 모든 블록 지표가 있는 **연속** 달(최대 120, 최소 60)로
      구한다. 모자라거나 0이면 계산 불가다.
    """
    require_month(baseline_month, "기준월")
    ids = config.indicator_ids
    months = tuple(
        month
        for month in (shift_month(baseline_month, offset) for offset in COMPARISON_OFFSETS)
        if all(month in phase_series_by_indicator[indicator_id] for indicator_id in ids)
    )
    if not months:
        missing = tuple(
            sorted(
                indicator_id
                for indicator_id in ids
                if baseline_month not in phase_series_by_indicator[indicator_id]
            )
        )
        return ModelUnavailable(
            baseline_month=baseline_month, reason=QUERY_INPUT_MISSING, indicator_ids=missing
        )

    scales: dict[str, Scale] = {}
    failures: dict[str, list[str]] = {}
    for indicator_id in ids:
        result = scale_for(indicator_id, baseline_month, phase_series_by_indicator[indicator_id])
        if isinstance(result, Scale):
            scales[indicator_id] = result
        else:
            failures.setdefault(result.reason, []).append(indicator_id)
    if failures:
        reason = (
            SCALE_WINDOW_TOO_SHORT if SCALE_WINDOW_TOO_SHORT in failures else sorted(failures)[0]
        )
        return ModelUnavailable(
            baseline_month=baseline_month,
            reason=reason,
            indicator_ids=tuple(sorted(failures[reason])),
        )

    spreads: dict[str, float] = {}
    draft = PhaseModel(
        baseline_month=baseline_month,
        config=config,
        months=months,
        scales=scales,
        block_spread={block.id: 1.0 for block in config.blocks},
        series=phase_series_by_indicator,
    )
    for block in config.blocks:
        window = _continuous_block_window(block, baseline_month, phase_series_by_indicator)
        if len(window) < WINDOW_MINIMUM_MONTHS:
            return ModelUnavailable(
                baseline_month=baseline_month,
                reason=SCALE_WINDOW_TOO_SHORT,
                indicator_ids=block.indicator_ids,
            )
        values = [draft.block_raw(block, month) for month in window]
        spread = _sample_std(values)
        if round_for_storage(spread) == 0.0:
            return ModelUnavailable(
                baseline_month=baseline_month, reason=SCALE_ZERO, indicator_ids=block.indicator_ids
            )
        spreads[block.id] = spread

    return PhaseModel(
        baseline_month=baseline_month,
        config=config,
        months=months,
        scales=scales,
        block_spread=spreads,
        series=phase_series_by_indicator,
    )


def _continuous_block_window(
    block: Block, baseline_month: date, series: Mapping[str, Mapping[date, MonthlyValue]]
) -> list[date]:
    """t-3에서 거슬러 블록의 모든 지표가 있는 연속 달.

    처음 끊기는 곳에서 멈춘다(FR-005와 같은 규칙).
    """
    collected: list[date] = []
    month = shift_month(baseline_month, 3)
    while len(collected) < WINDOW_MAX_MONTHS and all(
        month in series[indicator_id] for indicator_id in block.indicator_ids
    ):
        collected.append(month)
        month = shift_month(month, 1)
    return collected


def _sample_std(values: Sequence[float]) -> float:
    mean = sum(values) / len(values)
    return math.sqrt(sum((value - mean) ** 2 for value in values) / (len(values) - 1))


def describe(model: PhaseModel | ModelUnavailable) -> str:
    """로그용 한 줄 요약."""
    if isinstance(model, ModelUnavailable):
        return f"{format_month(model.baseline_month)} 계산 불가 {model.reason}"
    return (
        f"{format_month(model.baseline_month)} 비교 시점 "
        f"{', '.join(format_month(m) for m in model.months)}"
    )
