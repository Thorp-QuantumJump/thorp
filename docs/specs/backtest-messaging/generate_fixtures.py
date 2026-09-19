"""백테스트 메시지 계약 v2 픽스처 생성기.

`examples/` 아래 JSON 8개를 만든다. 수치는 시드 고정 랜덤워크 + 단순 현금 회계로 만들어
`equity = cash + positionValue`, 지표 계산 규약, 손익 항등식이 실제로 성립한다.

- `engine-input.json`: 백엔드 `EngineInputAssembler`가 조립하는 결과와 **정확히 같아야 한다**
  (`EngineInputFixtureTest`). 그 테스트가 조립하는 실행은 접수 경로를 거치지 않은 행이라, 그 경로에서
  생략되는 필드(`universe.mappingVersion`·`items[].sectorId`, `allocation.rules`,
  `evaluation.splitVersion`)를 여기서도 비우고 비용은 고정값(데모)을 싣는다.
- `engine-input-target-risk.json`: 계약 구조 예시. PD-18 목표 위험 맞춤 시나리오와 003 연동 뒤의
  전체 필드를 보여준다. 비용은 기본 비용(PD-07 확정값)이다. 조립기 검증 대상은 아니다.
- `event-*.json`: STARTED / PROGRESS / FINISHED(COMPLETED · FAILED · CANCELLED).
- `control-cancel.json`: 백엔드 → 워커 제어 메시지.
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

SCHEMA_VERSION = 2
RUN_ID = "BT-20260907-000001"  # T003(요청/실행 분리) 전까지 run 식별자 값은 backtestId와 같다
EXECUTION_ID = "EXEC-000001"
START, END = date(2025, 1, 1), date(2025, 6, 30)
INITIAL_CASH = Decimal("10000000")
COMMISSION_RATE = Decimal("0.00015")  # 0.015%
SLIPPAGE_RATE = Decimal("0.0005")  # 5 bps
PROGRESS_CHUNK = 20
CANCEL_AFTER_DAYS = 60  # CANCELLED 픽스처: 60거래일 처리 뒤 취소
CENT = Decimal("0.01")
RATIO = Decimal("0.000001")
ENGINE_VERSION = "nautilus_trader 1.219.0"
WORKER_VERSION = "2.0.0"
# engine-input.json 은 비용 버전이 기록되지 않은 실행의 조립 결과라 ExecutionPolicy 고정값(데모)을 싣는다.
COST_PROFILE = {
    "version": "PROVISIONAL-DEMO-1",
    "commissionBps": "0",
    "spreadBps": "0",
    "slippageBps": "5",
    "taxBps": "0",
    "provisional": True,
}
# 접수 경로를 거친 요청의 기본 비용(PD-07 확정, T021). 등록표 행을 그대로 실으므로 NUMERIC(8,4) 네 자리다.
CONFIRMED_COST_PROFILE = {
    "version": "KR-ETF-2026-09",
    "commissionBps": "1.5000",
    "spreadBps": "3.0000",
    "slippageBps": "5.0000",
    "taxBps": "0.0000",
    "provisional": False,
}
# queue·role 은 어느 큐에서 어느 역할의 워커가 집었나(spec 004 T012·T015). STARTED·FINISHED 모두 싣는다.
SERVED = {"queue": "backtest.jobs", "role": "single"}
# dataRows·dataBytes 는 읽은 자료의 양(데이터 원가, T019). 자료를 읽지 못한 실패 예시에는 없다.
DATA_USAGE = {"dataRows": 258, "dataBytes": 61920}
RESOURCE = {
    "peakRssMb": "512.5",
    "cpuSeconds": "3.210",
    "wallSeconds": "4.000",
    **SERVED,
    **DATA_USAGE,
}


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


INSTRUMENTS = ["005930.XKRX", "000660.XKRX"]
prices = {
    "005930.XKRX": walk(54000, 0.016, 0.0006),
    "000660.XKRX": walk(198000, 0.024, -0.0004),
}

# 거래 시나리오: (종목, 진입 인덱스, 청산 인덱스|None, 수량)
scenario = [
    ("005930.XKRX", 15, 50, 90),
    ("000660.XKRX", 26, 66, 24),
    ("005930.XKRX", 95, None, 90),
]
# 분배금: (종목, 지급일 인덱스, 주당 금액). 보유 중이면 현금 유입 1회 (FR-013).
# 완료 픽스처에는 넣지 않는다 — API 예시(`docs/specs/backtest-api/examples`)가 v1 완료 수치에서
# 파생됐고 `BacktestResultApiIT`가 그 예시와 응답을 대조한다. cashFlows 예시는 취소 픽스처에만 있다.
DISTRIBUTIONS_FOR_CANCELLED = [("005930.XKRX", 30, Decimal("1000"))]
# 거래 정지: 이 날 000660 주문이 다음 거래 가능일로 이월됐다는 예외 기록 (diagnostics와 짝)
HALT_DAY = 40


def ts(day: date) -> str:
    # KRX 09:00 KST = 00:00Z
    return f"{day.isoformat()}T00:00:00Z"


def simulate(until: int, distributions: list[tuple[str, int, Decimal]]) -> dict:
    """`days[:until]` 구간을 회계 처리한 결과. 완료 픽스처는 until=N, 취소 픽스처는 부분 구간."""
    cash = INITIAL_CASH
    holdings: dict[str, int] = {}
    avg_entry: dict[str, Decimal] = {}
    open_trades: dict[int, dict] = {}
    series: list[dict] = []
    weights: list[dict] = []
    order_count = fill_count = 0
    total_commission = Decimal(0)
    total_slippage = Decimal(0)
    peak = None
    prev_equity = None

    for i, day in enumerate(days[:until]):
        changed = i == 0 or i == until - 1
        cash_flows: list[dict] = []
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
                    "reason": "SIGNAL",
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
        for inst, pay_idx, per_share in distributions:
            if pay_idx == i and holdings.get(inst):
                amount = per_share * holdings[inst]
                cash += amount
                cash_flows.append(
                    {"type": "DISTRIBUTION", "instrumentId": inst, "amount": money(amount)}
                )

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
            actual = {k: ratio((prices[k][i] * v) / equity) for k, v in holdings.items()}
            weights.append(
                {
                    "date": day.isoformat(),
                    "target": actual,  # 신호 기반 데모: 목표 = 실제 (정수 수량 오차 없음)
                    "actual": actual,
                    "weightError": "0.000000",
                }
            )
        if cash_flows:
            snap["cashFlows"] = cash_flows
        series.append(snap)
        prev_equity = equity

    trades: list[dict] = []
    for t_idx in sorted(open_trades):
        t = open_trades[t_idx]
        t["commission"] = money(t["commission"])
        t["slippageCost"] = money(t["slippageCost"])
        trades.append({k: v for k, v in t.items() if not k.startswith("_")})

    # 지표 (설계 "지표 계산 규약")
    equities = [Decimal(s["equity"]) for s in series]
    initial_eq, final_eq = equities[0], equities[-1]
    daily = [float(equities[i] / equities[i - 1] - 1) for i in range(1, len(series))]
    daily = [0.0] + daily
    elapsed_days = (days[until - 1] - days[0]).days
    total_return = final_eq / initial_eq - 1
    cagr = Decimal(math.pow(float(final_eq / initial_eq), 365.0 / elapsed_days) - 1)
    vol = statistics.stdev(daily) * math.sqrt(252)
    sharpe = (statistics.mean(daily) * 252 - 0.0) / vol if vol else None
    mdd = min(Decimal(s["drawdown"]) for s in series)
    closed = [t for t in trades if t["status"] == "CLOSED"]
    wins = [t for t in closed if Decimal(t["realizedPnl"]) > 0]
    win_rate = Decimal(len(wins)) / Decimal(len(closed)) if closed else None
    total_cost = total_commission + total_slippage

    performance = {
        "initialEquity": money(initial_eq),
        "finalEquity": money(final_eq),
        "currency": "KRW",
        "totalReturn": ratio(total_return),
        "netTotalReturn": ratio(total_return),  # 비용은 자산에 이미 차감돼 있다
        "cagr": ratio(cagr),
        "maxDrawdown": ratio(mdd),
        "sharpeRatio": ratio(sharpe),
        "volatility": ratio(vol),
        "tradeCount": len(closed),
        "winRate": ratio(win_rate),
        "totalTradingCost": money(total_cost),
    }

    # 종료 시 청산 결과: 남은 보유를 마지막 가격에 전량 매도 (수수료·슬리피지 부담)
    last = until - 1
    liquidation_cost = Decimal(0)
    for k, v in holdings.items():
        notional = prices[k][last] * v
        liquidation_cost += (notional * (COMMISSION_RATE + SLIPPAGE_RATE)).quantize(
            CENT, ROUND_HALF_UP
        )
    liquidated_eq = final_eq - liquidation_cost
    liquidation = {
        "markToMarket": {"finalEquity": money(final_eq), "totalReturn": ratio(total_return)},
        "liquidated": {
            "finalEquity": money(liquidated_eq),
            "totalReturn": ratio(liquidated_eq / initial_eq - 1),
            "liquidationCost": money(liquidation_cost),
        },
    }

    # 분석 지표: 회복 기간(최대 낙폭 저점 → 이전 고점 회복까지 거래일), 회전율, 상관, 기여도
    trough = min(range(len(series)), key=lambda i: Decimal(series[i]["drawdown"]))
    recovery = next(
        (i - trough for i in range(trough, len(series)) if Decimal(series[i]["drawdown"]) == 0),
        None,
    )
    traded_notional = sum(
        Decimal(t["entryPrice"]) * Decimal(t["quantity"]) for t in trades
    ) + sum(Decimal(t["exitPrice"]) * Decimal(t["quantity"]) for t in closed)
    avg_equity = sum(equities, Decimal(0)) / len(equities)
    turnover = traded_notional / avg_equity / 2
    returns = {
        k: [float(prices[k][i] / prices[k][i - 1] - 1) for i in range(1, until)] for k in INSTRUMENTS
    }

    def corr(a: list[float], b: list[float]) -> float:
        if statistics.pstdev(a) == 0 or statistics.pstdev(b) == 0:
            return 0.0
        return statistics.correlation(a, b)

    correlation_matrix = {
        a: {b: ratio(Decimal(corr(returns[a], returns[b]))) for b in INSTRUMENTS} for a in INSTRUMENTS
    }
    # 위험·손실 기여: 종목별 실현 손익 절대값 비중 (데모용 단순 정의, 방법은 analytics.method에 명시)
    pnl_by_inst = {
        k: sum(Decimal(t["realizedPnl"]) for t in closed if t["instrumentId"] == k)
        for k in INSTRUMENTS
    }
    losses = {k: -v for k, v in pnl_by_inst.items() if v < 0}
    total_loss = sum(losses.values(), Decimal(0))
    total_abs = sum((abs(v) for v in pnl_by_inst.values()), Decimal(0))
    analytics = {
        "recoveryDays": recovery,
        "turnover": ratio(turnover),
        "correlationMatrix": correlation_matrix,
        "riskContribution": {
            k: ratio(abs(v) / total_abs) if total_abs else ratio(Decimal(0))
            for k, v in pnl_by_inst.items()
        },
        "lossContribution": {
            k: ratio(losses.get(k, Decimal(0)) / total_loss) if total_loss else ratio(Decimal(0))
            for k in INSTRUMENTS
        },
        "weightError": "0.000000",
        "method": "sample-covariance-daily",
    }

    exceptions = []
    if HALT_DAY < until:
        exceptions.append(
            {
                "date": days[HALT_DAY].isoformat(),
                "instrumentId": "000660.XKRX",
                "kind": "HALT_DEFERRED",
                "rule": "DEFER_TO_NEXT_TRADABLE",
                "action": "Order deferred to the next tradable session.",
            }
        )
    warnings = []
    if HALT_DAY < until:
        warnings.append(
            {
                "date": days[HALT_DAY].isoformat(),
                "instrumentId": "000660.XKRX",
                "code": "TRADING_HALTED",
                "message": "Order execution was delayed until trading resumed.",
            }
        )

    return {
        "series": series,
        "weights": weights,
        "trades": trades,
        "performance": performance,
        "execution": {
            "orderCount": order_count,
            "fillCount": fill_count,
            "rejectedOrderCount": 0,
            "totalCommission": money(total_commission),
            "totalSlippageCost": money(total_slippage),
        },
        "costs": {
            "commission": money(total_commission),
            "spread": "0.00",
            "slippage": money(total_slippage),
            "tax": "0.00",
            "total": money(total_cost),
        },
        "liquidation": liquidation,
        "analytics": analytics,
        "exceptions": exceptions,
        "warnings": warnings,
        "lastTradingDate": days[until - 1].isoformat(),
    }


VERSIONS = {
    "rules": {"version": "1.0.0", "provisional": False},
    "costProfile": {"version": "PROVISIONAL-DEMO-1", "provisional": True},
    "data": "FIXTURE-2026-09-07",
    "engine": ENGINE_VERSION,
    # 요청 evaluation.splitVersion 의 사본. 결과 행이 실행 행 없이도 재현 기준을 다 갖는다(T018).
    "evaluationSplit": "SPLIT-1",
    "worker": WORKER_VERSION,
}


def engine_output(result: dict, status: str, partial: bool, finished_at: str) -> dict:
    return {
        "run": {
            "runId": RUN_ID,
            "status": status,
            "startedAt": "2026-09-07T01:00:00Z",
            "finishedAt": finished_at,
            "partial": partial,
            "lastTradingDate": result["lastTradingDate"],
            "effectiveStart": days[0].isoformat(),
            "effectiveEnd": END.isoformat(),
            "error": None,
        },
        "performance": result["performance"],
        "portfolioSeries": result["series"],
        "weightsSeries": result["weights"],
        "weightsSource": "COMPUTED",
        "trades": result["trades"],
        "execution": result["execution"],
        "costs": result["costs"],
        "liquidation": result["liquidation"],
        "analytics": result["analytics"],
        "exceptions": result["exceptions"],
        "versions": VERSIONS,
        "diagnostics": {"warnings": result["warnings"]},
    }


# ---------- EngineInput (조립기 결과와 동일) ----------
engine_input = {
    "run": {
        "runId": RUN_ID,
        "start": START.isoformat(),
        "end": END.isoformat(),
        "randomSeed": 42,
        "scenario": "FULL_PERIOD",
    },
    "universe": {"items": [{"instrumentId": inst} for inst in INSTRUMENTS]},
    # 자료 버전은 접수가 정해 두 필드에 같은 값으로 싣는다(spec 001 research 결정 4, EngineInputFixtureTest).
    "marketData": {
        "datasetId": "KRX_DAILY_20260907",
        "dataVersion": "KRX_DAILY_20260907",
        "interval": "1D",
        "adjustment": "SPLIT_ADJUSTED",
        "priceType": "LAST",
        "fillPrice": "RAW",
        "signalPrice": "ADJUSTED",
        "distributions": "CASH_ON_PAY_DATE",
    },
    "allocation": {
        "strategyId": "MA_CROSSOVER",
        "rulesVersion": "1.0.0",
        # threshold(소수)는 자유 형식 파라미터의 숫자가 문자열로 변질되지 않는지 round-trip으로 확인하는 용도
        "parameters": {"shortWindow": 20, "longWindow": 60, "threshold": 0.05},
    },
    "portfolio": {
        "initialCash": str(INITIAL_CASH),
        "currency": "KRW",
        "positionSizing": {"type": "STRATEGY_DEFINED", "maxPositionPct": "0.50"},
        "shortSelling": False,
        "leverage": "1.0",
        "integerLots": True,
    },
    "execution": {
        "signalTiming": "BAR_CLOSE",
        "executionTiming": "NEXT_BAR_OPEN",
        "orderTypes": ["MARKET"],
        "costProfile": COST_PROFILE,
        "partialFill": False,
        "liquidity": {"model": "UNLIMITED"},
        "tradingHalt": "DEFER_TO_NEXT_TRADABLE",
        "delisting": "CAUSE_BASED",
    },
    "evaluation": {"liquidationAtEnd": False},
    "progress": {"batchTradingDays": PROGRESS_CHUNK},
}

# ---------- EngineInput 전체 필드 예시 (PD-18 목표 위험 맞춤, 003 연동 뒤) ----------
engine_input_target_risk = {
    "run": {
        "runId": "RUN-000123",
        "start": "2018-01-02",
        "end": "2025-12-30",
        "effectiveStart": "2018-04-02",
        "randomSeed": 42,
        "scenario": "TARGET_RISK",
        "scenarioParams": {"targetVolatility": "0.1000"},
        "scenarioRules": {
            "version": "TARGET_RISK_OVERLAY/1",
            "lookbackDays": 63,
            "adjustAt": "REBALANCE",
            "scaleDownOnly": True,
        },
    },
    "universe": {
        "mappingVersion": "SECTOR-ETF-2026-09",
        "items": [
            {"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX"},
            {"sectorId": "BATTERY", "instrumentId": "305720.XKRX"},
            {"sectorId": "BIO", "instrumentId": "244580.XKRX"},
        ],
    },
    "marketData": {
        "datasetId": "FIXTURE-three_etf_24m",
        "dataVersion": "KRX-2026-09-09",
        "interval": "1D",
        "adjustment": "SPLIT_ADJUSTED",
        "priceType": "LAST",
        "fillPrice": "RAW",
        "signalPrice": "ADJUSTED",
        "distributions": "CASH_ON_PAY_DATE",
    },
    "allocation": {
        "strategyId": "INVERSE_VOLATILITY",
        "rulesVersion": "1",
        "rules": {
            "lookbackDays": 63,
            "zeroVolatility": "EXCLUDE_THEN_ALLOCATE",
            "weightCap": "1.0000",
            "capOverflow": "CASH",
            "weightUnit": "0.000001",
            "investedFraction": "1.0000",
        },
        "parameters": {"rebalance": "MONTH_END"},
    },
    "portfolio": {
        "initialCash": "10000000.00",
        "currency": "KRW",
        "positionSizing": {"type": "STRATEGY_DEFINED"},
        "shortSelling": False,
        "leverage": "1.0",
        "integerLots": True,
    },
    "execution": {**engine_input["execution"], "costProfile": CONFIRMED_COST_PROFILE},
    "evaluation": {"splitVersion": "SPLIT-1", "warmupTradingDays": 63, "liquidationAtEnd": True},
    "progress": {"batchTradingDays": PROGRESS_CHUNK},
}


def envelope(event_id: str, type_: str, sequence: int, occurred_at: str, payload: dict) -> dict:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "eventId": event_id,
        "runId": RUN_ID,
        "executionId": EXECUTION_ID,
        "type": type_,
        "sequence": sequence,
        "occurredAt": occurred_at,
        "payload": payload,
    }


full = simulate(N, [])
partial = simulate(CANCEL_AFTER_DAYS, DISTRIBUTIONS_FOR_CANCELLED)

started = envelope(
    "EVT-000001",
    "STARTED",
    0,
    "2026-09-07T01:00:00Z",
    {
        "startedAt": "2026-09-07T01:00:00Z",
        "stage": "PREPARING_DATA",
        "resource": {"pid": 4242, "hostname": "worker-1", **SERVED},
    },
)

progress = envelope(
    "EVT-000002",
    "PROGRESS",
    1,
    "2026-09-07T01:00:01Z",
    {
        "stage": "COMPUTING",
        "completedTradingDays": PROGRESS_CHUNK,
        "totalTradingDays": N,
        "lastTradingDate": days[PROGRESS_CHUNK - 1].isoformat(),
        "portfolioSeries": [
            {k: s[k] for k in ("date", "equity", "cumulativeReturn", "drawdown")}
            for s in full["series"][:PROGRESS_CHUNK]
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
        "status": "COMPLETED",
        "partial": False,
        "lastTradingDate": full["lastTradingDate"],
        "engineOutput": engine_output(full, "COMPLETED", False, "2026-09-07T01:00:04Z"),
        "resource": RESOURCE,
    },
)

finished_failed = envelope(
    "EVT-000002",
    "FINISHED",
    1,
    "2026-09-07T01:00:01Z",
    {
        "status": "FAILED",
        "partial": False,
        "engineOutput": {
            "run": {
                "runId": RUN_ID,
                "status": "FAILED",
                "startedAt": "2026-09-07T01:00:00Z",
                "finishedAt": "2026-09-07T01:00:01Z",
                "partial": False,
                "error": {
                    "code": "DATA_INSUFFICIENT",
                    "message": "Market data is unavailable for 000660.XKRX in the requested period.",
                },
            },
            "performance": None,
            "portfolioSeries": [],
            "trades": [],
            "execution": None,
            "versions": VERSIONS,
            "diagnostics": {"warnings": []},
        },
        "resource": {"peakRssMb": "128.0", "cpuSeconds": "0.210", "wallSeconds": "0.900", **SERVED},
    },
)

cancel_progress_events = math.ceil(CANCEL_AFTER_DAYS / PROGRESS_CHUNK)
finished_cancelled = envelope(
    f"EVT-{cancel_progress_events + 2:06d}",
    "FINISHED",
    cancel_progress_events + 1,
    "2026-09-07T01:00:03Z",
    {
        "status": "CANCELLED",
        "partial": True,
        "lastTradingDate": partial["lastTradingDate"],
        "engineOutput": engine_output(partial, "CANCELLED", True, "2026-09-07T01:00:03Z"),
        "resource": {
            "peakRssMb": "400.0",
            "cpuSeconds": "1.500",
            "wallSeconds": "2.100",
            **SERVED,
            **DATA_USAGE,
        },
    },
)

control_cancel = {
    "schemaVersion": SCHEMA_VERSION,
    "type": "CANCEL",
    "runId": RUN_ID,
    "requestedAt": "2026-09-07T01:00:02Z",
}

files = {
    "engine-input.json": engine_input,
    "engine-input-target-risk.json": engine_input_target_risk,
    "event-started.json": started,
    "event-progress.json": progress,
    "event-finished-completed.json": finished_completed,
    "event-finished-failed.json": finished_failed,
    "event-finished-cancelled.json": finished_cancelled,
    "control-cancel.json": control_cancel,
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
print(
    f"cancelledAfter={CANCEL_AFTER_DAYS} cancelProgressEvents={cancel_progress_events} "
    f"cancelSequence={cancel_progress_events + 1}"
)
print(json.dumps(full["performance"], indent=2))
print("execution:", full["execution"])
print("costs:", full["costs"])
print("liquidation:", full["liquidation"])
print("snapshots with positions:", sum(1 for s in full["series"] if "positions" in s))
print("cashFlows days:", [s["date"] for s in full["series"] if "cashFlows" in s])
for t in full["trades"]:
    print(t["tradeId"], t["instrumentId"], t["status"], t["entryTime"][:10], (t["exitTime"] or "")[:10], t["realizedPnl"])
