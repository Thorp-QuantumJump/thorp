package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 요청 접수(spec 004 T005). 백엔드는 계산하지 않는다 — 요청을 검증해 실행을 만들고 작업 메시지를 발행한다.
 *
 * <p>접수가 하는 일은 "실행 전에 거절할 수 있는 것을 전부 거절하는 것"이다. 규칙이 팀 미확정이면 422, 워밍업이 모자라면 422, 비중 합이 1이 아니면 400이다.
 * 여기서 통과시킨 요청은 워커가 자료를 읽다가 실패하는 것 말고는 실패할 이유가 없어야 한다.
 *
 * <p><b>발행은 커밋 뒤에</b> 한다(R-18, {@link BacktestJobDispatcher}). 트랜잭션 안에서 발행하면 워커의 STARTED가 커밋을 앞질러
 * 모르는 실행으로 버려진다.
 */
@Service
public class BacktestService {

    private final BacktestRunRepository runRepository;
    private final BacktestRequestRepository requestRepository;
    private final StrategyRulesService strategyRulesService;
    private final CostProfileService costProfileService;
    private final EffectivePeriodCalculator effectivePeriodCalculator;
    private final DedupKeyCalculator dedupKeyCalculator;
    private final MarketMetadataPort marketMetadata;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String engineVersion;

    public BacktestService(
            BacktestRunRepository runRepository,
            BacktestRequestRepository requestRepository,
            StrategyRulesService strategyRulesService,
            CostProfileService costProfileService,
            EffectivePeriodCalculator effectivePeriodCalculator,
            DedupKeyCalculator dedupKeyCalculator,
            MarketMetadataPort marketMetadata,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${thorp.backtest.engine-version}") String engineVersion) {
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.strategyRulesService = strategyRulesService;
        this.costProfileService = costProfileService;
        this.effectivePeriodCalculator = effectivePeriodCalculator;
        this.dedupKeyCalculator = dedupKeyCalculator;
        this.marketMetadata = marketMetadata;
        this.events = events;
        this.clock = clock;
        this.engineVersion = engineVersion;
    }

    /** 목표 비중을 지정하는 유일한 전략. 나머지 전략에서 비중을 보내면 무시하지 않고 거절한다. */
    private static final String FIXED_WEIGHT = "FIXED_WEIGHT";

    /** 목표 변동성 허용 범위와 간격(PD-18). */
    private static final BigDecimal MIN_TARGET_VOLATILITY = new BigDecimal("0.0500");

    private static final BigDecimal MAX_TARGET_VOLATILITY = new BigDecimal("0.2000");
    private static final BigDecimal TARGET_VOLATILITY_STEP = new BigDecimal("0.0100");

