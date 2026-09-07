"""백테스트 메시지 계약 픽스처 생성기.

설계 문서 `NautilusTrader 설계(input, output).md`의 EngineInput / 이벤트 / EngineOutput / BenchmarkResult
규격을 따르는 예시 JSON 5개를 만든다. 수치는 시드 고정 랜덤워크 + 단순 현금 회계로 만들어
`equity = cash + positionValue`, 지표 계산 규약, 손익 항등식이 실제로 성립한다.
"""

from __future__ import annotations

import json
import math
import random
import statistics
import sys
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

OUT = Path(sys.argv[1])
OUT.mkdir(parents=True, exist_ok=True)

BACKTEST_ID = "BT-20260907-000001"
EXECUTION_ID = "EXEC-000001"
START, END = date(2025, 1, 1), date(2025, 6, 30)
INITIAL_CASH = Decimal("10000000")
COMMISSION_RATE = Decimal("0.00015")  # 0.015%
SLIPPAGE_RATE = Decimal("0.0005")  # 5 bps
PROGRESS_CHUNK = 20
CENT = Decimal("0.01")
RATIO = Decimal("0.000001")


def money(x: Decimal) -> str:
    return str(x.quantize(CENT, ROUND_HALF_UP))


def ratio(x: Decimal | float | None) -> str | None:
    if x is None:
        return None
    return str(Decimal(x).quantize(RATIO, ROUND_HALF_UP))


def qty(x: int) -> str:
    return str(x)


# 거래일: 평일만 사용 (KRX 휴장일은 반영하지 않는다 — 목 데이터)
days: list[date] = []
d = START
while d <= END:
    if d.weekday() < 5:
        days.append(d)
    d += timedelta(days=1)
N = len(days)

# 가격 경로 (시드 고정 랜덤워크, 호가단위 100원 반올림)
rng = random.Random(42)


def walk(start: int, vol: float, drift: float) -> list[Decimal]:
    p = float(start)
    out = []
    for _ in range(N):
        p *= math.exp(rng.gauss(drift, vol))
        out.append(Decimal(int(round(p / 100.0)) * 100))
    return out


prices = {
    "005930.XKRX": walk(54000, 0.016, 0.0006),
    "000660.XKRX": walk(198000, 0.024, -0.0004),
}
bench_prices = walk(32000, 0.011, 0.0004)

# 거래 시나리오: (종목, 진입 인덱스, 청산 인덱스|None, 수량)
scenario = [
    ("005930.XKRX", 15, 50, 90),
    ("000660.XKRX", 26, 66, 24),
    ("005930.XKRX", 95, None, 90),
]

cash = INITIAL_CASH
holdings: dict[str, int] = {}
avg_entry: dict[str, Decimal] = {}
trades: list[dict] = []
open_trades: dict[int, dict] = {}
series: list[dict] = []
order_count = fill_count = 0
total_commission = Decimal(0)
total_slippage = Decimal(0)
peak = None
prev_equity = None


def ts(day: date) -> str:
    # KRX 09:00 KST = 00:00Z
    return f"{day.isoformat()}T00:00:00Z"


for i, day in enumerate(days):
    changed = i == 0 or i == N - 1
    # 진입
    for t_idx, (inst, ei, xi, q) in enumerate(scenario):
        if ei == i:
            px = prices[inst][i]
            notional = px * q
            comm = (notional * COMMISSION_RATE).quantize(CENT, ROUND_HALF_UP)
            slip = (notional * SLIPPAGE_RATE).quantize(CENT, ROUND_HALF_UP)
            cash -= notional + comm + slip
            holdings[inst] = holdings.get(inst, 0) + q
            avg_entry[inst] = px
            order_count += 1
            fill_count += 1
            total_commission += comm
            total_slippage += slip
            open_trades[t_idx] = {
                "tradeId": f"TRD-{t_idx + 1:06d}",
                "instrumentId": inst,
                "side": "LONG",
                "status": "OPEN",
                "entryTime": ts(day),
                "exitTime": None,
                "entryPrice": money(px),
                "exitPrice": None,
                "quantity": qty(q),
                "grossPnl": None,
                "commission": comm,
                "slippageCost": slip,
                "realizedPnl": None,
                "returnRate": None,
                "_entry_notional": notional,
                "_entry_px": px,
            }
            changed = True
        if xi == i:
            px = prices[inst][i]
            notional = px * q
            comm = (notional * COMMISSION_RATE).quantize(CENT, ROUND_HALF_UP)
            slip = (notional * SLIPPAGE_RATE).quantize(CENT, ROUND_HALF_UP)
            cash += notional - comm - slip
            holdings[inst] -= q
            if holdings[inst] == 0:
                del holdings[inst]
                del avg_entry[inst]
            order_count += 1
            fill_count += 1
            total_commission += comm
            total_slippage += slip
            t = open_trades[t_idx]
            gross = (px - t["_entry_px"]) * q
            t["commission"] += comm
            t["slippageCost"] += slip
            realized = gross - t["commission"] - t["slippageCost"]
            t.update(
                status="CLOSED",
                exitTime=ts(day),
                exitPrice=money(px),
                grossPnl=money(gross),
                realizedPnl=money(realized),
                returnRate=ratio(realized / t["_entry_notional"]),
            )
            changed = True

    position_value = sum((prices[k][i] * v for k, v in holdings.items()), Decimal(0))
    equity = cash + position_value
    peak = equity if peak is None else max(peak, equity)
    daily_ret = Decimal(0) if prev_equity is None else equity / prev_equity - 1
    snap = {
        "date": day.isoformat(),
        "equity": money(equity),
        "cash": money(cash),
        "positionValue": money(position_value),
        "dailyReturn": ratio(daily_ret),
        "cumulativeReturn": ratio(equity / INITIAL_CASH - 1),
        "drawdown": ratio(equity / peak - 1),
    }
    if changed:
        snap["positions"] = [
            {
                "instrumentId": k,
                "quantity": qty(v),
                "marketValue": money(prices[k][i] * v),
                "weight": ratio((prices[k][i] * v) / equity),
                "unrealizedPnl": money((prices[k][i] - avg_entry[k]) * v),
            }
            for k, v in holdings.items()
        ]
    series.append(snap)
    prev_equity = equity

