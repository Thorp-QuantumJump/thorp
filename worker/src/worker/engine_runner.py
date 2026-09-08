"""Data loading, native execution and complete EngineOutput conversion."""

import hashlib
from datetime import UTC, datetime
from decimal import Decimal

import nautilus_trader

from worker.artifacts import OrderRecord, RunArtifacts
from worker.contracts.engine import ProgressCallback
from worker.contracts.engine_output import (
    Diagnostics,
    EngineError,
    EngineOutput,
    ExecutionResult,
    RunResult,
    RunStatus,
)
from worker.engine_contracts import EngineInput
from worker.market_data import DataProvider, MarketDataError, YFinanceDataProvider, validate_prices
from worker.nautilus_engine import CallbackError, execute_nautilus
from worker.performance import calculate_performance


class NautilusMomentumEngine:
    """Nautilus 기반 단일 종목 모멘텀 엔진.

    시장 데이터 공급자는 실행 한 건의 인자가 아니라 엔진 인스턴스의 성질이므로 생성자로
    주입한다. 생성자는 네트워크 I/O를 하지 않는다(실제 조회와 yfinance import는
    ``DataProvider.load`` 시점에 일어난다). 실행 상태는 전부 ``run`` 지역 변수이므로
    같은 인스턴스로 여러 번 실행해도 서로 영향을 주지 않는다.
    """

    def __init__(self, data_provider: DataProvider | None = None) -> None:
        self.data_provider = data_provider or YFinanceDataProvider()

    def run(
        self,
        engine_input: EngineInput | dict,
        on_progress: ProgressCallback | None = None,
    ) -> EngineOutput:
        """계약 메시지만 돌려준다 (``contracts.engine.BacktestEngine`` Protocol 모양).

        주문 원장·재현성 메타데이터가 필요하면 ``run_with_artifacts``를 쓴다.
        """
        output, _ = self.run_with_artifacts(engine_input, on_progress)
        return output

    def run_with_artifacts(
        self,
        engine_input: EngineInput | dict,
        on_progress: ProgressCallback | None = None,
    ) -> tuple[EngineOutput, RunArtifacts]:
        """계약 메시지와 로컬 진단 산출물을 함께 돌려준다.

        산출물을 인스턴스 속성으로 남기지 않고 반환값으로만 넘기므로, 같은 엔진 인스턴스를
        여러 번(또는 나중에 병렬로) 실행해도 실행 사이에 결과가 섞이지 않는다.
        """
        request = EngineInput.model_validate(engine_input)
        started = datetime.now(UTC)
        metadata = {
            "engineVersion": nautilus_trader.__version__,
            "inputHash": "sha256:"
            + hashlib.sha256(request.model_dump_json(by_alias=True).encode()).hexdigest(),
            "input": request.model_dump(mode="json", by_alias=True),
            "commissionRate": "0",
            "taxRate": "0",
            "slippageBps": 5,
            "metricsVersion": "DAILY_252_DDOF1_RF0_V1",
            "simulationCurrency": "KRW_SIM (1:1 KRW, precision 8)",
        }
        orders: list[OrderRecord] = []
        try:
            dataset = self.data_provider.load(request)
            if dataset.instrument_id != request.universe.instrument_ids[0]:
                raise MarketDataError("Provider instrument does not match requested instrument")
            if dataset.adjustment != request.market_data.adjustment:
                raise MarketDataError("Provider adjustment does not match requested adjustment")
            prices = validate_prices(dataset.prices)
            prices = prices.loc[prices.index.date <= request.run.end]
            evaluation = prices.loc[prices.index.date >= request.run.start]
            if evaluation.empty:
                raise MarketDataError("No evaluation trading days in requested period")
            metadata.update(dataset.metadata)
            metadata.update(
                {
                    "ticker": dataset.ticker,
                    "instrumentId": dataset.instrument_id,
                    "dataSource": dataset.source,
                    "adjustment": dataset.adjustment,
                    "evaluationStart": evaluation.index[0].date().isoformat(),
                    "evaluationEnd": evaluation.index[-1].date().isoformat(),
                    "warmupTradingDays": len(prices) - len(evaluation),
                    "totalTradingDays": len(evaluation),
                    "dataHash": "sha256:" + hashlib.sha256(prices.to_csv().encode()).hexdigest(),
                }
            )
            execution = execute_nautilus(request, prices, on_progress)
            orders = execution.orders
            slip = sum((trade.slippage_cost for trade in execution.trades), Decimal("0"))
            output = EngineOutput(
                run=RunResult(
                    backtest_id=request.run.backtest_id,
                    status=RunStatus.COMPLETED,
                    started_at=started,
                    finished_at=datetime.now(UTC),
                ),
                performance=calculate_performance(
                    request.portfolio.initial_cash,
                    execution.series,
                    execution.trades,
                    request.portfolio.currency,
                ),
                portfolio_series=execution.series,
                trades=execution.trades,
                execution=ExecutionResult(
                    order_count=len(orders),
                    fill_count=sum(order.status == "FILLED" for order in orders),
                    rejected_order_count=sum(order.status == "REJECTED" for order in orders),
                    total_commission=Decimal("0"),
                    total_slippage_cost=slip,
                ),
                diagnostics=Diagnostics(warnings=execution.warnings),
            )
        except CallbackError:
            raise
        except Exception as exc:
            # 실패해도 존재하지 않는 성과를 0으로 채우지 않는다 (계약 §4.3).
            output = EngineOutput(
                run=RunResult(
                    backtest_id=request.run.backtest_id,
                    status=RunStatus.FAILED,
                    started_at=started,
                    finished_at=datetime.now(UTC),
                    error=EngineError(
                        code="NO_MARKET_DATA"
                        if isinstance(exc, MarketDataError)
                        else "ENGINE_ERROR",
                        message=f"{type(exc).__name__}: {exc}",
                    ),
                ),
                portfolio_series=[],
                trades=[],
                diagnostics=Diagnostics(warnings=[]),
            )
        return output, RunArtifacts(orders=orders, metadata=metadata)
