package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.command.PreviewParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.exception.ParamSearchRejectedException;
import com.ssafy.thorp.paramsearch.application.exception.RegistryVersionChangedException;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParamSearchSnapshot;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchPreviewResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchProgressService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 접수 → 발행 → 결과 수신 → 집계까지(T042·T043·T044·T049a·T049b).
 *
 * <p>발행기는 목이다 — 여기서 확인할 것은 메시지가 나갔는지가 아니라 <b>묶음에 남는 것</b>이다. 저장된 탐색 공간으로 후보 집합을 재구성할 수 있는지, 등록표 버전이
 * 바뀌면 접수가 멈추는지, 동시에 도착한 결과가 집계를 덮어쓰지 않는지.
 *
 * <p>실제 등록표(임시 파일)와 실제 004 지원 목록을 쓴다. 전략 이름을 단언에 적지 않는 이유는 M2의 검증과 같다 — 마이그레이션이 전략을 더해도 이 테스트는 그대로
 * 성립해야 한다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ParamSearchIntakeIT extends PostgresTestSupport {

    /** 픽스처 대응표에 있는 종목. 상장일이 가장 이른 둘을 골라 공통 구간이 충분히 남게 한다. */
    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchProgressService progressService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    /** 실제 발행 대신 실행 행 id만 돌려준다. 이 테스트의 관심사는 큐가 아니라 묶음 상태다. */
    @MockitoBean private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(1);

    @BeforeEach
    void stubLauncher() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        // 결과 행이 실행 행을 FK로 참조한다. 자식을 먼저 지우지 않으면 다른 테스트가
        // 남긴 결과 때문에 이 정리가 통째로 실패한다.
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(900_100L);
        // 실행 행을 실제로 만든다 — search_candidate.backtest_job_id 와 param_search.baseline_job_id 가
        // backtest_run(id)을 참조하므로 없는 id를 돌려주면 FK 위반으로 접수가 통째로 실패한다.
        org.mockito.Mockito.when(
                        launcher.launchCandidate(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        org.mockito.Mockito.when(launcher.launchBaseline(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    /** 시퀀스가 닿지 않는 id로 실행 행을 하나 만든다. 다른 테스트의 실행과 겹치지 않아야 한다. */
    private long insertRun() {
        long id = nextJobId.getAndIncrement();
        jdbcTemplate.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, created_at, updated_at)"
                        + " VALUES (?, ?, 'STUB', '1', '{}'::jsonb, '[\"091160.XKRX\"]'::jsonb,"
                        + " DATE '2020-01-02', DATE '2026-08-31', 10000000.00,"
                        + " 'STRATEGY_DEFINED', 42, 'QUEUED', NOW(), NOW())",
                id,
                "BT-STUB-" + id);
        return id;
    }

    @Test
    @DisplayName("미리보기는 네 항목을 채우고 저장하지 않는다")
    void previewFillsFourSectionsWithoutSaving() {
        ParamSearchPreviewResult preview = paramSearchService.preview(previewCommand());

        assertThat(preview.registryVersion()).isEqualTo(registry.version());
        assertThat(preview.strategies()).isNotEmpty();
        assertThat(preview.spaceSummary()).isNotEmpty();
        assertThat(preview.commonPeriod()).isNotNull();
        // 작업량의 셈이 서로 맞는다 — 전체 = 유효 + 제외(T040).
        assertThat(preview.workload().totalCombinations())
                .isEqualTo(
                        preview.workload().validCombinations()
                                + preview.workload().excludedCombinations());
        assertThat(
                        preview.workload().exclusionReasons().stream()
                                .mapToLong(ParamSearchPreviewResult.ExclusionReason::count)
                                .sum())
                .isEqualTo(preview.workload().excludedCombinations());
        // 네 한도가 모두 임시값이다(FR-005a).
        assertThat(preview.limits().provisional()).contains("maxCandidates");
        assertThat(preview.validationLabel()).isEqualTo("UNVALIDATED");
        // 저장하지 않는다(FR-003).
        assertThat(countSearches()).isZero();
    }

    @Test
    @DisplayName("접수는 묶음과 후보를 저장하고 발행 창만큼만 발행한다")
    void createSavesAndDispatchesWindow() {
        ParamSearchResult accepted = paramSearchService.create(createCommand(registry.version()));

        ParamSearch saved = paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
        assertThat(saved.getSearchId().value()).startsWith("PS-");
        assertThat(saved.getStatus()).isEqualTo(ParamSearchStatus.RUNNING);
        assertThat(saved.getRegistryVersion()).isEqualTo(registry.version());
        assertThat(saved.getRandomSeed()).as("무작위 표본을 쓰지 않으므로 seed가 없다(FR-020)").isNull();

        List<SearchCandidate> candidates = candidateRepository.findByParamSearchId(saved.getId());
        assertThat(candidates).hasSize((int) saved.getCounts().expected());
        long dispatched =
                candidateRepository.countByStatus(saved.getId(), CandidateStatus.DISPATCHED);
        int window = (int) saved.getLimits().get("dispatchWindow");
        assertThat(dispatched).isLessThanOrEqualTo(window);
        // 기준 1건은 후보와 별도로 발행된다(FR-013d).
        assertThat(saved.getBaselineJobId()).isNotNull();
    }

    @Test
    @DisplayName("저장된 탐색 공간으로 후보 집합을 재구성할 수 있다")
    void savedSpaceReproducesCandidates() {
        ParamSearchResult accepted = paramSearchService.create(createCommand(registry.version()));
        ParamSearch saved = paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();

        long combinationsFromSpace = saved.getSearchSpace().combinationCount();

        assertThat(combinationsFromSpace).isEqualTo(saved.getCounts().expected());
        // 후보의 좌표가 저장된 축 값 목록의 인덱스를 가리킨다(FR-029).
        candidateRepository
                .findByParamSearchId(saved.getId())
                .forEach(
                        candidate ->
                                candidate
                                        .getGridCoordinates()
                                        .indices()
                                        .forEach(
                                                (axis, index) ->
                                                        assertThat(
                                                                        saved.getSearchSpace()
                                                                                .valuesOf(
                                                                                        candidate
                                                                                                .getStrategyId(),
                                                                                        axis)
                                                                                .get(index))
                                                                .isEqualTo(
                                                                        candidate
                                                                                .getParameters()
                                                                                .valueOf(axis))));
    }

    @Test
    @DisplayName("같은 선택으로 두 번 접수하면 후보 집합과 좌표가 같다")
    void twoIntakesProduceTheSameCandidates() {
        ParamSearchResult first = paramSearchService.create(createCommand(registry.version()));
        ParamSearchResult second = paramSearchService.create(createCommand(registry.version()));

        List<String> firstShape = shapeOf(first.searchId());
        List<String> secondShape = shapeOf(second.searchId());

        assertThat(secondShape).isEqualTo(firstShape);
        assertThat(first.searchId()).isNotEqualTo(second.searchId());
    }

    @Test
    @DisplayName("미리보기와 다른 등록표 버전으로 접수하면 409이고 묶음이 저장되지 않는다")
    void staleRegistryVersionIsRejectedWithoutSaving() {
        assertThatThrownBy(() -> paramSearchService.create(createCommand("PROVISIONAL-19990101")))
                .isInstanceOf(RegistryVersionChangedException.class)
                .extracting(e -> ((RegistryVersionChangedException) e).getErrorCode())
                .isEqualTo(ErrorCode.REGISTRY_VERSION_CHANGED);

        // 버전 드리프트는 사용자가 다시 확인하면 해소된다 — 재현 기록이 아니라 저장하지 않는다(R9).
        assertThat(countSearches()).isZero();
    }

    @Test
    @DisplayName("실행 미지원 전략은 거부를 REJECTED로 저장하고 사유를 남긴다")
    void unsupportedStrategyIsStoredAsRejected() {
        List<String> unsupported = unsupportedStrategyIds();
        assertThat(unsupported).as("픽스처 등록표에 실행 미지원 전략이 있어야 한다").isNotEmpty();

        CreateParamSearchCommand command =
                new CreateParamSearchCommand(
                        null,
                        UNIVERSE,
                        List.of(unsupported.get(0)),
                        null,
                        null,
                        null,
                        Map.of(),
                        registry.version());

        assertThatThrownBy(() -> paramSearchService.create(command))
                .isInstanceOf(ParamSearchRejectedException.class)
                .extracting(e -> ((ParamSearchRejectedException) e).getErrorCode())
                .isEqualTo(ErrorCode.STRATEGY_NOT_EXECUTABLE);
    }

    @Test
    @DisplayName("동시에 도착한 결과가 집계에서 서로를 덮어쓰지 않는다")
    void concurrentResultsDoNotLoseCounts() throws Exception {
        ParamSearchResult accepted = paramSearchService.create(createCommand(registry.version()));
        ParamSearch saved = paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
        List<SearchCandidate> dispatched =
                candidateRepository.findByParamSearchIdAndStatus(
                        saved.getId(), CandidateStatus.DISPATCHED);
        assertThat(dispatched).as("동시 수신을 시험하려면 발행된 후보가 둘 이상이어야 한다").hasSizeGreaterThan(1);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(dispatched.size());
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (SearchCandidate candidate : dispatched) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    progressService.candidateFinished(
                                            candidate.getBacktestJobId(), false, null);
                                    return null;
                                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        ParamSearch reloaded = paramSearchRepository.findById(saved.getId()).orElseThrow();
        // 완료 수가 실제 COMPLETED 후보 수와 같다 — 하나라도 덮어써지면 어긋난다(내부 계약 §4).
        assertThat(reloaded.getCounts().completed())
                .isEqualTo(
                        candidateRepository.countByStatus(
                                saved.getId(), CandidateStatus.COMPLETED));
        assertThat(reloaded.getCounts().completed()).isGreaterThanOrEqualTo(dispatched.size());
    }

    @Test
    @DisplayName("BacktestFinished를 받으면 후보가 끝나고 집계가 따라 움직인다")
    void finishedEventAdvancesTheSearch() {
        ParamSearchResult accepted = paramSearchService.create(createCommand(registry.version()));
        ParamSearch saved = paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
        SearchCandidate dispatched =
                candidateRepository
                        .findByParamSearchIdAndStatus(saved.getId(), CandidateStatus.DISPATCHED)
                        .get(0);

        // 004가 발행하는 것과 같은 이벤트를 같은 경로(AFTER_COMMIT 리스너)로 흘린다.
        transactionTemplate.executeWithoutResult(
                status ->
                        events.publishEvent(
                                new BacktestFinished(
                                        dispatched.getBacktestJobId(),
                                        "BT-STUB-" + dispatched.getBacktestJobId(),
                                        RunStatus.COMPLETED,
                                        null)));

        ParamSearch reloaded = paramSearchRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getCounts().completed()).isEqualTo(1);
        assertThat(candidateRepository.findAllByBacktestJobId(dispatched.getBacktestJobId()))
                .singleElement()
                .extracting(SearchCandidate::getStatus)
                .isEqualTo(CandidateStatus.COMPLETED);
        // 한 건이 끝나면 대기 후보 하나가 창에 들어간다(내부 계약 §4 "후보 종료").
        assertThat(candidateRepository.countByStatus(saved.getId(), CandidateStatus.DISPATCHED))
                .isLessThanOrEqualTo((int) saved.getLimits().get("dispatchWindow"));
    }

    @Test
    @DisplayName("묶음에 속하지 않은 실행 결과는 조용히 무시된다")
    void unknownJobIsIgnored() {
        assertThat(progressService.candidateFinished(999_999L, false, null)).isFalse();
    }

    /** 후보 집합의 모양 — 전략·순번·파라미터·좌표. 재현성 비교에 쓴다. */
    private List<String> shapeOf(String searchId) {
        ParamSearch search = paramSearchRepository.findBySearchId(searchId).orElseThrow();
        return candidateRepository.findByParamSearchId(search.getId()).stream()
                .map(
                        candidate ->
                                "%d|%s|%s|%s"
                                        .formatted(
                                                candidate.getCandidateNo(),
                                                candidate.getStrategyId(),
                                                candidate.getParameters().values(),
                                                candidate.getGridCoordinates().indices()))
                .toList();
    }

    /** 등록표에는 있으나 004가 실행을 지원하지 않는 전략. 이름을 적지 않고 조회로 찾는다. */
    private List<String> unsupportedStrategyIds() {
        List<String> registered =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status ="
                                + " 'REGISTERED'",
                        String.class);
        return registry.activeStrategies().stream()
                .map(StrategyRegistration::strategyId)
                .filter(strategyId -> !registered.contains(strategyId))
                .toList();
    }

    private PreviewParamSearchCommand previewCommand() {
        return new PreviewParamSearchCommand(
                null,
                UNIVERSE,
                SearchableStrategies.of(registry, jdbcTemplate),
                null,
                null,
                null,
                Map.of());
    }

    private CreateParamSearchCommand createCommand(String registryVersion) {
        return new CreateParamSearchCommand(
                null,
                UNIVERSE,
                SearchableStrategies.of(registry, jdbcTemplate),
                null,
                null,
                null,
                Map.of(),
                registryVersion);
    }

    private int countSearches() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM param_search", Integer.class);
    }

    /** 스냅샷 왕복이 값을 잃지 않는지 — 발행 경로가 이 값으로만 조립한다(FR-012). */
    @Test
    @DisplayName("실행 조건 스냅샷이 저장·복원을 거쳐도 같다")
    void executionPlanSurvivesRoundTrip() {
        ParamSearchResult accepted = paramSearchService.create(createCommand(registry.version()));
        ParamSearch saved = paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();

        ParamSearchSnapshot snapshot =
                ParamSearchSnapshot.fromMap(
                        saved.getId(),
                        saved.getSearchId().value(),
                        saved.getOwnerUserId(),
                        saved.getExecutionPlan());

        assertThat(snapshot.universe()).hasSize(UNIVERSE.size());
        assertThat(snapshot.universe())
                .allSatisfy(entry -> assertThat(entry.sectorId()).isNotBlank());
        assertThat(snapshot.start()).isBefore(snapshot.end());
        assertThat(snapshot.initialCash()).isPositive();
        assertThat(snapshot.registryVersion()).isEqualTo(registry.version());
        assertThat(snapshot.warmupTradingDays()).isNotNull();
        assertThat(snapshot.toMap()).isEqualTo(saved.getExecutionPlan());
    }

    @Test
    @DisplayName("거부된 묶음도 stop_reason과 함께 남는다")
    void rejectedSearchKeepsItsReason() {
        List<String> unsupported = unsupportedStrategyIds();
        if (unsupported.isEmpty()) {
            return;
        }
        CreateParamSearchCommand command =
                new CreateParamSearchCommand(
                        null,
                        UNIVERSE,
                        List.of(unsupported.get(0)),
                        null,
                        null,
                        null,
                        Map.of(),
                        registry.version());
        assertThatThrownBy(() -> paramSearchService.create(command))
                .isInstanceOf(ParamSearchRejectedException.class);

        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT stop_reason FROM param_search WHERE status = 'REJECTED'",
                                String.class))
                .containsExactly(StopReason.STRATEGY_NOT_EXECUTABLE.name());
    }
}