for t_idx in sorted(open_trades):
    t = open_trades[t_idx]
    t["commission"] = money(t["commission"])
    t["slippageCost"] = money(t["slippageCost"])
    trades.append({k: v for k, v in t.items() if not k.startswith("_")})

# 지표 (설계 "지표 계산 규약")
equities = [Decimal(s["equity"]) for s in series]
initial_eq, final_eq = equities[0], equities[-1]
daily = [float(equities[i] / equities[i - 1] - 1) for i in range(1, N)]
daily = [0.0] + daily
elapsed_days = (days[-1] - days[0]).days
total_return = final_eq / initial_eq - 1
cagr = Decimal(math.pow(float(final_eq / initial_eq), 365.0 / elapsed_days) - 1)
vol = statistics.stdev(daily) * math.sqrt(252)
sharpe = (statistics.mean(daily) * 252 - 0.0) / vol if vol else None
mdd = min(Decimal(s["drawdown"]) for s in series)
closed = [t for t in trades if t["status"] == "CLOSED"]
wins = [t for t in closed if Decimal(t["realizedPnl"]) > 0]
win_rate = Decimal(len(wins)) / Decimal(len(closed)) if closed else None

performance = {
    "initialEquity": money(initial_eq),
    "finalEquity": money(final_eq),
    "currency": "KRW",
    "totalReturn": ratio(total_return),
    "cagr": ratio(cagr),
    "maxDrawdown": ratio(mdd),
    "sharpeRatio": ratio(sharpe),
    "volatility": ratio(vol),
    "tradeCount": len(closed),
    "winRate": ratio(win_rate),
}

# 벤치마크: initialCash를 시작일 종가에 전액 매수한 Buy & Hold (수수료·슬리피지 없음)
b0 = bench_prices[0]
bench_series = []
for i, day in enumerate(days):
    eq = INITIAL_CASH * bench_prices[i] / b0
    bench_series.append(
        {"date": day.isoformat(), "equity": money(eq), "cumulativeReturn": ratio(eq / INITIAL_CASH - 1)}
    )
benchmark = {
    "instrumentId": "069500.XKRX",
    "totalReturn": ratio(bench_prices[-1] / b0 - 1),
    "timeseries": bench_series,
}

# ---------- EngineInput ----------
engine_input = {
    "run": {
        "backtestId": BACKTEST_ID,
        "start": START.isoformat(),
        "end": END.isoformat(),
        "randomSeed": 42,
    },
    "universe": {"instrumentIds": ["005930.XKRX", "000660.XKRX"]},
    "marketData": {
        "datasetId": "KRX_DAILY_V1",
        "interval": "1D",
        "adjustment": "SPLIT_ADJUSTED",
        "priceType": "LAST",
    },
    "strategy": {
        "strategyId": "MA_CROSSOVER",
        "version": "1.0.0",
        # threshold(소수)는 자유 형식 파라미터의 숫자가 문자열로 변질되지 않는지 round-trip으로 확인하는 용도
        "parameters": {"shortWindow": 20, "longWindow": 60, "threshold": 0.05},
    },
    "portfolio": {
        "initialCash": str(INITIAL_CASH),
        "currency": "KRW",
        "positionSizing": {"type": "STRATEGY_DEFINED", "maxPositionPct": "0.50"},
        "shortSelling": False,
        "leverage": "1.0",
    },
    "execution": {
        "signalTiming": "BAR_CLOSE",
        "executionTiming": "NEXT_BAR_OPEN",
        "orderTypes": ["MARKET"],
        "commission": {"model": "BROKER", "broker": "KIWOOM", "version": "2026-09"},
        "slippage": {"model": "FIXED_BPS", "bps": 5},
        "partialFill": False,
        "liquidity": {"model": "UNLIMITED"},
        "tradingHalt": "WAIT_UNTIL_RESUME",
        "delisting": "CAUSE_BASED",
    },
    "benchmark": {"instrumentId": "069500.XKRX"},
}


