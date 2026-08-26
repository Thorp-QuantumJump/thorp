"""백테스트 엔진 (스캐폴딩).

전략은 '시장 데이터 -> 매매 신호'만 출력하고,
엔진이 체결/자산 계산과 성과지표(수익률, MDD, Sharpe) 산출을 담당한다.
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
