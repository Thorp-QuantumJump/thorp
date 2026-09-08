"""옛 Kafka Job 계약용 미구현 스캐폴딩.

공개 실행 경로는 이 모듈이 아니라 `worker.engine_runner.NautilusMomentumEngine`이다
(`run(engine_input, on_progress) -> EngineOutput`, 원장·metadata까지 필요하면
`run_with_artifacts`). 시장 데이터 공급자는 엔진 인스턴스에 주입해 여러 실행에서 재사용한다.

여기 남은 `run_backtest`는 아직 구현되지 않은 옛 Job 계약(`worker.models`) 스캐폴딩이며
`worker.main`의 TODO만 이를 가리킨다. 워커 런타임을 pika로 교체할 때 함께 정리한다.
"""

import pandas as pd

from worker.models import BacktestJob, BacktestResult
from worker.strategies.base import Strategy


def run_backtest(job: BacktestJob, strategy: Strategy, prices: pd.DataFrame) -> BacktestResult:
    """단일 Job 백테스트 실행.

    Args:
        job: Kafka에서 받은 백테스트 Job.
        strategy: 실행할 전략 구현체.
        prices: OHLCV 일봉 데이터 (index: date).
    """
    # TODO: 신호 생성 -> 체결 시뮬레이션 -> 지표 계산
    raise NotImplementedError
