package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestReuseEventRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestReuseEvent;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.CostProfile;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RulesStatus;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.model.StrategyRules;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.infrastructure.marketdata.FixtureMarketMetadataAdapter;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.strategy.application.service.StrategySettingsValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 접수 유스케이스(spec 004 T005).
 *
 * <p>계산기 두 개는 진짜를 쓴다 — 동일 버전 키와 실효 기간은 접수의 결론 그 자체라서, 목으로 대체하면 "접수가 무엇을 확정했는가"를 확인할 수 없다. 저장소와 이벤트
 * 발행만 목이다.
 */
class BacktestServiceTest {

    private static final long REQUESTER = 7L;
    private static final Instant NOW = Instant.parse("2026-09-11T04:00:00Z");

    private final BacktestRunRepository runRepository = mock(BacktestRunRepository.class);
    private final BacktestRequestRepository requestRepository =
            mock(BacktestRequestRepository.class);
    private final BacktestReuseEventRepository reuseEventRepository =
            mock(BacktestReuseEventRepository.class);
    // 한도는 ExecutionLimitPolicyTest 가 따로 본다. 여기서는 통과하는 것으로 둔다.
    private final ExecutionLimitPolicy limitPolicy = mock(ExecutionLimitPolicy.class);
    private final StrategyRulesService strategyRulesService = mock(StrategyRulesService.class);

    /**
     * 이 테스트의 요청들은 {@code registryVersion}을 싣지 않아 등록표 판정을 타지 않는다(003 과도기 경로). 목만 두고 상호작용을 단언하지 않는
     * 이유는, 여기서 보는 것이 004 접수 규칙이고 등록표 판정은 {@code StrategySettingsValidatorTest}와 {@code
     * BacktestIntakeRegistryIT}가 보기 때문이다.
     */
    private final StrategySettingsValidator strategySettingsValidator =
            mock(StrategySettingsValidator.class);

