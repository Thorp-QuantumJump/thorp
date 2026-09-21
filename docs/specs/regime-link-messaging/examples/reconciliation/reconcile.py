"""사후 구간 요약의 독립 계산(spec 011 T014).

``worker/tests/regime_link/fixtures/`` 의 작업 픽스처와 종가 픽스처만 읽어 research R3의 구간
산식을 **워커를 import 하지 않고** 다시 센다. 결과는 같은 폴더의 ``manual_reconciliation.json``
이고 ``worker/tests/regime_link/test_slicer.py`` 가 그 파일을 기대값으로 읽는다.

워커 출력으로 기대값을 채우면 대조가 아니다. 그래서 여기서는 계약 모델도 ``slicer.py`` 도 쓰지
않고 JSON 과 ``Decimal`` 만으로 센다.

세는 규칙(R3, 연환산은 회전율에만 있다):

- 수익률 = 종료 자산 / 기준값 − 1. 기준값은 연속 운용이면 ``baselineDate`` 자산, 현금 시작이면
  ``run.initialCash``
- 최대 하락 폭 = 기준값부터 다시 센 고점 대비 최저
- 거래·비용 = 구간 안 체결의 건수와 비용 네 갈래 합
- 월당 거래횟수 = 체결 수 ÷ (구간 거래일 수 ÷ 21)
- 회전율 = (매수대금 + 매도대금) ÷ 2 ÷ 구간 일별 평균 자산 × 252 ÷ 구간 거래일 수
- 종목 구간 손익 = 종료 평가액 − 시작 직전 평가액 + 매도대금 − 매수대금 + 분배금 − 직접 차감 비용
- 손실 기여 = 섹터별 손익 ÷ 전체 손익(이익 종목도 포함, 합 1, 전체 0이면 생략)

    python docs/specs/regime-link-messaging/examples/reconciliation/reconcile.py
"""

from __future__ import annotations

import json
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
FIXTURES = HERE.parents[4] / "worker" / "tests" / "regime_link" / "fixtures"
OUTPUT = HERE / "manual_reconciliation.json"

CASES = ["continuous_63", "incomplete_34", "fresh_cash", "partial_delisted", "zero_pnl"]

RATIO = Decimal("0.0001")
MONEY = Decimal("0.01")
SHARE = Decimal("0.00000001")
ZERO = Decimal(0)


def q(value: Decimal, places: Decimal) -> str:
    return str(value.quantize(places, rounding=ROUND_HALF_UP))


def day_of(text: str) -> date:
    return date.fromisoformat(text)


def load(name: str) -> tuple[dict[str, Any], dict[str, dict[date, Decimal]]]:
    job = json.loads((FIXTURES / f"{name}.job.json").read_text(encoding="utf-8"))
    raw = json.loads((FIXTURES / f"{name}.closes.json").read_text(encoding="utf-8"))
    closes = {
        iid: {day_of(d): Decimal(price) for d, price in series.items()}
        for iid, series in raw.items()
    }
    return job, closes


def quantities(job: dict[str, Any], days: list[date]) -> dict[date, dict[str, int]]:
    """하루하루 손으로 이월한다 — 체결로 더하고 스냅샷이 있는 날은 그 수량으로 맞춘다."""
    held = {p["instrumentId"]: p["quantity"] for p in job["inputs"]["openingPositions"]}
    snapshots = {
        day_of(s["date"]): {p["instrumentId"]: p["quantity"] for p in s["positions"]}
        for s in job["inputs"]["dailyPositions"]
    }
    by_day: dict[date, dict[str, int]] = {}
    for day in days:
        for fill in job["inputs"]["trades"]:
            if day_of(fill["time"][:10]) != day:
                continue
            sign = 1 if fill["side"] == "BUY" else -1
            held[fill["instrumentId"]] = held.get(fill["instrumentId"], 0) + sign * fill["quantity"]
        if day in snapshots:
            held = dict(snapshots[day])
        by_day[day] = {iid: n for iid, n in held.items() if n}
    return by_day


def drawdown(base: Decimal, values: list[Decimal]) -> Decimal:
    peak = base
    worst = ZERO
    for value in values:
        if value > peak:
            peak = value
        fall = value / peak - 1
        if fall < worst:
            worst = fall
    return worst


