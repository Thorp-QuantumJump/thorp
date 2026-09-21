package com.ssafy.thorp.paramsearch.application.service;

import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.backtest.application.service.CostProfileService;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.command.PreviewParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.event.ParamSearchAccepted;
import com.ssafy.thorp.paramsearch.application.exception.ParamSearchRejectedException;
import com.ssafy.thorp.paramsearch.application.exception.RegistryVersionChangedException;
import com.ssafy.thorp.paramsearch.application.port.CandidatePerformance;
import com.ssafy.thorp.paramsearch.application.port.CandidatePerformancePort;
import com.ssafy.thorp.paramsearch.application.port.CandidateRunView;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.port.SaveSlotGuard;
import com.ssafy.thorp.paramsearch.application.port.SearchLimits;
import com.ssafy.thorp.paramsearch.application.port.StrategyExecutionSupportPort;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchPreviewResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.CandidateValidator.ValidatedPoint;
import com.ssafy.thorp.paramsearch.application.service.CommonPeriodResolver.CommonPeriod;
import com.ssafy.thorp.paramsearch.application.service.SearchTargetResolver.SearchTargets;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.SearchCounts;
import com.ssafy.thorp.paramsearch.domain.model.SelectionRule;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.paramsearch.domain.model.ValidationStatus;
import com.ssafy.thorp.paramsearch.domain.vo.ParameterCombination;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
import com.ssafy.thorp.strategy.application.port.ParameterAxis;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.SearchContext;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 탐색 접수 유스케이스(FR-001·FR-003·FR-005·FR-028).
 *
 * <p><b>계산하지 않는다.</b> 여기서 하는 일은 등록표에 묻고, 조합을 펼치고, 한도를 보고, 묶음과 후보를 저장한 뒤 발행 창만큼 004에 넘기는 것이다. 백테스트
 * 계산은 워커가 한다(constitution 아키텍처 원칙 1).
 *
 * <p><b>전략에 대한 판단이 하나도 없다.</b> 대상 전략은 {@link SearchTargetResolver}가, 조합은 {@link GridExpander}가,
 * 유효성은 등록표가 정한다. 이 클래스에는 전략 ID가 나타나지 않는다(FR-025).
 *
 * <p>기능 플래그가 켜졌을 때만 빈이 된다 — {@link ParamSearchProperties}의 네 한도가 {@code @NotNull}이라 조건 없이 두면 값을 채우지
 * 않은 환경에서 애플리케이션 전체가 기동에 실패한다({@code ParamSearchConfig} 주석).
 */
