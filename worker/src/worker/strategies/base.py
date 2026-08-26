"""전략 공통 인터페이스.

모든 전략은 동일한 시장 데이터를 입력받아 매매 신호를 반환한다.
    시장 데이터 -> 전략 -> 매매 신호 -> 백테스트 엔진
"""

from abc import ABC, abstractmethod

import pandas as pd


class Strategy(ABC):
    """전략 베이스 클래스. 파라미터는 생성자에서 받는다."""

    name: str = "base"

    @abstractmethod
    def generate_signals(self, prices: pd.DataFrame) -> pd.Series:
        """일별 목표 포지션(예: 1=매수, 0=현금)을 반환한다."""
        raise NotImplementedError
