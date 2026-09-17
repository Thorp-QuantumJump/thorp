package com.ssafy.thorp.paramsearch;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.repository.ParamSearchRepository;
import com.ssafy.thorp.paramsearch.application.repository.SearchCandidateRepository;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchCancelService;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
import com.ssafy.thorp.paramsearch.domain.model.CandidateStatus;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearch;
import com.ssafy.thorp.paramsearch.domain.model.ParamSearchStatus;
import com.ssafy.thorp.paramsearch.domain.model.SearchCandidate;
import com.ssafy.thorp.paramsearch.domain.vo.SearchSpace;
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
 * 끝난 묶음이 FR-020 기록 항목을 빠짐없이 갖는지(T117a, SC-007·FR-022, 원칙 VIII).
 *
 * <p><b>여기서 보는 것은 화면이 아니라 저장 상태다.</b> FR-022가 요구하는 "한 묶음으로 다시 열린다"를 007이 책임지는 범위는 <b>그 조회가 가능한 형태로
 * 저장하는 것</b>까지이고, 목록·펼침 화면은 006이 소유한다. 그래서 응답 모양이 아니라 저장된 값이 갖춰졌는지를 본다.
 *
 * <p>항목 하나가 비면 그 묶음은 나중에 다시 읽을 수 없다 — 등록표가 바뀐 뒤 "무엇을 탐색했는가"를 재구성하려면 실제 사용한 후보값이 있어야 하고, "왜 이 후보가
 * 빠졌는가"를 답하려면 계산하지 않은 후보의 사유까지 남아 있어야 한다. 재현 기록은 전부 있거나 없거나다.
 *
 * <p>완료와 부분 완료 둘 다 본다. 취소된 묶음이라고 기록이 덜 남으면, 사용자가 "여기까지 계산했다"를 되짚을 수 없다.
 */
// 창을 1로 좁힌다. ParamSearchCancelIT·ParamSearchRetryTest와 컨텍스트를 함께 쓰므로 글자 그대로 같게 둔다.
@TestPropertySource(properties = "thorp.param-search.dispatch-window=1")
class ParamSearchRecordCompletenessTest extends LauncherMockedIntegrationTestSupport {

