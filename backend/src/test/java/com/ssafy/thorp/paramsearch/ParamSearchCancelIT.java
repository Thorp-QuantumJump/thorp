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
import com.ssafy.thorp.paramsearch.application.result.ParamSearchCancelResult;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchCancelService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchProgressService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.model.StopReason;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.LauncherMockedIntegrationTestSupport;
import com.ssafy.thorp.support.TestUsers;
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
 * 취소와 부분 결과(T093·T094, FR-009, 내부 계약 §4 불변식 2·4).
 *
 * <p>취소는 "즉시 멈춤"이 아니라 "여기까지만"이다. 이미 큐에 들어간 후보는 회수할 수 없어 완주한다(research R2). 그래서 여기서 보는 것이 셋이다 — 발행 전
 * 후보가 '미실행'으로 확정되는가, 취소 뒤에 <b>새 후보가 한 건도 나가지 않는가</b>, 남은 후보가 끝났을 때 {@code PARTIAL}로 닫히며 부분 결과와 선정이
 * 남는가.
 */
// 창을 1로 좁혀 '발행된 1건 + 대기 3건'을 만든다. 취소가 무엇을 남기고 무엇을 막는지 갈라 보려면 두 무리가 동시에 있어야 한다.
// 이 속성 때문에 기반 컨텍스트와 따로 뜨고, 같은 상수를 쓰는 검사끼리 그 컨텍스트를 함께 쓴다.
@TestPropertySource(properties = LauncherMockedIntegrationTestSupport.NARROW_DISPATCH_WINDOW)
class ParamSearchCancelIT extends LauncherMockedIntegrationTestSupport {

