package com.ssafy.thorp.myrecord;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.paramsearch.SearchableStrategies;
import com.ssafy.thorp.paramsearch.application.command.CreateParamSearchCommand;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.paramsearch.application.result.ParamSearchResult;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchService;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 탐색 접수가 실제로 묶음 기록을 남기는지 접수 경로로 확인한다(spec 006 research R16).
 *
 * <p>발행기는 목이다 — 여기서 볼 것은 큐가 아니라 접수가 남기는 행이다. 설정을 더하지 않아 다른 007 통합 검사와 같은 컨텍스트를 쓴다.
 */
class MyRecordSearchIntakeIT extends LauncherMockedIntegrationTestSupport {

    private static final List<String> UNIVERSE = List.of("091160.XKRX", "305720.XKRX");

    @Autowired private ParamSearchService paramSearchService;
    @Autowired private ParameterRegistryPort registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextRunId = new AtomicLong(930_000L);

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextRunId.set(930_000L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("탐색을 접수하면 묶음 기록이 하나 생기고 그 묶음을 가리킨다")
    void 탐색_접수는_묶음_기록을_남긴다() {
        long owner = TestUsers.ensure(jdbcTemplate, 90_001L);

        ParamSearchResult accepted = paramSearchService.create(command(owner));

        Long paramSearchId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM param_search WHERE search_id = ?",
                        Long.class,
                        accepted.searchId());
        assertThat(recordCount(owner)).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT kind FROM my_record WHERE owner_id = ?",
                                String.class,
                                owner))
                .isEqualTo("SEARCH_BUNDLE");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT param_search_id FROM my_record WHERE owner_id = ?",
                                Long.class,
                                owner))
                .isEqualTo(paramSearchId);
    }

    @Test
    @DisplayName("후보가 만든 실행은 분석 기록이 되지 않는다 — 묶음 항목이 대표한다")
    void 후보_실행은_분석_기록을_만들지_않는다() {
        long owner = TestUsers.ensure(jdbcTemplate, 90_002L);

        paramSearchService.create(command(owner));

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT count(*) FROM my_record WHERE owner_id = ? AND kind ="
                                        + " 'ANALYSIS'",
                                Integer.class,
                                owner))
                .isZero();
    }

    private int recordCount(long owner) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM my_record WHERE owner_id = ?", Integer.class, owner);
    }

    private CreateParamSearchCommand command(long requesterId) {
        return new CreateParamSearchCommand(
                requesterId,
                UNIVERSE,
                SearchableStrategies.of(registry, jdbcTemplate),
                null,
                null,
                null,
                Map.of(),
                registry.version());
    }

    /** 후보 발행 대신 실행 행만 만든다. 후보·기준이 backtest_run(id)을 FK로 참조한다. */
    private long insertRun() {
        long id = nextRunId.getAndIncrement();
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
}