def envelope(event_id: str, type_: str, sequence: int, occurred_at: str, payload: dict) -> dict:
    return {
        "schemaVersion": 1,
        "eventId": event_id,
        "backtestId": BACKTEST_ID,
        "executionId": EXECUTION_ID,
        "type": type_,
        "sequence": sequence,
        "occurredAt": occurred_at,
        "payload": payload,
    }


started = envelope(
    "EVT-000001", "STARTED", 0, "2026-09-07T01:00:00Z", {"startedAt": "2026-09-07T01:00:00Z"}
)

progress = envelope(
    "EVT-000002",
    "PROGRESS",
    1,
    "2026-09-07T01:00:01Z",
    {
        "completedTradingDays": PROGRESS_CHUNK,
        "totalTradingDays": N,
        "portfolioSeries": [
            {k: s[k] for k in ("date", "equity", "cumulativeReturn", "drawdown")}
            for s in series[:PROGRESS_CHUNK]
        ],
    },
)

progress_events = math.ceil(N / PROGRESS_CHUNK)
finished_completed = envelope(
    f"EVT-{progress_events + 2:06d}",
    "FINISHED",
    progress_events + 1,
    "2026-09-07T01:00:04Z",
    {
        "engineOutput": {
            "run": {
                "backtestId": BACKTEST_ID,
                "status": "COMPLETED",
                "startedAt": "2026-09-07T01:00:00Z",
                "finishedAt": "2026-09-07T01:00:04Z",
                "error": None,
            },
            "performance": performance,
            "portfolioSeries": series,
            "trades": trades,
            "execution": {
                "orderCount": order_count,
                "fillCount": fill_count,
                "rejectedOrderCount": 0,
                "totalCommission": money(total_commission),
                "totalSlippageCost": money(total_slippage),
            },
            "diagnostics": {
                "warnings": [
                    {
                        "date": days[40].isoformat(),
                        "instrumentId": "000660.XKRX",
                        "code": "TRADING_HALTED",
                        "message": "Order execution was delayed until trading resumed.",
                    }
                ]
            },
        },
        "benchmark": benchmark,
    },
)

finished_failed = envelope(
    "EVT-000002",
    "FINISHED",
    1,
    "2026-09-07T01:00:01Z",
    {
        "engineOutput": {
            "run": {
                "backtestId": BACKTEST_ID,
                "status": "FAILED",
                "startedAt": "2026-09-07T01:00:00Z",
                "finishedAt": "2026-09-07T01:00:01Z",
                "error": {
                    "code": "NO_MARKET_DATA",
                    "message": "Market data is unavailable for 000660.XKRX in the requested period.",
                },
            },
            "performance": None,
            "portfolioSeries": [],
            "trades": [],
            "execution": None,
            "diagnostics": {"warnings": []},
        },
        "benchmark": None,
    },
)

files = {
    "engine-input.json": engine_input,
    "event-started.json": started,
    "event-progress.json": progress,
    "event-finished-completed.json": finished_completed,
    "event-finished-failed.json": finished_failed,
}


def strip_none(obj):  # noqa: ANN001, ANN201 - 재귀 JSON 트리
    """계약 규칙: null 값 필드는 키 자체를 쓰지 않는다 (수신 측은 '키 없음 == null')."""
    if isinstance(obj, dict):
        return {k: strip_none(v) for k, v in obj.items() if v is not None}
    if isinstance(obj, list):
        return [strip_none(v) for v in obj]
    return obj


for name, obj in files.items():
    text = json.dumps(strip_none(obj), indent=2, ensure_ascii=False) + "\n"
    (OUT / name).write_text(text, encoding="utf-8", newline="\n")

# 요약 출력 (README 작성용)
print(f"tradingDays={N} progressEvents={progress_events} finishedSequence={progress_events + 1}")
print(json.dumps(performance, indent=2))
print("execution:", order_count, fill_count, money(total_commission), money(total_slippage))
print("benchmark totalReturn:", benchmark["totalReturn"])
print("snapshots with positions:", sum(1 for s in series if "positions" in s))
for t in trades:
    print(t["tradeId"], t["instrumentId"], t["status"], t["entryTime"][:10], (t["exitTime"] or "")[:10], t["realizedPnl"])