    private final CostProfileService costProfileService = mock(CostProfileService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final FixtureMarketMetadataAdapter marketMetadata =
            new FixtureMarketMetadataAdapter(LocalDate.of(2026, 12, 31));

    private final BacktestService service =
            new BacktestService(
                    runRepository,
                    requestRepository,
                    reuseEventRepository,
                    limitPolicy,
                    strategyRulesService,
                    strategySettingsValidator,
                    costProfileService,
                    new EffectivePeriodCalculator(marketMetadata),
                    new DedupKeyCalculator(),
                    marketMetadata,
                    events,
                    Clock.fixed(NOW, ZoneOffset.UTC),
                    "nautilus_trader 1.219.0 / worker 2.2.0");

    @BeforeEach
    void stubRegistries() {
        when(strategyRulesService.requireRegistered("FIXED_WEIGHT", "1"))
                .thenReturn(rules("FIXED_WEIGHT", Map.of()));
        // 등록표 초기 행과 같다 — 역변동성 규칙은 PD-02 확정 전 임시(provisional=true)다.
        when(strategyRulesService.requireRegistered("INVERSE_VOLATILITY", "1"))
                .thenReturn(
                        new StrategyRules(
                                "INVERSE_VOLATILITY",
                                "1",
                                RulesStatus.REGISTERED,
                                true,
                                Map.of("lookbackDays", 63),
                                "PD-02"));
        when(strategyRulesService.requireRegistered("TARGET_RISK_OVERLAY", "1"))
                .thenReturn(rules("TARGET_RISK_OVERLAY", Map.of("lookbackDays", 63)));
        when(costProfileService.resolve(any()))
                .thenReturn(
                        new CostProfile(
                                "PROVISIONAL-DEMO-1",
                                new BigDecimal("0"),
                                new BigDecimal("0"),
                                new BigDecimal("5"),
                                new BigDecimal("0"),
                                null,
                                true,
                                "ADR-0004"));
        when(runRepository.findActiveByDedupKey(any())).thenReturn(Optional.empty());
        when(runRepository.save(any()))
                .thenAnswer(invocation -> saved(invocation.getArgument(0), 11L));
        when(requestRepository.save(any()))
                .thenAnswer(invocation -> savedRequest(invocation.getArgument(0)));
    }

    private static StrategyRules rules(String strategyId, Map<String, Object> spec) {
        return new StrategyRules(strategyId, "1", RulesStatus.REGISTERED, false, spec, null);
    }

    /** 저장소가 하는 일(식별자 부여)만 흉내 낸다. */
    private static BacktestRun saved(BacktestRun run, long id) {
        return BacktestRun.restore(
                        id,
                        BacktestId.from("BT-20260911-000011"),
                        run.getStrategyId(),
                        run.getStrategyVersion(),
                        run.getParameters(),
                        run.getInstrumentIds(),
                        run.getPeriod(),
                        run.getInitialCash(),
                        run.getPositionSizingType(),
                        run.getMaxPositionPct(),
                        run.getBenchmarkInstrumentId(),
                        run.getRandomSeed(),
                        run.getState(),
                        run.getPlan(),
                        run.getLifecycle())
                .withPlan(run.getPlan());
    }

    private static BacktestRequest savedRequest(BacktestRequest request) {
        return BacktestRequest.restore(
                21L,
                BacktestId.from("BT-20260911-000021"),
                request.getRunId(),
                request.getRequesterId(),
                request.getExecutionType(),
                request.getResultSource(),
                request.getRetryOfRequestId(),
                null,
                null,
                false);
    }

    private static CreateBacktestCommand command(
            String strategyId,
            BigDecimal weight,
            Integer warmup,
            Scenario scenario,
            Map<String, Object> params) {
        return new CreateBacktestCommand(
                REQUESTER,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", weight),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY",
                                "305720.XKRX",
                                weight == null ? null : BigDecimal.ONE.subtract(weight))),
                strategyId,
                "1",
                // registryVersion 을 싣지 않는다 — 이 픽스처의 parameters 는 워커가 읽는 이름이고
                // 003 등록표의 축 이름이 아니다. 값을 실으면 접수가 등록표 판정을 걸어 409 다.
                null,
                Map.of("rebalance", "MONTH_END"),
                LocalDate.of(2024, 1, 2),
                LocalDate.of(2024, 12, 31),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                warmup,
                scenario,
                params);
    }

    private static CreateBacktestCommand fixedWeight() {
        return command("FIXED_WEIGHT", new BigDecimal("0.4000"), null, Scenario.FULL_PERIOD, null);
    }

    @Test
    @DisplayName("접수하면 실행과 요청이 생기고 202 본문에 실효 기간·버전이 담긴다")
    void createsRunAndRequest() {
        BacktestJobResult result = service.create(fixedWeight());

        assertThat(result.backtestId()).isEqualTo("BT-20260911-000021");
        assertThat(result.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(result.resultSource()).isEqualTo(ResultSource.NEW);
        assertThat(result.effectiveStart()).isEqualTo(LocalDate.of(2024, 1, 2));
        assertThat(result.effectiveEnd()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(result.versions())
                .containsEntry("mapping", "SECTOR-ETF-2026-09")
                .containsEntry("data", "FIXTURE-three_etf_24m")
                .containsEntry("engine", "nautilus_trader 1.219.0 / worker 2.2.0")
                .containsEntry("evaluationSplit", "SPLIT-1")
                .doesNotContainKeys("scenarioRules", "worker");
        assertThat(result.versions().get("rules"))
                .isEqualTo(Map.of("version", "1", "provisional", false));
        assertThat(result.versions().get("costProfile"))
                .isEqualTo(Map.of("version", "PROVISIONAL-DEMO-1", "provisional", true));
    }

    @Test
    @DisplayName("임시 규칙(PD-02)으로 계산하는 실행은 versions.rules.provisional=true 로 표시된다")
    void provisionalRulesAreVisibleInVersions() {
        BacktestJobResult result =
                service.create(
                        command("INVERSE_VOLATILITY", null, null, Scenario.FULL_PERIOD, null));

        assertThat(result.versions().get("rules"))
                .isEqualTo(Map.of("version", "1", "provisional", true));
    }

    @Test
    @DisplayName("요청이 주지 않은 버전(평가 분할 등)은 versions 에 키를 만들지 않는다 — 007 launcher 가 비워 보낸다")
    void absentVersionsAreOmittedNotNulled() {
        CreateBacktestCommand base = fixedWeight();
        CreateBacktestCommand withoutSplit =
                new CreateBacktestCommand(
                        base.requesterId(),
                        base.executionType(),
                        base.mappingVersion(),
                        base.items(),
                        base.strategyId(),
                        base.rulesVersion(),
                        base.registryVersion(),
                        base.parameters(),
                        base.start(),
                        base.end(),
                        base.initialCash(),
                        base.costProfileVersion(),
                        null,
                        base.warmupTradingDays(),
                        base.scenario(),
                        base.scenarioParams());

        BacktestJobResult result = service.create(withoutSplit);

        assertThat(result.versions()).doesNotContainKey("evaluationSplit").containsKey("rules");
    }

    @Test
    @DisplayName("목표 위험 시나리오는 오버레이 규칙 버전을 versions.scenarioRules 로 남긴다")
    void targetRiskRecordsTheOverlayVersion() {
        BacktestJobResult result =
                service.create(
                        command(
                                "FIXED_WEIGHT",
                                new BigDecimal("0.4000"),
                                null,
                                Scenario.TARGET_RISK,
                                Map.of("targetVolatility", "0.1000")));

        assertThat(result.versions()).containsEntry("scenarioRules", "TARGET_RISK_OVERLAY/1");
    }

    @Test
    @DisplayName("저장하는 실행에 동일 버전 키·발행 시도 1·접수 시각이 붙는다 (R-18)")
    void savedRunCarriesPlanAndFirstPublishAttempt() {
        service.create(fixedWeight());

        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).save(captor.capture());
        BacktestRun run = captor.getValue();

        assertThat(run.getDedupKey()).hasSize(64);
        assertThat(run.getPlan().costProfileVersion()).isEqualTo("PROVISIONAL-DEMO-1");
        assertThat(run.getPlan().requestSpec())
                .containsEntry("mappingVersion", "SECTOR-ETF-2026-09")
                .containsEntry("warmupTradingDays", 0);
        assertThat(run.getLifecycle().publishCount())
                .as("발행 시도는 커밋 전에 예고한다 — 못 보내도 상한은 지켜진다")
                .isEqualTo(1);
        assertThat(run.getLifecycle().publishedAt()).isNull();
        assertThat(run.getLifecycle().queuedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("발행은 커밋 뒤에 일어나도록 이벤트로만 알린다 — 여기서 직접 발행하지 않는다")
    void publishesAfterCommitViaEvent() {
        service.create(fixedWeight());

        verify(events).publishEvent(new BacktestJobDispatcher.RunAccepted(11L, 1));
    }

    @Test
    @DisplayName("같은 조건의 살아 있는 실행이 있으면 새로 계산하지 않고 합류한다")
    void joinsActiveRun() {
        BacktestRun running = saved(runningRun(), 11L);
        when(runRepository.findActiveByDedupKey(any())).thenReturn(Optional.of(running));
        when(runRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(running));

        BacktestJobResult result = service.create(fixedWeight());

        assertThat(result.resultSource()).isEqualTo(ResultSource.JOINED);
        verify(runRepository, never()).save(any());
        verify(events, never()).publishEvent(any());
        assertThat(recordedReuse())
                .isEqualTo(
                        new BacktestReuseEvent(
                                null, 11L, 21L, ResultSource.JOINED, ExecutionType.SINGLE, NOW));
    }

    @Test
    @DisplayName("완료된 실행의 결과를 재사용하면 REUSED 로 기록한다 — 아낀 계산을 원가 집계가 셀 수 있어야 한다")
    void recordsReuseOfACompletedRun() {
        BacktestRun completed = saved(runningRun(), 11L).markCompleted();
        when(runRepository.findActiveByDedupKey(any())).thenReturn(Optional.of(completed));
        when(runRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(completed));

        BacktestJobResult result = service.create(fixedWeight());

        assertThat(result.resultSource()).isEqualTo(ResultSource.REUSED);
        assertThat(recordedReuse().kind()).isEqualTo(ResultSource.REUSED);
    }

    @Test
    @DisplayName("새로 계산하는 요청은 재사용 기록을 남기지 않는다 — 실행 행 자체가 기록이다")
    void doesNotRecordReuseForANewRun() {
        service.create(fixedWeight());

        verify(reuseEventRepository, never()).save(any());
    }

    private BacktestReuseEvent recordedReuse() {
        ArgumentCaptor<BacktestReuseEvent> captor =
                ArgumentCaptor.forClass(BacktestReuseEvent.class);
        verify(reuseEventRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("취소 대기 중인 실행(CANCEL 을 보냈고 FINISHED 전)에는 합류하지 않는다 — 409 CANCEL_PENDING")
    void doesNotJoinACancelPendingRun() {
        BacktestRun running = saved(runningRun(), 11L);
        BacktestRun cancelPending =
                running.withLifecycle(
                        running.getLifecycle()
                                .cancelRequested(NOW.minusSeconds(5), CancelReason.USER));
        when(runRepository.findActiveByDedupKey(any())).thenReturn(Optional.of(cancelPending));
        when(runRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(cancelPending));

        assertThatThrownBy(() -> service.create(fixedWeight()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CANCEL_PENDING);
        verify(requestRepository, never()).save(any());
        verify(runRepository, never()).save(any());
    }

    @Test
    @DisplayName("동일 버전 조회 뒤 잠그기 전에 취소되면 잠근 뒤 다시 본 상태로 판단한다 — 그 사이 붙는 합류를 막는다")
    void reChecksTheRunUnderLockBeforeJoining() {
        BacktestRun running = saved(runningRun(), 11L);
        when(runRepository.findActiveByDedupKey(any())).thenReturn(Optional.of(running));
        // 잠금을 얻고 다시 읽으니 그 사이 마지막 요청이 취소했다
        when(runRepository.findByIdForUpdate(11L))
                .thenReturn(
                        Optional.of(
                                running.withLifecycle(
                                        running.getLifecycle()
                                                .cancelRequested(NOW, CancelReason.USER))));

        assertThatThrownBy(() -> service.create(fixedWeight()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CANCEL_PENDING);
        verify(requestRepository, never()).save(any());
    }

    @Test
    @DisplayName("잠근 뒤 보니 종결된 실행이면 재사용 대상이 아니다 — 같은 키로 새 실행을 만든다(부분 유니크 인덱스가 허용한다)")
    void createsANewRunWhenTheCandidateFinishedBeforeTheLock() {
        BacktestRun running = saved(runningRun(), 11L);
        when(runRepository.findActiveByDedupKey(any())).thenReturn(Optional.of(running));
        when(runRepository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(running.markFailed("SYSTEM_FAILURE", "worker gone")));

        BacktestJobResult result = service.create(fixedWeight());

        assertThat(result.resultSource()).isEqualTo(ResultSource.NEW);
        verify(runRepository).save(any());
    }

    @Test
    @DisplayName("완료된 실행이 있으면 결과를 재사용한다 — 상태가 이미 COMPLETED다")
    void reusesCompletedRun() {
        when(runRepository.findActiveByDedupKey(any()))
                .thenReturn(Optional.of(saved(completedRun(), 11L)));
        when(runRepository.findByIdForUpdate(11L))
                .thenReturn(Optional.of(saved(completedRun(), 11L)));

        BacktestJobResult result = service.create(fixedWeight());

        assertThat(result.resultSource()).isEqualTo(ResultSource.REUSED);
        assertThat(result.status()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    @DisplayName("고정 비중이 아닌 전략에 목표 비중을 보내면 400이다")
    void weightsOnlyForFixedWeight() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        command(
                                                "INVERSE_VOLATILITY",
                                                new BigDecimal("0.4000"),
                                                null,
                                                Scenario.FULL_PERIOD,
                                                null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("FIXED_WEIGHT");
    }

    @Test
    @DisplayName("목표 비중 합이 1이 아니면 400이다")
    void weightsMustSumToOne() {
        CreateBacktestCommand command =
                new CreateBacktestCommand(
                        REQUESTER,
                        ExecutionType.SINGLE,
                        "SECTOR-ETF-2026-09",
                        List.of(
                                new CreateBacktestCommand.AllocationItem(
                                        "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                                new CreateBacktestCommand.AllocationItem(
                                        "BATTERY", "305720.XKRX", new BigDecimal("0.4000"))),
                        "FIXED_WEIGHT",
                        "1",
                        null,
                        Map.of(),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        new BigDecimal("10000000.00"),
                        null,
                        null,
                        null,
                        Scenario.FULL_PERIOD,
                        null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("합이 1이 아닙니다");
    }

    @Test
    @DisplayName("대응표가 연결하지 않는 섹터·종목 쌍은 400이다 — 옛 화면이 보낸 요청을 조용히 실행하지 않는다")
    void mappingMismatchIsRejected() {
        CreateBacktestCommand command =
                new CreateBacktestCommand(
                        REQUESTER,
                        ExecutionType.SINGLE,
                        "SECTOR-ETF-2026-09",
                        List.of(
                                new CreateBacktestCommand.AllocationItem(
                                        "BIO", "091160.XKRX", BigDecimal.ONE)),
                        "FIXED_WEIGHT",
                        "1",
                        null,
                        Map.of(),
                        LocalDate.of(2024, 1, 2),
                        LocalDate.of(2024, 12, 31),
                        new BigDecimal("10000000.00"),
                        null,
                        null,
                        null,
                        Scenario.FULL_PERIOD,
                        null);

        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("연결되지 않습니다");
    }

    @Test
    @DisplayName("목표 위험 시나리오는 허용 범위와 간격을 지켜야 한다")
    void targetVolatilityIsValidated() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        command(
                                                "FIXED_WEIGHT",
                                                new BigDecimal("0.4000"),
                                                null,
                                                Scenario.TARGET_RISK,
                                                Map.of("targetVolatility", "0.3000"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("0.0500~0.2000");

        assertThatThrownBy(
                        () ->
                                service.create(
                                        command(
                                                "FIXED_WEIGHT",
                                                new BigDecimal("0.4000"),
                                                null,
                                                Scenario.TARGET_RISK,
                                                Map.of("targetVolatility", "0.1050"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("간격");
    }

    @Test
    @DisplayName("주 분석에 시나리오 입력값을 보내면 400이다")
    void scenarioParamsForbiddenOnFullPeriod() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        command(
                                                "FIXED_WEIGHT",
                                                new BigDecimal("0.4000"),
                                                null,
                                                Scenario.FULL_PERIOD,
                                                Map.of("targetVolatility", "0.1000"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("보낼 수 없습니다");
    }

    @Test
    @DisplayName("목표 위험 시나리오는 오버레이 관측 기간(63거래일)을 워밍업으로 확정한다")
    void targetRiskOverlayLookbackBecomesWarmup() {
        // 워커는 첫 판단일에 변동성을 추정할 수익률이 있어야 한다. 전략(고정 비중)만 보면 워밍업 0으로
        // 접수되고 워커가 ESTIMATION_FAILED 를 낸다 — 접수 정책과 엔진이 같은 규칙을 봐야 한다.
        BacktestJobResult result =
                service.create(
                        command(
                                "FIXED_WEIGHT",
                                new BigDecimal("0.4000"),
                                null,
                                Scenario.TARGET_RISK,
                                Map.of("targetVolatility", "0.1000")));

        // 워밍업이 확정돼도 두 종목 모두 2018년 이전 상장이라 요청 시작일 앞에서 읽으면 된다 —
        // 평가 시작일은 요청한 그날이다. 워밍업이 요청 구간을 깎지 않는다는 것이 여기서 드러난다.
        assertThat(result.effectiveStart()).isEqualTo(LocalDate.of(2024, 1, 2));
        ArgumentCaptor<BacktestRun> captor = ArgumentCaptor.forClass(BacktestRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getPlan().requestSpec())
                .containsEntry("warmupTradingDays", 63);
    }

    @Test
    @DisplayName("요청 워밍업이 전략 요구값보다 작으면 422다 (R-15)")
    void warmupBelowRequirementIsRejected() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        command(
                                                "INVERSE_VOLATILITY",
                                                null,
                                                20,
                                                Scenario.FULL_PERIOD,
                                                null)))
                .isInstanceOf(BusinessException.class)
                .extracting(exc -> ((BusinessException) exc).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_HISTORY);
    }

    private static BacktestRun runningRun() {
        return BacktestRun.create(
                        "FIXED_WEIGHT",
                        "1",
                        Map.of(),
                        List.of(new com.ssafy.thorp.backtest.domain.vo.InstrumentId("091160.XKRX")),
                        new com.ssafy.thorp.backtest.domain.vo.BacktestPeriod(
                                LocalDate.of(2024, 1, 2), LocalDate.of(2024, 12, 31)),
                        new BigDecimal("10000000.00"),
                        com.ssafy.thorp.backtest.domain.model.PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42)
                .markRunning("exec-1");
    }

    private static BacktestRun completedRun() {
        return runningRun().markCompleted();
    }
}
