"""자료 적재 → Nautilus 실행 → 완전한 EngineOutput 변환(spec 004 T006)."""

import hashlib
from collections.abc import Callable
from datetime import UTC, date, datetime
from decimal import Decimal

import nautilus_trader
import pandas as pd

from worker import __version__ as worker_version
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
from worker.control import CancelRegistry
from worker.costs import CostModel, Side, total_costs
from worker.engine_support import unsupported_reasons
from worker.market_data import DataUsage, MarketData, MarketDataError, MarketDataProvider
from worker.performance import calculate_analytics, calculate_performance
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


def required_warmup_trading_days(request: EngineInput) -> int:
    """요청 구간보다 **앞에서** 더 읽을 워밍업 거래일 수.

    ``evaluation.warmupTradingDays`` 가 있으면 그 값, 없으면 관측 길이 ``lookbackDays`` 다 —
    **배분 모듈이 계산에 쓰는 것과 같은 출처**로 읽는다(요청 ``parameters`` 가 있으면 그 값, 없으면
    규칙 사본). 두 곳이 갈리면 250거래일로 계산하면서 120거래일만 읽어 와 종목이 조용히 빠진다.
    ``TARGET_RISK`` 면 첫 판단일에 추정할 수익률이 있어야 하므로 오버레이 관측 길이와 큰 쪽을
    쓴다(백엔드도 같은 규칙으로 워밍업을 확정한다).

    달력 일수가 아니라 거래일 수다 — 연휴가 긴 해에 달력 일수로 잡으면 워밍업이 모자란다
    (001 contracts/market-dataset.md 「읽기 규칙」). 이 값을 공급자에 넘기면 공급자가
    ``warmup_anchor()`` 보다 이만큼 앞선 거래일부터 읽는다. **앞에 자료가 모자라면 있는 만큼
    읽는다** — 거절은 여기가 아니라 ``evaluation_start()`` 의 되밀기가 맡는다.
    """
    warmup = request.evaluation.warmup_trading_days
    if warmup is None:
        # 003 축이 오면 그것이 사용자가 고른 값이고 없으면 규칙표 값이 기본값이다
        # (백엔드와 같은 규칙).
        # **최근 제외 구간을 더한다** — 섹터 모멘텀의 점수는 P(t-S)/P(t-S-L) 이라 L 만으로는
        # S 거래일이 모자라고, 그만큼 초기 구간이 이력 부족으로 빠져 결과 기간과 수익률이 달라진다.
        warmup = _axis_days(request, "lookbackDays") + _axis_days(request, "skipRecentDays")
        if request.run.scenario is Scenario.TARGET_RISK:
            warmup = max(warmup, target_risk_rules(request.run).lookback_days)
    return warmup


def _axis_days(request: EngineInput, key: str) -> int:
    chosen = request.allocation.parameters.get(key)
    if chosen is None:
        chosen = (request.allocation.rules or {}).get(key, 0)
    return int(chosen)


def warmup_anchor(request: EngineInput) -> date:
    """워밍업을 그 **앞에서** 읽어야 하는 날. 달력을 읽기 전에 요청만으로 정한다.

    자료를 읽어야 달력을 알고 달력을 알아야 평가 시작일이 나오므로, 적재 시점에는 요청이
    말하는 평가 시작일로 대신한다. 갈래와 우선순위는 ``evaluation_start()`` 와 같다 —
    ``FRESH_CASH_FROM`` 의 ``scenarioParams.startDate`` → ``run.effectiveStart`` →
    ``run.start``. 셋 다 요청한 첫날보다 앞설 수 없으므로 ``run.start`` 와 늦은 쪽을 쓴다.

    **``run.start`` 를 앵커로 쓰면 안 된다.** 평가가 6월부터인데 1월 앞의 자료를 요구하게 되고,
    자료 맨 앞 근처를 요청한 실행이 읽기 단계에서 죽는다. 되민 시작일 앞에 자료가 멀쩡히 있어도
    마찬가지다.
    """
    run = request.run
    if run.scenario is Scenario.FRESH_CASH_FROM:
        # engine_support 가 존재·형식을 이미 확인했다.
        fresh_from = date.fromisoformat(str((run.scenario_params or {})["startDate"]))
        return max(run.start, fresh_from)
    if run.effective_start is not None:
        return max(run.start, run.effective_start)
    return run.start


