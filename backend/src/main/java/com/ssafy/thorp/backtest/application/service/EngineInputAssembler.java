package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.PositionSizingType;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.RulesRef;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 저장된 실행을 작업 메시지(schemaVersion 2)로 조립한다.
 *
 * <p>모든 값은 <b>실행에 고정된 것</b>에서 나온다 — 접수가 확정한 계획({@link RunPlan})과 그 계획이 가리키는 등록표 행이다. 조립 시점에 기본값을 새로
 * 고르지 않는다. 재발행이 며칠 뒤에 일어나도, 등록표에 새 버전이 생겨도, 같은 실행은 같은 메시지가 되어야 하기 때문이다.
 *
 * <p>등록표 <b>사본</b>(규칙 명세·비용 값)을 메시지에 실어 보낸다. 워커가 자체 기본값을 두지 않게 하려는 것이고(계약), 나중에 등록표 행을 고쳐도 이미 나간
 * 실행의 해석이 바뀌지 않게 하려는 것이다.
 *
 * <p>규칙은 {@link StrategyRulesService#requireExecutable}로 읽는다(T029). 접수와 발행 사이에 규칙이 새 버전으로 대체돼도 이
 * 실행은 접수 때 고정한 버전의 명세로 나가야 한다 — 지금 버전만 받으면 대기 중이던 실행이 규칙이 바뀐 날 전부 조립 실패로 닫힌다.
 */
@Component
public class EngineInputAssembler {

    private final StrategyRulesService strategyRulesService;
    private final CostProfileService costProfileService;

    public EngineInputAssembler(
            StrategyRulesService strategyRulesService, CostProfileService costProfileService) {
        this.strategyRulesService = strategyRulesService;
        this.costProfileService = costProfileService;
    }

    public EngineInput assemble(BacktestRun run) {
        if (run.getBacktestId() == null) {
            throw new IllegalStateException("저장되지 않은 실행은 발행할 수 없습니다.");
        }
        RunPlan plan = run.getPlan();
        Map<String, Object> spec = plan.requestSpecOrEmpty();
        Scenario scenario = scenario(spec);

        return new EngineInput(
                new EngineInput.RunSpec(
                        run.getBacktestId().value(),
                        run.getPeriod().startDate(),
                        run.getPeriod().endDate(),
                        plan.effectiveStart(),
                        run.getRandomSeed(),
                        scenario,
                        asMap(spec.get("scenarioParams")),
                        scenario == Scenario.TARGET_RISK ? targetRiskRules(plan) : null),
                new EngineInput.UniverseSpec(
                        (String) spec.get("mappingVersion"), universeItems(run, spec)),
                marketData(plan),
                allocation(run, plan),
                new EngineInput.PortfolioSpec(
                        run.getInitialCash(),
                        ExecutionPolicy.CURRENCY,
                        new EngineInput.PositionSizing(
                                PositionSizingType.STRATEGY_DEFINED, run.getMaxPositionPct()),
                        ExecutionPolicy.SHORT_SELLING,
                        ExecutionPolicy.LEVERAGE,
                        ExecutionPolicy.INTEGER_LOTS),
                execution(plan),
                new EngineInput.EvaluationSpec(
                        (String) spec.get("evaluationSplitVersion"),
                        plan.warmupTradingDays(),
                        ExecutionPolicy.EVALUATION.liquidationAtEnd()),
                ExecutionPolicy.PROGRESS);
    }

    /**
     * 자료 관련 두 값은 <b>같은 것</b>이다.
     *
     * <p>spec 001 research.md 결정 4가 "새 필드를 만들지 않고 기존 {@code marketData.datasetId} 를 자료 버전 식별자로 쓴다"고
     * 정했다. 그래서 둘에 같은 값을 싣는다. 예전에 {@code datasetId} 가 자리 표시 상수({@code KRX_DAILY_V1})였던 것은 001이 자료를
     * 적재하기 전이라 실제 값을 가져올 곳이 없었기 때문이다. 이제는 접수 시점에 정해져 실행에 고정된 자료 버전이 그 값이다.
     */
    private EngineInput.MarketDataSpec marketData(RunPlan plan) {
        EngineInput.MarketDataSpec fixed = ExecutionPolicy.MARKET_DATA;
        return new EngineInput.MarketDataSpec(
                plan.dataVersion(),
                plan.dataVersion(),
                fixed.interval(),
                fixed.adjustment(),
                fixed.priceType(),
                fixed.fillPrice(),
                fixed.signalPrice(),
                fixed.distributions());
    }

    /**
     * 규칙 <b>사본</b>은 접수가 등록표를 확인한 실행에만 붙인다.
     *
     * <p>버전 문자열은 계획이 없으면 실행의 {@code strategyVersion} 으로 되돌린다 — 접수 경로를 거치지 않은 실행(DEMO 픽스처)도 메시지에 버전이
     * 있어야 하고, 그 실행의 전략은 등록표에 없어서 사본을 만들 수 없다.
     */
    private EngineInput.AllocationSpec allocation(BacktestRun run, RunPlan plan) {
        String rulesVersion =
                plan.rulesVersion() == null ? run.getStrategyVersion() : plan.rulesVersion();
        Map<String, Object> rules =
                plan.rulesVersion() == null
                        ? null
                        : rulesCopy(
                                strategyRulesService.requireExecutable(
                                        run.getStrategyId(), plan.rulesVersion()));
        return new EngineInput.AllocationSpec(
                run.getStrategyId(), rulesVersion, rules, engineParameters(run.getParameters()));
    }

    /**
     * 003 축 이름을 엔진이 읽는 이름으로 옮긴다. <b>지금은 {@code rebalancePeriod} 하나다.</b>
     *
     * <p>003 등록표는 리밸런싱 주기를 객체({@code {mode, unit}})로 두고 엔진은 문자열 하나({@code rebalance})를 읽는다. 화면은 축
     * 이름을 그대로 실어 보내므로(003 FR-005 — 화면에 전략 ID·축 이름을 두지 않는다) 누군가는 옮겨야 하고, 그 자리는 <b>발행 경로 하나뿐인 이
     * 조립기</b>다. 접수가 저장하는 {@code backtest_run.parameters}는 003 이름 그대로 남는다 — 사용자가 무엇을 골랐는지의 기록이고 재현
     * 기준이기 때문이다.
     *
     * <p>이미 {@code rebalance}가 실려 온 요청(quickstart·워커 픽스처·기존 007 경로)은 건드리지 않는다. 옮길 수 없는 모양은 <b>지어내지
     * 않고 그대로 둔다</b> — 엔진이 "무엇을 받았고 무엇을 지원하는지"를 그대로 말하게 하는 편이, 백엔드가 엔진의 지원 범위를 따로 들고 있다가 어긋나는 것보다
     * 낫다.
     *
     * <p>나머지 축({@code topN}·{@code skipRecentDays} 등)은 이름이 그대로라 옮길 것이 없다.
     */
    private static Map<String, Object> engineParameters(Map<String, Object> parameters) {
        if (parameters == null
                || parameters.containsKey("rebalance")
                || !(parameters.get("rebalancePeriod") instanceof Map<?, ?> period)) {
            return parameters;
        }
        Object mode = period.get("mode");
        Object unit = period.get("unit");
        if (!"CALENDAR".equals(mode) || unit == null) {
            return parameters;
        }
        Map<String, Object> moved = new LinkedHashMap<>(parameters);
        moved.remove("rebalancePeriod");
        moved.put("rebalance", unit);
        return moved;
    }

    /**
     * 규칙 사본에 등록표의 {@code provisional} 을 함께 싣는다. 워커는 이 값을 결과 {@code versions.rules.provisional} 로
     * 되돌려 주고, 그 값이 있어야 저장된 결과만 보고도 임시 규칙(PD-02)으로 계산했는지 알 수 있다(T008 결정 ③ 후속). 배분 모듈은 자기가 아는 키만 읽으므로
     * 이 키가 계산을 바꾸지 않는다.
     */
    private static Map<String, Object> rulesCopy(StrategyRules registered) {
        Map<String, Object> copy = new LinkedHashMap<>(registered.spec());
        copy.put("provisional", registered.provisional());
        return copy;
    }

    /** 비용은 실행이 고른 버전의 등록표 값을 그대로 싣는다. 값이 바뀌면 새 버전이 생기므로 옛 실행은 옛 값을 계속 본다. */
    private EngineInput.ExecutionSpec execution(RunPlan plan) {
        EngineInput.ExecutionSpec fixed = ExecutionPolicy.EXECUTION;
        if (plan.costProfileVersion() == null) {
            return fixed;
        }
        CostProfile profile = costProfileService.resolve(plan.costProfileVersion());
        return new EngineInput.ExecutionSpec(
                fixed.signalTiming(),
                fixed.executionTiming(),
                fixed.orderTypes(),
                new EngineInput.CostProfile(
                        profile.version(),
                        profile.commissionBps(),
                        profile.spreadBps(),
                        profile.slippageBps(),
                        profile.taxBps(),
                        profile.provisional()),
                fixed.partialFill(),
                fixed.liquidity(),
                fixed.tradingHalt(),
                fixed.delisting());
    }

    /**
     * 목표 위험 오버레이 규칙 사본(PD-18). 사용자 입력이 아니라 등록표 값이다.
     *
     * <p>{@code version} 을 함께 싣는다 — 워커가 결과 {@code versions.scenarioRules} 에 그대로 적어 어느 오버레이 규칙으로
     * 계산했는지 남긴다(FR-028).
     *
     * <p>버전은 접수가 실행에 기록한 것을 읽는다(T029). 여기서 지금 버전을 고르면 오버레이 규칙이 바뀐 날 대기 중이던 실행이 접수 때와 다른 규칙으로 계산된다.
     * 기록이 없으면 지어내지 않는다 — {@code IllegalArgumentException}은 발행기가 영구 실패로 닫는다.
     */
    private Map<String, Object> targetRiskRules(RunPlan plan) {
        RulesRef recorded = plan.scenarioRules();
        if (recorded == null
                || !BacktestService.TARGET_RISK_OVERLAY.equals(recorded.strategyId())) {
            // 2026-09-15 전 실행은 T029 마이그레이션이 TARGET_RISK_OVERLAY/1 로 채웠다. 그래도 없으면 기록이 깨진 것이다.
            throw new IllegalArgumentException(
                    "TARGET_RISK run has no overlay rules recorded in versions.scenarioRules: "
                            + recorded);
        }
        StrategyRules overlay =
                strategyRulesService.requireExecutable(
                        recorded.strategyId(), recorded.rulesVersion());
        Map<String, Object> rules = new LinkedHashMap<>(overlay.spec());
        rules.put("version", overlay.ref().value());
        return rules;
    }

    /**
     * 섹터·목표 비중은 요청 사본에서 읽는다 — 실행 스칼라 컬럼에는 종목 목록만 있다.
     *
     * <p>사본이 없는 실행(접수 경로를 거치지 않은 T003 이전 행)은 종목만으로 조립한다.
     */
    private static List<EngineInput.UniverseItem> universeItems(
            BacktestRun run, Map<String, Object> spec) {
        Object raw = spec.get("items");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return run.getInstrumentIds().stream()
                    .map(id -> new EngineInput.UniverseItem(null, id.value(), null))
                    .toList();
        }
        List<EngineInput.UniverseItem> items = new ArrayList<>(list.size());
        for (Object element : list) {
            Map<String, Object> item = asMap(element);
            if (item == null) {
                continue;
            }
            items.add(
                    new EngineInput.UniverseItem(
                            (String) item.get("sectorId"),
                            (String) item.get("instrumentId"),
                            decimal(item.get("targetWeight"))));
        }
        return items;
    }

    private static Scenario scenario(Map<String, Object> spec) {
        Object raw = spec.get("scenario");
        return raw == null ? ExecutionPolicy.SCENARIO : Scenario.valueOf(String.valueOf(raw));
    }

    /** JSONB 로 다녀온 소수는 문자열이다(메시지 ObjectMapper 규칙). 다시 BigDecimal 로 세운다. */
    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
