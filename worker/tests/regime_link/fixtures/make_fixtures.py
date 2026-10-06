"""구간 계산 시험용 픽스처 생성기(spec 011 T014).

``test_slicer.py``와 독립 계산
(``docs/specs/regime-link-messaging/examples/reconciliation/reconcile.py``)이 함께 읽는 입력을
만든다. 만들어지는 짝은 사례마다 둘이다.

- ``{사례}.job.json`` — ``RegimeLinkJob`` 본문(계약 camelCase 그대로)
- ``{사례}.closes.json`` — 그 구간의 원시 종가(``{종목: {날짜: 종가}}``). 시장 자료는 메시지에
  없으므로 워커가 따로 읽는 값을 픽스처로 고정한다

수치는 실제 현금 회계로 만든다. 가격·수량이 정수이고 체결가·비용을 원 미만 둘째 자리에서
끊으므로 ``equity = cash + 평가액``과 종목 손익 항등식이 **정확히** 성립한다.

손으로 고치지 말고 이 파일을 고쳐 다시 만든다.

    python worker/tests/regime_link/fixtures/make_fixtures.py
"""

from __future__ import annotations

import hashlib
import json
import random
from datetime import date, timedelta
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path
from typing import Any

HERE = Path(__file__).resolve().parent
SEED = 20260921
ZERO = Decimal(0)
BPS = Decimal(10_000)

# 비용 프로필 `KR-ETF-2026-09`(PD-07). 국내주식형 ETF라 세금은 0이다.
COMMISSION_BPS = Decimal("1.5")
SPREAD_BPS = Decimal(3)
SLIPPAGE_BPS = Decimal(5)
TAX_BPS = ZERO

SEMI = "091160.XKRX"
BATTERY = "305720.XKRX"
BIO = "244580.XKRX"
BENCHMARK = "KOSPI"
UNIVERSE = [("SEMICONDUCTOR", SEMI), ("BATTERY", BATTERY), ("BIO", BIO)]

BASELINE = date(2012, 3, 30)
OPENING = {SEMI: 179, BATTERY: 151, BIO: 233}
START_PRICE = {SEMI: 23_200, BATTERY: 18_600, BIO: 12_400, BENCHMARK: 2_000}
INITIAL_CASH = Decimal(10_000_000)
OPENING_CASH = Decimal(1_200_000)

SPLIT_DAY = 30  # BIO 2:1 — 체결이 아니라 기업행위로 수량이 바뀐다
SPLIT_RATIO = 2
DISTRIBUTION_DAY = 45  # SEMI 주당 100원
DISTRIBUTION_PER_SHARE = Decimal(100)
REBALANCE: dict[int, list[tuple[str, str, int]]] = {
    5: [(SEMI, "SELL", 6), (BATTERY, "BUY", 4)],
    41: [(BIO, "SELL", 12), (SEMI, "BUY", 3)],
}


def money(value: Decimal) -> str:
    return str(value.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP))


def trading_days(start: date, count: int) -> list[date]:
    """주말만 뺀 픽스처 자체 달력. 실제 KRX 휴장일과 다르다."""
    days: list[date] = []
    day = start
    while len(days) < count:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def build_closes(
    days: list[date], *, flat_at: int | None = None, split: bool = True
) -> dict[str, dict[date, Decimal]]:
    """시드 고정 랜덤워크 종가(정수 원).

    ``flat_at``이 있으면 그 거래일 번호의 종가를 baseline 과 같게 맞춘다 — 구간 손익이 정확히 0인
    사례를 만들기 위한 것이다(``days[0]``이 baselineDate).
    """
    rng = random.Random(SEED)
    closes: dict[str, dict[date, Decimal]] = {}
    for iid in (SEMI, BATTERY, BIO, BENCHMARK):
        level = Decimal(START_PRICE[iid])
        series: dict[date, Decimal] = {}
        for index, day in enumerate(days):
            if index:
                level += Decimal(rng.randint(-90, 95))
            if split and iid == BIO and index == SPLIT_DAY + 1:
                level = (level / SPLIT_RATIO).quantize(Decimal(1), rounding=ROUND_HALF_UP)
            series[day] = level
        if flat_at is not None:
            series[days[flat_at]] = series[days[0]]
        closes[iid] = series
    return closes