    /** 접수는 로그인 세션의 요청자를 요구한다(004 T017). 취소·재시도는 그 소유자만 할 수 있다. */
    private static final long OWNER = 7L;

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParamSearchCancelService cancelService;
    @Autowired private ParamSearchRepository paramSearchRepository;
    @Autowired private SearchCandidateRepository candidateRepository;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ApplicationEventPublisher events;
    @Autowired private TransactionTemplate transactionTemplate;

    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextJobId = new AtomicLong(990_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextJobId.set(990_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("완료된 묶음이 FR-020 기록 항목을 전부 갖는다")
    void aCompletedBundleCarriesEveryRecordItem() {
        ParamSearch search = finishEverything(accept());

        assertThat(search.getStatus()).isEqualTo(ParamSearchStatus.COMPLETED);
        assertRecordIsComplete(search);
        assertThat(search.getSelection())
                .as("정렬 및 선정 규칙을 적용한 결과가 남아야 '왜 이 후보들인가'를 답할 수 있다")
                .isNotNull();
    }

    @Test
    @DisplayName("취소로 끝난 묶음도 기록이 덜하지 않다")
    void aPartialBundleCarriesEveryRecordItemToo() {
        ParamSearch accepted = accept();
        cancelService.cancel(accepted.getSearchId().value(), OWNER);
        ParamSearch search = finishEverything(accepted);

        assertThat(search.getStatus()).isEqualTo(ParamSearchStatus.PARTIAL);
        assertRecordIsComplete(search);
        assertThat(search.getStopReason()).as("왜 멈췄는지가 없으면 '여기까지'의 이유를 되짚을 수 없다").isNotNull();
        assertThat(search.getCounts().skipped()).as("미실행 후보 수는 완료 범위 표시의 근거다(FR-020)").isPositive();
    }

    @Test
    @DisplayName("계산하지 않은 후보도 사유를 남긴다")
    void uncomputedCandidatesKeepTheirReason() {
        ParamSearch accepted = accept();
        cancelService.cancel(accepted.getSearchId().value(), OWNER);
        ParamSearch search = finishEverything(accepted);

        List<SearchCandidate> uncomputed =
                candidateRepository.findByParamSearchId(search.getId()).stream()
                        .filter(candidate -> candidate.getStatus().isUncomputed())
                        .toList();
        assertThat(uncomputed).as("취소했으므로 계산하지 않은 후보가 있어야 한다").isNotEmpty();
        assertThat(uncomputed)
                .allSatisfy(
                        candidate -> {
                            assertThat(candidate.getReasonCode())
                                    .as("후보 %d 의 사유 코드", candidate.getCandidateNo())
                                    .isNotBlank();
                            assertThat(candidate.getReasonDetail())
                                    .as("코드만으로는 화면이 '왜 빠졌는지'를 설명할 수 없다")
                                    .isNotBlank();
                        });
    }

    @Test
    @DisplayName("저장된 탐색 공간으로 후보 집합을 다시 만들 수 있다")
    void theStoredSpaceReconstructsTheCandidateSet() {
        ParamSearch search = finishEverything(accept());

        SearchSpace space = search.getSearchSpace();
        long combinationsFromSpace = space.combinationCount();
        long storedCandidates = candidateRepository.findByParamSearchId(search.getId()).size();

        assertThat(combinationsFromSpace)
                .as("등록표가 바뀐 뒤에도 이 값만으로 그 묶음의 후보 집합이 재구성돼야 한다(FR-020)")
                .isEqualTo(storedCandidates);
        assertThat(space.strategyIds())
                .as("전략별로 축과 후보값이 남아 있어야 한다")
                .isEqualTo(search.getStrategyIds());
    }

    /** FR-020이 요구하는 항목을 하나씩 본다. 비면 그 묶음은 나중에 다시 읽을 수 없다. */
    private void assertRecordIsComplete(ParamSearch search) {
        assertThat(search.getSearchSpace()).as("탐색 공간").isNotNull();
        assertThat(search.getSearchSpace().isEmpty()).as("탐색 공간이 비어 있다").isFalse();
        assertThat(search.getSearchMethod()).as("탐색 방법").isNotNull();
        assertThat(search.getCounts().expected()).as("실행한 전체 후보").isPositive();
        assertThat(search.getCounts().skipped()).as("미실행 후보 수").isNotNegative();
        assertThat(search.getSelectionRule()).as("정렬 및 선정 규칙").isNotNull();
        assertThat(search.getReferenceVersions()).as("참조 버전").isNotEmpty();
        assertThat(search.getReferenceVersions())
                .as("조회한 등록표 버전이 참조 버전에 들어 있어야 한다(FR-020·FR-028)")
                .containsKey("registry");
        assertThat(search.getRegistryVersion()).as("등록표 버전").isNotBlank();
        assertThat(search.getBaselineJobId()).as("동일 비중 기준 결과 — 무엇과 비교했는지가 선정의 근거다").isNotNull();
        assertThat(search.getEvaluationSplit())
                .as("평가 분리는 미등록이라 비어 있다. 비어 있다는 것 자체가 기록이다(research R5)")
                .isNull();
        assertThat(search.getRandomSeed()).as("현재 방법은 난수를 쓰지 않아 시드가 없다(FR-020)").isNull();
        assertThat(search.getStartedAt()).as("시작 시각").isNotNull();
        assertThat(search.getFinishedAt()).as("종료 시각").isNotNull();
    }

    /** 발행된 후보가 없어질 때까지 결과를 흘린다 — 창이 좁아 여러 차례에 나눠 나간다. */
    private ParamSearch finishEverything(ParamSearch search) {
        List<SearchCandidate> inFlight = dispatched(search);
        while (!inFlight.isEmpty()) {
            inFlight.forEach(this::finish);
            inFlight = dispatched(search);
        }
        return paramSearchRepository.findById(search.getId()).orElseThrow();
    }

    private List<SearchCandidate> dispatched(ParamSearch search) {
        return candidateRepository.findByParamSearchIdAndStatus(
                search.getId(), CandidateStatus.DISPATCHED);
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

    /** 등록표 활성 ∩ 004 지원 ∩ 축 보유. 전략 ID를 테스트에 적지 않는다(FR-025). */
}
