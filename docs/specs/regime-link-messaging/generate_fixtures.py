"""사례 연결 메시지 계약 v1 픽스처 생성기(spec 011 T001).

`examples/` 아래 JSON 7개를 만든다. 수치는 시드 고정 랜덤워크 + 실제 현금 회계로 만들어
`equity = cash + positionValue`, 수량 이월, 비용 관계(`가격 충격 = (호가차 + 슬리피지) ÷ 수량`,
`cashCost = 수수료 + 세금`)가 실제로 성립한다.

- `job-continuous.json`: 연속 운용. 시작 보유가 있고 구간 안에서 일부 매도·매수·분할·분배금이 난다.
- `job-fresh-cash.json`: 현금 시작. `openingPositions`가 빈 목록이고 기준은 `run.initialCash`다.
- `job-partial-delisted.json`: 구간 안 상장폐지. 백엔드가 폐지 전날까지만 잘라 담은 모양이다.
- `event-*.json`: STARTED / FINISHED(COMPLETED · 미완성 63 · FAILED).

결과 이벤트의 `result` 수치는 **형식과 job과의 정합을 보이는 예시**다. 구간 산식의 기대값 대조는
`examples/reconciliation/`(spec 011 T014)이 워커를 import하지 않는 독립 계산으로 따로 만든다 —
기대값을 워커 출력으로 채우면 대조가 아니다.

    python docs/specs/regime-link-messaging/generate_fixtures.py
"""

from __future__ import annotations

import hashlib
import json
import random
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any

EXAMPLES = Path(__file__).resolve().parent / "examples"
SEED = 20260920
ZERO = Decimal(0)
BPS = Decimal(10_000)

#: 비용 프로필 `KR-ETF-2026-09`(PD-07 확정값). 세금은 국내주식형 ETF라 0이다.
COMMISSION_BPS = Decimal("1.5")
SPREAD_BPS = Decimal(3)
SLIPPAGE_BPS = Decimal(5)
TAX_BPS = ZERO

BASELINE = date(2012, 3, 30)
WINDOW_LENGTH = 63
SEMICONDUCTOR = "091160.XKRX"
BATTERY = "305720.XKRX"
BIO = "244580.XKRX"
UNIVERSE = [
    ("SEMICONDUCTOR", SEMICONDUCTOR),
    ("BATTERY", BATTERY),
    ("BIO", BIO),
]
OPENING = {SEMICONDUCTOR: 179, BATTERY: 151, BIO: 233}
START_PRICE = {SEMICONDUCTOR: 23200, BATTERY: 18600, BIO: 12400}
#: 구간 안에서 수량이 바뀌는 날(거래일 번호, d0 = 0)과 그날 일어나는 일.
REBALANCE_DAYS = {20: [(SEMICONDUCTOR, "SELL", 6), (BATTERY, "BUY", 4)],
                  41: [(BIO, "SELL", 12), (SEMICONDUCTOR, "BUY", 3)]}
SPLIT_DAY = 30  # BIO 2:1 — 체결이 아니라 기업행위로 수량이 바뀐다
SPLIT_RATIO = 2
DISTRIBUTION_DAY = 45  # SEMICONDUCTOR 주당 100원
DISTRIBUTION_PER_SHARE = Decimal(100)
DELISTED_AT = 50  # partial 픽스처에서 BIO 가 폐지되는 거래일 번호


