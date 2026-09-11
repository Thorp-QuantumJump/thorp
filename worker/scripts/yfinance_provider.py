"""개발용 yfinance 자료 공급자. **실행 경로에 두지 않는다**(spec 004 R-09, 원칙 X).

이 파일이 ``worker/src/`` 밖에 있는 이유: 배포되는 워커 패키지가 이 모듈을 import 할 수
없어야 브로커가 넘긴 작업이 실수로 외부 시세 API를 때리는 일이 구조적으로 막힌다. 운영
자료는 001이 적재한 PostgreSQL(``PostgresMarketDataProvider``)에서만 읽는다.

수기 확인·데모로 쓸 때만 ``worker/scripts`` 를 ``sys.path`` 에 넣고 import 한다
(``pyproject.toml`` 의 pytest ``pythonpath``, ``worker.demo`` 의 ``_load_yfinance_provider``).
"""

from datetime import timedelta

from worker.contracts.engine_input import EngineInput
from worker.market_data import MarketDataError, MarketDataset, validate_prices
from worker.strategies import prepare_strategy


class YFinanceDataProvider:
    def load(self, request: EngineInput) -> MarketDataset:
        import yfinance as yf

        instrument_id = request.universe.instrument_ids[0]
        ticker = instrument_id.split(".")[0] + ".KS"
        # 준비 기간은 전략별로 다르므로 레지스트리가 선언한 값을 쓴다.
        lookback = prepare_strategy(request.allocation).required_history
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
