"""전략별 ``allocation.parameters`` 스키마와 ``strategyId`` 레지스트리.

``AllocationSpec.parameters`` 봉투는 전략마다 키가 달라 ``dict[str, Any]`` 로 남겨 두고,
어떤 키가 오는지와 값의 불변식은 전략별 모델이 선언한다. 전략을 추가할 때는 이 패키지에
파일 하나와 아래 레지스트리 한 줄만 추가하면 되고, ``AllocationSpec`` 이나 백엔드 record,
픽스처는 건드리지 않는다. 계약 픽스처의 ``MA_CROSSOVER`` 는 미등록 전략으로 통과한다(다섯 배분
전략의 모델은 spec 004 T006에서 추가).
"""

from worker.contracts.base import ContractModel
from worker.contracts.strategy_params.momentum import MomentumParameters

#: ``allocation.strategyId`` → 파라미터 모델. 미등록 전략은 여기서 거부하지 않는다
#: (지원 범위 판단은 엔진 몫이다).
STRATEGY_PARAMETERS: dict[str, type[ContractModel]] = {
    "MOMENTUM": MomentumParameters,
}

__all__ = ["STRATEGY_PARAMETERS", "MomentumParameters"]
