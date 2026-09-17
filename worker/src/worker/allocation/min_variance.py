"""최소분산(FR-025, PD-04). 추정 공분산 아래에서 포트폴리오 분산이 가장 작은 비중을 찾는다.

**투자 비중 합계를 최적화 전에 고정한다.** 고정하지 않으면 "전액 현금"이 분산 0으로 언제나 최적이
되어 전략이 사라진다. 그래서 합계 제약을 `유효 투자 목표`로 못 박고 최적화기에게는 현금이라는
선택지를 주지 않는다. 계산 가능 종목이 모자라 목표를 낮춘 경우는 그 사실을 결과에 남긴다.

**추정 분산이 작다는 것과 앞으로 덜 잃는다는 것은 다른 말이다.** 이 모듈은 앞의 것만 계산한다.

규칙 값은 하나도 갖고 있지 않고 전부 ``allocation.rules`` 에서 읽는다(R-08).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from decimal import Decimal
from typing import Any

import numpy as np
import pandas as pd
from scipy.optimize import minimize

from worker.allocation.base import (
    CASH,
    EFFECTIVE_TARGET_REDUCED,
    SINGLE_ASSET_FALLBACK,
    ZERO,
    AllocationError,
    AllocationResult,
    decimal_rule,
    effective_cap,
    int_rule,
    quantise,
    require_rule_value,
)
from worker.contracts.engine_input import UniverseItem
from worker.contracts.engine_output import ExceptionRecord, WeightsSource

STRATEGY_ID = "MINIMUM_VARIANCE"

#: ``rules.estimationMethod`` 가 가질 수 있는 값. 모르는 값은 임의 해석하지 않고 거절한다.
SAMPLE_COV = "SAMPLE_COV"

#: 공분산 행렬 검사 허용오차(PD-04 ①). 대칭성은 부동소수 오차만 봐주고, 음의 고유값은
#: 절삭으로 덮을 수 있는 폭까지만 봐준다. 넘으면 보정하지 않고 실행 전체를 실패시킨다.
SYMMETRY_TOLERANCE = 1e-12
EIGENVALUE_TOLERANCE = -1e-10

#: 해 사후 검증 허용오차(PD-04 ②). 솔버가 경계를 티끌만큼 넘는 것은 경계로 당기고,
#: 그보다 크면 실패다.
SOLUTION_TOLERANCE = 1e-8

MAX_ITERATIONS = 1000
FUNCTION_TOLERANCE = 1e-9


def compute(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    parameters: Mapping[str, Any],
    rules: Mapping[str, Any],
    as_of: date,
) -> AllocationResult:
    lookback = int_rule(rules, "lookbackDays", STRATEGY_ID)
    method = require_rule_value(rules, "estimationMethod", STRATEGY_ID)
    overflow = require_rule_value(rules, "capOverflow", STRATEGY_ID)
    cap = effective_cap(parameters, rules, STRATEGY_ID)
    invested = decimal_rule(rules, "investedFraction", STRATEGY_ID)
    if method != SAMPLE_COV:
        raise AllocationError(
            "INVALID_REQUEST",
            f"allocation.rules.estimationMethod={method!r} is not implemented;"
            f" this engine only knows {SAMPLE_COV} (PD-04)",
        )
    if overflow != CASH:
        raise AllocationError(
            "INVALID_REQUEST",
            f"allocation.rules.capOverflow={overflow!r} is not implemented;"
            f" this engine only knows {CASH} (PD-03 6)",
        )

    computable, exceptions = _computable(universe, history, lookback, as_of)
    count = len(computable)
    target = min(invested, cap * count)
    if target < invested:
        exceptions.append(
            ExceptionRecord(
                date=as_of,
                kind=EFFECTIVE_TARGET_REDUCED,
                rule=f"investedFraction={invested}, weightCap={cap}, computable={count}",
                action=f"유효 투자 목표 {target}, 나머지 {invested - target} 은 현금으로 보유",
            )
        )
    if count == 0:
        # 계산할 수 있는 종목이 없다. 목표 0%·현금 100%로 그날을 넘긴다(PD-04 7).
        return AllocationResult(weights={}, source=WeightsSource.COMPUTED, exceptions=exceptions)
    if count == 1:
        instrument = computable[0]
        exceptions.append(
            ExceptionRecord(
                date=as_of,
                instrumentId=instrument,
                kind=SINGLE_ASSET_FALLBACK,
                rule="computable=1",
                action="최적화 없이 유효 투자 목표를 그 종목에 배분",
            )
        )
        weights = {instrument: target}
    else:
        covariance = _covariance(history, computable, lookback)
        weights = _minimise(covariance, computable, target, cap)
    return AllocationResult(
        weights=quantise(weights, target, cap, rules, STRATEGY_ID),
        source=WeightsSource.COMPUTED,
        exceptions=exceptions,
    )


def _computable(
    universe: Sequence[UniverseItem],
    history: pd.DataFrame,
    lookback: int,
    as_of: date,
) -> tuple[list[str], list[ExceptionRecord]]:
    """관측 구간을 채운 종목만 **종목코드 오름차순**으로. 나머지는 사유를 남기고 제외한다.

    순서를 종목코드로 고정하는 이유는 솔버의 초기값·반복 순서가 입력 순서에 따라 달라지기
    때문이다 — 같은 요청이 같은 답을 내려면 순서부터 같아야 한다(SC-002).
    """
    computable: list[str] = []
    exceptions: list[ExceptionRecord] = []
    for instrument in sorted({item.instrument_id for item in universe}):
        if instrument not in history.columns:
            continue
        if len(history[instrument].dropna()) < lookback + 1:
            exceptions.append(
                ExceptionRecord(
                    date=as_of,
                    instrumentId=instrument,
                    kind="INSUFFICIENT_HISTORY",
                    rule=f"lookbackDays={lookback}",
                    action="제외 후 나머지로 계산",
                )
            )
            continue
        computable.append(instrument)
    return computable, exceptions


def _covariance(history: pd.DataFrame, instruments: list[str], lookback: int) -> np.ndarray:
    """표본공분산(``ddof=1``)과 그 행렬이 쓸 만한지에 대한 검사.

    종목마다 상장일이 달라 앞쪽이 비어 있으므로 **공통 거래일**만 남기고 마지막 ``lookback + 1``
    개를 쓴다. 검사에 걸리면 보정하지 않고 멈춘다 — 못 믿을 행렬로 최적화하면 그럴듯한 숫자가
    나오고 그 숫자가 왜 틀렸는지는 아무도 모른다.
    """
    common = history[instruments].dropna(how="any").tail(lookback + 1)
    returns = common.pct_change().dropna(how="any")
    if len(returns) < lookback:
        raise AllocationError(
            "ESTIMATION_FAILED",
            f"{STRATEGY_ID} needs {lookback} common trading day returns, got {len(returns)}",
        )
    matrix = np.cov(returns.to_numpy(dtype=float), rowvar=False, ddof=1)
    if not np.all(np.isfinite(matrix)):
        raise AllocationError(
            "ESTIMATION_FAILED", f"{STRATEGY_ID} covariance holds non-finite values"
        )
    asymmetry = float(np.max(np.abs(matrix - matrix.T)))
    if asymmetry > SYMMETRY_TOLERANCE:
        raise AllocationError(
            "ESTIMATION_FAILED",
            f"{STRATEGY_ID} covariance is not symmetric: max |S - S^T| = {asymmetry}",
        )
    matrix = (matrix + matrix.T) / 2
    eigenvalues, vectors = np.linalg.eigh(matrix)
    smallest = float(eigenvalues.min())
    if smallest < EIGENVALUE_TOLERANCE:
        raise AllocationError(
            "ESTIMATION_FAILED",
            f"{STRATEGY_ID} covariance is not positive semi-definite: min eigenvalue {smallest}",
        )
    if smallest < 0:
        # 부동소수 오차만큼의 음수다. 잘라 내고 다시 세운다(PD-04 1).
        matrix = vectors @ np.diag(np.clip(eigenvalues, 0.0, None)) @ vectors.T
    return matrix


def _minimise(
    covariance: np.ndarray, instruments: list[str], target: Decimal, cap: Decimal
) -> dict[str, Decimal]:
    """SLSQP로 ``w^T S w`` 최소화. 제약은 합 = 유효 투자 목표, 0 ≤ w ≤ 상한.

    초기값을 동일 비중(``목표/n``)으로 두면 시작부터 두 제약을 모두 만족하므로 솔버가 실행 가능
    영역 밖에서 헤매지 않는다. 기울기 ``2 S w`` 를 직접 주는 것도 같은 이유다 — 수치 미분은
    환경마다 마지막 자리가 갈린다.

    **실패하면 보정하지 않는다.** ``success=False`` 를 무시하고 마지막 반복값을 쓰면 최소분산이
    아닌 비중이 최소분산 결과로 저장된다.
    """
    size = len(instruments)
    goal = float(target)
    bound = float(cap)
    result = minimize(
        lambda w: float(w @ covariance @ w),
        x0=np.full(size, goal / size),
        jac=lambda w: 2 * covariance @ w,
        method="SLSQP",
        bounds=[(0.0, bound)] * size,
        constraints=[{"type": "eq", "fun": lambda w: float(w.sum()) - goal}],
        options={"maxiter": MAX_ITERATIONS, "ftol": FUNCTION_TOLERANCE},
    )
    if not result.success:
        raise AllocationError(
            "ESTIMATION_FAILED", f"{STRATEGY_ID} solver did not converge: {result.message}"
        )
    solution = np.asarray(result.x, dtype=float)
    if not np.all(np.isfinite(solution)):
        raise AllocationError("ESTIMATION_FAILED", f"{STRATEGY_ID} solver returned a non-finite w")
    weights: dict[str, Decimal] = {}
    for instrument, value in zip(instruments, solution, strict=True):
        if -SOLUTION_TOLERANCE <= value < 0:
            value = 0.0
        elif bound < value <= bound + SOLUTION_TOLERANCE:
            value = bound
        if value < 0 or value > bound:
            raise AllocationError(
                "ESTIMATION_FAILED",
                f"{STRATEGY_ID} solver put {instrument} at {value}, outside [0, {bound}]",
            )
        weights[instrument] = Decimal(str(value))
    drift = abs(float(sum(weights.values(), ZERO)) - goal)
    if drift > SOLUTION_TOLERANCE:
        raise AllocationError(
            "ESTIMATION_FAILED", f"{STRATEGY_ID} solver weights miss the target by {drift}"
        )
    return weights
