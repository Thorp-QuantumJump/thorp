"""Replaceable daily data sources. Fixtures are always identified as test data."""

from dataclasses import dataclass, field
from datetime import timedelta
from pathlib import Path
from typing import Any, Protocol

import numpy as np
import pandas as pd

from worker.contracts.engine_input import EngineInput
from worker.strategies import prepare_strategy


class MarketDataError(ValueError):
    """Unavailable or invalid source data; never silently substitute another symbol."""


@dataclass
class MarketDataset:
    prices: pd.DataFrame
    instrument_id: str
    ticker: str
    source: str
    adjustment: str = "SPLIT_ADJUSTED"
    metadata: dict[str, Any] = field(default_factory=dict)


class DataProvider(Protocol):
    def load(self, request: EngineInput) -> MarketDataset: ...


def validate_prices(prices: pd.DataFrame) -> pd.DataFrame:
    """Reject ambiguous rows and malformed OHLCV instead of fabricating prices."""
    frame = prices.copy()
    if isinstance(frame.columns, pd.MultiIndex):
        raise MarketDataError("Expected single-ticker flat OHLCV columns, received MultiIndex")
    frame.columns = [str(column).lower() for column in frame.columns]
    required = ["open", "high", "low", "close", "volume"]
    if not set(required).issubset(frame.columns) or frame.columns.duplicated().any():
        raise MarketDataError("Missing or duplicate OHLCV columns")
    if frame.empty:
        raise MarketDataError("No market data returned for the requested ticker/period")
    if not isinstance(frame.index, pd.DatetimeIndex) or frame.index.isna().any():
        raise MarketDataError("Expected a valid DatetimeIndex")
    if frame.index.tz is not None:
        frame.index = frame.index.tz_convert("Asia/Seoul").tz_localize(None)
    frame.index = frame.index.normalize()
    if frame.index.has_duplicates or not frame.index.is_monotonic_increasing:
        raise MarketDataError("Trading dates must be unique and ascending")
    try:
        frame = frame[required].apply(pd.to_numeric, errors="raise")
    except (ValueError, TypeError) as exc:
        raise MarketDataError(f"Non-numeric OHLCV: {exc}") from exc
    if not np.isfinite(frame.to_numpy(dtype=float)).all():
        invalid = frame.index[~np.isfinite(frame.to_numpy(dtype=float)).all(axis=1)]
        raise MarketDataError(
            "Missing or non-finite OHLCV on " + ", ".join(day.date().isoformat() for day in invalid)
        )
    # Same precision as the native adjusted-price instrument; remove sub-micro-unit
    # floating error (e.g. High is 1e-11 below Close), never repair material bad prices.
    frame[["open", "high", "low", "close"]] = frame[["open", "high", "low", "close"]].round(6)
    if (frame[["open", "high", "low", "close"]] <= 0).any().any():
        raise MarketDataError("OHLC prices must be positive")
    if (frame.volume < 0).any() or (frame.volume % 1 != 0).any():
        raise MarketDataError("Volume must be a nonnegative integer")
    invalid_range = (frame.high < frame[["open", "low", "close"]].max(axis=1)) | (
        frame.low > frame[["open", "high", "close"]].min(axis=1)
    )
    if invalid_range.any():
        raise MarketDataError(
            "Inconsistent OHLC range on "
            + ", ".join(day.date().isoformat() for day in frame.index[invalid_range])
        )
    return frame


class YFinanceDataProvider:
    def load(self, request: EngineInput) -> MarketDataset:
        import yfinance as yf

        instrument_id = request.universe.instrument_ids[0]
        ticker = instrument_id.split(".")[0] + ".KS"
        # 준비 기간은 전략별로 다르므로 레지스트리가 선언한 값을 쓴다.
        lookback = prepare_strategy(request.strategy).required_history
        fetch_start = request.run.start - timedelta(days=max(365, lookback * 3))
        try:
            security = yf.Ticker(ticker)
            prices = security.history(
                start=fetch_start.isoformat(),
                end=(request.run.end + timedelta(days=1)).isoformat(),
                interval="1d",
                auto_adjust=False,
                actions=False,
                repair=False,
                keepna=True,
                raise_errors=True,
            )
            identity = security.get_history_metadata()
            if (
                identity.get("symbol") != ticker
                or identity.get("currency") != "KRW"
                or identity.get("instrumentType") != "EQUITY"
                or identity.get("exchangeName") not in {"KSC", "KOE"}
            ):
                raise MarketDataError(f"Unexpected identity for {ticker}: {identity}")
            prices = validate_prices(prices)
        except Exception as exc:
            raise MarketDataError(
                f"yfinance {yf.__version__} failed for {ticker}: {type(exc).__name__}: {exc}. "
                "Retry or explicitly choose another KOSPI ticker with --ticker; no fallback used."
            ) from exc
        return MarketDataset(
            prices=prices,
            instrument_id=instrument_id,
            ticker=ticker,
            source="yfinance",
            metadata={
                "yfinanceVersion": yf.__version__,
                "symbol": identity["symbol"],
                "instrumentName": identity.get("longName") or identity.get("shortName"),
                "exchange": identity["exchangeName"],
                "currency": identity["currency"],
                "fetchStart": fetch_start.isoformat(),
                "autoAdjust": False,
                "endExclusive": (request.run.end + timedelta(days=1)).isoformat(),
            },
        )


class CsvDataProvider:
    """Explicit offline fixture source; never advertised as historical market prices."""

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)

    def load(self, request: EngineInput) -> MarketDataset:
        prices = pd.read_csv(self.path, index_col="date", parse_dates=["date"])
        instrument_id = request.universe.instrument_ids[0]
        return MarketDataset(
            prices=validate_prices(prices),
            instrument_id=instrument_id,
            ticker=instrument_id.split(".")[0] + ".KS",
            source="FIXED_TEST_DATA",
            metadata={"fixture": self.path.name, "notRealMarketData": True},
        )