def fill_of(iid: str, day: date, side: str, quantity: int, reference: Decimal, index: int) -> dict:
    """체결 한 건. 비용은 ``수량 × referencePrice``에 매기고 충격은 체결가에 반영한다."""
    gross = reference * quantity
    commission = (gross * COMMISSION_BPS / BPS).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    spread = (gross * SPREAD_BPS / BPS).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    slippage = (gross * SLIPPAGE_BPS / BPS).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    tax = (gross * TAX_BPS / BPS).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    impact = ((spread + slippage) / quantity).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
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


def cash_delta(fill: dict) -> Decimal:
    gross = Decimal(fill["price"]) * fill["quantity"]
    direct = Decimal(fill["cashCost"])
    return gross - direct if fill["side"] == "SELL" else -gross - direct


def simulate(
    days: list[date],
    closes: dict[str, dict[date, Decimal]],
    *,
    fresh_cash: bool,
    last_index: int,
    trade: bool,
) -> dict[str, Any]:
    """구간을 실제로 굴려 ``inputs`` 블록을 만든다. ``days[0]``이 baselineDate 다."""
    held: dict[str, int] = {} if fresh_cash else dict(OPENING)
    cash = INITIAL_CASH if fresh_cash else OPENING_CASH
    series: list[dict[str, str]] = []
    daily_positions: list[dict[str, Any]] = []
    fills: list[dict[str, Any]] = []
    cash_flows: list[dict[str, Any]] = []

    def value_on(day: date) -> Decimal:
        return sum((Decimal(q) * closes[iid][day] for iid, q in held.items()), ZERO)

    def snapshot(day: date) -> None:
        daily_positions.append(
            {
                "date": day.isoformat(),
                "positions": [
                    {"instrumentId": iid, "quantity": q} for iid, q in sorted(held.items()) if q
                ],
            }
        )

    if not fresh_cash:
        baseline = days[0]
        series.append(
            {
                "date": baseline.isoformat(),
                "equity": money(value_on(baseline) + cash),
                "cash": money(cash),
            }
        )

    for index, day in enumerate(days[1:]):
        if index > last_index:
            break
        changed = False
        if fresh_cash and index == 0:
            for iid, quantity in sorted(OPENING.items()):
                fill = fill_of(iid, day, "BUY", quantity, closes[iid][day], len(fills) + 1)
                fills.append(fill)
                cash += cash_delta(fill)
                held[iid] = held.get(iid, 0) + quantity
            changed = True
        if trade:
            for iid, side, quantity in REBALANCE.get(index, []):
                fill = fill_of(iid, day, side, quantity, closes[iid][day], len(fills) + 1)
                fills.append(fill)
                cash += cash_delta(fill)
                held[iid] = held.get(iid, 0) + (quantity if side == "BUY" else -quantity)
                changed = True
            if index == SPLIT_DAY and held.get(BIO):
                held[BIO] *= SPLIT_RATIO
                changed = True
            if index == DISTRIBUTION_DAY and held.get(SEMI):
                amount = DISTRIBUTION_PER_SHARE * held[SEMI]
                cash += amount
                cash_flows.append(
                    {
                        "date": day.isoformat(),
                        "instrumentId": SEMI,
                        "type": "DISTRIBUTION",
                        "amount": money(amount),
                    }
                )
        if changed:
            snapshot(day)
        series.append(
            {
                "date": day.isoformat(),
                "equity": money(value_on(day) + cash),
                "cash": money(cash),
            }
        )

    opening = (
        []
        if fresh_cash
        else [{"instrumentId": iid, "quantity": q} for iid, q in sorted(OPENING.items())]
    )
    return {
        "portfolioSeries": series,
        "openingPositions": opening,
        "dailyPositions": daily_positions,
        "trades": fills,
        "cashFlows": cash_flows,
    }