    @Transactional
    public BacktestJobResult create(CreateBacktestCommand command) {
        Scenario scenario = command.scenario() == null ? Scenario.FULL_PERIOD : command.scenario();
        validateScenario(scenario, command.scenarioParams());
        validateUniverse(command);

        StrategyRules rules =
                strategyRulesService.requireRegistered(
                        command.strategyId(), command.rulesVersion());
        validateWeights(command, rules);
        StrategyRules overlay = scenario == Scenario.TARGET_RISK ? targetRiskOverlay() : null;
        CostProfile costProfile = costProfileService.resolve(command.costProfileVersion());

        EffectivePeriodCalculator.EffectivePeriod period =
                effectivePeriodCalculator.resolve(
                        rules,
                        command.instrumentIds(),
                        command.start(),
                        command.end(),
                        command.warmupTradingDays());

        String dataVersion = marketMetadata.currentDataVersion();
        String dedupKey =
                dedupKeyCalculator.calculate(
                        new DedupKeyCalculator.Input(
                                command.strategyId(),
                                command.rulesVersion(),
                                command.parameters(),
                                command.mappingVersion(),
                                command.items().stream()
                                        .map(
                                                item ->
                                                        new DedupKeyCalculator.UniverseEntry(
                                                                item.sectorId(),
                                                                item.instrumentId(),
                                                                item.targetWeight()))
                                        .toList(),
                                command.start(),
                                command.end(),
                                period.warmupTradingDays(),
                                command.initialCash(),
                                dataVersion,
                                command.evaluationSplitVersion(),
                                costProfile.version(),
                                engineVersion,
                                scenario.name(),
                                command.scenarioParams(),
                                overlay == null ? null : overlay.rulesVersion()));

        Optional<BacktestRun> active = runRepository.findActiveByDedupKey(dedupKey);
        if (active.isPresent()) {
            return joinExisting(command, active.get());
        }

        Map<String, Object> versions = versions(costProfile, command.rulesVersion(), dataVersion);
        RunPlan plan =
                new RunPlan(
                        dedupKey,
                        command.rulesVersion(),
                        costProfile.version(),
                        dataVersion,
                        period.effectiveStart(),
                        period.effectiveEnd(),
                        period.warmupTradingDays(),
                        requestSpec(command, scenario, period.warmupTradingDays()),
                        versions);

        BacktestRun run =
                BacktestRun.create(
                                command.strategyId(),
                                command.rulesVersion(),
                                command.parameters(),
                                command.instrumentIds().stream().map(InstrumentId::new).toList(),
                                new BacktestPeriod(command.start(), command.end()),
                                command.initialCash(),
                                PositionSizingMode.STRATEGY_DEFINED,
                                null,
                                null,
                                ExecutionPolicy.DEFAULT_RANDOM_SEED)
                        .withPlan(plan)
                        // 발행 시도를 미리 한 번 센다(R-18). 보내지 못하고 죽어도 시도 횟수는 실제 메시지 수의
                        // 상한이면 되므로 상한이 깨지지 않고, 재발행기가 published_at NULL 을 보고 집어 간다.
                        .withLifecycle(RunLifecycle.queuedAt(clock.instant()).publishAttempted());
        BacktestRun saved = runRepository.save(run);

        BacktestRequest request =
                requestRepository.save(
                        BacktestRequest.create(
                                saved.getId(),
                                command.requesterId(),
                                command.executionType(),
                                ResultSource.NEW,
                                null));
        events.publishEvent(new BacktestJobDispatcher.RunAccepted(saved.getId()));
        return BacktestJobResult.of(request, saved);
    }

    /**
     * 같은 조건의 실행이 이미 있다. 새로 계산하지 않고 요청만 붙인다.
     *
     * <p><b>여기까지가 T005의 몫이다.</b> 재사용 기록({@code backtest_reuse_event})과 동시 접수 경합의 재조회는 T010이 붙인다.
     * 그때까지 이 분기가 없으면 같은 조건을 두 사람이 요청했을 때 부분 유니크 인덱스에 걸려 500이 난다 — 정상적인 사용자 행동이 500이 되는 것은 미룰 일이
     * 아니다.
     */
    private BacktestJobResult joinExisting(CreateBacktestCommand command, BacktestRun run) {
        ResultSource source =
                run.getStatus() == RunStatus.COMPLETED ? ResultSource.REUSED : ResultSource.JOINED;
        BacktestRequest request =
                requestRepository.save(
                        BacktestRequest.create(
                                run.getId(),
                                command.requesterId(),
                                command.executionType(),
                                source,
                                null));
        return BacktestJobResult.of(request, run);
    }

    @Transactional(readOnly = true)
    public BacktestJobResult get(String backtestId) {
        BacktestRequest request =
                requestRepository
                        .findByBacktestId(backtestId)
                        .orElseThrow(() -> new BacktestNotFoundException(backtestId));
        BacktestRun run =
                runRepository
                        .findById(request.getRunId())
                        .orElseThrow(() -> new BacktestNotFoundException(backtestId));
        return BacktestJobResult.of(request, run);
    }

