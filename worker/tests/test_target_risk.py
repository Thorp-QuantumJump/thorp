"""목표 위험 맞춤 오버레이(PD-18) — 순수 함수 단위."""

import math
from datetime import date
from decimal import Decimal

import numpy as np
import pandas as pd
import pytest

from worker.allocation.base import AllocationError
from worker.contracts.engine_input import RunSpec
from worker.target_risk import (
    ESTIMATION_FAILED,
    TargetRiskRules,
    apply_target_risk,
    target_risk_rules,
)

A, B = "091160.XKRX", "305720.XKRX"
AS_OF = date(2024, 1, 31)


def run_spec(**kwargs) -> RunSpec:
    base = {"runId": "R", "start": "2024-01-02", "end": "2024-06-28", "scenario": "TARGET_RISK"}
    return RunSpec.model_validate({**base, **kwargs})


def history(days: int, seed: int = 7) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    index = pd.bdate_range(end=pd.Timestamp(AS_OF), periods=days)
    closes = 100 * np.exp(np.cumsum(rng.normal(0, 0.01, size=(days, 2)), axis=0))
    return pd.DataFrame(closes, index=index, columns=[A, B])


def test_rules_and_target_come_only_from_the_message() -> None:
    with pytest.raises(AllocationError, match="scenarioRules is required"):
        target_risk_rules(run_spec(scenarioParams={"targetVolatility": "0.1"}))
    with pytest.raises(AllocationError, match="scaleDownOnly"):
        target_risk_rules(
            run_spec(
                scenarioParams={"targetVolatility": "0.1"},
                scenarioRules={"lookbackDays": 63, "scaleDownOnly": False},
            )
        )
    rules = target_risk_rules(
        run_spec(
            scenarioParams={"targetVolatility": "0.1000"},
            scenarioRules={
                "version": "TARGET_RISK_OVERLAY/1",
                "lookbackDays": 63,
                "scaleDownOnly": True,
            },
        )
    )
    assert rules == TargetRiskRules(Decimal("0.1000"), 63, "TARGET_RISK_OVERLAY/1")


def test_estimate_is_the_annualised_std_of_the_target_portfolio_returns() -> None:
    frame = history(64)
    weights = {A: Decimal("0.6"), B: Decimal("0.4")}
    overlay = apply_target_risk(TargetRiskRules(Decimal("0.01"), 63, None), weights, frame, AS_OF)

    returns = frame.pct_change().dropna()
    expected = float((returns[A] * 0.6 + returns[B] * 0.4).std(ddof=1)) * math.sqrt(252)
    assert overlay.estimated_volatility == Decimal(repr(expected)).quantize(Decimal("1e-8"))
    assert overlay.risk_scale == Decimal("0.01") / overlay.estimated_volatility
    assert overlay.weights == {iid: w * overlay.risk_scale for iid, w in weights.items()}


def test_below_target_leaves_weights_untouched() -> None:
    overlay = apply_target_risk(
        TargetRiskRules(Decimal("5"), 63, None), {A: Decimal("1")}, history(64), AS_OF
    )
    assert overlay.risk_scale == 1 and overlay.weights == {A: Decimal("1")}


def test_short_history_fails_estimation_instead_of_guessing() -> None:
    with pytest.raises(AllocationError) as error:
        apply_target_risk(
            TargetRiskRules(Decimal("0.1"), 63, None), {A: Decimal("1")}, history(63), AS_OF
        )
    assert error.value.code == ESTIMATION_FAILED


def test_all_cash_has_nothing_to_estimate() -> None:
    overlay = apply_target_risk(
        TargetRiskRules(Decimal("0.1"), 63, None), {A: Decimal("0")}, history(10), AS_OF
    )
    assert overlay.risk_scale == 1 and overlay.estimated_volatility is None