def evaluation_start(request: EngineInput, calendar: list[date]) -> date:
    """성과 집계를 시작하는 거래일(FR-014·R-15).

    **``max(앵커, calendar[warmup])``.** 앵커는 ``warmup_anchor()`` 가 정하고, 워밍업 바닥은
    ``FRESH_CASH_FROM``·``run.effectiveStart``·``run.start`` **세 갈래 모두에** 얹는다.

    ``calendar`` 는 워밍업 구간까지 포함해 읽어 온 전체 달력이므로 ``calendar[warmup]`` 이
    "워밍업을 채우고 나서 평가할 수 있는 첫 거래일"이다. 공급자가 앞이 모자라도 있는 만큼만 읽고
    거절하지 않으므로(``market_data._warmup_start_from_days``) **워밍업이 모자란 채로 계산이
    시작되는 것을 막는 곳은 여기 하나다.**

    - 앵커 앞에 워밍업 자료가 넉넉하면 → 공급자가 앵커보다 정확히 ``warmup`` 거래일 앞에서 읽기
      시작하므로 ``calendar[warmup] == 앵커`` 가 되어 **바닥이 아무 일도 하지 않는다.** 워밍업을
      두 번 소비하지 않는다
    - 자료 맨 앞이나 상장일 근처라 앞이 모자라면 → **모자란 만큼만** 밀린다(001
      contracts/evaluation-window.md 의 ``firstEvaluable``). ``DATA_INSUFFICIENT`` 가 아니다

    **바닥을 ``run.start`` 갈래에만 얹으면 운영 경로에서 꺼진다.** 발행 경로는
    ``EngineInputAssembler`` 하나이고 ``BacktestService.create`` 가 언제나
    ``plan.effectiveStart()`` 를 싣는다. 그래서 실제로 오는 요청은 전부 ``run.effectiveStart``
    갈래를 타고, 그 갈래에 바닥이 없으면 워밍업 없이 첫 판단일을 맞아 전략이
    ``CONSTRAINT_VIOLATION``·``ESTIMATION_FAILED`` 로 죽는다. **`effectiveStart` 가 늘 채워져
    온다는 것은 안심의 근거가 아니라 위험의 근거다.**

    **남는 한계: 화면의 기간과 실제 평가 기간이 갈릴 수 있다.** 바닥이 일하면 워커는 백엔드가 준
    ``run.effectiveStart`` 보다 뒤에서 시작한다. 실패하는 것보다 낫지만 완전한 해결은 아니다.
    뿌리는 **백엔드가 데이터셋의 첫 거래일을 모른다**는 것이다 — ``firstEvaluable`` 의 기준점은
    ``max(상장일, 데이터셋 첫 거래일)`` 이라야 하는데 ``MarketMetadataPort`` 에 자료 시작일을
    묻는 길이 없다. 픽스처에서 그 둘은 8년 어긋나 있다(어댑터 상장일 2015·2018, 워커 픽스처 자료
    2023-01-02). 001의 자료 계층이 들어올 때 함께 옮긴다(001 tasks.md T029).
    """
    run = request.run
    in_range = [day for day in calendar if run.start <= day <= run.end]
    if not in_range:
        raise MarketDataError("No trading days in the requested period")
    floor = warmup_anchor(request)
    if run.scenario is Scenario.FRESH_CASH_FROM:
        # 앵커는 max(run.start, scenarioParams.startDate) 라 둘 중 어느 쪽인지 밝혀 적는다.
        source = f"{floor} (scenarioParams.startDate or run.start, whichever is later)"
    elif run.effective_start is not None:
        source = f"{floor} (run.effectiveStart or run.start, whichever is later)"
    else:
        source = f"run.start {floor}"
    warmup = required_warmup_trading_days(request)
    if warmup >= len(calendar):
        # 워밍업 구간까지 읽어 온 달력 전체로도 워밍업을 못 채운다. 있는 만큼 읽어 온 뒤라
        # 이것은 "앞이 짧다"가 아니라 "이 자료로는 이 전략을 계산할 수 없다"는 뜻이다.
        raise MarketDataError(
            f"Trading calendar holds {len(calendar)} day(s), "
            f"need {warmup} warmup trading day(s) before the first evaluation day"
        )
    if calendar[warmup] > floor:
        floor = calendar[warmup]
        source = f"{floor}, the first day with {warmup} warmup trading day(s) behind it"
    candidates = [day for day in in_range if day >= floor]
    if not candidates:
        raise MarketDataError(f"No trading day on or after {source}")
    return candidates[0]


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


