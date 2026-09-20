"""004 저장 결과로 011 사후 구간 입력을 만들 수 있는지 확인한다(spec 011 T000, DEP-004-011).

011 워커가 받게 될 입력(``openingPositions``·``dailyPositions``·``fills``·``cashFlows`` 와
기준일 한 행의 ``equity``·``cash``)만 꺼내 구간을 다시 세고, 004 가 자기 회계로 낸 값과 맞는지 본다.
004 의 지표 함수(``performance.py``)는 부르지 않는다 — 같은 경로로 두 번 세면 대조가 아니다.

세 가지를 센다.

1. 시작 직전 평가액: 기준일 이전 마지막 스냅샷의 수량 × 기준일 원시 종가 = 004 의 ``positionValue``.
2. 구간 일별 평가액·현금·자산: 개별 체결(체결가·수량·직접 차감 비용)과 분배금만으로 이월해 재구성.
3. 종목 구간 손익 합(research R3 산식) = 종료 자산 − 시작 직전 자산.

수량이 체결 밖에서 바뀌는 기업행위(분할)는 체결만으로 이월할 수 없다. 004 가 그날 남기는 보유
스냅샷으로 덮어써야 하며, ``--ignore-split-snapshot`` 은 그 스냅샷을 빼면 얼마나 어긋나는지를
대조군으로 보여준다.

저장소 전체가 보이는 곳에서 ``worker/`` 를 작업 디렉터리로 두고 실행한다::

    uv run --no-sync python scripts/regime_link_input_check.py --out check.json
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from collections import defaultdict
from datetime import date, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

WORKER_DIR = Path(__file__).resolve().parents[1]
# 픽스처 경로는 테스트가 정한 것 하나를 쓴다 — 여기서 다시 쓰면 둘이 어긋난다.
sys.path.insert(0, str(WORKER_DIR / "tests"))

from fixture_paths import THREE_ETF_DIR  # noqa: E402
from golden_inputs import load_json  # noqa: E402

from worker.contracts.engine_output import (  # noqa: E402
    EngineOutput,
    FillResult,
    FillSide,
    PortfolioSnapshot,
)
from worker.engine_runner import PortfolioBacktestEngine  # noqa: E402
from worker.market_data import CsvMarketDataProvider  # noqa: E402

ZERO = Decimal(0)
#: 구간 길이(거래일). 011 의 기본 사후 구간이다.
LENGTH = 63
#: d0 을 잡는 거래일 번호. 픽스처 24개월 안에서 분배금·분할·월말 리밸런싱을 고루 덮는다.
D0_INDEXES = (60, 100, 150, 176)


def kst(when: Any) -> date:
    """체결 시각(UTC)을 거래일로 바꾼다. 테스트가 쓰는 변환과 같다."""
    return (when + timedelta(hours=9)).date()


def raw_closes() -> dict[tuple[str, date], Decimal]:
    """원시 종가. 일별 평가액은 004 엔진과 같은 식(수량 × 그날 원시 종가)으로 만든다."""
    closes: dict[tuple[str, date], Decimal] = {}
    with (THREE_ETF_DIR / "prices_raw.csv").open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            closes[(row["instrument_id"], date.fromisoformat(row["date"]))] = Decimal(row["close"])
    return closes


def cash_delta(fill: FillResult) -> Decimal:
    """체결 하나가 현금에 남기는 변화. 체결가에 이미 반영된 호가차·슬리피지는 다시 빼지 않는다."""
    gross = fill.price * fill.quantity
    if fill.side is FillSide.BUY:
        return -gross - fill.cash_cost
    return gross - fill.cash_cost


def check_window(
    series: list[PortfolioSnapshot],
    fills: list[FillResult],
    closes: dict[tuple[str, date], Decimal],
    d0_index: int,
    *,
    ignore_split_snapshot: bool = False,
) -> dict[str, Any]:
    """구간 하나를 011 입력만으로 다시 세고 004 의 값과 대조한다."""
    baseline = series[d0_index - 1]
    window = series[d0_index : d0_index + LENGTH]
    d0, last = window[0].date, window[-1].date

    opening: dict[str, Decimal] = {}
    for row in series[:d0_index]:
        if row.positions is not None:
            opening = {p.instrument_id: p.quantity for p in row.positions}
    daily_positions = {
        row.date: {p.instrument_id: p.quantity for p in row.positions}
        for row in window
        if row.positions is not None
    }
    window_fills = [f for f in fills if d0 <= kst(f.time) <= last]
    cash_flows = [(row.date, flow) for row in window for flow in (row.cash_flows or [])]

    fills_by_day: dict[date, list[FillResult]] = defaultdict(list)
    for fill in window_fills:
        fills_by_day[kst(fill.time)].append(fill)
    flows_by_day: dict[date, Decimal] = defaultdict(lambda: ZERO)
    for day, flow in cash_flows:
        flows_by_day[day] += flow.amount

    # ① 시작 직전 평가액
    baseline_value = sum(
        (quantity * closes[(iid, baseline.date)] for iid, quantity in opening.items()), ZERO
    )

    # ② 구간 일별 평가액·현금·자산
    held = dict(opening)
    cash = baseline.cash
    carried_apart_from_fills: list[str] = []
    mismatches = {"positionValue": 0, "cash": 0, "equity": 0}
    equity_by_day: dict[date, Decimal] = {}
    for row in window:
        day = row.date
        for fill in fills_by_day[day]:
            signed = fill.quantity if fill.side is FillSide.BUY else -fill.quantity
            held[fill.instrument_id] = held.get(fill.instrument_id, ZERO) + signed
            cash += cash_delta(fill)
        cash += flows_by_day[day]
        if day in daily_positions:
            for iid, quantity in daily_positions[day].items():
                if held.get(iid, ZERO) != quantity:
                    carried_apart_from_fills.append(
                        f"{day.isoformat()} {iid} 체결만={held.get(iid, ZERO)} 스냅샷={quantity}"
                    )
            if not ignore_split_snapshot:
                held = dict(daily_positions[day])
        position_value = sum((held[iid] * closes[(iid, day)] for iid in held), ZERO)
        equity = position_value + cash
        equity_by_day[day] = equity
        mismatches["positionValue"] += int(position_value != row.position_value)
        mismatches["cash"] += int(cash != row.cash)
        mismatches["equity"] += int(equity != row.equity)

    # ③ 종목 구간 손익 합 = 종료 자산 − 시작 직전 자산
    instruments = set(opening) | set(held) | {f.instrument_id for f in window_fills}
    total_pnl = ZERO
    for iid in instruments:
        end_value = held.get(iid, ZERO) * closes[(iid, last)]
        start_value = opening.get(iid, ZERO) * closes[(iid, baseline.date)]
        sold = sum(
            (
                f.price * f.quantity
                for f in window_fills
                if f.instrument_id == iid and f.side is FillSide.SELL
            ),
            ZERO,
        )
        bought = sum(
            (
                f.price * f.quantity
                for f in window_fills
                if f.instrument_id == iid and f.side is FillSide.BUY
            ),
            ZERO,
        )
        distribution = sum(
            (flow.amount for _, flow in cash_flows if flow.instrument_id == iid), ZERO
        )
        direct_cost = sum((f.cash_cost for f in window_fills if f.instrument_id == iid), ZERO)
        total_pnl += end_value - start_value + sold - bought + distribution - direct_cost

    partial_sells = partial_sell_records(opening, window_fills)
    return {
        "baselineDate": baseline.date.isoformat(),
        "d0": d0.isoformat(),
        "lastAvailable": last.isoformat(),
        "tradingDays": len(window),
        "fillCount": len(window_fills),
        "baselineValueGap": str(baseline_value - baseline.position_value),
        "mismatches": mismatches,
        "identityGap": str(total_pnl - (equity_by_day[last] - baseline.equity)),
        "carriedApartFromFills": carried_apart_from_fills,
        "cases": {
            "openingPositions": {iid: str(q) for iid, q in opening.items()},
            "partialSells": partial_sells,
            "distributions": [
                {
                    "date": day.isoformat(),
                    "instrumentId": flow.instrument_id,
                    "amount": str(flow.amount),
                }
                for day, flow in cash_flows
            ],
            "openQuantityAtEnd": {iid: str(q) for iid, q in held.items() if q != ZERO},
            "sellFillsWithSeparatedCosts": [
                {
                    "fillId": f.fill_id,
                    "date": kst(f.time).isoformat(),
                    "instrumentId": f.instrument_id,
                    "quantity": str(f.quantity),
                    "commission": str(f.costs.commission),
                    "tax": str(f.costs.tax),
                    "spread": str(f.costs.spread),
                    "slippage": str(f.costs.slippage),
                    "cashCost": str(f.cash_cost),
                }
                for f in window_fills
                if f.side is FillSide.SELL
            ],
        },
    }


def partial_sell_records(
    opening: dict[str, Decimal], window_fills: list[FillResult]
) -> list[dict[str, str]]:
    """구간 안에서 보유 일부만 판 체결. '구간 내 일부 매도'가 실제로 있었음을 보인다."""
    running = dict(opening)
    records: list[dict[str, str]] = []
    for fill in window_fills:
        before = running.get(fill.instrument_id, ZERO)
        if fill.side is FillSide.SELL and before > fill.quantity:
            records.append(
                {
                    "date": kst(fill.time).isoformat(),
                    "instrumentId": fill.instrument_id,
                    "held": str(before),
                    "sold": str(fill.quantity),
                }
            )
        signed = fill.quantity if fill.side is FillSide.BUY else -fill.quantity
        running[fill.instrument_id] = before + signed
    return records


def run_fixture(path: Path) -> EngineOutput:
    output = PortfolioBacktestEngine(CsvMarketDataProvider(THREE_ETF_DIR)).run(load_json(path))
    if output.fills is None:
        raise RuntimeError(f"{path.name}: 체결 기록이 없다. 011 은 이런 실행을 계산하지 않는다")
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, help="보고서를 쓸 파일(없으면 표준 출력)")
    parser.add_argument(
        "--ignore-split-snapshot",
        action="store_true",
        help="대조군: 기업행위일의 보유 스냅샷을 빼고 체결만으로 이월한다",
    )
    args = parser.parse_args()

    closes = raw_closes()
    windows: list[dict[str, Any]] = []
    for path in sorted((WORKER_DIR / "tests" / "fixtures").glob("engine-input-three-etf-*.json")):
        output = run_fixture(path)
        series = output.portfolio_series
        for index in D0_INDEXES:
            if index + LENGTH > len(series):
                continue
            window = check_window(
                series,
                output.fills or [],
                closes,
                index,
                ignore_split_snapshot=args.ignore_split_snapshot,
            )
            window["fixture"] = path.stem.removeprefix("engine-input-three-etf-")
            windows.append(window)

    failed = [
        w
        for w in windows
        if Decimal(w["baselineValueGap"]) != ZERO
        or Decimal(w["identityGap"]) != ZERO
        or any(w["mismatches"].values())
    ]
    report = {
        "windowCount": len(windows),
        "failedCount": len(failed),
        "windows": windows,
    }
    text = json.dumps(report, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
        print(f"{len(windows)}개 구간 중 {len(failed)}개 불일치 → {args.out}")
    else:
        print(text)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