    // --- 검증 ---------------------------------------------------------------

    /**
     * 시나리오와 그 입력값이 서로 맞는지. 맞지 않는 조합은 조용히 무시하지 않고 거절한다 — 무시하면 사용자가 고른 것과 다른 조건의 결과가 그 조건의 결과로 저장된다.
     */
    private void validateScenario(Scenario scenario, Map<String, Object> params) {
        switch (scenario) {
            case TARGET_RISK -> validateTargetVolatility(params);
            case FRESH_CASH_FROM -> {
                if (params == null || params.get("startDate") == null) {
                    throw new BusinessException(
                            ErrorCode.INVALID_INPUT,
                            "FRESH_CASH_FROM 시나리오는 scenarioParams.startDate 가 필요합니다.");
                }
            }
            default -> {
                if (params != null && !params.isEmpty()) {
                    throw new BusinessException(
                            ErrorCode.INVALID_INPUT,
                            scenario + " 시나리오에는 scenarioParams 를 보낼 수 없습니다: " + params.keySet());
                }
            }
        }
    }

    private void validateTargetVolatility(Map<String, Object> params) {
        Object raw = params == null ? null : params.get("targetVolatility");
        if (raw == null) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "TARGET_RISK 시나리오는 scenarioParams.targetVolatility 가 필요합니다.");
        }
        BigDecimal value;
        try {
            value = new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException exc) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "targetVolatility 가 소수가 아닙니다: " + raw);
        }
        if (value.compareTo(MIN_TARGET_VOLATILITY) < 0
                || value.compareTo(MAX_TARGET_VOLATILITY) > 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "targetVolatility 는 %s~%s 여야 합니다: %s"
                            .formatted(MIN_TARGET_VOLATILITY, MAX_TARGET_VOLATILITY, value));
        }
        if (value.remainder(TARGET_VOLATILITY_STEP).signum() != 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "targetVolatility 는 %s 간격이어야 합니다: %s".formatted(TARGET_VOLATILITY_STEP, value));
        }
    }

    /** 종목 중복과 대응표 일치. 화면이 옛 대응표로 만든 요청이 조용히 다른 종목으로 실행되지 않게 한다. */
    private void validateUniverse(CreateBacktestCommand command) {
        List<String> instrumentIds = command.instrumentIds();
        if (new LinkedHashSet<>(instrumentIds).size() != instrumentIds.size()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "같은 종목을 두 번 담을 수 없습니다: " + instrumentIds);
        }
        for (CreateBacktestCommand.AllocationItem item : command.items()) {
            if (!marketMetadata.isMappedTo(
                    command.mappingVersion(), item.sectorId(), item.instrumentId())) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT,
                        "대응표 %s 에서 섹터 %s 는 종목 %s 로 연결되지 않습니다."
                                .formatted(
                                        command.mappingVersion(),
                                        item.sectorId(),
                                        item.instrumentId()));
            }
        }
    }

    /**
     * 목표 비중은 고정 비중 전략에서만 쓴다. 합은 1이어야 하고, 규칙이 종목별 상한을 두면 상한 합계가 투자 비중을 채울 수 있어야 한다.
     *
     * <p>상한 검사를 접수에서 하는 이유: 상한 합계가 투자 비중보다 작으면 어떤 배분도 조건을 만족하지 못한다. 실행해 봐야 실패하거나, 더 나쁘게는 조건을 어긴 채
     * 숫자가 나온다.
     */
    private void validateWeights(CreateBacktestCommand command, StrategyRules rules) {
        boolean fixedWeight = FIXED_WEIGHT.equals(command.strategyId());
        List<CreateBacktestCommand.AllocationItem> items = command.items();
        boolean anyWeight = items.stream().anyMatch(item -> item.targetWeight() != null);
        if (!fixedWeight) {
            if (anyWeight) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT,
                        "targetWeight 는 FIXED_WEIGHT 전략에서만 보낼 수 있습니다: " + command.strategyId());
            }
        } else {
            if (items.stream().anyMatch(item -> item.targetWeight() == null)) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT, "FIXED_WEIGHT 전략은 모든 항목에 targetWeight 가 필요합니다.");
            }
            BigDecimal sum =
                    items.stream()
                            .map(CreateBacktestCommand.AllocationItem::targetWeight)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(BigDecimal.ONE) != 0) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT, "targetWeight 합이 1이 아닙니다: " + sum.toPlainString());
            }
        }

        BigDecimal cap = decimal(rules.spec(), "weightCap");
        if (cap != null) {
            BigDecimal invested =
                    Optional.ofNullable(decimal(rules.spec(), "investedFraction"))
                            .orElse(BigDecimal.ONE);
            BigDecimal reachable = cap.multiply(BigDecimal.valueOf(items.size()));
            if (reachable.compareTo(invested) < 0) {
                throw new BusinessException(
                        ErrorCode.INVALID_INPUT,
                        "종목별 상한 %s × %d종목 = %s 로는 투자 비중 %s 를 채울 수 없습니다. 종목을 늘리거나 상한이 다른 규칙을 고르세요."
                                .formatted(
                                        cap.toPlainString(),
                                        items.size(),
                                        reachable.toPlainString(),
                                        invested.toPlainString()));
            }
        }
    }

    private StrategyRules targetRiskOverlay() {
        return strategyRulesService.requireRegistered("TARGET_RISK_OVERLAY", "1");
    }

    private static BigDecimal decimal(Map<String, Object> spec, String key) {
        Object raw = spec == null ? null : spec.get(key);
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException exc) {
            return null;
        }
    }

    // --- 저장할 사본 ---------------------------------------------------------

    /**
     * 조회 {@code config}가 그대로 돌려줄 요청 사본.
     *
     * <p>{@code warmupTradingDays}는 요청값이 아니라 <b>서버가 확정한 값</b>을 넣는다. 요청이 null 이었다고 null 을 돌려주면 "그래서
     * 몇 거래일을 썼나"라는 질문에 답할 수 없다.
     */
    private static Map<String, Object> requestSpec(
            CreateBacktestCommand command, Scenario scenario, int warmupTradingDays) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CreateBacktestCommand.AllocationItem item : command.items()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sectorId", item.sectorId());
            entry.put("instrumentId", item.instrumentId());
            if (item.targetWeight() != null) {
                entry.put("targetWeight", item.targetWeight());
            }
            items.add(entry);
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("mappingVersion", command.mappingVersion());
        spec.put("items", items);
        spec.put("strategyId", command.strategyId());
        spec.put("rulesVersion", command.rulesVersion());
        if (command.registryVersion() != null) {
            spec.put("registryVersion", command.registryVersion());
        }
        spec.put("parameters", command.parameters());
        spec.put("start", command.start().toString());
        spec.put("end", command.end().toString());
        spec.put("initialCash", command.initialCash());
        if (command.evaluationSplitVersion() != null) {
            spec.put("evaluationSplitVersion", command.evaluationSplitVersion());
        }
        spec.put("warmupTradingDays", warmupTradingDays);
        spec.put("scenario", scenario.name());
        if (command.scenarioParams() != null && !command.scenarioParams().isEmpty()) {
            spec.put("scenarioParams", command.scenarioParams());
        }
        return spec;
    }

    private Map<String, Object> versions(
            CostProfile costProfile, String rulesVersion, String dataVersion) {
        Map<String, Object> costs = new LinkedHashMap<>();
        costs.put("version", costProfile.version());
        costs.put("provisional", costProfile.provisional());
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("costProfile", costs);
        versions.put("rules", rulesVersion);
        versions.put("data", dataVersion);
        versions.put("engine", engineVersion);
        return versions;
    }
}
