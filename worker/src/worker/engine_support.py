"""이 엔진이 실제로 지원하는 요청의 범위(spec 004 T006 기준).

계약(``worker.contracts.engine_input``)은 "메시지만 보고 판단할 수 있고 수신자가 누구든 답이
같은" 불변식만 검증한다. 엔진이 어느 값까지 실제로 계산할 수 있는지는 수신자마다 다르므로
계약이 아니라 구현체의 책임이고, 그래서 이 판단은 ``contracts/`` 가 아니라 여기 있다.

**조용히 무시하지 않는다.** 지원하지 않는 값을 무시하고 ``COMPLETED`` 를 반환하면 요청한
정책과 다른 방식으로 계산한 숫자가 요청한 정책의 결과로 저장된다(ADR-0004 배경). 그래서
아래 항목은 실행 전에 전부 모아 ``INVALID_REQUEST`` 로 거절한다.
"""

from datetime import date

from worker.allocation import ALLOCATION_RULES
from worker.contracts.engine_input import EngineInput, PriceSource, Scenario
from worker.portfolio_execution import REBALANCE_MONTH_END
from worker.rules_registry import rules_not_registered_message

#: 엔진이 계산하는 통화. ``KRW_SIM``(KRW 와 1:1)으로 계산하므로 다른 통화를 받으면 KRW 로 계산해
#: 놓고 결과에 그 통화라고 적게 된다.
SUPPORTED_CURRENCY = "KRW"

#: 시나리오별 지원 상태. 없는 시나리오는 아직 계산하지 않는다 — TARGET_RISK 는 결과 계약에
#: ``riskScale``·``estimatedVolatility`` 칸이 없고(PD-18), COST_ESCALATION 은 ``escalation`` 규칙이
#: 미정의다. 둘 다 계약과 함께 별도 Task 에서 연다.
SUPPORTED_SCENARIOS = frozenset({Scenario.FULL_PERIOD, Scenario.FRESH_CASH_FROM})


def unsupported_reasons(request: EngineInput) -> list[str]:
    """이 엔진이 요청대로 계산할 수 없는 이유를 모두 모은다. 전부 지원하면 빈 리스트.

    한 건만 찾고 멈추지 않는다 — 요청자가 한 번의 실패로 고쳐야 할 필드를 다 알 수 있어야
    재요청이 한 번에 끝난다.
    """
    reasons: list[str] = []

    allocation = request.allocation
    if allocation.strategy_id not in ALLOCATION_RULES:
        reasons.append(
            f"allocation.strategyId: {rules_not_registered_message(allocation.strategy_id)}"
        )
    if not allocation.rules:
        reasons.append(
            "allocation.rules must carry the registered rule spec; this engine has no defaults "
            "(spec 004 R-08)"
        )
    rebalance = allocation.parameters.get("rebalance")
    if rebalance != REBALANCE_MONTH_END:
        reasons.append(
            f"allocation.parameters.rebalance must be {REBALANCE_MONTH_END}; got {rebalance!r}"
        )

    run = request.run
    if run.scenario not in SUPPORTED_SCENARIOS:
        supported = ", ".join(sorted(s.value for s in SUPPORTED_SCENARIOS))
        reasons.append(
            f"run.scenario {run.scenario} is not computed by this engine yet;"
            f" supported: {supported}"
        )
    if run.scenario is Scenario.FRESH_CASH_FROM:
        raw = (run.scenario_params or {}).get("startDate")
        try:
            fresh_from = date.fromisoformat(str(raw))
        except ValueError:
            fresh_from = None
        if fresh_from is None:
            reasons.append(
                "run.scenarioParams.startDate (ISO date) is required for scenario FRESH_CASH_FROM"
            )
        elif not run.start <= fresh_from <= run.end:
            reasons.append(
                f"run.scenarioParams.startDate {fresh_from} must lie within [run.start, run.end]"
            )

    market = request.market_data
    if market.fill_price is not PriceSource.RAW:
        reasons.append(
            f"marketData.fillPrice must be RAW; this engine fills at raw open quotes, got "
            f"{market.fill_price}"
        )
    if market.signal_price is not PriceSource.ADJUSTED:
        reasons.append(
            f"marketData.signalPrice must be ADJUSTED; this engine signals on adjusted closes, got "
            f"{market.signal_price}"
        )

    portfolio = request.portfolio
    if portfolio.leverage != 1:
        reasons.append(
            f"portfolio.leverage must be 1; this engine trades unlevered cash only, got "
            f"{portfolio.leverage}"
        )
    if portfolio.short_selling:
        reasons.append("portfolio.shortSelling must be false; this engine is long-only")
    if portfolio.currency != SUPPORTED_CURRENCY:
        reasons.append(
            f"portfolio.currency must be {SUPPORTED_CURRENCY}; this engine computes in "
            f"{SUPPORTED_CURRENCY} and would label {SUPPORTED_CURRENCY} results as "
            f"{portfolio.currency}"
        )
    if not portfolio.integer_lots:
        reasons.append(
            "portfolio.integerLots must be true; this engine sizes orders in whole shares and "
            "would round fractional lots without saying so"
        )

    if request.execution.partial_fill:
        reasons.append(
            "execution.partialFill must be false; this engine fills orders in full or rejects "
            "them entirely"
        )

    return reasons
