"""엔진이 실행할 수 있는 전략의 레지스트리.

``contracts/strategy_params`` 의 ``STRATEGY_PARAMETERS`` 와 대칭이다. 계약 쪽은 "이 전략의
``parameters`` 는 어떤 모양인가"를, 이쪽은 "이 엔진이 그 전략을 실제로 실행할 수 있는가"를
선언한다. 계약은 미등록 ``strategyId`` 를 거부하지 않으므로(엔진 지원 범위는 메시지만 보고
판단할 수 없다) 실제 거절은 여기 등록 여부로 결정된다.

전략을 추가하는 절차는 파일 2개 + 레지스트리 2줄이다.

1. ``worker/contracts/strategy_params/{전략}.py`` 에 파라미터 모델을 만들고
   ``STRATEGY_PARAMETERS`` 에 한 줄 등록한다.
2. ``worker/strategies/{전략}.py`` 에 ``Strategy`` 구현(``generate_signals`` 만)을 만들고
   아래 ``STRATEGIES`` 에 한 줄 등록한다.

``version`` 도 레지스트리가 선언한다. 지원하지 않는 버전을 1.0.0 로직으로 계산해 놓고 그
버전의 결과라고 반환하면 안 되므로, 미지원 버전은 미등록 전략과 같이 거절한다.
"""

from collections.abc import Callable
from dataclasses import dataclass

from worker.contracts.base import ContractModel
from worker.contracts.engine_input import StrategySpec
from worker.strategies.base import Strategy
from worker.strategies.momentum import MomentumStrategy


@dataclass(frozen=True)
class PreparedStrategy:
    """실행 준비가 끝난 전략.

    ``required_history`` 는 첫 신호를 내기 위해 필요한 **이전** 거래일 종가 수다. 엔진은 이
    값만큼 종가가 쌓이기 전에는 현금을 유지한다. ``Strategy`` ABC 는 ``generate_signals`` 만
    요구하므로(전략 수식은 엔진 없이 테스트·교체할 수 있어야 한다) 준비 기간은 전략 클래스가
    아니라 이 레지스트리가 선언한다.
    """

    signal: Strategy
    required_history: int


@dataclass(frozen=True)
class StrategyEntry:
    """``strategyId`` 하나의 지원 범위와 조립 방법."""

    #: 이 엔진이 결과를 그 버전의 결과라고 말할 수 있는 ``strategy.version`` 집합
    versions: frozenset[str]
    #: 계약이 전략별 모델로 검증한 ``typed_parameters`` 를 받아 실행 준비를 끝낸다
    build: Callable[[ContractModel], PreparedStrategy]


#: ``strategy.strategyId`` → 지원 범위. 여기 없는 전략은 엔진이 거절한다.
STRATEGIES: dict[str, StrategyEntry] = {
    "MOMENTUM": StrategyEntry(
        versions=frozenset({"1.0.0"}),
        build=lambda p: PreparedStrategy(MomentumStrategy(p.lookback_days), p.lookback_days),
    ),
}


def unsupported_strategy_reason(spec: StrategySpec) -> str | None:
    """엔진이 이 전략 요청을 실행할 수 없는 이유. 실행할 수 있으면 ``None``.

    ``StrategySpec.typed_parameters`` 는 미등록 ``strategyId`` 에서 ``LookupError`` 를 내므로
    파라미터를 꺼내기 전에 이 함수로 먼저 확인한다.
    """
    entry = STRATEGIES.get(spec.strategy_id)
    if entry is None:
        known = ", ".join(sorted(STRATEGIES)) or "(none)"
        return (
            f"strategy.strategyId {spec.strategy_id!r} is not implemented by this engine; "
            f"supported: {known}"
        )
    if spec.version not in entry.versions:
        supported = ", ".join(sorted(entry.versions))
        return (
            f"strategy.version {spec.version!r} is not implemented for "
            f"{spec.strategy_id}; supported: {supported}"
        )
    return None


def prepare_strategy(spec: StrategySpec) -> PreparedStrategy:
    """레지스트리의 builder로 전략을 조립한다.

    지원 범위 확인(``unsupported_strategy_reason``)을 통과한 요청에만 쓴다. 그러지 않은 요청은
    ``LookupError`` 로 실패한다 — 지원하지 않는 전략을 조용히 다른 전략으로 대체하지 않는다.
    """
    reason = unsupported_strategy_reason(spec)
    if reason is not None:
        raise LookupError(reason)
    return STRATEGIES[spec.strategy_id].build(spec.typed_parameters)


__all__ = [
    "STRATEGIES",
    "PreparedStrategy",
    "Strategy",
    "StrategyEntry",
    "prepare_strategy",
    "unsupported_strategy_reason",
]