def digest(body: dict[str, Any]) -> str:
    """계약과 같은 canonical JSON sha256(README "다시 만드는 법")."""
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def build_job(
    name: str,
    *,
    fresh_cash: bool = False,
    last_index: int = 62,
    trade: bool = True,
    delisted: tuple[str, int] | None = None,
    flat_at: int | None = None,
) -> tuple[dict[str, Any], dict[str, dict[date, Decimal]]]:
    days = trading_days(BASELINE, 64)  # days[0] = baselineDate, days[1] = d0
    closes = build_closes(days, flat_at=None if flat_at is None else flat_at + 1, split=trade)
    inputs = simulate(days, closes, fresh_cash=fresh_cash, last_index=last_index, trade=trade)
    items: list[dict[str, Any]] = []
    for sector, iid in UNIVERSE:
        item: dict[str, Any] = {"sectorId": sector, "instrumentId": iid}
        if delisted is not None and delisted[0] == iid:
            item["delistedOn"] = days[delisted[1] + 1].isoformat()
        items.append(item)

    window = {
        "d0": days[1].isoformat(),
        "d21": days[21].isoformat(),
        "d63": days[63].isoformat(),
        "lastAvailable": days[last_index + 1].isoformat(),
        "completedTradingDays": last_index + 1,
        "baselineDate": days[0].isoformat(),
    }
    run = {
        "runId": f"RUN-{name.upper()}",
        "effectiveStart": date(2010, 4, 2).isoformat(),
        "initialCash": money(INITIAL_CASH),
        "analyticsMethod": "ANALYTICS-1",
    }
    universe = {"mappingVersion": "SECTOR-ETF-2026-09", "items": items}
    market_data = {
        "datasetId": "KRX_DAILY_20260915",
        "dataVersion": "KRX-2026-09-09",
        "benchmarkInstrumentId": BENCHMARK,
    }
    scenario = "FRESH_CASH" if fresh_cash else "CONTINUOUS"
    job = {
        "job": {"linkId": f"RL-T014-{name}", "attempt": 1, "scenario": scenario},
        "window": window,
        "run": run,
        "universe": universe,
        "marketData": market_data,
        "inputs": inputs,
        "inputDigest": digest(
            {
                "inputs": inputs,
                "window": window,
                "scenario": scenario,
                "marketData": market_data,
            }
        ),
    }
    return job, closes


def write(name: str, job: dict[str, Any], closes: dict[str, dict[date, Decimal]]) -> None:
    last = date.fromisoformat(job["window"]["lastAvailable"])
    first = date.fromisoformat(job["window"]["baselineDate"])
    trimmed = {
        iid: {day.isoformat(): str(price) for day, price in series.items() if first <= day <= last}
        for iid, series in closes.items()
    }
    (HERE / f"{name}.job.json").write_text(
        json.dumps(job, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    (HERE / f"{name}.closes.json").write_text(
        json.dumps(trimmed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


#: 사례 이름과 뜻. ``reconcile.py``·``test_slicer.py``가 이 이름으로 읽는다.
CASES = {
    "continuous_63": "63거래일 완성. 시작 전 매수·일부 매도·분할·분배금·미청산 잔고",
    "incomplete_34": "34거래일 미완성. window63.complete=false",
    "fresh_cash": "현금 시작. baseline 행이 없고 기준은 initialCash",
    "partial_delisted": "구간 안 상장폐지. 폐지 전날까지 잘라 담았다",
    "zero_pnl": "전체 구간 손익 0. lossContribution 생략",
}


def main() -> None:
    write("continuous_63", *build_job("continuous_63"))
    write("incomplete_34", *build_job("incomplete_34", last_index=33))
    write("fresh_cash", *build_job("fresh_cash", fresh_cash=True))
    write(
        "partial_delisted",
        *build_job("partial_delisted", last_index=39, delisted=(BIO, 40)),
    )
    write("zero_pnl", *build_job("zero_pnl", trade=False, flat_at=62))
    for name, meaning in CASES.items():
        print(f"{name}: {meaning}")


if __name__ == "__main__":
    main()
