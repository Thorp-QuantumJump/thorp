package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.port.ParameterRegistryPort;
import com.ssafy.thorp.paramsearch.application.port.StrategyRegistration;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchProgressService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchSelectionService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
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
 * 선정 계산이 묶음당 정확히 1회인지(T072, 내부 계약 §4 불변식 3).
 *
 * <p><b>왜 1회여야 하는가</b>: 안정성 필터가 "묶음 내 상위 50%"라는 상대 분위다. 후보가 끝날 때마다 다시 계산하면 나중에 끝난 후보가 분위를 흔들어, 이미
 * 선정된 후보의 근거가 사라지고 같은 묶음의 결과가 볼 때마다 달라진다(원칙 VIII).
 *
 * <p>발행기는 목이다 — 여기서 볼 것은 큐가 아니라 <b>마지막 후보가 끝나는 순간에만</b> 선정이 도는가다. 성과 행은 테스트가 직접 넣는다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.param-search.enabled=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ParamSearchSelectionOnceTest extends PostgresTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchProgressService progressService;
    @Autowired private ParamSearchSelectionService selectionService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(920_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(920_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("마지막 후보가 끝나기 전에는 선정이 없고, 끝나면 한 번 생긴다")
    void selectionHappensOnlyWhenTheBatchDrains() {
        ParamSearch search = accept();
        List<SearchCandidate> dispatched = dispatched(search);
        assertThat(dispatched).as("중간 상태를 보려면 발행된 후보가 둘 이상이어야 한다").hasSizeGreaterThan(1);

        // 마지막 하나를 남기고 끝낸다.
        for (int i = 0; i < dispatched.size() - 1; i++) {
            finish(dispatched.get(i), "0.1000");
            assertThat(reload(search).getSelection()).as("아직 남은 후보가 있으면 분위가 확정되지 않는다").isNull();
        }
        finish(dispatched.get(dispatched.size() - 1), "0.2000");

        ParamSearch finished = reload(search);
        assertThat(finished.getSelection()).isNotNull();
        assertThat(finished.getStatus()).isEqualTo(ParamSearchStatus.COMPLETED);
        assertThat(finished.getSelection())
                .containsKeys("displayed", "shortOfDisplayCount", "note", "drawdownFilterApplied");
    }

    @Test
    @DisplayName("이미 선정된 묶음을 다시 계산하지 않는다")
    void selectionIsNotRecomputed() {
        ParamSearch search = accept();
        dispatched(search).forEach(candidate -> finish(candidate, "0.1000"));

        ParamSearch finished = reload(search);
        Map<String, Object> first = finished.getSelection();
        assertThat(first).isNotNull();

        ParamSearch again = selectionService.selectOnce(finished);

        assertThat(again.getSelection()).as("다시 계산하면 같은 묶음의 결과가 볼 때마다 달라진다(불변식 3)").isSameAs(first);
    }

    @Test
    @DisplayName("표시 후보에는 선정 사유가, 나머지에도 어느 단계에서 빠졌는지가 남는다")
    void everyCandidateKeepsItsReason() {
        ParamSearch search = accept();
        dispatched(search).forEach(candidate -> finish(candidate, "0.1000"));

        List<SearchCandidate> candidates = candidateRepository.findByParamSearchId(search.getId());
        assertThat(candidates)
                .allSatisfy(
                        candidate ->
                                assertThat(candidate.getSelectionReason())
                                        .as("후보 %d 의 사유", candidate.getCandidateNo())
                                        .isNotNull());
        assertThat(candidates).anyMatch(SearchCandidate::isSelected);
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

    private List<SearchCandidate> dispatched(ParamSearch search) {
        return candidateRepository.findByParamSearchIdAndStatus(
                search.getId(), CandidateStatus.DISPATCHED);
    }

    private ParamSearch reload(ParamSearch search) {
        return paramSearchRepository.findById(search.getId()).orElseThrow();
    }

    /** 004가 보내는 것과 같은 이벤트를 같은 경로로 흘린다. 성과 행은 그 전에 넣는다. */
    private void finish(SearchCandidate candidate, String netReturn) {
        insertResult(candidate.getBacktestJobId(), netReturn);
        transactionTemplate.executeWithoutResult(
                status ->
                        events.publishEvent(
                                new BacktestFinished(
                                        candidate.getBacktestJobId(),
                                        "BT-STUB-" + candidate.getBacktestJobId(),
                                        RunStatus.COMPLETED,
                                        null)));
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

    /** decimal을 문자열로 넣는다 — 저장 규약과 같아야 조회가 scale을 그대로 돌려준다. */
    private void insertResult(long runId, String netReturn) {
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
                {"netTotalReturn": "%s", "maxDrawdown": "-0.1000", "totalTradingCost": "0.0100"}
                """
                        .formatted(netReturn));
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
