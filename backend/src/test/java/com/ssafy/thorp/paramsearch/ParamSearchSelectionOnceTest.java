package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 선정 계산이 묶음당 정확히 1회인지(T072, 내부 계약 §4 불변식 3).
 *
 * <p><b>왜 1회여야 하는가</b>: 안정성 필터가 "묶음 내 상위 50%"라는 상대 분위다. 후보가 끝날 때마다 다시 계산하면 나중에 끝난 후보가 분위를 흔들어, 이미
 * 선정된 후보의 근거가 사라지고 같은 묶음의 결과가 볼 때마다 달라진다(원칙 VIII).
 *
 * <p>발행기는 목이다 — 여기서 볼 것은 큐가 아니라 <b>마지막 후보가 끝나는 순간에만</b> 선정이 도는가다. 성과 행은 테스트가 직접 넣는다.
 */
class ParamSearchSelectionOnceTest extends LauncherMockedIntegrationTestSupport {

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

    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(920_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
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
        // 계산 전에 제외된 후보(EXCLUDED)는 끝낼 수 없다 — 세면 마지막 하나를 남기지 못하고 다 비운다.
        // 유효성 규칙이 늘면 제외가 늘어나므로 상태로 거른다(2026-09-17 등록표 1.3.0).
        int runnable =
                (int)
                        candidateRepository.findByParamSearchId(search.getId()).stream()
                                .filter(
                                        candidate ->
                                                candidate.getStatus() != CandidateStatus.EXCLUDED)
                                .count();
        assertThat(runnable).as("중간 상태를 보려면 끝낼 수 있는 후보가 둘 이상이어야 한다").isGreaterThan(1);

        // 마지막 하나를 남기고 끝낸다. 발행 창이 좁아 한 번에 다 나와 있지 않으므로 끝낼 때마다 다시 본다.
        for (int done = 0; done < runnable - 1; done++) {
            finish(nextDispatched(search), "0.1000");
            assertThat(reload(search).getSelection()).as("아직 남은 후보가 있으면 분위가 확정되지 않는다").isNull();
        }
        finish(nextDispatched(search), "0.2000");

        ParamSearch finished = reload(search);
        assertThat(finished.getSelection()).isNotNull();
        assertThat(finished.getStatus()).isEqualTo(ParamSearchStatus.COMPLETED);
        Map<String, Object> selection = finished.getSelection();
        assertThat(selection)
                .containsKeys("displayed", "shortOfDisplayCount", "drawdownFilterApplied");
        // note 는 "표시 수를 채우지 못한 이유"라 채울 이유가 없으면 null이고, 저장 JSON 규칙이
        // null 값의 키를 생략한다(읽는 쪽은 "키 없음 == null"). 채운 경우에만 키가 있어야 한다.
        if (Boolean.TRUE.equals(selection.get("shortOfDisplayCount"))) {
            assertThat(selection).containsKey("note");
        }
    }

    @Test
    @DisplayName("이미 선정된 묶음을 다시 계산하지 않는다")
    void selectionIsNotRecomputed() {
        ParamSearch search = accept();
        drain(search);

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
        drain(search);

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
                                SearchableStrategies.of(registry, jdbcTemplate),
                                null,
                                null,
                                null,
                                Map.of(),
                                registry.version()));
        return paramSearchRepository.findBySearchId(accepted.searchId()).orElseThrow();
    }

    /**
     * 후보가 하나도 남지 않을 때까지 끝낸다.
     *
     * <p><b>발행된 목록을 한 번 찍어 두고 그것만 끝내지 않는다</b> — 발행 창이 좁아 후보 하나가 끝나야 다음이 나간다. 등록표의 조합 수가 늘면 찍어 둔 목록이
     * 전체의 일부가 되고, 묶음이 끝나지 않아 선정이 돌지 않는다.
     */
    private void drain(ParamSearch search) {
        List<SearchCandidate> next;
        while (!(next = dispatched(search)).isEmpty()) {
            next.forEach(candidate -> finish(candidate, "0.1000"));
        }
    }

    private SearchCandidate nextDispatched(ParamSearch search) {
        return dispatched(search).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("발행된 후보가 없다"));
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
}