def assemble_output(
    request: EngineInput,
    market: MarketData,
    execution: PortfolioRebalanceExecution,
    started: datetime,
    *,
    status: RunStatus = RunStatus.COMPLETED,
    partial: bool = False,
) -> EngineOutput:
    """실행 상태 → 결과 계약. 완료도, 취소·한도의 부분 결과(T011·T014)도 같은 함수가 만든다.

    부분 결과는 ``execution.series`` 가 ``lastTradingDate`` 까지만 담겨 있다는 것 외에 다르지 않다 —
    지표·비용·비중·분석 블록을 그 시계열로 똑같이 계산한다.
    """
    series = execution.series
    orders = execution.orders
    costs = total_costs(execution.fills)
    first, last = series[0].date, series[-1].date
    # 평가 기간 종목 일별 수익률(조정 종가). 상관·위험 기여의 입력이다.
    evaluated = execution.signal_history.loc[pd.Timestamp(first) : pd.Timestamp(last)]
    instrument_returns = evaluated.pct_change().dropna()
    return EngineOutput(
        run=RunResult(
            run_id=request.run.run_id,
            status=status,
            started_at=started,
            finished_at=datetime.now(UTC),
            partial=partial,
            last_trading_date=last,
            effective_start=first,
            effective_end=last,
        ),
        performance=calculate_performance(
            request.portfolio.initial_cash,
            series,
            execution.trades,
            request.portfolio.currency,
            total_trading_cost=costs.total,
        ),
        portfolio_series=series,
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
            _liquidation(request, execution) if request.evaluation.liquidation_at_end else None
        ),
        analytics=calculate_analytics(
            series, execution.weights_series, execution.trades, instrument_returns
        ),
        exceptions=execution.exceptions,
        versions=reference_versions(request, market),
        diagnostics=Diagnostics(warnings=_run_level_warnings(request, first) + execution.warnings),
    )


def reference_versions(request: EngineInput, market: MarketData | None) -> Versions:
    """참조 버전(FR-028). 거절 결과에도 붙으므로 검증하지 않고 있는 값만 읽는다.

    ``rules.provisional`` 은 규칙 사본의 ``provisional`` 키를 그대로 옮긴다 — 백엔드 조립기가
    등록표 열을 사본에 합쳐 보낸다(spec 004 T018). 키가 없는 사본(옛 픽스처·직접 만든 입력)은
    모른다고(키 생략) 둔다. 워커가 등록표를 갖고 있지 않으므로 지어내지 않는다.
    """
    cost = request.execution.cost_profile
    overlay_version = (request.run.scenario_rules or {}).get("version")
    scenario_rules = (
        str(overlay_version)
        if request.run.scenario is Scenario.TARGET_RISK and overlay_version is not None
        else None
    )
    provisional = (request.allocation.rules or {}).get("provisional")
    return Versions(
        mapping=request.universe.mapping_version,
        rules=RuleVersion(
            version=request.allocation.rules_version,
            provisional=provisional if isinstance(provisional, bool) else None,
        ),
        scenario_rules=scenario_rules,
        cost_profile=RuleVersion(version=cost.version, provisional=cost.provisional),
        data=market.dataset_id if market is not None else None,
        engine=f"nautilus_trader {nautilus_trader.__version__}",
        evaluation_split=request.evaluation.split_version,
        worker=f"thorp-worker {worker_version}",
    )


