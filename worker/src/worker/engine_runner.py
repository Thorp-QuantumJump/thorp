"""자료 적재 → Nautilus 실행 → 완전한 EngineOutput 변환(spec 004 T006)."""

import hashlib
from datetime import UTC, date, datetime
from decimal import Decimal

import nautilus_trader

from worker.allocation import AllocationError
from worker.artifacts import OrderRecord, RunArtifacts
from worker.contracts.engine import ProgressCallback
from worker.contracts.engine_input import DelistingPolicy, EngineInput, Scenario
from worker.contracts.engine_output import (
    Diagnostics,
    EngineError,
    EngineOutput,
    ExecutionResult,
    Liquidated,
    Liquidation,
    MarkToMarket,
    RuleVersion,
    RunResult,
    RunStatus,
    Versions,
)
from worker.contracts.engine_output import Warning as ContractWarning
from worker.costs import CostModel, Side, total_costs
from worker.engine_support import unsupported_reasons
from worker.market_data import MarketData, MarketDataError, MarketDataProvider
from worker.performance import calculate_performance
from worker.portfolio_execution import (
    REBALANCE_MONTH_END,
    CallbackError,
    EngineFailure,
    PortfolioRebalanceExecution,
    effective_random_seed,
    execute_portfolio,
    plan_days,
)

ZERO = Decimal("0")


def evaluation_start(request: EngineInput, calendar: list[date]) -> date:
    """성과 집계를 시작하는 거래일(FR-014·R-15).

    우선순위: ``FRESH_CASH_FROM`` 의 ``scenarioParams.startDate`` → 백엔드가 계산한
    ``run.effectiveStart`` → ``evaluation.warmupTradingDays`` 만큼 소비한 뒤 → 규칙의
    ``lookbackDays`` 만큼 소비한 뒤. 어느 것도 없으면 첫 거래일이다. 워밍업 일수를 채울 자료가
    없으면 지어내지 않고 ``DATA_INSUFFICIENT`` 다.
    """
    run = request.run
    in_range = [day for day in calendar if run.start <= day <= run.end]
    if not in_range:
        raise MarketDataError("No trading days in the requested period")
    if run.scenario is Scenario.FRESH_CASH_FROM:
        # engine_support 가 존재·형식을 이미 확인했다.
        fresh_from = date.fromisoformat(str((run.scenario_params or {})["startDate"]))
        candidates = [day for day in in_range if day >= fresh_from]
        if not candidates:
            raise MarketDataError(
                f"No trading day on or after scenarioParams.startDate {fresh_from}"
            )
        return candidates[0]
    if run.effective_start is not None:
        candidates = [day for day in in_range if day >= run.effective_start]
        if not candidates:
            raise MarketDataError(
                f"No trading day on or after run.effectiveStart {run.effective_start}"
            )
        return candidates[0]
    warmup = request.evaluation.warmup_trading_days
    if warmup is None:
        warmup = int((request.allocation.rules or {}).get("lookbackDays", 0))
    if warmup >= len(in_range):
        raise MarketDataError(
            f"warmup of {warmup} trading days leaves no evaluation day in {len(in_range)} available"
        )
    return in_range[warmup]


def _run_level_warnings(request: EngineInput, first_evaluation_day: date) -> list[ContractWarning]:
    """특정 거래일이 아니라 실행 전체에 걸리는 경고. ``Warning.date`` 는 필수라 평가 첫날을 쓴다."""
    if request.execution.delisting is not DelistingPolicy.CAUSE_BASED:
        return []
    # 공급자가 폐지 사유를 주지 않아 사유별 청산을 구현할 수 없다. 조용히 통과시키면 생존 편향을
    # 눈치채지 못한다(ADR-0003 D-상장폐지). 종목마다가 아니라 실행마다 1건이다.
    return [
        ContractWarning(
            date=first_evaluation_day,
            instrument_id=request.universe.instrument_ids[0],
            code="DELISTING_CLASSIFICATION_UNSUPPORTED",
            message=(
                "execution.delisting=CAUSE_BASED was accepted, but this engine does not classify "
                "delisting causes; the data provider supplies no cause and sessions without data "
                "are skipped. Results may carry survivorship bias."
            ),
        )
    ]


