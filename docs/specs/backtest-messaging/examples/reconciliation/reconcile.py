"""수기 대조 기대값 계산기(spec 004 SC-003, T008) — 워커 코드를 import 하지 않는다.

스프레드시트 대신 표준 라이브러리만으로 3종목 픽스처의 대표 거래일 세 개를 계산한다.
① 첫 리밸런싱 체결일 ② 분배금 지급일 ③ 거래 정지 이월일. 입력은 픽스처 CSV 원본, 규칙은 아래
문서에서 옮겨 적은 것이다.

- 판단(판단일 종가): 수량 = floor(자산 × 목표 비중 ÷ (종가 × (1 + 호가차 + 슬리피지) + 종가 × 수수료율))
  (spec 004 T007 결정 ①: 비용 방향, `costs.py` affordable_quantity)
- 체결(다음 거래일 시가): 체결가 = 시가 × (1 + 호가차 + 슬리피지) 를 소수 6자리로 반올림(Nautilus 가격
  정밀도), 수수료·호가차·슬리피지·세금의 기준 금액은 가격 충격 전 시가 × 수량. 현금에서 빠지는 것은
  체결가 × 수량 + 수수료(+ 매도면 세금). 호가차·슬리피지는 체결가에 들어 있다
- 제출 직전 예상 현금으로 살 수 있는 만큼만 산다(종목 이름순 매수, 매도 먼저) — T006 결정 ②
- 분배금: 권리락일 **시작 시점** 보유 × 주당 금액이 지급일에 현금으로 들어온다(FR-013)
- 거래 정지일 주문은 다음 거래 가능일 시가로 미룬다(HALT_DEFERRED, plannedDate = 원래 체결 예정일)

실행: `python reconcile.py <worker/examples/three_etf_24m> <출력 디렉터리> <worker/tests/fixtures/manual_reconciliation.json>`
"""

from __future__ import annotations

import csv
import json
import sys
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_FLOOR, Decimal
from pathlib import Path

BPS = Decimal(10_000)
PRICE_QUANTUM = Decimal("0.000001")
SEMICONDUCTOR, BATTERY, BIO = "091160.XKRX", "305720.XKRX", "244580.XKRX"
WEIGHTS = {SEMICONDUCTOR: Decimal("0.4"), BATTERY: Decimal("0.3"), BIO: Decimal("0.3")}
INITIAL_CASH = Decimal(10_000_000)
COSTS = {"commissionBps": "1.5", "spreadBps": "2", "slippageBps": "5", "taxBps": "23"}
COST_PROFILE = {"version": "PROVISIONAL-FULL-1", **COSTS, "provisional": True}


@dataclass(frozen=True)
class Row:
    open: Decimal
    close: Decimal
    suspended: bool


def load_prices(fixture_dir: Path) -> dict[str, dict[date, Row]]:
    prices: dict[str, dict[date, Row]] = {}
    with (fixture_dir / "prices_raw.csv").open(encoding="utf-8", newline="") as handle:
        for record in csv.DictReader(handle):
            prices.setdefault(record["instrument_id"], {})[date.fromisoformat(record["date"])] = Row(
                Decimal(record["open"]),
                Decimal(record["close"]),
                record["trading_suspended"].lower() == "true",
            )
    return prices


def trading_days(prices: dict[str, dict[date, Row]]) -> list[date]:
    return sorted({day for rows in prices.values() for day in rows})


def rate(key: str) -> Decimal:
    return Decimal(COSTS[key]) / BPS


IMPACT = rate("spreadBps") + rate("slippageBps")
COMMISSION = rate("commissionBps")


def affordable(cash: Decimal, reference: Decimal) -> int:
    unit = reference * (1 + IMPACT) + reference * COMMISSION
    return max(0, int((cash / unit).to_integral_value(rounding=ROUND_FLOOR)))


def decide(equity: Decimal, closes: dict[str, Decimal]) -> dict[str, int]:
    return {iid: affordable(equity * weight, closes[iid]) for iid, weight in WEIGHTS.items()}


@dataclass
class Fill:
    instrument_id: str
    date: date
    planned_date: date
    quantity: int
    price: Decimal
    commission: Decimal
    spread: Decimal
    slippage: Decimal

    def as_record(self) -> dict[str, str]:
        return {
            "instrumentId": self.instrument_id,
            "fillDate": self.date.isoformat(),
            "plannedDate": self.planned_date.isoformat(),
            "quantity": str(self.quantity),
            "fillPrice": str(self.price),
            "commission": str(self.commission),
            "spread": str(self.spread),
            "slippage": str(self.slippage),
        }


