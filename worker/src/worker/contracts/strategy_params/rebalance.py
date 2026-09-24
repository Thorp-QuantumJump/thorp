"""배분 전략 공통 ``allocation.parameters`` (spec 004 FR-011).

규칙 값의 **기본값**은 ``allocation.rules`` 에 있고 ``parameters`` 에는 **사용자가 화면에서 고른
것**이 온다. 둘 다 있으면 사용자가 고른 값이 이긴다 — 규칙표 값은 고르지 않은 요청의 기본값
역할이다(PD-03 ⑥가 ``maxWeight`` 로 정한 규칙을 다른 축에도 같게 적용한다).

키 이름은 **003 등록표의 축 이름 그대로**다. 다만 ``rebalancePeriod`` 는 003이 객체
(``{mode, unit}``)로 두고 엔진은 문자열 하나만 쓰므로 백엔드 ``EngineInputAssembler`` 가
``rebalance`` 로 옮겨 싣는다 — 그 변환은 발행 경로 한 곳에만 있다.

``maxWeight`` 는 003 축과 같은 **정수 %** 다. 규칙표와 엔진은 소수로 다루므로 변환은
``allocation.base.effective_cap`` 한 곳에서만 한다 — 두 곳에서 나누면 한 곳이 100배 어긋난다.
"""

import re
from typing import Literal

from pydantic import ConfigDict, Field

from worker.contracts.base import ContractModel

#: 계약이 허용하는 리밸런싱 주기(FR-011). 달력 주기 둘과 ``TRADING_DAYS_{n}``(평가 시작일부터
#: n거래일마다). 003 등록표의 ``{mode: TRADING_DAYS, days: n}`` 은 백엔드가 이 철자로 옮겨 싣는다.
REBALANCE_PATTERN = r"^(MONTH_END|QUARTER_END|TRADING_DAYS_[1-9][0-9]*)$"


def trading_day_period(rebalance: str) -> int | None:
    """``TRADING_DAYS_21`` → 21. 달력 주기(월말·분기말)는 None."""
    match = re.fullmatch(r"TRADING_DAYS_([1-9][0-9]*)", rebalance)
    return int(match.group(1)) if match else None


class RebalanceParameters(ContractModel):
    """동일 비중. 사용자가 고르는 것은 주기뿐이다.

    ``validate_by_name=False`` 은 일부러다. ``ContractModel`` 은 필드 이름(snake_case)도
    받아주지만, 여기서는 003 축 이름(camelCase)만 받는다 — 계산은 원본 dict 를 축
    이름으로 읽으므로(``effective_cap`` 등), 다른 철자를 받아주면 검증은 통과하고
    계산은 그 값을 못 본 채 규칙표 기본값으로 조용히 빠진다.
    """

    model_config = ConfigDict(extra="forbid", validate_by_name=False)

    rebalance: str = Field(pattern=REBALANCE_PATTERN)


class CappedRebalanceParameters(RebalanceParameters):
    """종목별 상한 축을 가진 전략(고정 비중·역변동성·최소분산).

    동일 비중은 003 에 ``maxWeight`` 축이 없어 여기 들어오지 않는다 — 받아 놓고 1/N 을
    그대로 내면 상한을 고른 사용자가 상한 없는 결과를 받는다.
    """

    # None = 미지정. 그때는 규칙표의 weightCap 을 쓴다(PD-03 6).
    max_weight: int | None = Field(default=None, gt=0, le=100)


class LookbackRebalanceParameters(CappedRebalanceParameters):
    """관측 기간 축을 가진 전략(역변동성·최소분산·섹터 모멘텀).

    **여기서 받지 않으면 화면이 고른 관측 기간이 조용히 무시된다** — 사용자가 250거래일을 골라도
    규칙표 기본값 120으로 계산한 결과가 250의 결과인 척 저장된다. 계약이 키를 거절하는 편이
    낫고(그러면 요청이 실패한다), 받아서 계산에 쓰는 편이 더 낫다.

    축을 선언한 전략이 셋이라 필드를 여기 한 번만 둔다. 전략마다 따로 적으면 한 곳을 빠뜨렸을 때
    그 전략만 조용히 규칙표 기본값으로 돈다 — 역변동성이 실제로 그렇게 빠져 있었다.
    """

    # None = 미지정. 그때는 규칙표의 lookbackDays 를 쓴다.
    lookback_days: int | None = Field(default=None, gt=0)


class MinimumVarianceParameters(LookbackRebalanceParameters):
    """최소분산(PD-04). 003 축 ``estimationMethod`` 를 더 받는다."""

    # None = 미지정. 그때는 규칙표의 estimationMethod 를 쓴다.
    estimation_method: str | None = None


class SectorMomentumParameters(LookbackRebalanceParameters):
    """섹터 모멘텀(PD-03). 003 축 다섯을 ``lookbackDays`` 위에 더 받는다.

    ``topN``·``threshold``·``topPercent`` 는 003 등록표에서 ``selectionMode`` 에 딸린 축이라
    (``appliesWhen``) 고른 방식의 것 하나만 온다. 계약이 셋을 다 선택으로 두는 이유는 어느 것이
    오는지가 다른 축의 값에 달려 있어서이고, **그 짝이 맞는지는 등록표를 든 접수 관문이 본다** —
    메시지만 보고 판단할 수 있는 불변식이 아니다(계약과 지원 범위의 경계).

    ``threshold`` 는 수익률 %라 음수가 될 수 있어 ``gt=0`` 을 걸지 않는다.
    """

    # None = 미지정. 그때는 규칙표 값이 기본값이다.
    skip_recent_days: int | None = Field(default=None, ge=0)
    selection_mode: Literal["TOP_N", "THRESHOLD", "TOP_PERCENT", "ALL"] | None = None
    top_n: int | None = Field(default=None, gt=0)
    threshold: float | None = None
    top_percent: int | None = Field(default=None, gt=0, le=100)
    allocation_method: Literal["EQUAL", "INVERSE_VOLATILITY"] | None = None
