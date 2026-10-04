package com.ssafy.thorp.myrecord;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.paramsearch.SearchableStrategies;
import com.ssafy.thorp.paramsearch.application.port.CandidateJobLauncher;
import com.ssafy.thorp.strategy.application.port.ParameterRegistryPort;
import com.ssafy.thorp.support.LauncherMockedIntegrationTestSupport;
import com.ssafy.thorp.support.TestUsers;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 탐색 묶음 저장 상한(spec 006 T034, FR-018·research R11, quickstart S10).
 *
 * <p>여기서 보는 것은 상한 <b>값</b>이 아니라 <b>닿았을 때의 동작</b>이다 — 접수가 시작되기 전에 거절되는가, 거절이 아무것도 남기지 않는가, 자리를 비우면
 * 다시 통과하는가. 값은 PD-13이 실측 뒤 정한다.
 */
// 상한 2는 다른 테스트의 접수를 막으므로 이 테스트만 쓴다(컨텍스트를 따로 만든다).
@TestPropertySource(properties = {"thorp.my-records.search-bundle-limit=2"})
class MyRecordLimitIT extends LauncherMockedIntegrationTestSupport {

    private static final String UNIVERSE = "[\"091160.XKRX\", \"305720.XKRX\"]";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ParameterRegistryPort registry;

    @Autowired private CandidateJobLauncher launcher;

    private final AtomicLong nextRunId = new AtomicLong(940_000L);

    private long owner;

    @BeforeEach
    void prepare() {
        jdbcTemplate.update("DELETE FROM search_candidate");
        // 내 기록이 이 행들을 가리키므로 먼저 지운다(spec 006 my_record FK).
        jdbcTemplate.update("DELETE FROM my_record");
        jdbcTemplate.update("DELETE FROM param_search");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id >= 900000");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE id >= 900000");
        nextRunId.set(940_000L);
        owner = TestUsers.ensure(jdbcTemplate, 94_001L);
        Mockito.when(launcher.launchCandidate(ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
        Mockito.when(launcher.launchBaseline(ArgumentMatchers.any()))
                .thenAnswer(invocation -> insertRun());
    }

    @Test
    @DisplayName("상한에 닿으면 접수가 시작되기 전에 409로 거절하고 현재 개수와 상한을 싣는다")
    void thirdIntakeIsRejectedBeforeAnythingRuns() throws Exception {
        accept();
        accept();
        int searchesBefore = paramSearchCount();
        int recordsBefore = recordCount();
        int launchesBefore = launchCount();

        mockMvc.perform(intake())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RECORD_LIMIT_REACHED"))
                // 화면이 "N/M"을 보이려면 숫자가 필요하다. 문장에서 뽑아 쓰게 두지 않는다.
                .andExpect(jsonPath("$.error.details.current").value(2))
                .andExpect(jsonPath("$.error.details.limit").value(2));

        assertThat(paramSearchCount())
                .as("거절은 묶음 행을 남기지 않는다 — 후보 상한 거절(REJECTED)과 다르다")
                .isEqualTo(searchesBefore);
        assertThat(launchCount())
                .as("계산이 시작되지 않는다. 계산 뒤에 막으면 그만큼이 버려진다(quickstart S10)")
                .isEqualTo(launchesBefore);
        assertThat(recordCount()).as("기존 항목을 지우지 않는다").isEqualTo(recordsBefore);
    }

    @Test
    @DisplayName("기록 하나를 지우면 다음 접수가 통과한다")
    void deletingARecordFreesASlot() throws Exception {
        accept();
        accept();
        mockMvc.perform(intake()).andExpect(status().isConflict());

        jdbcTemplate.update(
                "UPDATE my_record SET deleted_at = NOW() WHERE id ="
                        + " (SELECT id FROM my_record WHERE owner_id = ? ORDER BY id LIMIT 1)",
                owner);

        mockMvc.perform(intake()).andExpect(status().isAccepted());
        assertThat(recordCount()).as("지운 항목은 세지 않으므로 살아 있는 항목이 다시 둘이다").isEqualTo(2);
    }

    private void accept() throws Exception {
        mockMvc.perform(intake()).andExpect(status().isAccepted());
    }

    private MockHttpServletRequestBuilder intake() {
        return post("/param-searches")
                .with(csrf())
                .with(as(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody());
    }

    private int paramSearchCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM param_search WHERE owner_user_id = ?", Integer.class, owner);
    }

    /** 살아 있는 항목만 센다 — 상한이 세는 것과 같은 조건이다. */
    private int recordCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM my_record WHERE owner_id = ? AND deleted_at IS NULL",
                Integer.class,
                owner);
    }

    private int launchCount() {
        return Mockito.mockingDetails(launcher).getInvocations().size();
    }

    private String createBody() {
        return """
               {
                 "universe": { "instrumentIds": %s },
                 "strategy": { "strategyIds": %s },
                 "registryVersion": "%s"
               }
               """
                .formatted(UNIVERSE, supportedStrategyIdsJson(), registry.version());
    }

    /** 탐색을 접수할 수 있는 전략({@link SearchableStrategies}). 전략 ID를 테스트에 적지 않는다(007 FR-025). */
    private String supportedStrategyIdsJson() {
        return SearchableStrategies.of(registry, jdbcTemplate).stream()
                .map(id -> "\"" + id + "\"")
                .reduce((left, right) -> left + ", " + right)
                .map(joined -> "[" + joined + "]")
                .orElse("[]");
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
