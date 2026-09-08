"""백테스트 엔진의 공개 진입점.

`run_engine`은 Jira 101에서 구현한 Nautilus 경로다. `run_backtest`는 이전 Job 계약을 위한
미구현 스캐폴딩이며 운영 Consumer도 아직 어느 경로에도 연결되지 않았다.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING, Any

import pandas as pd

from worker.engine_contracts import EngineInput, EngineOutput, ProgressSnapshot
from worker.models import BacktestJob, BacktestResult
from worker.strategies.base import Strategy

if TYPE_CHECKING:
    from worker.market_data import DataProvider


def run_backtest(job: BacktestJob, strategy: Strategy, prices: pd.DataFrame) -> BacktestResult:
    """단일 Job 백테스트 실행.

    Args:
        job: Kafka에서 받은 백테스트 Job.
        strategy: 실행할 전략 구현체.
        prices: OHLCV 일봉 데이터 (index: date).
    """
    # TODO: 신호 생성 -> 체결 시뮬레이션 -> 지표 계산
    raise NotImplementedError


def run_engine(
    engine_input: EngineInput | dict[str, Any],
    *,
    data_provider: DataProvider | None = None,
    on_snapshot: Callable[[ProgressSnapshot], None] | None = None,
) -> EngineOutput:
    """Nautilus 엔진을 실행한다. 메시지 발행·결과 저장·ACK는 호출자가 담당한다.

    `NautilusMomentumEngine` 인스턴스를 실행마다 새로 만드는 얇은 래퍼다. 공급자를 여러
    실행에서 재사용하거나 전략별 엔진 구현체를 고르려면 클래스를 직접 쓴다.
    """
    from worker.engine_runner import NautilusMomentumEngine

    return NautilusMomentumEngine(data_provider).run(engine_input, on_snapshot)