def _liquidation(request: EngineInput, execution: PortfolioRebalanceExecution) -> Liquidation:
    """종료일 종가에 전량 매도했다면(비용 포함) 얼마가 남는가(FR-017).

    가정이므로 거래·비용 합계에는 넣지 않는다.
    """
    costs = CostModel(request.execution.cost_profile)
    initial = request.portfolio.initial_cash
    last = execution.series[-1]
    proceeds = ZERO
    for iid in execution.instrument_ids:
        quantity = execution.quantity[iid]
        if quantity == ZERO:
            continue
        close = execution.last_close[iid]
        proceeds += costs.effective_price(Side.SELL, close) * quantity
        proceeds -= costs.for_fill(Side.SELL, close, quantity).cash
    liquidated = execution.cash + proceeds
    return Liquidation(
        mark_to_market=MarkToMarket(
            final_equity=last.equity, total_return=last.equity / initial - 1
        ),
        liquidated=Liquidated(
            final_equity=liquidated,
            total_return=liquidated / initial - 1,
            liquidation_cost=last.equity - liquidated,
        ),
    )


class PortfolioBacktestEngine:
    """다종목 리밸런싱 엔진. 공급자는 인스턴스의 성질이므로 생성자로 주입한다(기본값 없음, R-09)."""

    def __init__(self, market_data: MarketDataProvider) -> None:
        self.market_data = market_data

    def run(
        self,
        engine_input: EngineInput | dict,
        on_progress: ProgressCallback | None = None,
    ) -> EngineOutput:
        """계약 메시지만 돌려준다(``contracts.engine.BacktestEngine`` Protocol)."""
        output, _ = self.run_with_artifacts(engine_input, on_progress)
        return output

    def run_with_artifacts(
        self,
        engine_input: EngineInput | dict,
        on_progress: ProgressCallback | None = None,
    ) -> tuple[EngineOutput, RunArtifacts]:
        """계약 메시지와 로컬 진단 산출물을 함께 돌려준다. 상태는 전부 지역 변수다."""
        request = EngineInput.model_validate(engine_input)
        started = datetime.now(UTC)
        metadata = {
            "engineVersion": nautilus_trader.__version__,
            "inputHash": "sha256:"
            + hashlib.sha256(request.model_dump_json(by_alias=True).encode()).hexdigest(),
            "input": request.model_dump(mode="json", by_alias=True),
            "randomSeed": effective_random_seed(request.run),
            "metricsVersion": "DAILY_252_DDOF1_RF0_V1",
            "simulationCurrency": "KRW_SIM (1:1 KRW, precision 8)",
        }
        orders: list[OrderRecord] = []
        # 계약은 통과했지만 이 엔진이 계산할 수 없는 값은 실행 전에 거절한다(README §4.3).
        reasons = unsupported_reasons(request)
        if reasons:
            return (
                self._rejected(request, None, started, "INVALID_REQUEST", "; ".join(reasons)),
                RunArtifacts(orders=orders, metadata=metadata),
            )
        market: MarketData | None = None
        try:
            market = self.market_data.load(
                request.universe.instrument_ids,
                request.run.start,
                request.run.end,
                request.market_data.data_version,
            )
            calendar = market.trading_days
            first_day = evaluation_start(request, calendar)
            plan = plan_days(
                calendar,
                first_day,
                request.run.end,
                str(request.allocation.parameters.get("rebalance", REBALANCE_MONTH_END)),
            )
            metadata.update(
                {
                    "datasetId": market.dataset_id,
                    "evaluationStart": first_day.isoformat(),
                    "evaluationEnd": plan.evaluation_days[-1].isoformat(),
                    "warmupTradingDays": len([d for d in calendar if d < first_day]),
                    "totalTradingDays": len(plan.evaluation_days),
                    "decisionDays": len(plan.decision_days),
                    "dataHash": "sha256:"
                    + hashlib.sha256(
                        "".join(market.prices[i].to_csv() for i in sorted(market.prices)).encode()
                    ).hexdigest(),
                }
            )
            execution = execute_portfolio(request, market, plan, on_progress)
            orders = execution.orders
            costs = total_costs(execution.fills)
            performance = calculate_performance(
                request.portfolio.initial_cash,
                execution.series,
                execution.trades,
                request.portfolio.currency,
            )
            output = EngineOutput(
                run=RunResult(
                    run_id=request.run.run_id,
                    status=RunStatus.COMPLETED,
                    started_at=started,
                    finished_at=datetime.now(UTC),
                    partial=False,
                    last_trading_date=execution.series[-1].date,
                    effective_start=execution.series[0].date,
                    effective_end=execution.series[-1].date,
                ),
                # 자산 곡선이 이미 비용을 뺀 값이라 총수익률이 곧 순수익률이다. 지표 확장은 T008.
                performance=performance.model_copy(
                    update={
                        "net_total_return": performance.total_return,
                        "total_trading_cost": costs.total,
                    }
                ),
                portfolio_series=execution.series,
                weights_series=execution.weights_series,
                weights_source=execution.weights_source,
                trades=execution.trades,
                execution=ExecutionResult(
                    order_count=len(orders),
                    fill_count=sum(order.status == "FILLED" for order in orders),
                    rejected_order_count=sum(order.status == "REJECTED" for order in orders),
                    total_commission=costs.commission,
                    total_slippage_cost=costs.spread + costs.slippage,
                ),
                costs=costs,
                liquidation=(
                    _liquidation(request, execution)
                    if request.evaluation.liquidation_at_end
                    else None
                ),
                exceptions=execution.exceptions,
                versions=self._versions(request, market),
                diagnostics=Diagnostics(
                    warnings=_run_level_warnings(request, execution.series[0].date)
                    + execution.warnings
                ),
            )
        except CallbackError:
            raise
        except AllocationError as exc:
            output = self._rejected(request, market, started, exc.code, str(exc))
        except MarketDataError as exc:
            output = self._rejected(request, market, started, "DATA_INSUFFICIENT", str(exc))
        except EngineFailure as exc:
            # 같은 입력이면 다시 나는 엔진 결함. 결과로 돌려준다 — 재전달해도 달라지지 않는다.
            output = self._rejected(request, market, started, "ENGINE_ERROR", str(exc))
        # 그 밖의 예외(공급자 접속 실패 등 일시 장애)는 결과로 바꾸지 않고 소비자에게 전파한다.
        # 소비자는 ACK 하지 않고 재전달 경로로 넘긴다(계약 §7 ③). 여기서 FAILED 로 바꾸면
        # FINISHED 가 발행·ACK 되어 일시 장애가 최종 실패로 굳는다.
        return output, RunArtifacts(orders=orders, metadata=metadata)

    @staticmethod
    def _versions(request: EngineInput, market: MarketData | None) -> Versions:
        cost = request.execution.cost_profile
        return Versions(
            mapping=request.universe.mapping_version,
            rules=RuleVersion(version=request.allocation.rules_version),
            cost_profile=RuleVersion(version=cost.version, provisional=cost.provisional),
            data=market.dataset_id if market is not None else None,
            engine=f"nautilus_trader {nautilus_trader.__version__}",
        )

    @classmethod
    def _rejected(
        cls,
        request: EngineInput,
        market: MarketData | None,
        started: datetime,
        code: str,
        message: str,
    ) -> EngineOutput:
        """실패는 예외가 아니라 ``run.error`` 로 반환한다(계약 §4.3). 성과 블록은 채우지 않는다."""
        return EngineOutput(
            run=RunResult(
                run_id=request.run.run_id,
                status=RunStatus.FAILED,
                started_at=started,
                finished_at=datetime.now(UTC),
                partial=False,
                error=EngineError(code=code, message=message),
            ),
            portfolio_series=[],
            trades=[],
            versions=cls._versions(request, market),
            diagnostics=Diagnostics(warnings=[]),
        )