def summarise(
    job: dict[str, Any],
    closes: dict[str, dict[date, Decimal]],
    by_day: dict[date, dict[str, int]],
    days: list[date],
    expected: int,
) -> dict[str, Any]:
    equity = {day_of(p["date"]): Decimal(p["equity"]) for p in job["inputs"]["portfolioSeries"]}
    fresh_cash = job["job"]["scenario"] == "FRESH_CASH"
    baseline = day_of(job["window"]["baselineDate"])
    base = Decimal(job["run"]["initialCash"]) if fresh_cash else equity[baseline]
    start, end = days[0], days[-1]

    values = [equity[day] for day in days]
    commission = spread = slippage = tax = traded = ZERO
    count = 0
    pnl = {item["instrumentId"]: ZERO for item in job["universe"]["items"]}
    sector_of = {item["instrumentId"]: item["sectorId"] for item in job["universe"]["items"]}

    for fill in job["inputs"]["trades"]:
        day = day_of(fill["time"][:10])
        if not start <= day <= end:
            continue
        count += 1
        commission += Decimal(fill["costs"]["commission"])
        spread += Decimal(fill["costs"]["spread"])
        slippage += Decimal(fill["costs"]["slippage"])
        tax += Decimal(fill["costs"]["tax"])
        gross = Decimal(fill["price"]) * fill["quantity"]
        traded += gross
        signed = gross if fill["side"] == "SELL" else -gross
        pnl[fill["instrumentId"]] += signed - Decimal(fill["cashCost"])

    if not fresh_cash:
        for p in job["inputs"]["openingPositions"]:
            pnl[p["instrumentId"]] -= Decimal(p["quantity"]) * closes[p["instrumentId"]][baseline]
    for iid, quantity in by_day[end].items():
        pnl[iid] += Decimal(quantity) * closes[iid][end]
    for flow in job["inputs"]["cashFlows"]:
        if start <= day_of(flow["date"]) <= end:
            pnl[flow["instrumentId"]] += Decimal(flow["amount"])

    total = sum(pnl.values(), ZERO)
    contribution: dict[str, str] | None = None
    if total != ZERO:
        by_sector: dict[str, Decimal] = {}
        for iid, value in pnl.items():
            by_sector[sector_of[iid]] = by_sector.get(sector_of[iid], ZERO) + value
        contribution = {
            sector: q(value / total, SHARE) for sector, value in sorted(by_sector.items())
        }

    length = Decimal(len(days))
    average = sum(values, ZERO) / length
    return {
        "tradingDays": len(days),
        "complete": len(days) == expected,
        "return": q(values[-1] / base - 1, RATIO),
        "maxDrawdown": q(drawdown(base, values), RATIO),
        "tradeCount": count,
        "tradesPerMonth": q(Decimal(count) * 21 / length, MONEY),
        "turnover": q(traded / 2 / average * 252 / length, RATIO),
        "turnoverBasis": "ANNUALIZED_252",
        "costs": {
            "commission": q(commission, MONEY),
            "spread": q(spread, MONEY),
            "slippage": q(slippage, MONEY),
            "tax": q(tax, MONEY),
            "total": q(commission + spread + slippage + tax, MONEY),
        },
        "lossContribution": contribution,
        "instrumentPnl": {iid: q(value, MONEY) for iid, value in sorted(pnl.items())},
        "pnlSum": q(total, MONEY),
        "equityChange": q(values[-1] - base, MONEY),
    }


def benchmark(
    job: dict[str, Any], closes: dict[str, dict[date, Decimal]], days: list[date]
) -> dict[str, str]:
    iid = job["marketData"]["benchmarkInstrumentId"]
    base = closes[iid][day_of(job["window"]["baselineDate"])]
    values = [closes[iid][day] for day in days]
    return {
        "return": q(values[-1] / base - 1, RATIO),
        "maxDrawdown": q(drawdown(base, values), RATIO),
    }


def reconcile(name: str) -> dict[str, Any]:
    job, closes = load(name)
    window = job["window"]
    equity_days = sorted(
        day_of(p["date"])
        for p in job["inputs"]["portfolioSeries"]
        if day_of(window["d0"]) <= day_of(p["date"]) <= day_of(window["lastAvailable"])
    )
    end63 = min(day_of(window["d63"]), day_of(window["lastAvailable"]))
    days63 = [day for day in equity_days if day <= end63]
    days21 = [day for day in equity_days if day <= day_of(window["d21"])]
    by_day = quantities(job, equity_days)

    delisted = sorted(
        item["delistedOn"]
        for item in job["universe"]["items"]
        if item.get("delistedOn")
        and window["d0"] <= item["delistedOn"] <= window["d63"]
    )
    equity = {day_of(p["date"]): Decimal(p["equity"]) for p in job["inputs"]["portfolioSeries"]}
    holdings = {
        day.isoformat(): {
            iid: q(Decimal(n) * closes[iid][day] / equity[day], RATIO)
            for iid, n in sorted(by_day[day].items())
        }
        for day in (days63[0], days63[-1])
    }
    return {
        "partial": bool(delisted),
        "delistedOn": delisted[0] if delisted else None,
        "window63": summarise(job, closes, by_day, days63, 63),
        "window21": summarise(job, closes, by_day, days21, 21),
        "kospi": {
            "window63": benchmark(job, closes, days63),
            "window21": benchmark(job, closes, days21),
        },
        "holdingDays": len(days63),
        "holdings": holdings,
    }


def main() -> None:
    result = {name: reconcile(name) for name in CASES}
    OUTPUT.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    for name, case in result.items():
        window = case["window63"]
        print(
            f"{name}: 63거래일={window['tradingDays']} 수익률={window['return']} "
            f"손익합={window['pnlSum']} 자산증감={window['equityChange']}"
        )


if __name__ == "__main__":
    main()
