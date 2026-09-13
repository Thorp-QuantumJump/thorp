"""목표 위험 맞춤 오버레이(spec 004 FR-016 ``TARGET_RISK``, PD-18, R-17).

전략이 아니라 전략 **위에** 얹는 규칙이다. 판단일마다 배분 모듈이 낸 목표 비중으로 만든 포트폴리오의
최근 ``lookbackDays`` 일별 수익률 표준편차 × √252 를 변동성 추정치로 삼고, 추정치가 목표를 넘으면
비중 전체에 ``target / estimate`` 를 곱해 나머지를 현금으로 둔다. **내리기만 한다** — 올리면
레버리지다(FR-012).

**추정 대상은 실제 보유가 아니라 이번에 정한 목표 비중의 포트폴리오다.** 실제 보유 수익률은 첫
판단일에 하나도 없고, 리밸런싱 직후에는 옛 비중의 성질을 물려받는다. 목표 비중 포트폴리오의 과거
수익률은 판단일까지의 자료만으로 언제나 같은 값이 나온다(R-14).

규칙 값(``lookbackDays``·``scaleDownOnly``)은 ``run.scenarioRules`` 사본에서, 목표 변동성은
``run.scenarioParams.targetVolatility`` 에서만 읽는다. 없으면 지어내지 않는다(R-08).
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from datetime import date
from decimal import Decimal

import pandas as pd

from worker.allocation.base import ONE, ZERO, AllocationError
from worker.contracts.engine_input import RunSpec

#: 결과 계약 오류 코드. 추정에 필요한 수익률이 모자란다 — 워밍업을 늘리면 풀린다.
ESTIMATION_FAILED = "ESTIMATION_FAILED"
TRADING_DAYS_PER_YEAR = 252
VOLATILITY_QUANTUM = Decimal("0.00000001")


@dataclass(frozen=True)
class TargetRiskRules:
    target_volatility: Decimal
    lookback_days: int
    version: str | None


@dataclass(frozen=True)
class Overlay:
    weights: dict[str, Decimal]
    risk_scale: Decimal
    estimated_volatility: Decimal | None  # None = 투자 비중이 없어 추정할 것이 없다


def target_risk_rules(run: RunSpec) -> TargetRiskRules:
    """규칙·입력의 존재와 형식을 확인한다. 없거나 어긋나면 ``INVALID_REQUEST``."""
    rules = run.scenario_rules or {}
    params = run.scenario_params or {}
    if not rules:
        raise AllocationError(
            "INVALID_REQUEST",
            "run.scenarioRules is required for scenario TARGET_RISK;"
            " this engine has no overlay defaults (spec 004 R-17)",
        )
    lookback = rules.get("lookbackDays")
    if isinstance(lookback, bool) or not isinstance(lookback, int) or lookback < 2:
        raise AllocationError(
            "INVALID_REQUEST",
            f"run.scenarioRules.lookbackDays must be an integer >= 2: {lookback!r}",
        )
    if rules.get("scaleDownOnly") is not True:
        raise AllocationError(
            "INVALID_REQUEST",
            "run.scenarioRules.scaleDownOnly must be true; scaling up would be leverage (FR-012)",
        )
    if rules.get("adjustAt", "REBALANCE") != "REBALANCE":
        raise AllocationError(
            "INVALID_REQUEST",
            f"run.scenarioRules.adjustAt must be REBALANCE: {rules.get('adjustAt')!r}",
        )
    raw = params.get("targetVolatility")
    try:
        target = Decimal(str(raw))
    except (ArithmeticError, ValueError) as exc:
        raise AllocationError(
            "INVALID_REQUEST",
            f"run.scenarioParams.targetVolatility must be a decimal for TARGET_RISK: {raw!r}",
        ) from exc
    # NaN 은 아래 비교에서 InvalidOperation 을 던지고 Infinity 는 통과해 조절이 영원히 없다.
    if not target.is_finite() or target <= ZERO:
        raise AllocationError(
            "INVALID_REQUEST",
            f"run.scenarioParams.targetVolatility must be a finite positive decimal: {target}",
        )
    version = rules.get("version")
    return TargetRiskRules(target, lookback, str(version) if version is not None else None)


def apply_target_risk(
    rules: TargetRiskRules,
    weights: dict[str, Decimal],
    history: pd.DataFrame,
    as_of: date,
) -> Overlay:
    """목표 비중 포트폴리오의 변동성을 추정해 필요하면 비중을 내린다.

    :param history: 조정 종가 이력(index=거래일, columns=종목). **판단일까지만** 담겨 있어야 한다
    """
    invested = [iid for iid, weight in weights.items() if weight > ZERO]
    if not invested:
        return Overlay(dict(weights), ONE, None)
    closes = history.loc[:, invested].dropna()
    returns = closes.pct_change().dropna().tail(rules.lookback_days)
    if len(returns) < rules.lookback_days:
        raise AllocationError(
            ESTIMATION_FAILED,
            f"TARGET_RISK needs {rules.lookback_days} daily returns for {invested} up to {as_of},"
            f" found {len(returns)}; extend the warmup",
        )
    vector = [float(weights[iid]) for iid in invested]
    portfolio = (returns[invested] * vector).sum(axis=1)
    annualised = float(portfolio.std(ddof=1)) * math.sqrt(TRADING_DAYS_PER_YEAR)
    estimated = Decimal(repr(annualised)).quantize(VOLATILITY_QUANTUM)
    if estimated <= rules.target_volatility:
        return Overlay(dict(weights), ONE, estimated)
    scale = rules.target_volatility / estimated
    return Overlay({iid: weight * scale for iid, weight in weights.items()}, scale, estimated)
