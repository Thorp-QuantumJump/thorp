"""시세 데이터 로딩.

운영/측정에서는 data 파트가 만든 Parquet 스냅샷을 읽는다.
data 파이프라인이 아직 스캐폴딩이므로, 배선 확인용 합성 데이터 생성기를 함께 둔다.
합성 데이터로 잰 수치는 참고용이며 공식 측정치로 쓰지 않는다.
"""

from collections.abc import Mapping, Sequence
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

#: 표준 컬럼 (data/CLAUDE.md와 동일)
OHLCV_COLUMNS = ["open", "high", "low", "close", "volume"]


def _normalize(frame: pd.DataFrame, start: date, end: date) -> pd.DataFrame:
    """index를 date로 맞추고 기간으로 자른다."""
    if "date" in frame.columns:
        frame = frame.set_index("date")
    frame.index = pd.to_datetime(frame.index)
    frame = frame[~frame.index.duplicated(keep="last")].sort_index()
    frame = frame.loc[str(start) : str(end)]

    missing = [c for c in OHLCV_COLUMNS if c not in frame.columns]
    if missing:
        raise ValueError(f"표준 컬럼 누락: {missing} (필요: {OHLCV_COLUMNS})")
    return frame[OHLCV_COLUMNS]


def load_prices(
    data_path: str | Path, tickers: Sequence[str], start: date, end: date
) -> dict[str, pd.DataFrame]:
    """Parquet 스냅샷에서 종목별 일봉을 읽는다.

    지원 레이아웃 (순서대로 시도):
        <root>/ticker=<코드>/...  (hive 파티션)
        <root>/<코드>.parquet
    """
    root = Path(data_path)
    if not root.exists():
        raise FileNotFoundError(f"시세 데이터 경로가 없다: {root}")

    prices: dict[str, pd.DataFrame] = {}
    for ticker in tickers:
        hive_dir = root / f"ticker={ticker}"
        flat_file = root / f"{ticker}.parquet"
        if hive_dir.is_dir():
            frame = pd.read_parquet(hive_dir)
        elif flat_file.is_file():
            frame = pd.read_parquet(flat_file)
        else:
            raise FileNotFoundError(
                f"{ticker} 데이터를 찾지 못했다. 시도한 경로: {hive_dir}, {flat_file}"
            )
        prices[ticker] = _normalize(frame, start, end)
    return prices


def generate_synthetic_prices(
    tickers: Sequence[str], start: date, end: date, seed: int = 20260904
) -> dict[str, pd.DataFrame]:
    """배선 확인용 합성 일봉 생성. 시드 고정이라 실행할 때마다 동일하다."""
    index = pd.bdate_range(start=start, end=end)
    if len(index) == 0:
        raise ValueError(f"거래일이 0일이다: {start} ~ {end}")

    prices: dict[str, pd.DataFrame] = {}
    for offset, ticker in enumerate(tickers):
        rng = np.random.default_rng(seed + offset)
        # 연 8% 드리프트 / 연 25% 변동성 근사의 기하 브라운 운동
        daily_return = rng.normal(0.08 / 252, 0.25 / np.sqrt(252), len(index))
        close = 50_000.0 * np.exp(np.cumsum(daily_return))
        prices[ticker] = pd.DataFrame(
            {
                "open": close * 0.998,
                "high": close * 1.012,
                "low": close * 0.988,
                "close": close,
                "volume": rng.integers(100_000, 2_000_000, len(index)).astype("float64"),
            },
            index=index,
        )
    return prices


def count_rows(prices_by_ticker: Mapping[str, pd.DataFrame]) -> int:
    """전체 입력 행 수 (종목 수 × 거래일 수). 측정 메타데이터에 기록한다."""
    return int(sum(len(frame) for frame in prices_by_ticker.values()))