    /** 접수는 로그인 세션의 요청자를 요구한다(004 T017). 취소·재시도는 그 소유자만 할 수 있다. */
    private static final long OWNER = 7L;

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchCancelService cancelService;
    @Autowired private ParamSearchProgressService progressService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(940_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
        // 접수가 내 기록을 만들려면 그 요청자가 users 에 있어야 한다(my_record.owner_id FK).
        TestUsers.ensure(jdbcTemplate, OWNER);
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(940_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("취소하면 대기 후보가 미실행으로 확정되고 발행된 후보만 남는다")
    void cancelSkipsPendingAndLetsInFlightFinish() {
        ParamSearch search = accept();
        long dispatchedBefore =
                candidateRepository.countByStatus(search.getId(), CandidateStatus.DISPATCHED);
        assertThat(dispatchedBefore).as("발행 창이 1이라 한 건만 나가 있어야 한다").isEqualTo(1);
        int expectedSkipped = pendingBefore(search);
        assertThat(expectedSkipped).as("멈출 대기 후보가 있어야 취소를 볼 수 있다").isPositive();

        ParamSearchCancelResult result = cancelService.cancel(search.getSearchId().value(), OWNER);

        assertThat(result.skipped()).isEqualTo(expectedSkipped);
        assertThat(result.inFlight()).isEqualTo(1);
        assertThat(result.status())
                .as("발행된 후보가 완주하기 전까지는 아직 RUNNING 이다(계약 §5)")
                .isEqualTo(ParamSearchStatus.RUNNING.name());
        assertThat(candidateRepository.countByStatus(search.getId(), CandidateStatus.PENDING))
                .isZero();
        assertThat(candidateRepository.countByStatus(search.getId(), CandidateStatus.SKIPPED))
                .isEqualTo(expectedSkipped);
    }

    @Test
    @DisplayName("취소 뒤에는 후보가 끝나도 새로 발행하지 않는다")
    void noCandidateIsDispatchedAfterCancel() {
        ParamSearch search = accept();
        cancelService.cancel(search.getSearchId().value(), OWNER);
        int launchesBefore = Mockito.mockingDetails(launcher).getInvocations().size();

        // 발행돼 있던 후보가 끝난다 — 평소라면 여기서 다음 후보가 창에 들어간다.
        finish(inFlight(search));

        assertThat(Mockito.mockingDetails(launcher).getInvocations())
                .as("취소가 즉시 효과를 내려면 여기서 발행이 늘면 안 된다(불변식 2)")
                .hasSize(launchesBefore);
    }

    @Test
    @DisplayName("남은 후보가 끝나면 PARTIAL로 닫히고 부분 결과와 선정이 남는다")
    void lastInFlightCandidateClosesTheBundleAsPartial() {
        ParamSearch search = accept();
        int expectedSkipped = pendingBefore(search);
        cancelService.cancel(search.getSearchId().value(), OWNER);

        finish(inFlight(search));

        ParamSearch closed = paramSearchRepository.findById(search.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(ParamSearchStatus.PARTIAL);
        assertThat(closed.getStopReason()).isEqualTo(StopReason.CANCELLED);
        assertThat(closed.getCounts().skipped()).isEqualTo(expectedSkipped);
        assertThat(closed.getSelection())
                .as("취소된 묶음도 완료된 후보끼리는 비교할 수 있다(data-model §3)")
                .isNotNull();

        ParamSearchResult view = paramSearchService.get(closed.getSearchId().value());
        assertThat(view.partial()).isTrue();
        assertThat(view.partialNote()).contains(String.valueOf(expectedSkipped));
    }

    @Test
    @DisplayName("미실행 후보는 되돌아오지 않는다")
    void skippedCandidatesStaySkipped() {
        ParamSearch search = accept();
        cancelService.cancel(search.getSearchId().value(), OWNER);
        finish(inFlight(search));

        assertThat(candidateRepository.findByParamSearchId(search.getId()))
                .filteredOn(candidate -> candidate.getStatus() == CandidateStatus.SKIPPED)
                .allSatisfy(
                        candidate -> {
                            assertThat(candidate.getReasonCode()).isEqualTo("CANCELLED");
                            assertThat(candidate.getBacktestJobId())
                                    .as("발행되지 않았으므로 실행 행이 없다")
                                    .isNull();
                        });
    }

    @Test
    @DisplayName("끝난 묶음을 취소하면 409 ALREADY_FINISHED다")
    void cancellingAFinishedBundleIsConflict() {
        ParamSearch search = accept();
        cancelService.cancel(search.getSearchId().value(), OWNER);
        finish(inFlight(search));

        assertThatThrownBy(() -> cancelService.cancel(search.getSearchId().value(), OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.ALREADY_FINISHED);
    }

    @Test
    @DisplayName("없는 묶음 취소는 404다")
    void cancellingAnUnknownBundleIsNotFound() {
        assertThatThrownBy(() -> cancelService.cancel("PS-20000101-000001", OWNER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private SearchCandidate inFlight(ParamSearch search) {
        return candidateRepository
                .findByParamSearchIdAndStatus(search.getId(), CandidateStatus.DISPATCHED)
                .get(0);
    }

    private void finish(SearchCandidate candidate) {
        insertResult(candidate.getBacktestJobId());
        transactionTemplate.executeWithoutResult(
                status ->
                        events.publishEvent(
                                new BacktestFinished(
                                        candidate.getBacktestJobId(),
                                        "BT-STUB-" + candidate.getBacktestJobId(),
                                        RunStatus.COMPLETED,
                                        null)));
    }

    private ParamSearch accept() {
        ParamSearchResult accepted =
                paramSearchService.create(
                        new CreateParamSearchCommand(
                                OWNER,
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

    /**
     * 취소 시점에 미실행으로 확정될 후보 수 — <b>취소 직전에 대기 중이던 후보를 센다.</b>
     *
     * <p><b>수를 적어 두지 않는다</b> — 등록표의 축이나 후보값이 늘면 조합 수가 곱으로 바뀐다. 적어 두면 등록표를 고칠 때마다 이 테스트가 이유 없이 깨지고,
     * 그때 수만 고쳐 넣으면 취소가 실제로 무엇을 멈췄는지 보지 않게 된다.
     *
     * <p>등록표의 {@code combinationCount}(격자 크기)를 쓰지 않는 이유: 격자는 유효성 규칙을 모른다. 후보 섹터가 둘인 이 묶음에서는 {@code
     * MAX_WEIGHT_TOO_LOW_FOR_UNIVERSE}가 상한 30·40 조합을 계산 전에 걸러내므로(007 FR-006) 실제 후보가 격자보다 적다. 취소가 멈춘
     * 것은 <b>대기 중이던 후보</b>이지 격자의 점이 아니다.
     */
    private int pendingBefore(ParamSearch search) {
        return (int) candidateRepository.countByStatus(search.getId(), CandidateStatus.PENDING);
    }
}