class PortfolioBacktestEngine:
    """다종목 리밸런싱 엔진. 공급자는 인스턴스의 성질이므로 생성자로 주입한다(기본값 없음, R-09).

    ``cancel_registry`` 를 주면 거래일마다 자기 runId 의 취소 표시를 보고 그날 평가를 마친 뒤
    멈춘다(R-04). 결과는 ``status=CANCELLED, partial=true`` 에 ``lastTradingDate`` 까지의
    시계열이다. 엔진은 표시를 **읽기만** 한다 — 활성 실행 등록·해제는 소비자가 STARTED
    앞뒤에서 한다.
    레지스트리가 없으면 끝까지 돈다(테스트·데모).
    """

    def __init__(
        self, market_data: MarketDataProvider, cancel_registry: CancelRegistry | None = None
    ) -> None:
        self.market_data = market_data
        self.cancel_registry = cancel_registry
        # 마지막 실행이 읽은 자료의 양(T019). 소비자가 FINISHED resource 에 싣는다 — 엔진 경계
        # Protocol 은 EngineOutput 만 돌려주고 자원 사용은 메시지 밖이라, 있으면 읽는 선택 속성이다.
        self.last_data_usage: DataUsage | None = None

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
        """계약 메시지와 로컬 진단 산출물을 함께 돌려준다.

        계산 상태는 전부 지역 변수다. **예외는 ``last_data_usage`` 하나**다(T019) — 소비자가
        ``run()`` 이 끝난 뒤 읽어 FINISHED ``resource`` 에 싣는다. 엔진 Protocol 이
        ``EngineOutput`` 만 돌려주고 자원 사용은 메시지 밖이라 반환값에 태울 자리가 없다.

        그래서 **엔진 인스턴스 하나는 한 번에 한 작업만 돌려야 한다.** 소비자가 프로세스당 인스턴스
        하나를 단일 스레드로 쓰는 지금 구조가 그 전제다. 두 작업이 한 인스턴스를 공유하면 뒤 실행의
        FINISHED 가 앞 실행의 자료량을 싣고, 데이터 원가가 엉뚱한 실행에 붙는다 — 값이 그럴듯해서
        아무도 알아채지 못한다. 동시 실행을 도입한다면 이 속성부터 걷어내야 한다.
        """
        request = EngineInput.model_validate(engine_input)
        started = datetime.now(UTC)
        self.last_data_usage = None
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
        # 워밍업은 요청 구간 **앞에서** 읽는다(001 contracts/market-dataset.md 「읽기 규칙」).
        # 요청 구간 안에서 소비하면 사용자가 요청한 첫날부터의 결과를 받지 못한다.
        try:
            warmup = required_warmup_trading_days(request)
            anchor = warmup_anchor(request)
        except (ArithmeticError, LookupError, TypeError, ValueError) as exc:
            # 규칙 사본의 ``lookbackDays`` 가 정수가 아니거나 시나리오 입력이 깨졌다. 결정적
            # 결함이므로 전파해 재전달시키지 않고 결과로 돌려준다 — 아래 광역 처리와 같은
            # 판단이다(계약 §4.3).
            return (
                self._rejected(
                    request, None, started, "ENGINE_ERROR", f"{type(exc).__name__}: {exc}"
                ),
                RunArtifacts(orders=orders, metadata=metadata),
            )
        metadata["requestedWarmupTradingDays"] = warmup
        metadata["warmupAnchor"] = anchor.isoformat()
        # 일시 장애(DB 접속 실패 등)는 자료 적재에서만 난다. 그래서 이 호출만 try 밖에 두고
        # 그대로 전파한다 — 소비자는 ACK 하지 않고 재전달 경로로 넘긴다(계약 §7 ③). 결과로
        # 바꾸면 FINISHED 가 발행·ACK 되어 일시 장애가 최종 실패로 굳는다. 자료 부족(행 결측·
        # 상장 전 구간만 요청)은 확정 오류이므로 아래에서 결과로 바꾼다.
        #
        # 앵커는 ``run.start`` 가 아니라 **평가가 실제로 시작될 날**이다. 요청한 첫날을 기준으로
        # 삼으면 평가가 6월부터인 실행이 1월 앞의 자료를 요구하다 죽는다. 앞이 모자라면 공급자가
        # 있는 만큼만 읽고, 워밍업이 모자란 채로 계산되는 것은 ``evaluation_start`` 가 막는다.
        try:
            market = self.market_data.load(
                request.universe.instrument_ids,
                anchor,
                request.run.end,
                request.market_data.data_version,
                warmup_trading_days=warmup,
            )
        except MarketDataError as exc:
            return (
                self._rejected(request, None, started, "DATA_INSUFFICIENT", str(exc)),
                RunArtifacts(orders=orders, metadata=metadata),
            )
        self.last_data_usage = market.usage()
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
                    # 평가 시작일 앞에 실제로 쥐고 있는 거래일 수. 요청 구간 앞에서 읽은
                    # 워밍업이 여기 들어간다(``requestedWarmupTradingDays`` 가 요청값이다).
                    "warmupTradingDays": len([d for d in calendar if d < first_day]),
                    "totalTradingDays": len(plan.evaluation_days),
                    "decisionDays": len(plan.decision_days),
                    "dataHash": "sha256:"
                    + hashlib.sha256(
                        "".join(market.prices[i].to_csv() for i in sorted(market.prices)).encode()
                    ).hexdigest(),
                }
            )
            execution = execute_portfolio(
                request, market, plan, on_progress, stop_after=self._stop_after(request.run.run_id)
            )
            orders = execution.orders
            if execution.stopped_early:
                # 취소(R-04): 멈춘 날까지의 부분 결과다. 실패가 아니므로 run.error 는 없다.
                output = assemble_output(
                    request, market, execution, started, status=RunStatus.CANCELLED, partial=True
                )
            else:
                output = assemble_output(request, market, execution, started)
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

    def _stop_after(self, run_id: str) -> Callable[[date], bool] | None:
        registry = self.cancel_registry
        if registry is None:
            return None
        return lambda _day: registry.is_cancelled(run_id)

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
            versions=reference_versions(request, market),
            diagnostics=Diagnostics(warnings=[]),
        )