def buy_day(
    day: date,
    cash: Decimal,
    pending: dict[str, tuple[int, date]],
    prices: dict[str, dict[date, Row]],
) -> tuple[Decimal, list[Fill], dict[str, tuple[int, date]]]:
    """하루의 매수 제출·체결. 정지 종목은 그대로 남긴다(다음 날 다시 시도)."""
    fills: list[Fill] = []
    deferred: dict[str, tuple[int, date]] = {}
    spent = Decimal(0)
    for iid in sorted(pending):
        quantity, planned = pending[iid]
        row = prices[iid][day]
        if row.suspended:
            deferred[iid] = (quantity, planned)
            continue
        quantity = min(quantity, affordable(cash - spent, row.open))
        if quantity == 0:
            continue
        price = (row.open * (1 + IMPACT)).quantize(PRICE_QUANTUM)
        notional = row.open * quantity
        commission = notional * COMMISSION
        spent += price * quantity + commission
        fills.append(
            Fill(iid, day, planned, quantity, price, commission,
                 notional * rate("spreadBps"), notional * rate("slippageBps"))
        )
    return cash - spent, fills, deferred


def next_day(calendar: list[date], day: date) -> date:
    return calendar[calendar.index(day) + 1]


def request(run_id: str, start: str, end: str, **run_extra: object) -> dict[str, object]:
    return {
        "run": {"runId": run_id, "start": start, "end": end, "randomSeed": 42,
                "scenario": "FULL_PERIOD", **run_extra},
        "universe": {
            "mappingVersion": "SECTOR-ETF-2026-09",
            "items": [
                {"sectorId": "SEMICONDUCTOR", "instrumentId": SEMICONDUCTOR, "targetWeight": "0.4"},
                {"sectorId": "BATTERY", "instrumentId": BATTERY, "targetWeight": "0.3"},
                {"sectorId": "BIO", "instrumentId": BIO, "targetWeight": "0.3"},
            ],
        },
        "marketData": {"datasetId": "FIXTURE-three_etf_24m", "dataVersion": "FIXTURE-three_etf_24m",
                       "interval": "1D", "adjustment": "SPLIT_ADJUSTED", "priceType": "LAST",
                       "fillPrice": "RAW", "signalPrice": "ADJUSTED",
                       "distributions": "CASH_ON_PAY_DATE"},
        "allocation": {"strategyId": "FIXED_WEIGHT", "rulesVersion": "1",
                       "rules": {"targetWeights": "USER_SPECIFIED", "weightsSource": "USER"},
                       "parameters": {"rebalance": "MONTH_END"}},
        "portfolio": {"initialCash": str(INITIAL_CASH), "currency": "KRW",
                      "positionSizing": {"type": "STRATEGY_DEFINED"}, "shortSelling": False,
                      "leverage": "1", "integerLots": True},
        "execution": {"signalTiming": "BAR_CLOSE", "executionTiming": "NEXT_BAR_OPEN",
                      "orderTypes": ["MARKET"], "costProfile": COST_PROFILE, "partialFill": False,
                      "liquidity": {"model": "UNLIMITED"}, "tradingHalt": "DEFER_TO_NEXT_TRADABLE",
                      "delisting": "CAUSE_BASED"},
        "evaluation": {"warmupTradingDays": 0, "liquidationAtEnd": False},
        "progress": {"batchTradingDays": 20},
    }


def first_rebalance(prices: dict[str, dict[date, Row]], calendar: list[date]) -> dict[str, object]:
    """① 2024-01-02 판단 → 2024-01-03 시가 체결."""
    decision = date(2024, 1, 2)
    fill_day = next_day(calendar, decision)
    targets = decide(INITIAL_CASH, {iid: prices[iid][decision].close for iid in WEIGHTS})
    cash, fills, deferred = buy_day(fill_day, INITIAL_CASH, {iid: (q, fill_day) for iid, q in targets.items()}, prices)
    assert not deferred
    return {
        "name": "first_rebalance",
        "engineInput": request("RECON-FIRST", "2024-01-02", "2024-01-05"),
        "fills": [f.as_record() for f in fills],
        "cashAfter": {fill_day.isoformat(): str(cash)},
    }


