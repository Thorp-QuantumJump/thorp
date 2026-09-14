package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.PositionSizingType;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
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
                        scenario == Scenario.TARGET_RISK ? targetRiskRules() : null),
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

    private EngineInput.MarketDataSpec marketData(RunPlan plan) {
        EngineInput.MarketDataSpec fixed = ExecutionPolicy.MARKET_DATA;
        return new EngineInput.MarketDataSpec(
                fixed.datasetId(),
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
                        : strategyRulesService
                                .requireRegistered(run.getStrategyId(), plan.rulesVersion())
                                .spec();
        return new EngineInput.AllocationSpec(
                run.getStrategyId(), rulesVersion, rules, run.getParameters());
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
     */
    private Map<String, Object> targetRiskRules() {
        StrategyRules overlay = strategyRulesService.requireRegistered("TARGET_RISK_OVERLAY", "1");
        Map<String, Object> rules = new LinkedHashMap<>(overlay.spec());
        rules.put("version", overlay.strategyId() + "/" + overlay.rulesVersion());
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
