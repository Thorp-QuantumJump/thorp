"""사후 구간 잘라내기와 요약 계산.

011 화면의 숫자는 전부 여기서 나온다. 작업 메시지의 ``window``와 004 실행 사본에서 63·21거래일
구간을 잘라 수익률·최대 하락 폭·거래와 비용·월당 거래횟수·회전율·구간 손익 기여를 내고, 시장
자료에서 KOSPI 참고 값 두 개를 더한다.

정의는 research R3의 구간 전용 산식이다. **수익률·최대 하락 폭을 연환산하지 않고**(63거래일 값을
4배 확대하면 "더 벌 수 있다"로 읽힌다), 회전율만 연환산 단위를 값과 함께 밝힌다. 입력이 모자라면
0으로 채우지 않고 ``INPUT_INCOMPLETE``로 실패한다(원칙 VI).

``performance.calculate_analytics``를 구간 조각에 그대로 부르지 않는다(R3) — 그 함수의 회전율·손실
기여는 실행 전체를 전제로 하고, 여기서 필요한 것은 같은 경제적 정의를 구간 경계에 맞춘 값이다.
``performance.py``는 고치지 않는다.

**시장 자료는 메시지에 없다.** 원시 종가는 ``ClosePrices``로 주입받는다 — 이 모듈은 pandas도 DB도
모르고, 구간 계산만 한다. 소비자(M3)가 ``market_data.py``로 같은 구간을 읽어 넘긴다.

수량 이월은 **덮어쓰기**다. 체결로 더하되 ``dailyPositions`` 스냅샷이 있는 날은 그 수량으로 맞춘다 —
분할 같은 기업행위는 체결 밖에서 수량을 바꾸므로 체결만 누적하면 그날부터 평가액이 어긋난다
(research R3 대조에서 22개 구간 중 9개가 어긋났다).
"""

from collections import defaultdict
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from typing import Protocol

from worker.regime_link.contracts import (
    Basis,
    BasisKind,
    Benchmark,
    BenchmarkWindow,
    FillSide,
    Holding,
    InputTrade,
    PortfolioPoint,
    RegimeLinkErrorCode,
    RegimeLinkJob,
    RegimeLinkResultPayload,
    RegimeLinkScenario,
    ResultTrade,
    TurnoverBasis,
    WindowCosts,
    WindowSummary,
)

#: 표기 자리수. 계약 예시(`docs/specs/regime-link-messaging/examples/`)와 같은 자리수다.
RATIO = Decimal("0.0001")
MONEY = Decimal("0.01")
SHARE = Decimal("0.00000001")

#: 월당 거래횟수의 한 달(R3 "구간 거래일 수 / 21").
TRADING_DAYS_PER_MONTH = Decimal(21)

#: 회전율 연환산 계수. 값과 함께 ``TurnoverBasis.ANNUALIZED_252``를 실어 단위를 밝힌다.
TRADING_DAYS_PER_YEAR = Decimal(252)

#: 종목 손익 합과 자산 증감의 대조 허용 오차(원). 입력 금액이 전 단계에서 원 미만으로 반올림돼
#: 있어 정확히 0이 되지 않을 수 있다. 이보다 벌어지면 이월이나 자료가 틀린 것이다.
IDENTITY_TOLERANCE = Decimal("1")

ZERO = Decimal(0)


