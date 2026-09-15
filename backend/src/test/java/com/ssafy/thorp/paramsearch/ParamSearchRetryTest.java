package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchRetryResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchCancelService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchRetryService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.strategy.application.port.StrategyRegistration;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실패 후보 재시도(T098c, FR-024, SC-009).
 *
 * <p>여기서 지키는 것이 셋이다 — <b>새 묶음을 만들지 않고</b> 접수 시점 스냅샷을 그대로 쓰는가, <b>{@code SKIPPED}는 되살리지 않는가</b>, 다시
 * 끝났을 때 <b>선정을 다시 계산하는가</b>.
 *
 * <p>선정을 다시 계산해야 하는 이유는 안정성 필터가 묶음 내 상대 분위이기 때문이다. 후보 하나가 늘면 분위 자체가 달라져 이전 선정은 더 이상 사실이 아니다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            // 창을 1로 좁힌다. 취소가 '미실행'으로 돌릴 대기 후보가 남아 있어야
            // 재시도가 그것을 되살리지 않는다는 것을 볼 수 있다.
            "thorp.param-search.dispatch-window=1",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ParamSearchRetryTest extends PostgresTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchRetryService retryService;
    @Autowired private ParamSearchCancelService cancelService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(950_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(950_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("재시도는 새 묶음을 만들지 않고 같은 묶음을 다시 연다")
    void retryReopensTheSameBundle() {
        ParamSearch search = acceptAndFinish(1);
        Map<String, Object> planBefore = search.getExecutionPlan();
        Map<String, Object> ruleBefore = search.getSelectionRule().toMap();

        ParamSearchRetryResult result = retryService.retryFailed(search.getSearchId().value());

        assertThat(result.searchId()).isEqualTo(search.getSearchId().value());
        assertThat(result.retried()).isEqualTo(1);
        assertThat(countSearches()).as("같은 탐색의 결과가 두 묶음으로 갈리면 비교가 성립하지 않는다").isEqualTo(1);

        ParamSearch reopened = paramSearchRepository.findById(search.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(ParamSearchStatus.RUNNING);
        assertThat(reopened.getExecutionPlan())
                .as("참조 버전·구간·금액이 그대로여야 먼저 끝난 후보와 나란히 놓을 수 있다(FR-012)")
                .isEqualTo(planBefore);
        assertThat(reopened.getSelectionRule().toMap())
                .as("선정 규칙을 바꾸면 그것은 새 실험이다(FR-013c)")
                .isEqualTo(ruleBefore);
    }

    @Test
    @DisplayName("다시 연 묶음은 이전 선정을 지우고, 끝나면 다시 계산한다")
    void selectionIsClearedAndRecomputed() {
        ParamSearch search = acceptAndFinish(1);
        assertThat(search.getSelection()).as("먼저 끝났을 때 선정이 있어야 비교가 된다").isNotNull();

        retryService.retryFailed(search.getSearchId().value());

        assertThat(paramSearchRepository.findById(search.getId()).orElseThrow().getSelection())
                .as("후보가 더 붙으므로 이전 선정은 이미 사실이 아니다")
                .isNull();

        finishAll(search);

        ParamSearch closed = paramSearchRepository.findById(search.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ParamSearchStatus.COMPLETED);
        assertThat(closed.getSelection()).isNotNull();
        assertThat(closed.getCounts().failed()).isZero();
    }

    @Test
    @DisplayName("재시도한 후보의 재시도 횟수가 올라가고 이전 실패 사유는 지워진다")
    void retryCountRisesAndTheOldReasonIsCleared() {
        ParamSearch search = acceptAndFinish(1);

        retryService.retryFailed(search.getSearchId().value());

        SearchCandidate retried =
                candidateRepository
                        .findByParamSearchIdAndStatus(search.getId(), CandidateStatus.DISPATCHED)
                        .get(0);
        assertThat(retried.getRetryCount()).isEqualTo(1);
        assertThat(retried.getReasonCode()).as("성공한 후보에 옛 실패 사유가 붙어 있으면 결과 해석이 틀어진다").isNull();
    }

    @Test
    @DisplayName("미실행 후보는 재시도 대상이 아니다")
    void skippedCandidatesAreNotRevived() {
        ParamSearch search = accept();
        // 발행된 후보 하나를 실패로 끝내고, 나머지는 취소로 '미실행'이 되게 한다.
        finish(dispatched(search).get(0), true);
        cancelService.cancel(search.getSearchId().value());
        finishAll(search);

        long skippedBefore =
                candidateRepository.countByStatus(search.getId(), CandidateStatus.SKIPPED);
        assertThat(skippedBefore).as("취소로 빠진 후보가 있어야 이 검증이 성립한다").isPositive();

        ParamSearchRetryResult result = retryService.retryFailed(search.getSearchId().value());

        assertThat(result.retried()).isEqualTo(1);
        assertThat(candidateRepository.countByStatus(search.getId(), CandidateStatus.SKIPPED))
                .as("취소로 빠진 것을 되살리면 '여기까지 계산했다'는 표시가 뜻을 잃는다")
                .isEqualTo(skippedBefore);
    }

    @Test
    @DisplayName("실패 후보가 없으면 409 NOTHING_TO_RETRY다")
    void nothingToRetryIsConflict() {
        ParamSearch search = acceptAndFinish(0);

        assertThatThrownBy(() -> retryService.retryFailed(search.getSearchId().value()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOTHING_TO_RETRY);
    }

    @Test
    @DisplayName("없는 묶음 재시도는 404다")
    void retryingAnUnknownBundleIsNotFound() {
        assertThatThrownBy(() -> retryService.retryFailed("PS-20000101-000001"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /** 접수한 뒤 앞 {@code failCount}건을 실패로, 나머지를 성공으로 끝낸다. */
    private ParamSearch acceptAndFinish(int failCount) {
        ParamSearch search = accept();
        List<SearchCandidate> dispatched = dispatched(search);
        for (int i = 0; i < dispatched.size(); i++) {
            finish(dispatched.get(i), i < failCount);
        }
        finishAll(search);
        return paramSearchRepository.findById(search.getId()).orElseThrow();
    }

    /** 발행된 후보가 없을 때까지 결과를 흘린다 — 창이 좁아 여러 차례에 나눠 나간다. */
    private void finishAll(ParamSearch search) {
        List<SearchCandidate> inFlight = dispatched(search);
        while (!inFlight.isEmpty()) {
            inFlight.forEach(candidate -> finish(candidate, false));
            inFlight = dispatched(search);
        }
    }

    private List<SearchCandidate> dispatched(ParamSearch search) {
        return candidateRepository.findByParamSearchIdAndStatus(
                search.getId(), CandidateStatus.DISPATCHED);
    }

    private void finish(SearchCandidate candidate, boolean failed) {
        if (!failed) {
            insertResult(candidate.getBacktestJobId());
        }
        transactionTemplate.executeWithoutResult(
                status ->
                        events.publishEvent(
                                new BacktestFinished(
                                        candidate.getBacktestJobId(),
                                        "BT-STUB-" + candidate.getBacktestJobId(),
                                        failed ? RunStatus.FAILED : RunStatus.COMPLETED,
                                        failed ? "ENGINE_ERROR" : null)));
    }

    private ParamSearch accept() {
        ParamSearchResult accepted =
                paramSearchService.create(
                        new CreateParamSearchCommand(
                                null,
                                UNIVERSE,
                                searchableStrategyIds(),
                                null,
                                null,
                                null,
                                Map.of(),
                                registry.version()));
        return paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
    }

    private int countSearches() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM param_search", Integer.class);
    }

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

    private void insertResult(long runId) {
        jdbcTemplate.update(
                "INSERT INTO backtest_result (run_id, execution_id, finished_event_id, started_at,"
                        + " finished_at, portfolio_series, diagnostics, performance, created_at,"
                        + " updated_at)"
                        + " VALUES (?, ?, ?, NOW(), NOW(), '[]'::jsonb, '{}'::jsonb, ?::jsonb,"
                        + " NOW(), NOW())"
                        + " ON CONFLICT (run_id) DO NOTHING",
                runId,
                "EXEC-" + runId,
                "EVT-" + runId,
                """
                {"netTotalReturn": "0.1000", "maxDrawdown": "-0.1000", "totalTradingCost": "0.0100"}
                """);
    }

    /** 등록표 활성 ∩ 004 지원 ∩ 축 보유. 전략 ID를 테스트에 적지 않는다(FR-025). */
    private List<String> searchableStrategyIds() {
        List<String> registered =
                jdbcTemplate.queryForList(
                        "SELECT DISTINCT strategy_id FROM strategy_rules WHERE status ="
                                + " 'REGISTERED'",
                        String.class);
        return registry.activeStrategies().stream()
                .filter(registration -> !registration.axes().isEmpty())
                .map(StrategyRegistration::strategyId)
                .filter(registered::contains)
                .toList();
    }
}
