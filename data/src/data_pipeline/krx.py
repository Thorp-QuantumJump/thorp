"""KRX 시세 수집 모듈 (스캐폴딩).

KRX OPEN API로 일봉 OHLCV·거래대금을 받아 Parquet/DB에 저장한다(research.md 결정 1).
Parquet(자산/연도 파티션) 포맷을 유지하면 추후 Spark 도입 시 그대로 읽을 수 있다.
"""

from datetime import date

import pandas as pd


def fetch_ohlcv(ticker: str, start: date, end: date) -> pd.DataFrame:
    """단일 종목 일봉 OHLCV 조회.

    TODO: KRX OPEN API(ETF 일별매매정보) 연동,
    컬럼 표준화(date/open/high/low/close/volume/value)
    """
    raise NotImplementedError