class SliceError(Exception):
    """구간 계산을 끝까지 갈 수 없다.

    ``code``는 그대로 ``FINISHED(FAILED)``의 오류 코드가 된다. 워커는
    ``DATA_INSUFFICIENT``를 내지 않는다(research R13) — 자료 부족은 발행 전 백엔드 판정이라
    여기까지 오지 않고, 여기서 드러난 부족은 입력 조립이나 자료 정합의 문제다.
    """

    def __init__(self, code: RegimeLinkErrorCode, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


def _incomplete(message: str) -> SliceError:
    return SliceError(RegimeLinkErrorCode.INPUT_INCOMPLETE, message)


def _missing(message: str) -> SliceError:
    return SliceError(RegimeLinkErrorCode.MARKET_DATA_MISSING, message)


class ClosePrices(Protocol):
    """원시 종가 조회. 없는 날은 ``None``이고 채우지 않는다."""

    def close(self, instrument_id: str, day: date) -> Decimal | None: ...


@dataclass(frozen=True)
class MappingClosePrices:
    """``{종목: {날짜: 종가}}`` 사전을 그대로 읽는 구현(테스트·픽스처용)."""

    closes: Mapping[str, Mapping[date, Decimal]]

    def close(self, instrument_id: str, day: date) -> Decimal | None:
        return self.closes.get(instrument_id, {}).get(day)


@dataclass(frozen=True)
class SliceOutcome:
    """계산 결과와 이벤트 봉투가 함께 실어야 하는 두 값.

    ``partial``·``delisted_on``은 ``result`` 안이 아니라 ``FINISHED`` payload에 있으므로
    소비자가 쓸 수 있게 따로 돌려준다.
    """

    result: RegimeLinkResultPayload
    partial: bool
    delisted_on: date | None


# --- 입력 정리 -----------------------------------------------------------------------


@dataclass(frozen=True)
class _Prepared:
    """구간 계산이 반복해서 쓰는 중간값."""

    days: list[date]
    equity: dict[date, Decimal]
    positions: dict[date, dict[str, int]]
    fills_by_day: dict[date, list[InputTrade]]
    baseline_value: Decimal
    opening: dict[str, int]
    sector_of: dict[str, str]


def _series_index(points: Sequence[PortfolioPoint]) -> dict[date, Decimal]:
    index: dict[date, Decimal] = {}
    previous: date | None = None
    for point in points:
        if point.date in index:
            raise _incomplete(f"portfolioSeries 에 같은 날짜가 두 번 있다: {point.date}")
        if previous is not None and point.date < previous:
            raise _incomplete("portfolioSeries 가 날짜 오름차순이 아니다")
        previous = point.date
        index[point.date] = point.equity
    return index


def _close_of(prices: ClosePrices, instrument_id: str, day: date) -> Decimal:
    value = prices.close(instrument_id, day)
    if value is None:
        raise _missing(f"{instrument_id} 의 {day} 종가가 없다")
    return value


def _valuation(
    prices: ClosePrices, holdings: Mapping[str, int], day: date
) -> tuple[Decimal, dict[str, Decimal]]:
    per_instrument = {
        iid: Decimal(quantity) * _close_of(prices, iid, day)
        for iid, quantity in holdings.items()
        if quantity
    }
    return sum(per_instrument.values(), ZERO), per_instrument


def _prepare(job: RegimeLinkJob, prices: ClosePrices) -> _Prepared:
    """구간 거래일·자산·일별 보유를 정리하고 입력의 빈 자리를 여기서 잡는다."""
    window = job.window
    inputs = job.inputs
    fresh_cash = job.job.scenario is RegimeLinkScenario.FRESH_CASH

    equity = _series_index(inputs.portfolio_series)
    days = sorted(day for day in equity if window.d0 <= day <= window.last_available)
    if not days:
        raise _incomplete("구간 안 portfolioSeries 행이 하나도 없다")
    if days[0] != window.d0:
        raise _incomplete(f"portfolioSeries 에 d0({window.d0}) 행이 없다")
    if days[-1] != window.last_available:
        raise _incomplete(f"portfolioSeries 에 lastAvailable({window.last_available}) 행이 없다")
    if len(days) != window.completed_trading_days:
        raise _incomplete(
            f"구간 거래일 수가 completedTradingDays 와 다르다: "
            f"{len(days)} != {window.completed_trading_days}"
        )
    if window.d21 not in equity:
        raise _incomplete(f"portfolioSeries 에 d21({window.d21}) 행이 없다")

    sector_of = {item.instrument_id: item.sector_id for item in job.universe.items}
    opening = {p.instrument_id: p.quantity for p in inputs.opening_positions if p.quantity}
    if fresh_cash:
        if opening:
            raise _incomplete("FRESH_CASH 는 시작 보유가 없어야 한다")
        baseline_value = ZERO
    else:
        if window.baseline_date not in equity:
            raise _incomplete(f"연속 운용인데 baselineDate({window.baseline_date}) 행이 없다")
        baseline_value, _ = _valuation(prices, opening, window.baseline_date)

    known = set(sector_of)
    used = set(opening) | {fill.instrument_id for fill in inputs.trades}
    used |= {flow.instrument_id for flow in inputs.cash_flows}
    for snapshot in inputs.daily_positions:
        used |= {p.instrument_id for p in snapshot.positions}
    unknown = sorted(used - known)
    if unknown:
        raise _incomplete(f"비교 묶음에 없는 종목이 입력에 있다: {', '.join(unknown)}")

    fills_by_day: dict[date, list[InputTrade]] = defaultdict(list)
    for fill in inputs.trades:
        fills_by_day[fill.time.date()].append(fill)

    snapshots = {snapshot.date: snapshot for snapshot in inputs.daily_positions}
    held = dict(opening)
    positions: dict[date, dict[str, int]] = {}
    for day in days:
        for fill in fills_by_day.get(day, []):
            signed = fill.quantity if fill.side is FillSide.BUY else -fill.quantity
            held[fill.instrument_id] = held.get(fill.instrument_id, 0) + signed
        snapshot = snapshots.get(day)
        if snapshot is not None:
            # 부분 갱신이 아니라 덮어쓰기다 — 스냅샷에 없는 종목은 그날 보유 0이다.
            held = {p.instrument_id: p.quantity for p in snapshot.positions if p.quantity}
        if any(quantity < 0 for quantity in held.values()):
            raise _incomplete(f"{day} 보유 수량이 음수가 됐다 — 체결·스냅샷이 어긋난다")
        positions[day] = {iid: quantity for iid, quantity in held.items() if quantity}

    outside = sorted(d for d in snapshots if d not in positions)
    if outside:
        raise _incomplete(f"구간 밖 dailyPositions 가 있다: {outside[0]}")

    return _Prepared(
        days=days,
        equity=equity,
        positions=positions,
        fills_by_day=dict(fills_by_day),
        baseline_value=baseline_value,
        opening=opening,
        sector_of=sector_of,
    )


# --- 구간 계산 -----------------------------------------------------------------------


def _base_equity(job: RegimeLinkJob, prepared: _Prepared) -> Decimal:
    """구간 수익률·낙폭의 기준값(R3).

    연속 운용은 ``baselineDate``의 자산, 현금 시작은 ``run.initialCash``다. 현금 시작이
    baseline 행을 쓰지 않는 이유는 그 실행이 d0에 현금으로 시작하기 때문이다.
    """
    if job.job.scenario is RegimeLinkScenario.FRESH_CASH:
        return job.run.initial_cash
    return prepared.equity[job.window.baseline_date]


def _max_drawdown(base: Decimal, values: Sequence[Decimal]) -> Decimal:
    """시작 기준값부터 다시 센 최대 낙폭. 전체 기간 낙폭을 잘라 쓰지 않는다(R3)."""
    peak = base
    worst = ZERO
    for value in values:
        peak = max(peak, value)
        if peak > ZERO:
            worst = min(worst, value / peak - 1)
    return worst


def _instrument_pnl(
    job: RegimeLinkJob,
    prepared: _Prepared,
    prices: ClosePrices,
    days: Sequence[date],
) -> dict[str, Decimal]:
    """종목 구간 손익 = 종료 평가액 − 시작 직전 평가액 + 매도대금 − 매수대금 + 분배금 − 직접 비용.

    가격에 이미 반영된 호가차·슬리피지를 다시 빼지 않는다 — 체결대금이 실제 체결가 × 수량이라
    그만큼 이미 불리하게 잡혀 있다. 직접 차감 비용(``cashCost`` = 수수료 + 세금)만 뺀다(R3).
    """
    start, end = days[0], days[-1]
    fresh_cash = job.job.scenario is RegimeLinkScenario.FRESH_CASH
    pnl: dict[str, Decimal] = {iid: ZERO for iid in prepared.sector_of}

    if not fresh_cash:
        for iid, quantity in prepared.opening.items():
            pnl[iid] -= Decimal(quantity) * _close_of(prices, iid, job.window.baseline_date)
    for iid, quantity in prepared.positions[end].items():
        pnl[iid] += Decimal(quantity) * _close_of(prices, iid, end)
    for day in days:
        for fill in prepared.fills_by_day.get(day, []):
            gross = Decimal(fill.quantity) * fill.price
            signed = gross if fill.side is FillSide.SELL else -gross
            pnl[fill.instrument_id] += signed - fill.cash_cost
    for flow in job.inputs.cash_flows:
        if start <= flow.date <= end:
            pnl[flow.instrument_id] += flow.amount
    return pnl


def _loss_contribution(
    pnl: Mapping[str, Decimal], sector_of: Mapping[str, str]
) -> dict[str, Decimal] | None:
    """섹터별 구간 손익 몫. 이익 종목도 들어가고 합이 1이며, 전체 손익 0이면 키를 생략한다(R3)."""
    total = sum(pnl.values(), ZERO)
    if total == ZERO:
        return None
    by_sector: dict[str, Decimal] = defaultdict(lambda: ZERO)
    for iid, value in pnl.items():
        by_sector[sector_of[iid]] += value
    return {
        sector: (value / total).quantize(SHARE, rounding=ROUND_HALF_UP)
        for sector, value in sorted(by_sector.items())
    }


def _summary(
    job: RegimeLinkJob,
    prepared: _Prepared,
    prices: ClosePrices,
    end: date,
    expected_days: int,
) -> WindowSummary:
    """63·21 두 구간을 같은 함수로 낸다. 연환산은 회전율에만 있다."""
    days = [day for day in prepared.days if day <= end]
    equities = [prepared.equity[day] for day in days]
    base = _base_equity(job, prepared)
    if base <= ZERO:
        raise _incomplete("구간 기준 자산이 0 이하다")

    commission = spread = slippage = tax = ZERO
    traded = ZERO
    trade_count = 0
    for day in days:
        for fill in prepared.fills_by_day.get(day, []):
            trade_count += 1
            commission += fill.costs.commission
            spread += fill.costs.spread
            slippage += fill.costs.slippage
            tax += fill.costs.tax
            traded += Decimal(fill.quantity) * fill.price

    count = Decimal(len(days))
    average_equity = sum(equities, ZERO) / count
    if average_equity <= ZERO:
        raise _incomplete("구간 평균 자산이 0 이하다")
    turnover = traded / 2 / average_equity * TRADING_DAYS_PER_YEAR / count

    pnl = _instrument_pnl(job, prepared, prices, days)
    total_pnl = sum(pnl.values(), ZERO)
    equity_change = equities[-1] - base
    if abs(total_pnl - equity_change) > IDENTITY_TOLERANCE:
        raise _missing(
            f"종목 손익 합({total_pnl})이 구간 자산 증감({equity_change})과 다르다 — "
            "수량 이월이나 시장 자료가 어긋났다"
        )

    return WindowSummary(
        trading_days=len(days),
        window_return=(equities[-1] / base - 1).quantize(RATIO, rounding=ROUND_HALF_UP),
        max_drawdown=_max_drawdown(base, equities).quantize(RATIO, rounding=ROUND_HALF_UP),
        trade_count=trade_count,
        trades_per_month=(Decimal(trade_count) * TRADING_DAYS_PER_MONTH / count).quantize(
            MONEY, rounding=ROUND_HALF_UP
        ),
        turnover=turnover.quantize(RATIO, rounding=ROUND_HALF_UP),
        turnover_basis=TurnoverBasis.ANNUALIZED_252,
        costs=WindowCosts(
            commission=commission.quantize(MONEY, rounding=ROUND_HALF_UP),
            spread=spread.quantize(MONEY, rounding=ROUND_HALF_UP),
            slippage=slippage.quantize(MONEY, rounding=ROUND_HALF_UP),
            tax=tax.quantize(MONEY, rounding=ROUND_HALF_UP),
            total=(commission + spread + slippage + tax).quantize(MONEY, rounding=ROUND_HALF_UP),
        ),
        loss_contribution=_loss_contribution(pnl, prepared.sector_of),
        complete=len(days) == expected_days,
    )


def _holdings(prepared: _Prepared, prices: ClosePrices, days: Sequence[date]) -> list[Holding]:
    """구간의 **모든** 거래일 실제 비중. 리밸런싱 시점 비중을 복사하지 않는다(R3)."""
    result: list[Holding] = []
    for day in days:
        equity = prepared.equity[day]
        if equity <= ZERO:
            raise _incomplete(f"{day} 자산이 0 이하라 비중을 낼 수 없다")
        _, per_instrument = _valuation(prices, prepared.positions[day], day)
        result.append(
            Holding(
                date=day,
                actual={
                    iid: (value / equity).quantize(RATIO, rounding=ROUND_HALF_UP)
                    for iid, value in sorted(per_instrument.items())
                },
            )
        )
    return result


def _trades(prepared: _Prepared, days: Sequence[date]) -> list[ResultTrade]:
    """표시용 거래 목록. 개수가 ``window63.tradeCount``와 같다."""
    result: list[ResultTrade] = []
    for day in days:
        for fill in prepared.fills_by_day.get(day, []):
            costs = fill.costs
            result.append(
                ResultTrade(
                    date=day,
                    instrument_id=fill.instrument_id,
                    side=fill.side,
                    quantity=fill.quantity,
                    price=fill.price,
                    cost=(costs.commission + costs.spread + costs.slippage + costs.tax).quantize(
                        MONEY, rounding=ROUND_HALF_UP
                    ),
                    reason=fill.reason,
                )
            )
    return result


def _benchmark_window(
    prices: ClosePrices, instrument_id: str, baseline: date, days: Sequence[date]
) -> BenchmarkWindow:
    """KOSPI 참고 값 **두 개만**. 전략과의 차이는 내지 않는다(R3)."""
    base = _close_of(prices, instrument_id, baseline)
    if base <= ZERO:
        raise _missing(f"{instrument_id} 의 {baseline} 종가가 0 이하다")
    closes = [_close_of(prices, instrument_id, day) for day in days]
    return BenchmarkWindow(
        window_return=(closes[-1] / base - 1).quantize(RATIO, rounding=ROUND_HALF_UP),
        max_drawdown=_max_drawdown(base, closes).quantize(RATIO, rounding=ROUND_HALF_UP),
    )


def slice_window(job: RegimeLinkJob, prices: ClosePrices) -> SliceOutcome:
    """작업 메시지 하나를 잘라 결과 payload를 만든다.

    ``window63``은 d0~min(d63, lastAvailable), ``window21``은 d0~d21이다. 상장폐지로 63을 못
    채우면 ``complete=false``·``partial=true``이고 ``window21``은 언제나 완성이다(R4).
    """
    prepared = _prepare(job, prices)
    window = job.window
    end63 = min(window.d63, window.last_available)
    if end63 not in prepared.equity:
        raise _incomplete(f"portfolioSeries 에 63거래일 구간 종료일({end63}) 행이 없다")

    days63 = [day for day in prepared.days if day <= end63]
    delisted = sorted(
        item.delisted_on
        for item in job.universe.items
        if item.delisted_on is not None and window.d0 <= item.delisted_on <= window.d63
    )
    result = RegimeLinkResultPayload(
        window63=_summary(job, prepared, prices, end63, 63),
        window21=_summary(job, prepared, prices, window.d21, 21),
        holdings=_holdings(prepared, prices, days63),
        trades=_trades(prepared, days63),
        kospi=Benchmark(
            window63=_benchmark_window(
                prices, job.market_data.benchmark_instrument_id, window.baseline_date, days63
            ),
            window21=_benchmark_window(
                prices,
                job.market_data.benchmark_instrument_id,
                window.baseline_date,
                [day for day in prepared.days if day <= window.d21],
            ),
        ),
        basis=Basis(
            kind=BasisKind.HISTORICAL_REVISION,
            data_version=job.market_data.data_version,
            dataset_id=job.market_data.dataset_id,
            analytics_method=job.run.analytics_method,
        ),
        input_digest=job.input_digest,
    )
    return SliceOutcome(
        result=result,
        partial=bool(delisted),
        delisted_on=delisted[0] if delisted else None,
    )
