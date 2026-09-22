package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.command.PinnedVersions;
import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.event.BacktestRequestAccepted;
import com.ssafy.thorp.backtest.application.port.MarketMetadataPort;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestReuseEventRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.PublicResultToken;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RulesRef;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.strategy.application.service.StrategySettingsValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 요청 접수(spec 004 T005). 백엔드는 계산하지 않는다 — 요청을 검증해 실행을 만들고 작업 메시지를 발행한다.
 *
 * <p>접수가 하는 일은 "실행 전에 거절할 수 있는 것을 전부 거절하는 것"이다. 규칙이 팀 미확정이면 422, 새 버전으로 대체된 옛 규칙이면 409, 워밍업이 모자라면
 * 422, 비중 합이 1이 아니면 400이다. 여기서 통과시킨 요청은 워커가 자료를 읽다가 실패하는 것 말고는 실패할 이유가 없어야 한다.
 *
 * <p><b>입구가 둘이다</b>(T029). 새 요청({@link #create})은 지금 등록된 규칙·등록표 버전만 받고, 버전이 고정된 요청({@link
 * #createPinned} — 재시도와 007 묶음 후보)은 접수 때 판정받은 옛 버전도 받는다. 나머지 판정(동일 버전 재사용·합류·한도)은 둘이 같다.
 *
 * <p><b>접수 거절은 호출자의 트랜잭션을 되돌리지 않는다</b>({@code noRollbackFor = BusinessException.class}, T014).
 * 기본값이면 여기서 던진 예외가 참여 중인 트랜잭션을 rollback-only 로 표시하고, 호출자가 그것을 잡아도 커밋할 수 없다. 007 자동 탐색은 후보 정산과 다음 후보
 * 접수를 한 트랜잭션에서 하므로, 대기열이 찬 순간 <b>이미 끝난 후보의 완료 처리까지 함께 사라진다</b> — 접수를 거절하려다 남의 커밋을 지우는 셈이다.
 *
 * <p>이 설정이 안전한 근거는 <b>거절이 쓰기보다 먼저 일어난다</b>는 것 하나다. 이 메서드가 {@link BusinessException} 을 던지는 자리는 검증·동일
 * 버전 판정·한도 검사뿐이고 그 앞에는 잠금과 조회만 있다. <b>쓰기 뒤에 던지는 분기를 새로 만들면 이 설정을 함께 거두어야 한다</b> — 그 순간 절반만 쓰인 접수가
 * 커밋된다. {@code BacktestServiceTest} 가 그 순서를 단언한다.
 *
 * <p><b>발행은 커밋 뒤에</b> 한다(R-18, {@link BacktestJobDispatcher}). 트랜잭션 안에서 발행하면 워커의 STARTED가 커밋을 앞질러
 * 모르는 실행으로 버려진다.
 *
 * <p><b>요청 행마다 공개 토큰을 발급한다</b>(spec 005 B01). 새 계산·합류·재사용 세 경로 모두 새 요청 행을 만들므로 셋 다 토큰이 붙는다 — 같은 실행을
 * 보는 두 요청도 토큰은 다르다. 요청자가 없으면(비로그인) 요청 생성 시각에 유효 시간을 더한 만료 시각을 함께 남긴다(005 R-03).
 */
@Service
public class BacktestService {

    private static final Logger log = LoggerFactory.getLogger(BacktestService.class);

    private final BacktestRunRepository runRepository;
    private final BacktestRequestRepository requestRepository;
    private final BacktestReuseEventRepository reuseEventRepository;
    private final ExecutionLimitPolicy executionLimitPolicy;
    private final StrategyRulesService strategyRulesService;
    private final StrategySettingsValidator strategySettingsValidator;
    private final CostProfileService costProfileService;
    private final EffectivePeriodCalculator effectivePeriodCalculator;
    private final DedupKeyCalculator dedupKeyCalculator;
    private final MarketMetadataPort marketMetadata;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String engineVersion;
    private final Duration publicTokenTtl;

    public BacktestService(
            BacktestRunRepository runRepository,
            BacktestRequestRepository requestRepository,
            BacktestReuseEventRepository reuseEventRepository,
            ExecutionLimitPolicy executionLimitPolicy,
            StrategyRulesService strategyRulesService,
            StrategySettingsValidator strategySettingsValidator,
            CostProfileService costProfileService,
            EffectivePeriodCalculator effectivePeriodCalculator,
            DedupKeyCalculator dedupKeyCalculator,
            MarketMetadataPort marketMetadata,
            ApplicationEventPublisher events,
            Clock clock,
            @Value("${thorp.backtest.engine-version}") String engineVersion,
            // ResultProperties(result 패키지)를 주입받지 않고 키만 읽는다 — 의존 방향이 result → backtest 하나다(ArchUnit
            // 규칙 9).
            @Value("${thorp.result.public-token-ttl}") Duration publicTokenTtl) {
        this.runRepository = runRepository;
        this.requestRepository = requestRepository;
        this.reuseEventRepository = reuseEventRepository;
        this.executionLimitPolicy = executionLimitPolicy;
        this.strategyRulesService = strategyRulesService;
        this.strategySettingsValidator = strategySettingsValidator;
        this.costProfileService = costProfileService;
        this.effectivePeriodCalculator = effectivePeriodCalculator;
        this.dedupKeyCalculator = dedupKeyCalculator;
        this.marketMetadata = marketMetadata;
        this.events = events;
        this.clock = clock;
        this.engineVersion = engineVersion;
        this.publicTokenTtl = publicTokenTtl;
    }

    /** 목표 비중을 지정하는 유일한 전략. 나머지 전략에서 비중을 보내면 무시하지 않고 거절한다. */
    private static final String FIXED_WEIGHT = "FIXED_WEIGHT";

    private static final String SECTOR_MOMENTUM = "SECTOR_MOMENTUM";

    /** 목표 위험 맞춤 시나리오의 규칙 행. 전략이 아니지만 같은 등록표에서 버전을 관리한다(PD-18). */
    static final String TARGET_RISK_OVERLAY = "TARGET_RISK_OVERLAY";

    /** 목표 변동성 허용 범위와 간격(PD-18). */
    private static final BigDecimal MIN_TARGET_VOLATILITY = new BigDecimal("0.0500");

    private static final BigDecimal MAX_TARGET_VOLATILITY = new BigDecimal("0.2000");
    private static final BigDecimal TARGET_VOLATILITY_STEP = new BigDecimal("0.0100");

    /** 003 축은 %, 규칙표와 워커는 소수다. 변환은 {@link #effectiveWeightCap} 한 곳에서만 한다. */
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** 새 요청. 규칙·등록표 버전은 지금 등록된 것만 받는다(T029). */
    @Transactional(noRollbackFor = BusinessException.class)
    public BacktestJobResult create(CreateBacktestCommand command) {
        return admit(command, null);
    }

    /**
     * 버전이 이미 고정된 요청을 접수한다 — 실패·취소한 요청의 재시도(T010)와 007 묶음의 후보다({@link PinnedVersions}, T029).
     *
     * <p>이 요청도 접수를 <b>다시</b> 타므로 동일 버전 재사용·합류 판정을 똑같이 받는다 — 원본이 실패한 뒤 다른 사람이 같은 조건을 이미 돌리고 있으면 거기에
     * 합류한다. 새 요청과 다른 것은 버전 판정 하나다. 전략 규칙은 대체된 옛 버전도 받고({@link
     * StrategyRulesService#requireExecutable}), 003 등록표도 그 요청이 판정받은 과거 버전을 받는다({@link
     * StrategySettingsValidator#verifySaved}). 규칙이 바뀐 날부터 옛 요청을 다시 돌릴 방법이 사라지거나 진행 중인 묶음이 멈추면 안 되기
     * 때문이다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public BacktestJobResult createPinned(CreateBacktestCommand command, PinnedVersions pinned) {
        return admit(command, Objects.requireNonNull(pinned, "pinned must not be null"));
    }

    /**
     * @param pinned null = 새 요청, 아니면 버전이 고정된 요청
     */
    private BacktestJobResult admit(CreateBacktestCommand command, PinnedVersions pinned) {
        Scenario scenario = command.scenario() == null ? Scenario.FULL_PERIOD : command.scenario();
        validateScenario(scenario, command.scenarioParams());
        validateUniverse(command);

        StrategyRules rules =
                pinned == null
                        ? strategyRulesService.requireCurrent(
                                command.strategyId(), command.rulesVersion())
                        : strategyRulesService.requireExecutable(
                                command.strategyId(), command.rulesVersion());
        validateWeights(command, rules);
        Integer warmupTradingDays = verifyRegistrySettings(command, pinned != null);
        StrategyRules overlay = scenario == Scenario.TARGET_RISK ? targetRiskOverlay(pinned) : null;
        CostProfile costProfile = costProfileService.resolve(command.costProfileVersion());
        Long retryOfRequestId = pinned == null ? null : pinned.retryOfRequestId();

        EffectivePeriodCalculator.EffectivePeriod period =
                effectivePeriodCalculator.resolve(
                        rules,
                        overlay,
                        command.parameters(),
                        command.instrumentIds(),
                        command.start(),
                        command.end(),
                        warmupTradingDays);

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

        // 조회 → 판단 → INSERT 를 같은 키끼리 직렬화한다. 잠그지 않으면 두 접수가 나란히 "없다"를 읽고
        // 둘 다 INSERT 해 부분 유니크 인덱스에 걸린다 — 정상적인 사용자 행동이 500 이 된다.
        runRepository.lockDedupKey(dedupKey);

        Optional<BacktestRun> active = runRepository.findActiveByDedupKey(dedupKey);
        if (active.isPresent()) {
            Optional<BacktestJobResult> joined =
                    joinExisting(command, active.get().getId(), retryOfRequestId);
            if (joined.isPresent()) {
                return joined.get();
            }
            // 잠근 뒤 보니 종결된 실행이었다. 부분 유니크 인덱스가 같은 키의 새 실행을 허용하므로 아래에서 만든다.
        }

        // 여기까지 왔으면 새 계산을 만든다. 합류·재사용은 위에서 끝났으므로 한도에 세지 않는다(R-19).
        executionLimitPolicy.requireAdmission(command.requesterId(), command.executionType());

        Map<String, Object> versions = versions(command, rules, overlay, costProfile, dataVersion);
        RunPlan plan =
                new RunPlan(
                        dedupKey,
                        command.rulesVersion(),
                        costProfile.version(),
                        dataVersion,
                        period.effectiveStart(),
                        period.effectiveEnd(),
                        period.warmupTradingDays(),
                        RequestSpecCodec.write(command, scenario, period.warmupTradingDays()),
                        versions,
                        // 발행 큐를 정한다(R-20). 첫 요청의 유형으로 고정하고 뒤에 합류하는 요청은 바꾸지 못한다.
                        command.executionType());

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
                        newRequest(command, saved.getId(), ResultSource.NEW, retryOfRequestId));
        publishRequestAccepted(command, request.getId(), saved.getId());
        events.publishEvent(
                new BacktestJobDispatcher.RunAccepted(
                        saved.getId(), saved.getLifecycle().publishCount()));
        return BacktestJobResult.of(request, saved);
    }

    /**
     * 같은 조건의 살아 있는 실행에 합류(또는 완료 결과를 재사용)한다.
     *
     * <p><b>실행 행을 잠그고 최신 상태를 다시 본다.</b> 동일 버전 조회와 이 사이에 마지막 활성 요청이 취소해 CANCEL 이 나갔을 수 있는데, 그 실행에
     * 붙이면 취소하지 않은 이 요청까지 부분 결과로 끝난다(R-04). 취소 경로도 같은 행을 잠그므로 둘은 직렬화되고 어느 쪽이 이기든 답이 하나로 정해진다 — 취소가
     * 먼저면 이 접수는 {@code CANCEL_PENDING}, 접수가 먼저면 그 취소는 마지막 요청이 아니게 되어 실행이 계속 돈다.
     *
     * @return 빈 값 = 잠근 뒤 보니 종결된 실행이라 재사용 대상이 아니다(호출자가 새 실행을 만든다)
     */
    private Optional<BacktestJobResult> joinExisting(
            CreateBacktestCommand command, Long runId, Long retryOfRequestId) {
        BacktestRun run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null || !isReusable(run.getStatus())) {
            return Optional.empty();
        }
        if (run.getLifecycle().cancelRequestedAt() != null) {
            // 마지막 활성 요청이 취소해 CANCEL 을 보낸 실행이다. 워커가 다음 거래일에 멈추고 FINISHED(CANCELLED)를 보내면
            // 종결된다 — 여기에 합류시키면 취소하지 않은 이 요청까지 부분 결과로 끝난다(R-04). 새 실행은 동일 버전
            // 부분 유니크 인덱스(status IN QUEUED·RUNNING·COMPLETED) 때문에 그때까지 만들 수 없으니 잠시 뒤 다시 오게 한다.
            throw new BusinessException(
                    ErrorCode.CANCEL_PENDING,
                    "같은 조건의 실행이 취소 처리 중입니다: runId=" + run.getBacktestId().value());
        }
        ResultSource source =
                run.getStatus() == RunStatus.COMPLETED ? ResultSource.REUSED : ResultSource.JOINED;
        BacktestRequest request =
                requestRepository.save(newRequest(command, run.getId(), source, retryOfRequestId));
        publishRequestAccepted(command, request.getId(), run.getId());
        // 아낀 계산을 한 줄 남긴다(FR-008). 요청 저장과 같은 트랜잭션이라 둘 중 하나만 남지 않는다.
        reuseEventRepository.save(
                BacktestReuseEvent.of(
                        run.getId(),
                        request.getId(),
                        source,
                        command.executionType(),
                        clock.instant()));
        if (source == ResultSource.REUSED) {
            // **끝난 실행을 재사용하면 이 접수에는 FINISHED 가 다시 오지 않는다.** 007 후보는 그 이벤트
            // 하나로만 DISPATCHED 를 벗어나므로(BacktestFinishedListener), 알리지 않으면 이미 답이 나와
            // 있는 후보가 영원히 '실행 중'으로 남고 발행 창이 막혀 묶음 전체가 제한시간까지 멈춘다.
            // 같은 조합을 두 번째로 돌리는 순간 바로 걸리는 자리다.
            //
            // JOINED 는 여기서 알리지 않는다 — 그 실행은 아직 돌고 있고, 끝나면 제 FINISHED 하나가
            // 걸린 후보를 전부 전진시킨다. 미리 보내면 아직 없는 결과를 완료로 읽는다.
            events.publishEvent(
                    new BacktestFinished(
                            run.getId(), run.getBacktestId().value(), run.getStatus(), null));
        }
        return Optional.of(BacktestJobResult.of(request, run));
    }

    /**
     * 접수한 요청 하나를 알린다. 새 실행도 합류·재사용도 같은 이벤트다 — 어느 쪽이든 요청은 새로 생긴다.
     *
     * <p>004 는 받는 쪽을 알지 못한다. 지금 듣는 것은 006 내 기록이고, 새 항목을 만들지 기존 항목을 돌려줄지는 그쪽이 판단한다(spec 006 research
     * R16).
     */
    private void publishRequestAccepted(CreateBacktestCommand command, long requestId, long runId) {
        events.publishEvent(
                new BacktestRequestAccepted(
                        requestId,
                        runId,
                        command.requesterId(),
                        command.executionType(),
                        command.items().stream()
                                .map(
                                        item ->
                                                new BacktestRequestAccepted.AcceptedSector(
                                                        item.sectorId(), item.targetWeight()))
                                .toList(),
                        command.strategyId(),
                        command.start(),
                        command.end(),
                        clock.instant()));
    }

    /**
     * 요청 행 하나. 공개 토큰은 경로와 상관없이 새로 발급하고, 비로그인이면 만료 시각을 붙인다.
     *
     * <p>만료 기준이 요청 생성 시각인 것은 임시다(005 R-03). PD-23 이 "결과 완료 시각"으로 정하면 완료 이벤트 쪽에서 채우도록 바꾼다.
     */
    private BacktestRequest newRequest(
            CreateBacktestCommand command, Long runId, ResultSource source, Long retryOfRequestId) {
        Instant expiresAt =
                command.requesterId() == null ? clock.instant().plus(publicTokenTtl) : null;
        return BacktestRequest.create(
                runId,
                command.requesterId(),
                command.executionType(),
                source,
                retryOfRequestId,
                PublicResultToken.generate(),
                expiresAt);
    }

    /** 동일 버전 부분 유니크 인덱스 {@code uk_backtest_run_dedup_active} 와 같은 상태 집합이다. */
    private static boolean isReusable(RunStatus status) {
        return status == RunStatus.QUEUED
                || status == RunStatus.RUNNING
                || status == RunStatus.COMPLETED;
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
     * 003 등록표로 설정을 다시 판정한다(FR-006). <b>지금은 {@code registryVersion}이 실린 요청에만 건다.</b>
     *
     * <p><b>[미완료: 004 워커 메시지 정합 전]</b> 관문을 모든 요청에 걸면 004·007의 기존 접수가 막힌다. 워커는 아직 {@code
     * parameters.rebalance} 문자열과 {@code rules.lookbackDays}만 읽고, 003 등록표의 {@code rebalancePeriod}
     * 객체값이나 {@code maxWeight}·{@code topN} 같은 축 이름이 메시지 계약에 자리가 없다({@code
     * docs/specs/backtest-messaging/}). 그 계약은 004가 소유한다.
     *
     * <p>그래서 {@code registryVersion}이 없는 요청은 종전처럼 통과시키고 경고만 남긴다. <b>이 호환 경로가 남아 있는 동안 FR-006과
     * SC-003은 닫히지 않는다</b> — 화면을 우회한 요청이 {@code registryVersion}을 빼면 판정을 건너뛸 수 있기 때문이다. 004가 워커 계약을
     * 맞춘 뒤 모든 요청에 {@code registryVersion}과 등록표 검증을 필수화하는 후속 변경까지 끝나야 요구사항이 닫힌다.
     *
     * <p>등록표가 산출한 워밍업이 004 규칙이 요구하는 관측 길이보다 짧으면 {@link EffectivePeriodCalculator}가 422 {@code
     * INSUFFICIENT_HISTORY}로 거절한다. 그 자리에서 큰 쪽으로 올리지 않는 이유는 004와 같다 — 두 값이 어긋났다는 것은 등록표와 규칙 중 하나가
     * 틀렸다는 신호이고, 고쳐 통과시키면 어긋난 채로 계속 간다.
     *
     * <p><b>버전이 고정된 요청(재시도·007 묶음 후보)은 그 요청이 판정받은 등록표 버전을 그대로 받는다</b>(T029, {@link
     * StrategySettingsValidator#verifySaved}). 둘 다 워밍업을 싣고 오므로(재시도는 저장된 실효값, 묶음은 공통 워밍업) 판정을 건너뛰어도
     * 워밍업은 비지 않는다.
     *
     * @param pinned true = 버전이 고정된 요청
     * @return 실행에 쓸 워밍업 거래일 수. 요청이 값을 보냈으면 그대로 두고(004의 기존 규칙이 판정한다), 보내지 않았고 등록표 판정을 거쳤으면 등록표가 산출한
     *     값이다
     */
    private Integer verifyRegistrySettings(CreateBacktestCommand command, boolean pinned) {
        if (command.registryVersion() == null) {
            log.warn(
                    "등록표 검증을 건너뛴 접수다(과도기 호환): strategyId={}, requesterId={}."
                            + " registryVersion 이 없는 요청이라 003 FR-006 관문을 걸지 않았다.",
                    command.strategyId(),
                    command.requesterId());
            return command.warmupTradingDays();
        }
        if (!pinned) {
            StrategySettingsValidator.Verified verified =
                    strategySettingsValidator.verify(
                            command.strategyId(),
                            command.parameters(),
                            command.registryVersion(),
                            command.items().size(),
                            command.initialCash());
            // 요청이 워밍업을 실어 보냈으면 그것이 비교 묶음이 계산한 공통 워밍업이다(R-15).
            // 전략 요구값보다 작으면 EffectivePeriodCalculator 가 422 INSUFFICIENT_HISTORY 로 거절한다.
            return command.warmupTradingDays() == null
                    ? verified.warmupTradingDays()
                    : command.warmupTradingDays();
        }
        Optional<StrategySettingsValidator.Verified> verified =
                strategySettingsValidator.verifySaved(
                        command.strategyId(),
                        command.parameters(),
                        command.registryVersion(),
                        command.items().size(),
                        command.initialCash());
        if (command.warmupTradingDays() != null) {
            return command.warmupTradingDays();
        }
        // 과거 등록표 버전이라 판정하지 않았는데 워밍업도 없다. 재시도의 저장 사본은 언제나 실효 워밍업을 싣고
        // (RequestSpecCodec.write) 묶음은 공통 워밍업을 싣는다 — 비었으면 기록이 깨진 것이라 지어내지 않는다.
        return verified.map(StrategySettingsValidator.Verified::warmupTradingDays)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "pinned request has no warmupTradingDays and its registry"
                                                + " version is not current: strategyId="
                                                + command.strategyId()));
    }

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

        BigDecimal cap = effectiveWeightCap(command.parameters(), rules);
        if (cap != null) {
            BigDecimal invested =
                    Optional.ofNullable(decimal(rules.spec(), "investedFraction"))
                            .orElse(BigDecimal.ONE);
            Integer weighted = weightedCount(command, items.size());
            if (weighted != null) {
                BigDecimal reachable = cap.multiply(BigDecimal.valueOf(weighted));
                if (reachable.compareTo(invested) < 0) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_RULE_VIOLATED,
                            "종목별 상한 %s × %d종목 = %s 로는 투자 비중 %s 를 채울 수 없습니다. 종목을 늘리거나 상한을 높이세요."
                                    .formatted(
                                            cap.toPlainString(),
                                            weighted,
                                            reachable.toPlainString(),
                                            invested.toPlainString()));
                }
            }
            // 고정 비중은 사용자가 비중을 직접 준다 — 재분배할 것이 없으므로 상한은 "넘지 않았는지"의
            // 뜻뿐이다. 접수에서 보지 않으면 상한 40%를 고르고 60%를 준 요청이 그대로 실행되고,
            // 결과가 사용자가 고른 조건을 어긴 채 그 조건의 결과로 저장된다.
            if (fixedWeight) {
                items.stream()
                        .filter(item -> item.targetWeight().compareTo(cap) > 0)
                        .findFirst()
                        .ifPresent(
                                item -> {
                                    throw new BusinessException(
                                            ErrorCode.VALIDATION_RULE_VIOLATED,
                                            "%s 의 목표 비중 %s 가 종목별 상한 %s 를 넘습니다. 비중을 낮추거나 상한을 높이세요."
                                                    .formatted(
                                                            item.instrumentId(),
                                                            item.targetWeight().toPlainString(),
                                                            cap.toPlainString()));
                                });
            }
        }
    }

    /**
     * 상한 검사가 세어야 할 종목 수 — <b>비중을 받는 종목의 수</b>다(PD-03, T023).
     *
     * <p>대부분의 전략은 고른 섹터 전부에 비중을 주므로 {@code universeSize}가 그대로다. 섹터 모멘텀만 그중 일부를 골라 배분하므로 선정 방식이 정하는
     * 수를 센다 — 전체 섹터 수로 재면 {@code topN=2}에 상한 40%인 설정이 접수를 통과하고 실행에서야 채우지 못한다.
     *
     * <p>세는 값은 요청의 투자 대상 섹터 수 {@code universeSize}이지 PD-01의 여덟이 아니다. 사용자는 섹터 일부만 고를 수 있다.
     *
     * @return 셀 수 있으면 그 수, 접수 시점에 알 수 없으면 {@code null}(검사를 건너뛴다). {@code THRESHOLD}는 몇 개가 기준을 넘을지
     *     가격을 봐야 알 수 있어 여기 해당한다 — 실행 중에 모자라면 남는 몫을 현금으로 둔다(PD-03 ⑥)
     */
    private static Integer weightedCount(CreateBacktestCommand command, int universeSize) {
        if (!SECTOR_MOMENTUM.equals(command.strategyId())) {
            return universeSize;
        }
        Map<String, Object> parameters = command.parameters();
        Object mode = parameters == null ? null : parameters.get("selectionMode");
        if (mode == null) {
            // 축을 고르지 않았으면 규칙표 기본값으로 도는데 그 값은 여기서 읽지 않는다.
            // 검사를 건너뛰어도 실행이 현금 잔여로 안전하게 끝난다(PD-03 ⑥).
            return null;
        }
        return switch (String.valueOf(mode)) {
            case "TOP_N" -> positiveInt(parameters.get("topN"), "topN");
            case "TOP_PERCENT" -> {
                Integer percent = positiveInt(parameters.get("topPercent"), "topPercent");
                yield percent == null
                        ? null
                        // 올림은 PD-03 ④가 정한 규칙이고 워커 momentum.py 와 같아야 한다.
                        : Math.max(1, (int) Math.ceil(universeSize * percent / 100.0));
            }
            case "ALL" -> universeSize;
            // THRESHOLD 는 접수 시점에 셀 수 없다.
            default -> null;
        };
    }

    private static Integer positiveInt(Object raw, String field) {
        if (raw == null) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException exc) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, field + " 는 정수여야 합니다: " + raw);
        }
        if (value.stripTrailingZeros().scale() > 0 || value.signum() <= 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, field + " 는 1 이상의 정수여야 합니다: " + value.toPlainString());
        }
        return value.intValueExact();
    }

    /**
     * 이 실행에 실제로 적용하는 종목별 상한(PD-03 ⑥).
     *
     * <p><b>요청의 {@code allocation.parameters.maxWeight}(003 축, 정수 %)가 있으면 그것을 100으로 나눈 값이고, 없으면
     * 규칙표의 {@code weightCap}이다.</b> 사용자가 화면에서 고른 상한이 우선이고 규칙표 값은 그것을 고르지 않은 요청의 기본값 역할을 한다.
     *
     * <p>접수와 워커가 같은 규칙으로 읽어야 한다 — 규칙표 값만 보면 역변동성 {@code /1}의 {@code weightCap}이 1.0000이라 "상한 40% ×
     * 2종목"이 접수를 통과하고 워커에서야 조건을 못 채운다. 워커 쪽 같은 규칙은 {@code worker/src/worker/allocation/base.py}의
     * {@code effective_cap}이다.
     *
     * @return 소수 표기의 유효 상한. 규칙표에도 요청에도 상한이 없으면 {@code null}(제한 없음)
     */
    private static BigDecimal effectiveWeightCap(
            Map<String, Object> parameters, StrategyRules rules) {
        Object raw = parameters == null ? null : parameters.get("maxWeight");
        if (raw == null) {
            return decimal(rules.spec(), "weightCap");
        }
        BigDecimal percent;
        try {
            percent = new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException exc) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "maxWeight 는 정수 퍼센트여야 합니다: " + raw);
        }
        if (percent.stripTrailingZeros().scale() > 0
                || percent.signum() <= 0
                || percent.compareTo(HUNDRED) > 0) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT,
                    "maxWeight 는 1~100 사이의 정수 퍼센트여야 합니다: " + percent.toPlainString());
        }
        return percent.divide(HUNDRED);
    }

    /**
     * 목표 위험 오버레이 규칙(PD-18). 요청이 버전을 싣지 않으므로 새 요청은 지금 버전을, 버전이 고정된 요청은 원본 실행이 쓴 버전을 쓴다(T029).
     *
     * <p>버전이 동일 버전 키에 들어가므로, 재시도가 지금 버전을 고르면 원본과 같은 조건인데도 다른 키가 되고 재사용·합류를 받지 못한다.
     */
    private StrategyRules targetRiskOverlay(PinnedVersions pinned) {
        if (pinned == null) {
            return strategyRulesService.requireCurrent(TARGET_RISK_OVERLAY);
        }
        RulesRef recorded = pinned.scenarioRules();
        if (recorded == null || !TARGET_RISK_OVERLAY.equals(recorded.strategyId())) {
            // 2026-09-15 전 실행은 T029 마이그레이션이 TARGET_RISK_OVERLAY/1 로 채웠다. 그래도 없으면 기록이 깨진 것이다.
            throw new IllegalStateException(
                    "pinned TARGET_RISK request has no overlay rules recorded: retryOf="
                            + pinned.retryOfRequestId()
                            + ", recorded="
                            + recorded);
        }
        return strategyRulesService.requireExecutable(
                recorded.strategyId(), recorded.rulesVersion());
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
     * 결과에 표시할 참조 버전(FR-028, data-model {@code backtest_run.versions}).
     *
     * <p>모양은 워커가 결과에 싣는 {@code versions}와 같다 — {@code rules}·{@code costProfile}은 {@code {version,
     * provisional}}이고 {@code provisional=true}는 팀 결정(PD-02·PD-07) 전 임시값으로 계산했다는 뜻이다. 사용자가 확정 규칙 결과와
     * 임시 규칙 결과를 구분할 수 있어야 하므로 접수 시점에 등록표 값을 그대로 옮긴다. {@code scenarioRules}는 오버레이가 있을 때만, {@code
     * worker}는 계산을 마친 워커가 보고하는 값이라 여기 없고 조회가 결과 행에서 합친다({@code BacktestDetailResult}).
     *
     * <p>동일 버전 키({@code DedupKeyCalculator})는 이 맵을 보지 않는다 — 키는 버전 문자열만 쓴다.
     */
    private Map<String, Object> versions(
            CreateBacktestCommand command,
            StrategyRules rules,
            StrategyRules overlay,
            CostProfile costProfile,
            String dataVersion) {
        Map<String, Object> versions = new LinkedHashMap<>();
        // 요청이 주지 않은 버전은 키를 만들지 않는다 — null 을 채우면 "없다"와 "모른다"가 섞이고,
        // RunPlan 이 Map.copyOf 로 사본을 뜨므로 null 값은 NPE 다(007 launcher 가 평가 분할을 비운다).
        putIfPresent(versions, "mapping", command.mappingVersion());
        versions.put("rules", versionOf(rules.rulesVersion(), rules.provisional()));
        if (overlay != null) {
            versions.put("scenarioRules", overlay.ref().value());
        }
        versions.put("costProfile", versionOf(costProfile.version(), costProfile.provisional()));
        versions.put("data", dataVersion);
        versions.put("engine", engineVersion);
        putIfPresent(versions, "evaluationSplit", command.evaluationSplitVersion());
        return versions;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Map<String, Object> versionOf(String version, boolean provisional) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", version);
        value.put("provisional", provisional);
        return value;
    }
}
