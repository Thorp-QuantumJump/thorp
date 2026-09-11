"""개발용 yfinance 공급자 회귀(spec 004 R-09).

이 공급자는 ``worker/scripts`` 에 있고 배포 패키지에 들어가지 않는다. 그래도 테스트는
남긴다 — 데모로 실제 시세를 받을 때 계약이 요구하는 조정 방식(``auto_adjust=False``)과
종목 동일성 확인이 깨지면 잘못된 가격으로 수기 확인을 하게 된다.
"""

from pathlib import Path
from typing import Any

import pandas as pd
import pytest
from conftest import engine_input_body

from worker.contracts.engine_input import EngineInput
from worker.market_data import CsvDataProvider, MarketDataError

FIXTURE = Path(__file__).parents[1] / "examples" / "momentum_fixture.csv"


def request(**overrides: Any) -> EngineInput:
    return EngineInput.model_validate(engine_input_body(**overrides))


def test_yfinance_identity_and_exclusive_end(monkeypatch: pytest.MonkeyPatch) -> None:
    import yfinance
    from yfinance_provider import YFinanceDataProvider

    calls = []

    class FakeTicker:
        def __init__(self, ticker: str) -> None:
            calls.append(ticker)

        def history(self, **kwargs: Any) -> pd.DataFrame:
            calls.append(kwargs)
            return CsvDataProvider(FIXTURE).load(request()).prices

        def get_history_metadata(self) -> dict:
            return {
                "symbol": "000660.KS",
                "currency": "KRW",
                "instrumentType": "EQUITY",
                "exchangeName": "KSC",
                "longName": "SK hynix Inc.",
            }

    monkeypatch.setattr(yfinance, "Ticker", FakeTicker)
    dataset = YFinanceDataProvider().load(request())
    assert calls[0] == "000660.KS"
    assert calls[1]["end"] == "2025-01-15"
    # 계약 고정값 marketData.adjustment=SPLIT_ADJUSTED = 액면분할만 반영한 원본 시계열.
    # auto_adjust=True 로 두면 배당까지 반영되어 계약과 어긋난다.
    assert calls[1]["auto_adjust"] is False
    assert calls[1]["keepna"] is True
    assert dataset.adjustment == "SPLIT_ADJUSTED"
    assert dataset.metadata["autoAdjust"] is False
    assert dataset.metadata["instrumentName"] == "SK hynix Inc."
    monkeypatch.setattr(FakeTicker, "get_history_metadata", lambda _: {"symbol": "WRONG"})
    with pytest.raises(MarketDataError, match="Unexpected identity"):
        YFinanceDataProvider().load(request())
