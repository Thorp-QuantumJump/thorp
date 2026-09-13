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
from worker.target_risk import target_risk_rules

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
        if run.scenario is Scenario.TARGET_RISK:
            # 첫 판단일에 추정할 수익률이 있어야 한다. 백엔드도 같은 규칙으로 워밍업을 확정한다.
            warmup = max(warmup, target_risk_rules(run).lookback_days)
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
        # 일시 장애(DB 접속 실패 등)는 자료 적재에서만 난다. 그래서 이 호출만 try 밖에 두고
        # 그대로 전파한다 — 소비자는 ACK 하지 않고 재전달 경로로 넘긴다(계약 §7 ③). 결과로
        # 바꾸면 FINISHED 가 발행·ACK 되어 일시 장애가 최종 실패로 굳는다. 자료 부족은 확정
        # 오류이므로 아래에서 결과로 바꾼다.
        try:
            market = self.market_data.load(
                request.universe.instrument_ids,
                request.run.start,
                request.run.end,
                request.market_data.data_version,
            )
        except MarketDataError as exc:
            return (
                self._rejected(request, None, started, "DATA_INSUFFICIENT", str(exc)),
                RunArtifacts(orders=orders, metadata=metadata),
            )
        try:
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
            output = self._rejected(request, market, started, "ENGINE_ERROR", str(exc))
        except Exception as exc:  # noqa: BLE001 - 아래 이유로 의도한 광역 처리
            # 자료가 메모리에 오른 뒤의 예외는 전부 결정적이다 — 같은 입력이면 다시 난다. 전파하면
            # 소비자가 재전달하고 한 번 더 같은 자리에서 죽어 DLQ(SYSTEM_FAILURE)로 가는데, 그건
            # 장애가 아니라 엔진 결함이다. 결과로 돌려준다(계약 §4.3 ENGINE_ERROR).
            output = self._rejected(
                request, market, started, "ENGINE_ERROR", f"{type(exc).__name__}: {exc}"
            )
        return output, RunArtifacts(orders=orders, metadata=metadata)

    @staticmethod
    def _versions(request: EngineInput, market: MarketData | None) -> Versions:
        cost = request.execution.cost_profile
        # 거절 결과에도 versions 가 붙으므로 여기서는 검증하지 않고 있는 값만 읽는다.
        overlay_version = (request.run.scenario_rules or {}).get("version")
        scenario_rules = (
            str(overlay_version)
            if request.run.scenario is Scenario.TARGET_RISK and overlay_version is not None
            else None
        )
        return Versions(
            mapping=request.universe.mapping_version,
            rules=RuleVersion(version=request.allocation.rules_version),
            scenario_rules=scenario_rules,
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
