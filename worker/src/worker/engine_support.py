"""이 엔진이 실제로 지원하는 요청의 범위.

계약(``worker.contracts.engine_input``)은 "메시지만 보고 판단할 수 있고 수신자가 누구든 답이
같은" 불변식만 검증한다. 엔진이 어느 값까지 실제로 계산할 수 있는지는 수신자마다 다르므로
계약이 아니라 구현체의 책임이고, 그래서 이 판단은 ``contracts/`` 가 아니라 여기 있다.
계약 픽스처(``docs/specs/backtest-messaging/examples/engine-input.json``: MA_CROSSOVER·2종목)는
계약 구조 검증용이므로 ``contracts/`` 검증을 계속 통과해야 하고, 이 엔진에는 지원 범위를
벗어난 요청이다.

**조용히 무시하지 않는다.** 지원하지 않는 값을 무시하고 ``COMPLETED`` 를 반환하면 요청한
정책과 다른 방식으로 계산한 숫자가 요청한 정책의 결과로 저장된다(ADR-0004 배경). 그래서
아래 항목은 실행 전에 전부 모아 ``INVALID_REQUEST`` 로 거절한다.

지원 범위 자체의 근거는 ADR-0003 "7. DEMO 시장 모델"이다. 다종목·비용 프로파일·시나리오는
spec 004의 T006~T009가 넓힌다.
"""

from decimal import Decimal

from worker.contracts.engine_input import EngineInput, Scenario
from worker.nautilus_engine import SLIPPAGE_BPS
from worker.strategies import unsupported_strategy_reason

#: 엔진이 계산하는 통화. ``execute_nautilus`` 가 ``KRW_SIM``(KRW와 1:1)을 하드코딩하고
#: ``portfolio.currency`` 는 성과 라벨링에만 쓰이므로, 다른 통화를 받으면 KRW로 계산해 놓고
#: 결과에 그 통화라고 적게 된다.
SUPPORTED_CURRENCY = "KRW"


def unsupported_reasons(request: EngineInput) -> list[str]:
    """이 엔진이 요청대로 계산할 수 없는 이유를 모두 모은다. 전부 지원하면 빈 리스트.

    한 건만 찾고 멈추지 않는다 — 요청자가 한 번의 실패로 고쳐야 할 필드를 다 알 수 있어야
    재요청이 한 번에 끝난다.
    """
    reasons: list[str] = []

    # 전략 지원 여부는 하드코딩하지 않고 레지스트리(worker.strategies.STRATEGIES)가 정한다.
    strategy_reason = unsupported_strategy_reason(request.allocation)
    if strategy_reason is not None:
        reasons.append(strategy_reason)

    if request.run.scenario is not Scenario.FULL_PERIOD:
        reasons.append(
            f"run.scenario must be {Scenario.FULL_PERIOD}; this engine runs the main analysis "
            f"only and would ignore {request.run.scenario}"
        )

    if request.run.effective_start is not None:
        reasons.append(
            "run.effectiveStart must be absent; this engine derives the evaluation start from "
            "the strategy lookback and would ignore the requested value"
        )

    instrument_ids = request.universe.instrument_ids
    if len(instrument_ids) > 1:
        reasons.append(
            "universe.items must contain exactly one instrument; this engine trades a "
            f"single instrument and would silently ignore {instrument_ids[1:]}"
        )

    if request.portfolio.leverage != 1:
        reasons.append(
            f"portfolio.leverage must be 1; this engine trades unlevered cash only, got "
            f"{request.portfolio.leverage}"
        )

    if request.portfolio.short_selling:
        reasons.append(
            "portfolio.shortSelling must be false; this engine is long-only and would ignore "
            "short signals"
        )

    if request.portfolio.currency != SUPPORTED_CURRENCY:
        reasons.append(
            f"portfolio.currency must be {SUPPORTED_CURRENCY}; this engine computes in "
            f"{SUPPORTED_CURRENCY} and would label {SUPPORTED_CURRENCY} results as "
            f"{request.portfolio.currency}"
        )

    if not request.portfolio.integer_lots:
        reasons.append(
            "portfolio.integerLots must be true; this engine sizes orders in whole shares and "
            "would round fractional lots without saying so"
        )

    cost = request.execution.cost_profile
    zero = Decimal(0)
    for name, value in (
        ("commissionBps", cost.commission_bps),
        ("spreadBps", cost.spread_bps),
        ("taxBps", cost.tax_bps),
    ):
        if value != zero:
            reasons.append(
                f"execution.costProfile.{name} must be 0; this engine charges slippage only and "
                f"would report {name}={value} costs as zero"
            )
    if cost.slippage_bps != SLIPPAGE_BPS:
        reasons.append(
            f"execution.costProfile.slippageBps must be {SLIPPAGE_BPS}; this engine applies a "
            f"fixed {SLIPPAGE_BPS} bps and would ignore {cost.slippage_bps}"
        )

    if request.execution.partial_fill:
        reasons.append(
            "execution.partialFill must be false; this engine fills orders in full or rejects "
            "them entirely"
        )

    if request.evaluation.warmup_trading_days is not None:
        reasons.append(
            "evaluation.warmupTradingDays must be absent; this engine warms up by the strategy "
            "lookback and would ignore the requested value"
        )

    if request.evaluation.liquidation_at_end:
        reasons.append(
            "evaluation.liquidationAtEnd must be false; this engine reports mark-to-market only "
            "and has no liquidation cost model"
        )

    return reasons