@Service
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class ParamSearchService {

    private final ParameterRegistryPort registry;
    private final StrategyExecutionSupportPort executionSupport;
    private final SearchTargetResolver targetResolver;
    private final GridExpander gridExpander;
    private final CandidateValidator candidateValidator;
    private final CommonPeriodResolver commonPeriodResolver;
    private final CandidateDispatcher dispatcher;
    private final ParamSearchRepository paramSearchRepository;
    private final SearchCandidateRepository candidateRepository;
    private final MarketMetadataPort marketMetadata;
    private final CostProfileService costProfileService;
    private final CandidatePerformancePort performancePort;
    private final SearchLimits limits;
    private final SaveSlotGuard saveSlotGuard;
    private final Clock clock;
    private final String engineVersion;
    private final BigDecimal defaultInitialCash;
    private final ApplicationEventPublisher events;

    public ParamSearchService(
            ParameterRegistryPort registry,
            StrategyExecutionSupportPort executionSupport,
            SearchTargetResolver targetResolver,
            GridExpander gridExpander,
            CandidateValidator candidateValidator,
            CommonPeriodResolver commonPeriodResolver,
            CandidateDispatcher dispatcher,
            ParamSearchRepository paramSearchRepository,
            SearchCandidateRepository candidateRepository,
            MarketMetadataPort marketMetadata,
            CostProfileService costProfileService,
            CandidatePerformancePort performancePort,
            SearchLimits limits,
            SaveSlotGuard saveSlotGuard,
            Clock clock,
            @Value("${thorp.backtest.engine-version}") String engineVersion,
            @Value("${thorp.param-search.default-initial-cash}") BigDecimal defaultInitialCash,
            ApplicationEventPublisher events) {
        this.registry = registry;
        this.executionSupport = executionSupport;
        this.targetResolver = targetResolver;
        this.gridExpander = gridExpander;
        this.candidateValidator = candidateValidator;
        this.commonPeriodResolver = commonPeriodResolver;
        this.dispatcher = dispatcher;
        this.paramSearchRepository = paramSearchRepository;
        this.candidateRepository = candidateRepository;
        this.marketMetadata = marketMetadata;
        this.costProfileService = costProfileService;
        this.performancePort = performancePort;
        this.limits = limits;
        this.saveSlotGuard = saveSlotGuard;
        this.clock = clock;
        this.engineVersion = engineVersion;
        this.defaultInitialCash = defaultInitialCash;
        this.events = events;
    }

    /**
     * 실행 전 확인. <b>저장하지 않는다</b>(FR-003).
     *
     * <p>거부 사유가 있어도 여기서는 예외를 던지지 않는 것과 던지는 것이 갈린다 — 전략 거부(미등록·실행 미지원)는 사용자가 선택을 바꿔야 하는 일이라 422로
     * 알리고, 한도 초과는 화면이 범위를 좁히도록 {@code limits.exceeded}로 알린다(FR-005a·FR-005b).
     */
    @Transactional(readOnly = true)
    public ParamSearchPreviewResult preview(PreviewParamSearchCommand command) {
        Preflight preflight =
                preflight(
                        command.strategyIds(),
                        command.instrumentIds(),
                        command.space(),
                        command.start(),
                        command.end());
        return preflight.toPreviewResult(limits);
    }

    /**
     * 탐색 실행 접수(FR-028).
     *
     * <p>저장과 발행을 한 트랜잭션으로 묶는다 — 발행에 실패하면 묶음도 남지 않아야 재시도가 같은 의미를 갖는다({@code BacktestService}와 같은
     * 규약).
     *
     * <p>{@code noRollbackFor}가 필요한 이유: 거부는 예외로 알리지만 <b>묶음은 남아야 한다</b>. 기본 설정은 {@code
     * RuntimeException}에서 되돌리므로 거부 기록이 함께 사라진다(FR-020).
     *
     * <p>거부는 두 갈래다. 사용자의 선택이 남긴 결과(상한 초과·유효 조합 0건·전략 거부)는 묶음을 {@code REJECTED}로 <b>저장</b>하고, 버전
     * 드리프트와 조회 실패는 저장하지 않는다 — 일시적이거나 다시 확인하면 해소되는 일이라 재현 기록이 아니다(research R9).
     */
    @Transactional(noRollbackFor = ParamSearchRejectedException.class)
    public ParamSearchResult create(CreateParamSearchCommand command) {
        // 저장 자리부터 본다(spec 006 FR-018, research R11). 어떤 행을 쓰기도, 어떤 후보를 발행하기도
        // 전이라 거절이 되돌릴 것이 없다 — 아래 rejected(...) 갈래와 달리 남길 묶음이 없어
        // noRollbackFor 대상이 아니다. 비로그인 접수는 기록을 만들지 않으므로 셀 것도 없다.
        //
        // 이 자리가 preflight 보다도 앞이라, 자리가 없는 사용자는 등록표 버전이 어긋나거나 후보 상한에
        // 걸려 어차피 거절될 요청에도 409 를 받는다. 통과시켜 봐야 저장할 곳이 없으므로 그대로 둔다.
        if (command.requesterId() != null) {
            saveSlotGuard.requireSaveSlot(command.requesterId());
        }

        Preflight preflight =
                preflight(
                        command.strategyIds(),
                        command.instrumentIds(),
                        command.space(),
                        command.start(),
                        command.end());

        // 미리보기에서 본 버전과 다르면 여기서 멈춘다. 확인한 작업량과 다른 것을 실행하면 재현 기록이
        // 거짓이 된다(FR-028). 묶음은 저장하지 않는다 — 사용자가 다시 확인하면 해소되는 일이다.
        if (!preflight.registryVersion().equals(command.registryVersion())) {
            throw new RegistryVersionChangedException(
                    command.registryVersion(), preflight.registryVersion());
        }

        if (preflight.targets().isRejected()) {
            return rejected(command, preflight, preflight.targets().stopReason());
        }
        if (preflight.validCount() == 0) {
            return rejected(command, preflight, StopReason.NO_VALID_CANDIDATE);
        }
        if (preflight.validCount() > limits.maxCandidates()) {
            return rejected(command, preflight, StopReason.CANDIDATE_LIMIT);
        }

        ParamSearch saved =
                paramSearchRepository.save(
                        ParamSearch.create(
                                command.requesterId(),
                                preflight.targetIds(),
                                command.instrumentIds(),
                                preflight.registryVersion(),
                                preflight.space(),
                                selectionRule(preflight.baselineStrategyId()),
                                null,
                                limitsSnapshot(),
                                referenceVersions(preflight),
                                executionPlan(command, preflight).toMap(),
                                SearchCounts.initial(
                                        preflight.totalCount(),
                                        preflight.validCount(),
                                        preflight.excludedCount())));
        candidateRepository.saveAll(saved.getId(), toCandidates(preflight.validated()));

        // 실행이 시작되는 묶음만 알린다 — 위 rejected(...) 갈래는 발행하지 않는다. 지금 듣는 것은 006 내
        // 기록이고, 007은 받는 쪽을 알지 못한다(spec 006 research R16).
        events.publishEvent(
                new ParamSearchAccepted(
                        saved.getId(),
                        saved.getOwnerUserId(),
                        saved.getStrategyIds(),
                        saved.getInstrumentIds(),
                        command.start(),
                        command.end(),
                        clock.instant()));

        ParamSearchSnapshot snapshot =
                ParamSearchSnapshot.fromMap(
                        saved.getId(),
                        saved.getSearchId().value(),
                        saved.getOwnerUserId(),
                        saved.getExecutionPlan());
        return ParamSearchResult.from(dispatcher.start(saved, snapshot));
    }

    /** 묶음 상태 조회. 실패해도 200이며 상태로 판단한다(contracts §3). */
    @Transactional(readOnly = true)
    public ParamSearchResult get(String searchId) {
        ParamSearch search =
                paramSearchRepository
                        .findBySearchId(searchId)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.RESOURCE_NOT_FOUND,
                                                "탐색 묶음을 찾을 수 없습니다: " + searchId));
        return ParamSearchResult.from(search, baselineOf(search));
    }

    /**
     * 손실 제약의 기준이 된 실행(FR-013d, 계약 §3).
     *
     * <p>전략 ID·표시명은 <b>접수 시점 스냅샷</b>에서 읽는다 — 지금 등록표를 다시 물으면 버전이 올라간 뒤 그 묶음이 무엇을 기준으로 삼았는지가
     * 바뀐다(FR-020, 원칙 VIII). 숫자는 실행에서 읽는다.
     *
     * <p>기준 실행을 만들지 않았으면 null이다. 만들었지만 아직 안 끝났거나 실패했으면 숫자만 null이고, 그 경우 선정은 손실 제약을 걸지 않은 채 그 사실을
     * {@code drawdownFilterApplied=false}로 알린다.
     */
    private ParamSearchResult.Baseline baselineOf(ParamSearch search) {
        Long baselineJobId = search.getBaselineJobId();
        if (baselineJobId == null) {
            return null;
        }
        SelectionRule.DrawdownFilter filter = search.getSelectionRule().drawdownFilter();
        CandidateRunView view =
                performancePort.findRunViews(List.of(baselineJobId)).get(baselineJobId);
        CandidatePerformance performance = view == null ? null : view.performance();
        return new ParamSearchResult.Baseline(
                filter.baseline(),
                filter.baselineName(),
                view == null ? null : view.backtestId(),
                view == null ? null : view.status(),
                performance == null ? null : performance.netReturn(),
                performance == null ? null : performance.maxDrawdown());
    }

    /**
     * 미리보기와 접수가 공유하는 계산. 등록표 조회 → 대상 판정 → 탐색 공간 → 전개 → 유효성 → 공통 구간 순이다.
     *
     * <p>두 경로가 같은 계산을 쓰는 것이 {@code registryVersion} 비교를 의미 있게 만든다 — 다른 계산을 쓰면 버전이 같아도 결과가 갈릴 수 있다.
     */
    private Preflight preflight(
            List<String> strategyIds,
            List<String> instrumentIds,
            Map<String, Map<String, List<Object>>> narrowing,
            LocalDate start,
            LocalDate end) {
        SearchTargets targets = targetResolver.resolve(strategyIds);
        if (targets.isRejected()) {
            // 조합을 만들기 전에 멈춘다. 대상이 없으면 탐색 공간도 없다(FR-026).
            return new Preflight(
                    targets, SearchSpace.of(Map.of()), List.of(), null, supportedIds());
        }

        SearchSpace space = narrow(targets, narrowing);
        List<ValidatedPoint> validated =
                candidateValidator.validate(
                        withoutInapplicableAxes(targets, gridExpander.expand(space)),
                        new SearchContext(instrumentIds.size()));
        CommonPeriod period =
                commonPeriodResolver.resolve(instrumentIds, space, targets.targets(), start, end);
        return new Preflight(targets, space, validated, period, supportedIds());
    }

    private List<String> supportedIds() {
        return List.copyOf(executionSupport.supportedStrategyIds());
    }

    /**
     * 사용자가 고른 범위로 탐색 공간을 좁힌다. 생략하면 등록표 전체 범위다.
     *
     * <p><b>등록표에 없는 값은 만들지 않는다</b>(FR-002) — 값 하나라도 등록 목록 밖이면 400 {@code
     * UNREGISTERED_PARAMETER_VALUE}다. 좁히는 것은 등록표 안에서만 가능하다.
     */
    private SearchSpace narrow(
            SearchTargets targets, Map<String, Map<String, List<Object>>> narrowing) {
        if (narrowing.isEmpty()) {
            return targets.fullSearchSpace();
        }
        Map<String, SearchSpace.StrategySpace> strategies = new LinkedHashMap<>();
        for (StrategyRegistration registration : targets.targets()) {
            Map<String, List<Object>> axisNarrowing =
                    narrowing.getOrDefault(registration.strategyId(), Map.of());
            List<SearchSpace.Axis> axes = new ArrayList<>();
            for (ParameterAxis axis : registration.axes()) {
                List<Object> values =
                        axisNarrowing.containsKey(axis.name())
                                ? requireRegistered(
                                        registration, axis, axisNarrowing.get(axis.name()))
                                : axis.searchValues();
                axes.add(new SearchSpace.Axis(axis.name(), axis.label(), axis.unit(), values));
            }
            strategies.put(
                    registration.strategyId(),
                    new SearchSpace.StrategySpace(registration.name(), axes));
        }
        rejectUnknownKeys(targets, narrowing);
        return SearchSpace.of(strategies);
    }

    private static List<Object> requireRegistered(
            StrategyRegistration registration, ParameterAxis axis, List<Object> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "축의 후보값을 비울 수 없습니다: %s/%s".formatted(registration.strategyId(), axis.name()));
        }
        for (Object value : requested) {
            if (axis.indexOf(value) < 0) {
                throw new BusinessException(
                        ErrorCode.UNREGISTERED_PARAMETER_VALUE,
                        "등록되지 않은 값입니다: %s/%s = %s"
                                .formatted(registration.strategyId(), axis.name(), value));
            }
        }
        // 등록표 순서를 유지한다. 요청 순서를 그대로 쓰면 같은 선택이 다른 그리드 좌표를 만든다(FR-029).
        return axis.searchValues().stream().filter(requested::contains).toList();
    }

    /** 대상에 없는 전략이나 등록되지 않은 축을 좁히기에 담으면 조용히 무시하지 않고 거절한다. */
    private static void rejectUnknownKeys(
            SearchTargets targets, Map<String, Map<String, List<Object>>> narrowing) {
        Map<String, StrategyRegistration> byId = new LinkedHashMap<>();
        targets.targets().forEach(target -> byId.put(target.strategyId(), target));
        narrowing.forEach(
                (strategyId, axes) -> {
                    StrategyRegistration registration = byId.get(strategyId);
                    if (registration == null) {
                        throw new BusinessException(
                                ErrorCode.INVALID_INPUT, "탐색 대상이 아닌 전략의 범위입니다: " + strategyId);
                    }
                    axes.keySet()
                            .forEach(
                                    axisName -> {
                                        if (registration.axis(axisName).isEmpty()) {
                                            throw new BusinessException(
                                                    ErrorCode.UNREGISTERED_PARAMETER_VALUE,
                                                    "등록되지 않은 축입니다: %s/%s"
                                                            .formatted(strategyId, axisName));
                                        }
                                    });
                });
    }

    /**
     * 거부한 묶음을 저장한다. 무엇을 왜 실행하지 않았는지가 재현 기록의 일부다(FR-020).
     *
     * <p>후보는 만들지 않는다 — 계산 대상이 없다. 거부 사유는 {@code stop_reason}에 남고, 두 전략 거부는 서로 구분된다(FR-026).
     */
    private ParamSearchResult rejected(
            CreateParamSearchCommand command, Preflight preflight, StopReason stopReason) {
        ParamSearch saved =
                paramSearchRepository.save(
                        ParamSearch.create(
                                command.requesterId(),
                                command.strategyIds(),
                                command.instrumentIds(),
                                preflight.registryVersion(),
                                preflight.space(),
                                selectionRule(preflight.baselineStrategyId()),
                                null,
                                limitsSnapshot(),
                                referenceVersions(preflight),
                                rejectedExecutionPlan(command, preflight),
                                SearchCounts.initial(
                                        preflight.totalCount(),
                                        preflight.validCount(),
                                        preflight.excludedCount())));
        ParamSearch rejected =
                paramSearchRepository.update(saved.reject(stopReason, clock.instant()));
        throw new ParamSearchRejectedException(
                stopReason, rejected, describe(stopReason, preflight));
    }

    /** 거부된 묶음에도 실행 조건을 남긴다. 구간을 못 정한 거부(전략 거부)는 그 사실을 그대로 둔다. */
    private Map<String, Object> rejectedExecutionPlan(
            CreateParamSearchCommand command, Preflight preflight) {
        if (preflight.period() == null) {
            return Map.of("rejectedBeforePlanning", true);
        }
        return executionPlan(command, preflight).toMap();
    }

    private String describe(StopReason stopReason, Preflight preflight) {
        return switch (stopReason) {
            case CANDIDATE_LIMIT ->
                    "유효 후보 %d개가 상한 %d개를 넘습니다. 축의 후보값을 줄이거나 전략을 하나만 고르세요"
                            .formatted(preflight.validCount(), limits.maxCandidates());
            case NO_VALID_CANDIDATE ->
                    "고른 범위의 조합이 모두 제약에 걸렸습니다: " + preflight.firstExclusionMessage();
            case STRATEGY_NOT_REGISTERED, STRATEGY_NOT_EXECUTABLE ->
                    preflight.targets().toException().getMessage();
            default -> "실행하지 않았습니다: " + stopReason;
        };
    }

    /**
     * 선정 규칙 스냅샷(FR-013c). 기준 전략은 <b>등록표가 지정한 역할</b>에서 온다 — 007 코드에 기준 전략 ID를 두지 않는다(FR-013d,
     * FR-025). 지정이 없으면 하락 폭 필터를 적용하지 않는다는 사실을 규칙에 남긴다.
     */
    private SelectionRule selectionRule(String baselineStrategyId) {
        SelectionRule.DrawdownFilter drawdownFilter =
                baselineStrategyId == null
                        ? new SelectionRule.DrawdownFilter("NOT_APPLIED", "NO_BASELINE_REGISTERED")
                        : new SelectionRule.DrawdownFilter(
                                "NOT_WORSE_THAN_BASELINE",
                                baselineStrategyId,
                                baselineStrategyName(baselineStrategyId));
        return new SelectionRule(
                drawdownFilter,
                new SelectionRule.StabilityFilter("BATCH_PERCENTILE", 50),
                "NET_RETURN",
                5,
                "SHOW_ALL_TIED",
                new SelectionRule.StabilityFormula(
                        "SINGLE_AXIS_ONE_STEP", "MEAN_ABS_NET_RETURN_DIFF"),
                List.of("displayCount"));
    }

    /** 기준 전략의 표시명. 등록표 값을 그대로 옮긴다 — 007이 이름을 만들지 않는다(FR-025). */
    private String baselineStrategyName(String baselineStrategyId) {
        return registry.find(baselineStrategyId).map(StrategyRegistration::name).orElse(null);
    }

    private Map<String, Object> limitsSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("maxCandidates", limits.maxCandidates());
        snapshot.put("timeLimitSeconds", limits.timeLimitSeconds());
        snapshot.put("dispatchWindow", limits.dispatchWindow());
        snapshot.put("consecutiveFailureLimit", limits.consecutiveFailureLimit());
        snapshot.put("provisional", limits.provisional());
        return snapshot;
    }

    /** 시작 시점의 자료·등록표·비용·엔진 버전(FR-012). 묶음은 이 버전으로 끝까지 계산한다. */
    private Map<String, Object> referenceVersions(Preflight preflight) {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("marketData", marketMetadata.currentDataVersion());
        versions.put("registry", preflight.registryVersion());
        versions.put("cost", costProfileService.resolve(null).version());
        versions.put("engine", engineVersion);
        return versions;
    }

    private ParamSearchSnapshot executionPlan(
            CreateParamSearchCommand command, Preflight preflight) {
        String mappingVersion = marketMetadata.currentMappingVersion();
        List<ParamSearchSnapshot.UniverseEntry> universe =
                command.instrumentIds().stream()
                        .map(
                                instrumentId ->
                                        new ParamSearchSnapshot.UniverseEntry(
                                                sectorOf(mappingVersion, instrumentId),
                                                instrumentId,
                                                null))
                        .toList();
        return new ParamSearchSnapshot(
                null,
                null,
                command.requesterId(),
                universe,
                mappingVersion,
                preflight.period().start(),
                preflight.period().end(),
                command.initialCash() == null ? defaultInitialCash : command.initialCash(),
                costProfileService.resolve(null).version(),
                null,
                preflight.period().warmupTradingDays(),
                preflight.registryVersion(),
                rulesVersions(preflight),
                preflight.baselineStrategyId(),
                baselineParameters(preflight.baselineStrategyId()));
    }

    private String sectorOf(String mappingVersion, String instrumentId) {
        return marketMetadata
                .sectorOf(mappingVersion, instrumentId)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        ErrorCode.INVALID_INPUT,
                                        "대응표 %s 에 없는 종목입니다: %s"
                                                .formatted(mappingVersion, instrumentId)));
    }

    /**
     * 전략별 규칙 버전. <b>004가 준 값을 그대로 고정한다</b> — 007이 버전을 고르면 004 등록표와 갈리고, 발행할 때마다 다시 읽으면 묶음 안의 후보들이
     * 서로 다른 규칙으로 계산된다(FR-012).
     *
     * <p><b>기준 전략도 담는다.</b> 기준은 사용자가 고른 전략이 아니라 등록표가 지정한 역할이라 {@code targetIds}에 없다(FR-013d). 빠뜨리면
     * 기준 발행이 규칙 버전 {@code null}로 004에 가서 접수 전체가 422로 죽는다 — 요청 전략에 기준이 우연히 섞이지 않는 한 <b>모든 접수</b>가
     * 그렇다.
     */
    private static Map<String, String> rulesVersions(Preflight preflight) {
        Map<String, String> supported = preflight.targets().rulesVersionByStrategy();
        List<String> needed = new ArrayList<>(preflight.targetIds());
        String baselineStrategyId = preflight.baselineStrategyId();
        if (baselineStrategyId != null && !needed.contains(baselineStrategyId)) {
            needed.add(baselineStrategyId);
        }
        Map<String, String> versions = new LinkedHashMap<>();
        needed.forEach(
                strategyId -> {
                    String version = supported.get(strategyId);
                    if (version != null) {
                        versions.put(strategyId, version);
                    }
                });
        return versions;
    }

    /** 기준 전략의 파라미터는 <b>등록된 기본 시작값</b>이다. 007이 값을 고르지 않는다(FR-013d·FR-027). */
    private Map<String, Object> baselineParameters(String baselineStrategyId) {
        if (baselineStrategyId == null) {
            return Map.of();
        }
        Optional<StrategyRegistration> registration = registry.find(baselineStrategyId);
        if (registration.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        registration
                .get()
                .axes()
                .forEach(
                        axis -> {
                            if (axis.defaultValue() != null) {
                                parameters.put(axis.name(), axis.defaultValue());
                            }
                        });
        return parameters;
    }

    /**
     * 조건 밖 축의 값을 조합에서 뺀다(003 FR-003, {@code ParameterAxis.appliesWhen}).
     *
     * <p>그리드는 축을 모두 펼치므로 선정 방식이 '상위 몇 개'인 점에도 임계값·상위 비율이 딸려 온다. 그 값은 사용자가 고른 것도 규칙이 볼 것도 아니라 실행에 싣지
     * 않는다. <b>좌표는 그대로 둔다</b> — 좌표는 그리드 안의 자리이지 실행 값이 아니고, 축을 빼면 같은 등록표 버전 안에서 인접 판정이 흔들린다(FR-013a).
     * 조건은 등록표가 정하고 여기서 축 이름을 해석하지 않는다.
     */
    private static List<GridExpander.GridPoint> withoutInapplicableAxes(
            SearchTargets targets, List<GridExpander.GridPoint> points) {
        Map<String, StrategyRegistration> byId = new LinkedHashMap<>();
        targets.targets().forEach(target -> byId.put(target.strategyId(), target));
        List<GridExpander.GridPoint> trimmed = new ArrayList<>(points.size());
        for (GridExpander.GridPoint point : points) {
            StrategyRegistration registration = byId.get(point.strategyId());
            Map<String, Object> values = point.parameters().values();
            Map<String, Object> kept =
                    registration == null ? values : registration.applicableValues(values);
            trimmed.add(
                    kept.size() == values.size()
                            ? point
                            : new GridExpander.GridPoint(
                                    point.strategyId(),
                                    ParameterCombination.of(kept),
                                    point.coordinates()));
        }
        return List.copyOf(trimmed);
    }

    private static List<SearchCandidate> toCandidates(List<ValidatedPoint> validated) {
        List<SearchCandidate> candidates = new ArrayList<>(validated.size());
        int candidateNo = 1;
        for (ValidatedPoint point : validated) {
            candidates.add(
                    point.isValid()
                            ? SearchCandidate.pending(
                                    candidateNo,
                                    point.point().strategyId(),
                                    point.point().parameters(),
                                    point.point().coordinates())
                            : SearchCandidate.excluded(
                                    candidateNo,
                                    point.point().strategyId(),
                                    point.point().parameters(),
                                    point.point().coordinates(),
                                    point.verdict().reasonCode(),
                                    point.verdict().reasonDetail()));
            candidateNo++;
        }
        return candidates;
    }

    /** 미리보기와 접수가 공유하는 계산 결과. */
    private record Preflight(
            SearchTargets targets,
            SearchSpace space,
            List<ValidatedPoint> validated,
            CommonPeriod period,
            List<String> supportedStrategyIds) {

        String registryVersion() {
            return targets.registryVersion();
        }

        List<String> targetIds() {
            return targets.targets().stream().map(StrategyRegistration::strategyId).toList();
        }

        String baselineStrategyId() {
            return targets.baselineStrategyId();
        }

        long totalCount() {
            return validated.size();
        }

        long validCount() {
            return validated.stream().filter(ValidatedPoint::isValid).count();
        }

        long excludedCount() {
            return totalCount() - validCount();
        }

        String firstExclusionMessage() {
            return validated.stream()
                    .filter(point -> !point.isValid())
                    .map(point -> point.verdict().reasonDetail())
                    .findFirst()
                    .orElse("제외 사유가 기록되지 않았습니다");
        }

        ParamSearchPreviewResult toPreviewResult(SearchLimits limits) {
            long valid = validCount();
            // 실행 전에도 미검증임을 알린다 — 결과를 본 뒤에야 알게 되면 그것은 이미 오해가 생긴 뒤다(FR-015).
            ValidationStatus validation = ValidationStatus.unvalidated();
            return new ParamSearchPreviewResult(
                    registryVersion(),
                    strategySummaries(),
                    spaceSummaries(),
                    workload(),
                    new ParamSearchPreviewResult.Limits(
                            limits.maxCandidates(),
                            limits.timeLimitSeconds(),
                            limits.dispatchWindow(),
                            limits.consecutiveFailureLimit(),
                            limits.provisional(),
                            valid > limits.maxCandidates()),
                    period == null
                            ? null
                            : new ParamSearchPreviewResult.CommonPeriod(
                                    period.start(), period.end(), period.shortenedBy()),
                    validation.label().name(),
                    validation.note());
        }

        private List<ParamSearchPreviewResult.StrategySummary> strategySummaries() {
            List<ParamSearchPreviewResult.StrategySummary> summaries = new ArrayList<>();
            targets.targets()
                    .forEach(
                            registration ->
                                    summaries.add(
                                            new ParamSearchPreviewResult.StrategySummary(
                                                    registration.strategyId(),
                                                    registration.name(),
                                                    true)));
            targets.notExecutable()
                    .forEach(
                            strategyId ->
                                    summaries.add(
                                            new ParamSearchPreviewResult.StrategySummary(
                                                    strategyId, null, false)));
            return summaries;
        }

        private List<ParamSearchPreviewResult.StrategySpaceSummary> spaceSummaries() {
            List<ParamSearchPreviewResult.StrategySpaceSummary> summaries = new ArrayList<>();
            for (StrategyRegistration registration : targets.targets()) {
                SearchSpace.StrategySpace strategy =
                        space.strategies().get(registration.strategyId());
                if (strategy == null) {
                    continue;
                }
                List<ParamSearchPreviewResult.AxisSummary> axes = new ArrayList<>();
                for (SearchSpace.Axis axis : strategy.axes()) {
                    ParameterAxis registered = registration.axis(axis.name()).orElseThrow();
                    axes.add(
                            new ParamSearchPreviewResult.AxisSummary(
                                    axis.name(),
                                    axis.label(),
                                    axis.unit(),
                                    axis.values(),
                                    registered.defaultValue(),
                                    registered.allowedRange() == null
                                            ? null
                                            : new ParamSearchPreviewResult.AllowedRangeSummary(
                                                    registered.allowedRange().min(),
                                                    registered.allowedRange().max(),
                                                    registered.allowedRange().step(),
                                                    registered.allowedRange().values())));
                }
                summaries.add(
                        new ParamSearchPreviewResult.StrategySpaceSummary(
                                registration.strategyId(),
                                registration.name(),
                                axes,
                                strategy.combinationCount()));
            }
            return summaries;
        }

        /** 사유별 건수의 합은 제외 건수와 같다 — 화면이 "왜 몇 개가 빠졌는지"를 설명할 수 있어야 한다. */
        private ParamSearchPreviewResult.Workload workload() {
            Map<String, ParamSearchPreviewResult.ExclusionReason> byCode = new LinkedHashMap<>();
            validated.stream()
                    .filter(point -> !point.isValid())
                    .forEach(
                            point -> {
                                String code = point.verdict().reasonCode();
                                ParamSearchPreviewResult.ExclusionReason existing =
                                        byCode.get(code);
                                byCode.put(
                                        code,
                                        new ParamSearchPreviewResult.ExclusionReason(
                                                code,
                                                point.verdict().reasonDetail(),
                                                existing == null ? 1 : existing.count() + 1));
                            });
            return new ParamSearchPreviewResult.Workload(
                    totalCount(), validCount(), excludedCount(), List.copyOf(byCode.values()));
        }
    }
}
