from copy import deepcopy
from pathlib import Path
from typing import Any

# 백엔드 테스트와 같은 파일을 읽는다 (테스트 리소스로 복사하지 않는다 — 복사하면 어긋난다).
FIXTURE_DIR = (
    Path(__file__).resolve().parents[2] / "docs" / "specs" / "backtest-messaging" / "examples"
)

# 계약 EngineInput은 여덟 블록이 모두 필수이므로 부분 dict를 넘길 수 없다. 회귀 기준 실행 조건
# (고정 데이터, lookbackDays=2, 초기자금 1000)에 계약 §4.1 고정 실행 정책을 채운 전체 본문을
# 기준으로 두고, 테스트는 섹션 단위로 덮어쓴다.
# 계약 구조 검증용 픽스처(examples/engine-input.json: MA_CROSSOVER·2종목)와 달리
# 이 본문은 엔진이 실제로 실행할 수 있는 요청이다.
ENGINE_INPUT_BODY: dict[str, Any] = {
    "run": {
        "runId": "TEST",
        "start": "2025-01-06",
        "end": "2025-01-14",
        "randomSeed": 42,
        "scenario": "FULL_PERIOD",
    },
    "universe": {"items": [{"instrumentId": "000660.XKRX"}]},
    "marketData": {
        "datasetId": "YFINANCE_DAILY",
        "interval": "1D",
        "adjustment": "SPLIT_ADJUSTED",
        "priceType": "LAST",
        "fillPrice": "RAW",
        "signalPrice": "ADJUSTED",
        "distributions": "CASH_ON_PAY_DATE",
    },
    "allocation": {
        "strategyId": "MOMENTUM",
        "rulesVersion": "1.0.0",
        "parameters": {"lookbackDays": 2},
    },
    "portfolio": {
        "initialCash": "1000",
        "currency": "KRW",
        "positionSizing": {"type": "STRATEGY_DEFINED", "maxPositionPct": "1"},
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
    "evaluation": {"liquidationAtEnd": False},
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