def distribution(prices: dict[str, dict[date, Row]], calendar: list[date], fixture_dir: Path) -> dict[str, object]:
    """② 2024-04-01 판단 → 04-02 체결, 04-29 권리락 보유 × 85원 → 05-02 지급."""
    decision = date(2024, 4, 1)
    fill_day = next_day(calendar, decision)
    targets = decide(INITIAL_CASH, {iid: prices[iid][decision].close for iid in WEIGHTS})
    cash, fills, deferred = buy_day(fill_day, INITIAL_CASH, {iid: (q, fill_day) for iid, q in targets.items()}, prices)
    assert not deferred
    with (fixture_dir / "distributions.csv").open(encoding="utf-8", newline="") as handle:
        rows = [r for r in csv.DictReader(handle) if r["instrument_id"] == BATTERY]
    (row,) = rows
    assert row["ex_date"] == "2024-04-29" and row["pay_date"] == "2024-05-02"
    held = next(f.quantity for f in fills if f.instrument_id == BATTERY)
    amount = Decimal(row["amount"]) * held
    return {
        "name": "distribution",
        "engineInput": request("RECON-DIST", "2024-04-01", "2024-04-26"),
        "fills": [f.as_record() for f in fills],
        "cashAfter": {fill_day.isoformat(): str(cash)},
        # 지급일은 별도 실행으로 본다 — 4월 말 리밸런싱이 끼어도 권리락일 보유는 04-02 체결분이다.
        "distribution": {
            "engineInput": request("RECON-DIST-PAY", "2024-04-01", "2024-05-03"),
            "instrumentId": BATTERY,
            "exDate": row["ex_date"],
            "payDate": row["pay_date"],
            "heldOnExDate": str(held),
            "amount": str(amount),
        },
    }


def halt_deferral(prices: dict[str, dict[date, Row]], calendar: list[date]) -> dict[str, object]:
    """③ 2024-06-10 판단 → 06-11 체결 예정, 반도체 ETF 정지 → 06-12 시가 체결."""
    decision = date(2024, 6, 10)
    fill_day = next_day(calendar, decision)
    assert prices[SEMICONDUCTOR][fill_day].suspended
    targets = decide(INITIAL_CASH, {iid: prices[iid][decision].close for iid in WEIGHTS})
    cash, fills, deferred = buy_day(fill_day, INITIAL_CASH, {iid: (q, fill_day) for iid, q in targets.items()}, prices)
    assert set(deferred) == {SEMICONDUCTOR}
    cash_after: dict[str, str] = {fill_day.isoformat(): str(cash)}
    day = next_day(calendar, fill_day)
    cash, late, deferred = buy_day(day, cash, deferred, prices)
    assert not deferred and len(late) == 1
    cash_after[day.isoformat()] = str(cash)
    return {
        "name": "halt_deferral",
        "engineInput": request("RECON-HALT", "2024-06-03", "2024-06-14", effectiveStart="2024-06-10"),
        "fills": [f.as_record() for f in fills + late],
        "cashAfter": cash_after,
    }


def write_csv(out_dir: Path, case: dict[str, object]) -> None:
    fills = case["fills"]
    assert isinstance(fills, list)
    with (out_dir / f"{case['name']}.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(fills[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(fills)
    cash_after = case["cashAfter"]
    assert isinstance(cash_after, dict)
    with (out_dir / f"{case['name']}_cash.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(["date", "cashAfter"])
        writer.writerows(cash_after.items())


def main(fixture_dir: Path, out_dir: Path, json_path: Path) -> None:
    prices = load_prices(fixture_dir)
    calendar = trading_days(prices)
    cases = [
        first_rebalance(prices, calendar),
        distribution(prices, calendar, fixture_dir),
        halt_deferral(prices, calendar),
    ]
    out_dir.mkdir(parents=True, exist_ok=True)
    for case in cases:
        write_csv(out_dir, case)
    payload = {
        "source": "docs/specs/backtest-messaging/examples/reconciliation/reconcile.py",
        "dataset": "FIXTURE-three_etf_24m",
        "tolerance": "0",
        "cases": cases,
    }
    json_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    for case in cases:
        print(case["name"], case["cashAfter"])


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3]))
