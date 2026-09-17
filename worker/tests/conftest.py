from copy import deepcopy
from pathlib import Path
from typing import Any

# 백엔드 테스트와 같은 파일을 읽는다 (테스트 리소스로 복사하지 않는다 — 복사하면 어긋난다).
FIXTURE_DIR = (
    Path(__file__).resolve().parents[2] / "docs" / "specs" / "backtest-messaging" / "examples"
)

#: 3종목 24개월 합성 픽스처(spec 004 T004). 결과는 항상 픽스처로 식별된다.
THREE_ETF_DIR = Path(__file__).resolve().parents[1] / "examples" / "three_etf_24m"
THREE_ETF_DATASET = "FIXTURE-three_etf_24m"

SEMICONDUCTOR = "091160.XKRX"  # 2015-01-02 상장
BATTERY = "305720.XKRX"  # 2018-09-12 상장
BIO = "244580.XKRX"  # 2023-07-03 상장, 2024-03-04 2:1 분할

# 계약 EngineInput은 여덟 블록이 모두 필수이므로 부분 dict를 넘길 수 없다. 엔진이 실제로 실행할 수
# 있는 요청(두 종목 고정 비중, 7거래일)을 기준으로 두고 테스트는 섹션 단위로 덮어쓴다.
# 계약 구조 검증용 픽스처(examples/engine-input.json: MA_CROSSOVER·2종목)와는 다른 본문이다.
ENGINE_INPUT_BODY: dict[str, Any] = {
    "run": {
        "runId": "TEST",
        "start": "2023-01-02",
        "end": "2023-01-10",
        "randomSeed": 42,
        "scenario": "FULL_PERIOD",
    },
    "universe": {
        "mappingVersion": "SECTOR-ETF-2026-09",
        "items": [
            {"sectorId": "SEMICONDUCTOR", "instrumentId": SEMICONDUCTOR, "targetWeight": "0.5"},
            {"sectorId": "BATTERY", "instrumentId": BATTERY, "targetWeight": "0.5"},
        ],
    },
    "marketData": {
        "datasetId": "FIXTURE-three_etf_24m",
        "dataVersion": THREE_ETF_DATASET,
        "interval": "1D",
        "adjustment": "SPLIT_ADJUSTED",
        "priceType": "LAST",
        "fillPrice": "RAW",
        "signalPrice": "ADJUSTED",
        "distributions": "CASH_ON_PAY_DATE",
    },
    "allocation": {
        "strategyId": "FIXED_WEIGHT",
        "rulesVersion": "1",
        "rules": {"investedFraction": "1.0000"},
        "parameters": {"rebalance": "MONTH_END"},
    },
    "portfolio": {
        "initialCash": "10000000",
        "currency": "KRW",
        "positionSizing": {"type": "STRATEGY_DEFINED"},
        "shortSelling": False,
        "leverage": "1",
        "integerLots": True,
    },
    "execution": {
        "signalTiming": "BAR_CLOSE",
        "executionTiming": "NEXT_BAR_OPEN",
        "orderTypes": ["MARKET"],
        "costProfile": {
            "version": "PROVISIONAL-DEMO-1",
            "commissionBps": "0",
            "spreadBps": "0",
            "slippageBps": "5",
            "taxBps": "0",
            "provisional": True,
        },
        "partialFill": False,
        "liquidity": {"model": "UNLIMITED"},
        "tradingHalt": "DEFER_TO_NEXT_TRADABLE",
        "delisting": "CAUSE_BASED",
    },
    "evaluation": {"warmupTradingDays": 0, "liquidationAtEnd": False},
    "progress": {"batchTradingDays": 20},
}


def engine_input_body(**overrides: Any) -> dict[str, Any]:
    """``ENGINE_INPUT_BODY`` 에 최상위 섹션 단위로 덮어쓴 전체 EngineInput 본문."""
    values = deepcopy(ENGINE_INPUT_BODY)
    for section, override in overrides.items():
        current = values.get(section)
        if isinstance(current, dict) and isinstance(override, dict):
            values[section] = {**current, **override}
        else:
            values[section] = override
    return values
