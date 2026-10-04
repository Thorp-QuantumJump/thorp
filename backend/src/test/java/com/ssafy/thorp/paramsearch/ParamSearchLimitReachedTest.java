package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.LauncherMockedIntegrationTestSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연속 실패 한도 도달(T101a, FR-005·FR-009, SC-003).
 *
 * <p>한도가 하는 일은 <b>더 발행하지 않는 것</b>뿐이다. 이미 큐에 든 후보는 끊지 않는다 — 계산이 끝나가는 후보를 버리면 그만큼이 통째로 사라진다. 그래서 결과는
 * "중단"이 아니라 {@code PARTIAL}이고, 남은 대기 후보는 '미실행'으로 남아 완료 범위를 밝힌다.
 *
 * <p>연속 실패 한도를 1로 좁혀 둔다. 여기서 볼 것은 한도 값이 아니라 <b>닿았을 때의 동작</b>이다. 실행시간 한도도 같은 자리에서({@code
 * applyLimits}) 같은 방식으로 판정하지만 지금은 상한을 두지 않아 켜지지 않는다(2026-09-23) — 그래서 <b>이 한도가 망가진 묶음을 멈추는 유일한
 * 장치</b>다.
 */
// 연속 실패 한도 1은 실패 후보가 있는 다른 자동 탐색 테스트의 묶음을 닫아 버리므로 이 테스트만 쓴다(컨텍스트를 따로 만든다).
@TestPropertySource(
        properties = {
            LauncherMockedIntegrationTestSupport.NARROW_DISPATCH_WINDOW,
            "thorp.param-search.consecutive-failure-limit=1"
        })
class ParamSearchLimitReachedTest extends LauncherMockedIntegrationTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(960_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(960_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("연속 실패 한도에 닿으면 발행을 멈추고 PARTIAL + FAILURE_LIMIT으로 닫힌다")
    void consecutiveFailuresStopTheBundle() {
        ParamSearch search = accept();
        int launchesBefore = Mockito.mockingDetails(launcher).getInvocations().size();

        // 발행돼 있던 한 건이 실패한다. 한도가 1이라 이것으로 연속 실패가 성립한다.
        finishAsFailure(inFlight(search));

        ParamSearch closed = paramSearchRepository.findById(search.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ParamSearchStatus.PARTIAL);
        assertThat(closed.getStopReason()).isEqualTo(StopReason.FAILURE_LIMIT);
        assertThat(Mockito.mockingDetails(launcher).getInvocations())
                .as("한도에 닿은 뒤에는 새 후보가 나가지 않는다(불변식 2)")
                .hasSize(launchesBefore);
    }

    @Test
    @DisplayName("남은 대기 후보는 사유와 함께 미실행으로 남는다")
    void remainingCandidatesAreMarkedSkipped() {
        ParamSearch search = accept();

        finishAsFailure(inFlight(search));

        assertThat(candidateRepository.countByStatus(search.getId(), CandidateStatus.PENDING))
                .isZero();
        assertThat(candidateRepository.findByParamSearchId(search.getId()))
                .filteredOn(candidate -> candidate.getStatus() == CandidateStatus.SKIPPED)
                .isNotEmpty()
                .allSatisfy(
                        candidate -> {
                            assertThat(candidate.getReasonCode())
                                    .isEqualTo(StopReason.FAILURE_LIMIT.name());
                            assertThat(candidate.getReasonDetail()).isNotBlank();
                        });
    }

    @Test
    @DisplayName("부분 결과 문장이 완료·미실행 수를 담는다")
    void partialNoteTellsTheScope() {
        ParamSearch search = accept();

        finishAsFailure(inFlight(search));

        ParamSearchResult view = paramSearchService.get(search.getSearchId().value());
        assertThat(view.partial()).isTrue();
        assertThat(view.stopReason()).isEqualTo(StopReason.FAILURE_LIMIT);
        assertThat(view.partialNote())
                .as("몇 개 중 몇 개까지 계산했는지가 다음 판단을 가른다(FR-009)")
                .contains(String.valueOf(view.counts().skipped()));
    }

    @Test
    @DisplayName("성공이 섞여 있으면 연속이 끊겨 멈추지 않는다")
    void aSuccessBreaksTheStreak() {
        ParamSearch search = accept();

        finishAsSuccess(inFlight(search));

        ParamSearch running = paramSearchRepository.findById(search.getId()).orElseThrow();
        assertThat(running.getStatus())
                .as("드문드문 난 실패로 멀쩡한 탐색이 멈추면 안 된다")
                .isEqualTo(ParamSearchStatus.RUNNING);
        assertThat(running.getStopReason()).isNull();
        assertThat(candidateRepository.countByStatus(search.getId(), CandidateStatus.DISPATCHED))
                .as("다음 후보가 창에 들어간다")
                .isEqualTo(1);
    }

    private SearchCandidate inFlight(ParamSearch search) {
        return candidateRepository
                .findByParamSearchIdAndStatus(search.getId(), CandidateStatus.DISPATCHED)
                .get(0);
    }

    private void finishAsFailure(SearchCandidate candidate) {
        publish(candidate, true);
    }

    private void finishAsSuccess(SearchCandidate candidate) {
        insertResult(candidate.getBacktestJobId());
        publish(candidate, false);
    }

    private void publish(SearchCandidate candidate, boolean failed) {
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
                                SearchableStrategies.of(registry, jdbcTemplate),
                                null,
                                null,
                                null,
                                Map.of(),
                                registry.version()));
        return paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
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
}