def money(value: Decimal) -> str:
    """금액 표기. 계약의 '소수 plain string' 규칙을 따른다."""
    return str(value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def ratio(value: Decimal, places: str = "0.0001") -> str:
    return str(value.quantize(Decimal(places), rounding=ROUND_HALF_UP))


def trading_days(start: date, count: int) -> list[date]:
    """주말만 제외한 거래일. 픽스처 자체 달력이며 실제 KRX 휴장일과 다르다."""
    days: list[date] = []
    day = start
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def build_prices(days: list[date]) -> dict[str, dict[date, Decimal]]:
    """시드 고정 랜덤워크로 만든 원시 종가(정수 원). 분할일에는 가격도 함께 나뉜다."""
    rng = random.Random(SEED)
    prices: dict[str, dict[date, Decimal]] = {}
    for _, iid in UNIVERSE:
        level = Decimal(START_PRICE[iid])
        series: dict[date, Decimal] = {}
        for index, day in enumerate(days):
            if index:
                level += Decimal(rng.randint(-90, 95))
            if iid == BIO and index == SPLIT_DAY + 1:  # baseline 다음이 d0 이므로 +1
                level = (level / SPLIT_RATIO).quantize(Decimal(1), rounding=ROUND_HALF_UP)
            series[day] = level
        prices[iid] = series
    return prices


def fill_of(iid: str, day: date, side: str, quantity: int, reference: Decimal,
            index: int) -> dict[str, Any]:
    """체결 한 건. 비용은 `수량 × referencePrice`에 매기고 가격 충격은 체결가에 반영한다."""
    gross = reference * quantity
    commission = gross * COMMISSION_BPS / BPS
    spread = gross * SPREAD_BPS / BPS
    slippage = gross * SLIPPAGE_BPS / BPS
    tax = gross * TAX_BPS / BPS if side == "SELL" else ZERO
    impact = (spread + slippage) / quantity
    price = reference + impact if side == "BUY" else reference - impact
    return {
        "fillId": f"FL-{index:06d}",
        "time": f"{day.isoformat()}T00:00:00Z",
        "instrumentId": iid,
        "side": side,
        "quantity": quantity,
        "price": money(price),
        "referencePrice": money(reference),
        "costs": {
            "commission": money(commission),
            "spread": money(spread),
            "slippage": money(slippage),
            "tax": money(tax),
        },
        "cashCost": money(commission + tax),
        "reason": "REBALANCE",
    }


def cash_delta(fill: dict[str, Any]) -> Decimal:
    gross = Decimal(fill["price"]) * fill["quantity"]
    direct = Decimal(fill["cashCost"])
    return gross - direct if fill["side"] == "SELL" else -gross - direct


def simulate(days: list[date], prices: dict[str, dict[date, Decimal]],
             *, fresh_cash: bool, last_index: int) -> dict[str, Any]:
    """구간을 실제로 굴려 입력 블록을 만든다. days[0] 은 baselineDate 다."""
    held = {} if fresh_cash else dict(OPENING)
    cash = Decimal(1_200_000) if not fresh_cash else Decimal(0)
    series: list[dict[str, str]] = []
    daily_positions: list[dict[str, Any]] = []
    fills: list[dict[str, Any]] = []
    cash_flows: list[dict[str, Any]] = []

    def position_value(day: date) -> Decimal:
        return sum((Decimal(q) * prices[iid][day] for iid, q in held.items()), ZERO)

    # baselineDate — 구간 시작 직전 상태
    baseline_day = days[0]
    if fresh_cash:
        cash = Decimal(10_000_000)
    series.append({"date": baseline_day.isoformat(), "equity": money(position_value(baseline_day) + cash),
                   "cash": money(cash)})

    for index, day in enumerate(days[1:]):
        if index > last_index:
            break
        if fresh_cash and index == 0:  # 현금 시작은 d0 에 전량 매수한다
            for iid, quantity in OPENING.items():
                fill = fill_of(iid, day, "BUY", quantity, prices[iid][day], len(fills) + 1)
                fills.append(fill)
                cash += cash_delta(fill)
                held[iid] = held.get(iid, 0) + quantity
        for iid, side, quantity in REBALANCE_DAYS.get(index, []):
            fill = fill_of(iid, day, side, quantity, prices[iid][day], len(fills) + 1)
            fills.append(fill)
            cash += cash_delta(fill)
            held[iid] = held.get(iid, 0) + (quantity if side == "BUY" else -quantity)
        if index == SPLIT_DAY and BIO in held:
            held[BIO] *= SPLIT_RATIO
        if index == DISTRIBUTION_DAY and held.get(SEMICONDUCTOR):
            amount = DISTRIBUTION_PER_SHARE * held[SEMICONDUCTOR]
            cash += amount
            cash_flows.append({"date": day.isoformat(), "instrumentId": SEMICONDUCTOR,
                               "type": "DISTRIBUTION", "amount": money(amount)})
        if index in REBALANCE_DAYS or index == SPLIT_DAY or (fresh_cash and index == 0):
            daily_positions.append({
                "date": day.isoformat(),
                "positions": [{"instrumentId": iid, "quantity": quantity}
                              for iid, quantity in sorted(held.items()) if quantity],
            })
        series.append({"date": day.isoformat(),
                       "equity": money(position_value(day) + cash), "cash": money(cash)})

    opening = [] if fresh_cash else [{"instrumentId": iid, "quantity": quantity}
                                     for iid, quantity in sorted(OPENING.items())]
    return {
        "portfolioSeries": series,
        "openingPositions": opening,
        "dailyPositions": daily_positions,
        "trades": fills,
        "cashFlows": cash_flows,
    }


def input_digest(job: dict[str, Any]) -> str:
    """`inputs·window·job.scenario·marketData` canonical JSON 의 sha256(계약 "작업 메시지")."""
    canonical = json.dumps(
        {"inputs": job["inputs"], "window": job["window"],
         "scenario": job["job"]["scenario"], "marketData": job["marketData"]},
        sort_keys=True, separators=(",", ":"), ensure_ascii=False,
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def build_job(link_id: str, scenario: str, days: list[date],
              prices: dict[str, dict[date, Decimal]], *, last_index: int,
              delisted: bool = False) -> dict[str, Any]:
    window_days = days[1:]
    inputs = simulate(days, prices, fresh_cash=scenario == "FRESH_CASH", last_index=last_index)
    items: list[dict[str, Any]] = []
    for sector_id, iid in UNIVERSE:
        item: dict[str, Any] = {"sectorId": sector_id, "instrumentId": iid}
        if delisted and iid == BIO:  # null 은 키 생략이라 폐지된 종목에만 넣는다
            item["delistedOn"] = window_days[DELISTED_AT].isoformat()
        items.append(item)
    job = {
        "job": {"linkId": link_id, "attempt": 1, "scenario": scenario},
        "window": {
            "d0": window_days[0].isoformat(),
            "d21": window_days[20].isoformat(),
            "d63": window_days[WINDOW_LENGTH - 1].isoformat(),
            "lastAvailable": window_days[last_index].isoformat(),
            "completedTradingDays": last_index + 1,
            "baselineDate": days[0].isoformat(),
        },
        "run": {"runId": "RUN-000123", "effectiveStart": "2010-04-02",
                "initialCash": "10000000.00", "analyticsMethod": "ANALYTICS-1"},
        "universe": {"mappingVersion": "SECTOR-ETF-2026-09", "items": items},
        "marketData": {"datasetId": "KRX_DAILY_20260915", "dataVersion": "KRX-2026-09-09",
                       "benchmarkInstrumentId": "KOSPI"},
        "inputs": inputs,
    }
    job["inputDigest"] = input_digest(job)
    return job


def window_summary(job: dict[str, Any], length: int, *, complete: bool) -> dict[str, Any]:
    """구간 요약. R3 산식 중 입력만으로 닫히는 항(수익률·거래·비용·회전율)은 실제로 계산한다."""
    series = job["inputs"]["portfolioSeries"]
    rows = series[: length + 1]
    start_equity = Decimal(rows[0]["equity"])
    equities = [Decimal(row["equity"]) for row in rows[1:]]
    end_equity = equities[-1]
    if job["job"]["scenario"] == "FRESH_CASH":
        start_equity = Decimal(job["run"]["initialCash"])

    peak = start_equity
    drawdown = ZERO
    for equity in equities:
        peak = max(peak, equity)
        drawdown = min(drawdown, equity / peak - 1)

    days = {row["date"] for row in rows[1:]}
    fills = [f for f in job["inputs"]["trades"] if f["time"][:10] in days]
    costs = {key: sum((Decimal(f["costs"][key]) for f in fills), ZERO)
             for key in ("commission", "spread", "slippage", "tax")}
    buy = sum((Decimal(f["price"]) * f["quantity"] for f in fills if f["side"] == "BUY"), ZERO)
    sell = sum((Decimal(f["price"]) * f["quantity"] for f in fills if f["side"] == "SELL"), ZERO)
    average_equity = sum(equities, ZERO) / len(equities)
    turnover = (buy + sell) / 2 / average_equity * Decimal(252) / Decimal(len(equities))
    return {
        "tradingDays": len(equities),
        "return": ratio(end_equity / start_equity - 1),
        "maxDrawdown": ratio(drawdown),
        "tradeCount": len(fills),
        "tradesPerMonth": ratio(
            Decimal(len(fills)) / (Decimal(len(equities)) / Decimal(21)), "0.01"
        ),
        "turnover": ratio(turnover),
        "turnoverBasis": "ANNUALIZED_252",
        "costs": {**{k: money(v) for k, v in costs.items()},
                  "total": money(sum(costs.values(), ZERO))},
        "lossContribution": {"SEMICONDUCTOR": "0.62000000", "BATTERY": "0.21000000",
                             "BIO": "0.17000000"},
        "complete": complete,
    }


def build_result(job: dict[str, Any], *, complete63: bool) -> dict[str, Any]:
    length = WINDOW_LENGTH if complete63 else job["window"]["completedTradingDays"]
    holdings = [{"date": row["date"],
                 "actual": {SEMICONDUCTOR: "0.3980", BATTERY: "0.3010", BIO: "0.3010"}}
                for row in job["inputs"]["portfolioSeries"][1:2]]
    trades = [{"date": f["time"][:10], "instrumentId": f["instrumentId"], "side": f["side"],
               "quantity": f["quantity"], "price": f["price"],
               "cost": money(sum((Decimal(v) for v in f["costs"].values()), ZERO)),
               "reason": f["reason"]}
              for f in job["inputs"]["trades"]]
    return {
        "window63": window_summary(job, length, complete=complete63),
        "window21": window_summary(job, 21, complete=True),
        "holdings": holdings,
        "trades": trades,
        "kospi": {"window63": {"return": "0.0180", "maxDrawdown": "-0.0420"},
                  "window21": {"return": "0.0050", "maxDrawdown": "-0.0150"}},
        "basis": {"kind": "HISTORICAL_REVISION", "dataVersion": job["marketData"]["dataVersion"],
                  "datasetId": job["marketData"]["datasetId"],
                  "analyticsMethod": job["run"]["analyticsMethod"]},
        "inputDigest": job["inputDigest"],
    }


def envelope(event_id: str, link_id: str, type_: str, occurred_at: str,
             payload: dict[str, Any]) -> dict[str, Any]:
    return {"schemaVersion": 1, "eventId": event_id, "linkId": link_id, "attempt": 1,
            "type": type_, "occurredAt": occurred_at, "payload": payload}


def write(name: str, document: dict[str, Any]) -> None:
    path = EXAMPLES / name
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"  {name}")


def main() -> None:
    EXAMPLES.mkdir(parents=True, exist_ok=True)
    days = trading_days(BASELINE, WINDOW_LENGTH + 1)
    prices = build_prices(days)
    last = WINDOW_LENGTH - 1

    continuous = build_job("RL-20260918-000101", "CONTINUOUS", days, prices, last_index=last)
    fresh_cash = build_job("RL-20260918-000102", "FRESH_CASH", days, prices, last_index=last)
    partial = build_job("RL-20260918-000103", "CONTINUOUS", days, prices,
                        last_index=DELISTED_AT - 1, delisted=True)

    print("작업 메시지")
    write("job-continuous.json", continuous)
    write("job-fresh-cash.json", fresh_cash)
    write("job-partial-delisted.json", partial)

    print("이벤트")
    write("event-started.json", envelope(
        "EVT-RL-000001", continuous["job"]["linkId"], "STARTED", "2026-09-18T02:00:00Z",
        {"startedAt": "2026-09-18T02:00:00Z", "resource": {"pid": 4242, "hostname": "regime-link-1"}},
    ))
    write("event-finished-completed.json", envelope(
        "EVT-RL-000002", continuous["job"]["linkId"], "FINISHED", "2026-09-18T02:00:03Z",
        {"status": "COMPLETED", "partial": False,
         "result": build_result(continuous, complete63=True),
         "resource": {"wallSeconds": "3.41"}},
    ))
    write("event-finished-incomplete63.json", envelope(
        "EVT-RL-000003", partial["job"]["linkId"], "FINISHED", "2026-09-18T02:01:07Z",
        {"status": "COMPLETED", "partial": True,
         "delistedOn": partial["universe"]["items"][2]["delistedOn"],
         "result": build_result(partial, complete63=False),
         "resource": {"wallSeconds": "2.86"}},
    ))
    write("event-finished-failed.json", envelope(
        "EVT-RL-000004", fresh_cash["job"]["linkId"], "FINISHED", "2026-09-18T02:02:11Z",
        {"status": "FAILED", "partial": False,
         "error": {"code": "INPUT_INCOMPLETE",
                   "message": "portfolioSeries 에 baselineDate 행이 없다"},
         "resource": {"wallSeconds": "0.12"}},
    ))


if __name__ == "__main__":
    main()
