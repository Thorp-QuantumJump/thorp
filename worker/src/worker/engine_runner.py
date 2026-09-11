"""Data loading, native execution and complete EngineOutput conversion."""

import hashlib
from datetime import UTC, date, datetime
from decimal import Decimal

import nautilus_trader

from worker.artifacts import OrderRecord, RunArtifacts
from worker.contracts.engine import ProgressCallback
from worker.contracts.engine_input import DelistingPolicy, EngineInput
from worker.contracts.engine_output import (
    Costs,
    Diagnostics,
    EngineError,
    EngineOutput,
    ExecutionResult,
    RuleVersion,
    RunResult,
    RunStatus,
    Versions,
)
from worker.contracts.engine_output import Warning as ContractWarning
from worker.engine_support import unsupported_reasons
from worker.market_data import DataProvider, MarketDataError, YFinanceDataProvider, validate_prices
from worker.nautilus_engine import (
    SLIPPAGE_BPS,
    CallbackError,
    effective_random_seed,
    execute_nautilus,
)
from worker.performance import calculate_performance

ZERO = Decimal("0")


def _run_level_warnings(request: EngineInput, first_evaluation_day: date) -> list[ContractWarning]:
    """특정 거래일이 아니라 실행 전체에 걸리는 경고.

    계약 ``Warning.date`` 는 필수이므로 **평가 구간의 첫 거래일**을 쓴다(ADR-0003 D-상장폐지).
    실행마다 1건씩만 남긴다.
    """
    instrument_id = request.universe.instrument_ids[0]
    warnings: list[ContractWarning] = []
    if request.execution.delisting is DelistingPolicy.CAUSE_BASED:
        # 요청을 거부하지 않고 받되 미지원 사실을 남긴다. 현재 공급자가 폐지 사유를 주지
        # 않으므로 사유별 청산을 구현할 수 없고, 조용히 통과시키면 다종목으로 넓혔을 때
        # 생존 편향을 눈치채지 못한다 (ADR-0003 D-상장폐지).
        warnings.append(
            ContractWarning(
                date=first_evaluation_day,
                instrument_id=instrument_id,
                code="DELISTING_CLASSIFICATION_UNSUPPORTED",
                message=(
                    "execution.delisting=CAUSE_BASED was accepted, but this engine does not "
                    "classify delisting causes; the data provider supplies no cause and sessions "
                    "without data are skipped. Results may carry survivorship bias."
                ),
            )
        )
    if request.market_data.fill_price != request.market_data.signal_price:
        # 계약은 체결(RAW)과 신호(ADJUSTED) 가격 계열을 나누지만 이 엔진은 공급자가 준 단일
        # 시계열(액면분할만 반영)로 둘 다 계산한다. 다종목 엔진(T007)에서 분리한다.
        warnings.append(
            ContractWarning(
                date=first_evaluation_day,
                instrument_id=instrument_id,
                code="PRICE_SERIES_NOT_DISTINGUISHED",
                message=(
                    f"marketData.fillPrice={request.market_data.fill_price} and "
                    f"signalPrice={request.market_data.signal_price} were accepted, but this "
                    "engine fills and signals on the same split-adjusted series."
                ),
            )
        )
    return warnings


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
            "slippageBps": SLIPPAGE_BPS,
            # 계약은 run.randomSeed 미지정을 허용하므로 실제로 쓴 값을 남긴다.
            "randomSeed": effective_random_seed(request.run),
            "metricsVersion": "DAILY_252_DDOF1_RF0_V1",
            "simulationCurrency": "KRW_SIM (1:1 KRW, precision 8)",
        }
        orders: list[OrderRecord] = []
        # 계약은 통과했지만 이 엔진이 계산할 수 없는 값은 실행 전에 거절한다. 조용히 무시하고
        # COMPLETED를 반환하면 요청한 정책과 다른 방식으로 계산한 숫자가 그 정책의 결과로
        # 저장된다. 예외가 아니라 계약 위치(run.error)에 담아 반환한다 (README §4.3).
        reasons = unsupported_reasons(request)
        if reasons:
            return (
                self._rejected(request, started, "INVALID_REQUEST", "; ".join(reasons)),
                RunArtifacts(orders=orders, metadata=metadata),
            )
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
            slip = sum((trade.slippage_cost for trade in execution.trades), ZERO)
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
                # 슬리피지가 유일한 비용이고 체결가에 이미 반영돼 있어 총수익률이 곧 순수익률이다.
                performance=performance.model_copy(
                    update={
                        "net_total_return": performance.total_return,
                        "total_trading_cost": slip,
                    }
                ),
                portfolio_series=execution.series,
                trades=execution.trades,
                execution=ExecutionResult(
                    order_count=len(orders),
                    fill_count=sum(order.status == "FILLED" for order in orders),
                    rejected_order_count=sum(order.status == "REJECTED" for order in orders),
                    total_commission=ZERO,
                    total_slippage_cost=slip,
                ),
                costs=Costs(commission=ZERO, spread=ZERO, slippage=slip, tax=ZERO, total=slip),
                versions=self._versions(request, dataset.source),
                diagnostics=Diagnostics(
                    warnings=_run_level_warnings(request, evaluation.index[0].date())
                    + execution.warnings
                ),
            )
        except CallbackError:
            raise
        except Exception as exc:
            # 실패해도 존재하지 않는 성과를 0으로 채우지 않는다 (계약 §4.3).
            output = self._rejected(
                request,
                started,
                "DATA_INSUFFICIENT" if isinstance(exc, MarketDataError) else "ENGINE_ERROR",
                f"{type(exc).__name__}: {exc}",
            )
        return output, RunArtifacts(orders=orders, metadata=metadata)

    @staticmethod
    def _versions(request: EngineInput, data: str | None) -> Versions:
        cost = request.execution.cost_profile
        return Versions(
            rules=RuleVersion(version=request.allocation.rules_version, provisional=False),
            cost_profile=RuleVersion(version=cost.version, provisional=cost.provisional),
            data=data,
            engine=f"nautilus_trader {nautilus_trader.__version__}",
        )

    @classmethod
    def _rejected(
        cls, request: EngineInput, started: datetime, code: str, message: str
    ) -> EngineOutput:
        """실패는 예외가 아니라 ``run.error`` 로 반환한다 (계약 §4.3).

        존재하지 않는 성과를 0으로 채우지 않으므로 ``performance``·``execution`` 은 채우지
        않는다 (직렬화에서 키가 빠진다).
        """
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
            versions=cls._versions(request, None),
            diagnostics=Diagnostics(warnings=[]),
        )
