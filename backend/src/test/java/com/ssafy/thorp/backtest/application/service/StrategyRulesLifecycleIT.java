package com.ssafy.thorp.backtest.application.service;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 규칙 버전 수명주기(spec 004 T029). 새 버전이 옛 버전을 대체한 뒤 옛 버전의 새 요청은 409, 옛 버전 실행의 재시도·발행은 옛 명세 그대로 통과하는지 실제
 * DB·HTTP 경로로 본다.
 *
 * <p><b>합성 전략({@value #STRATEGY})을 쓴다.</b> 공용 DB 라 실제 전략 행을 잠시라도 대체하면 같은 컨텍스트를 쓰는 다른 IT 가 영향을 받는다.
 * 접수 경로는 전략 이름으로 갈리는 분기(고정 비중·모멘텀) 밖에서는 등록표 행만 보므로, 합성 전략으로도 같은 판정을 탄다. 끝나면 이 전략이 만든 행을 모두 지운다 —
 * 남기면 {@code GET /backtests/registry} 를 단언하는 다른 IT 가 깨진다.
 */
class StrategyRulesLifecycleIT extends IntegrationTestSupport {

    private static final String STRATEGY = "T029_LIFECYCLE";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private BacktestRequestRepository requestRepository;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private EngineInputAssembler assembler;

    @BeforeEach
    void registerVersion1() {
        // 앞선 실행이 정리 도중 멈춰 남긴 행이 있으면 먼저 지운다. 그대로 INSERT 하면 키 충돌로 이 테스트가 원인과 무관하게 깨진다.
        removeEverythingThisStrategyMade();
        jdbcTemplate.update(
                "INSERT INTO strategy_rules"
                        + " (strategy_id, rules_version, status, provisional, spec, decision_ref)"
                        + " VALUES (?, '1', 'REGISTERED', false,"
                        + " '{\"marker\": \"v1\"}'::jsonb, 'T029 IT')",
                STRATEGY);
    }

    /**
     * 실행을 가리키는 표를 참조 순서대로 지운다. 측정·한도 도달·DLQ 기록은 {@code ON DELETE CASCADE}라 실행과 함께 지워지고,
     * 나머지(결과·거래·재사용 기록·요청)는 여기서 먼저 지운다. 하나라도 남으면 실행 삭제가 막히고, 합성 전략 행이 공용 DB에 남아 {@code GET
     * /backtests/registry}와 스냅샷 대조를 단언하는 다른 IT가 이 테스트와 무관한 이유로 깨진다.
     */
    @AfterEach
    void removeEverythingThisStrategyMade() {
        // 재시도 요청은 원본 요청을 가리키지만 한 문장 안에서 함께 지우므로 걸리지 않는다.
        String runs = "SELECT id FROM backtest_run WHERE strategy_id = '" + STRATEGY + "'";
        jdbcTemplate.update("DELETE FROM backtest_fill WHERE run_id IN (" + runs + ")");
        jdbcTemplate.update("DELETE FROM backtest_trade WHERE run_id IN (" + runs + ")");
        jdbcTemplate.update("DELETE FROM backtest_result WHERE run_id IN (" + runs + ")");
        jdbcTemplate.update("DELETE FROM backtest_reuse_event WHERE run_id IN (" + runs + ")");
        jdbcTemplate.update("DELETE FROM backtest_request WHERE run_id IN (" + runs + ")");
        jdbcTemplate.update("DELETE FROM backtest_run WHERE strategy_id = ?", STRATEGY);
        jdbcTemplate.update("DELETE FROM strategy_rules WHERE strategy_id = ?", STRATEGY);
    }

    /** 새 규칙 버전을 넣는 마이그레이션의 순서 그대로: 옛 행을 SUPERSEDED 로 둔 뒤 새 행을 INSERT 한다. */
    private void supersedeWithVersion2() {
        jdbcTemplate.update(
                "UPDATE strategy_rules SET status = 'SUPERSEDED'"
                        + " WHERE strategy_id = ? AND rules_version = '1'",
                STRATEGY);
        jdbcTemplate.update(
                "INSERT INTO strategy_rules"
                        + " (strategy_id, rules_version, status, provisional, spec, decision_ref)"
                        + " VALUES (?, '2', 'REGISTERED', false,"
                        + " '{\"marker\": \"v2\"}'::jsonb, 'T029 IT')",
                STRATEGY);
    }

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "rules-" + suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                "u" + suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** 합성 전략이라 003 등록표에 없다 — {@code registryVersion} 을 싣지 않는다(과도기 경로). */
    private static String body(String rulesVersion) {
        return """
                {
                  "executionType": "SINGLE",
                  "sectorAllocation": {
                    "mappingVersion": "SECTOR-ETF-2026-09",
                    "items": [
                      {"sectorId": "SEMICONDUCTOR", "instrumentId": "091160.XKRX"},
                      {"sectorId": "BATTERY", "instrumentId": "305720.XKRX"}
                    ]
                  },
                  "strategy": {
                    "strategyId": "%s",
                    "rulesVersion": "%s",
                    "parameters": {"rebalance": "MONTH_END"}
                  },
                  "conditions": {
                    "start": "2024-03-04",
                    "end": "2024-10-31",
                    "initialCash": "10000000.00"
                  }
                }
                """
                .formatted(STRATEGY, rulesVersion);
    }

    private ResultActions submit(long user, String rulesVersion) throws Exception {
        return mockMvc.perform(
                post("/backtests")
                        .with(csrf())
                        .with(as(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(rulesVersion)));
    }

    private String backtestIdOf(ResultActions accepted) throws Exception {
        return objectMapper
                .readTree(accepted.andReturn().getResponse().getContentAsString())
                .at("/data/backtestId")
                .asText();
    }

    private BacktestRun runOf(String backtestId) {
        Long runId = requestRepository.findByBacktestId(backtestId).orElseThrow().getRunId();
        return runRepository.findById(runId).orElseThrow();
    }

    /** 워커 없이 실행을 실패로 끝낸다. */
    private void fail(BacktestRun run) {
        transactionTemplate.executeWithoutResult(
                status -> {
                    BacktestRun failed =
                            runRepository
                                    .findById(run.getId())
                                    .orElseThrow()
                                    .markFailed("SYSTEM_FAILURE", "worker gone");
                    runRepository.update(
                            failed.withLifecycle(failed.getLifecycle().finished(Instant.now())));
                });
    }

    private String dedupKeyOf(BacktestRun run) {
        return jdbcTemplate.queryForObject(
                "SELECT dedup_key FROM backtest_run WHERE id = ?", String.class, run.getId());
    }

    @Test
    @DisplayName("대체된 옛 버전: 새 요청은 409, 그 버전 실행의 재시도는 202이고 옛 명세로 조립된다")
    void supersededVersionRejectsNewRequestsButKeepsRetriesOnTheOldSpec() throws Exception {
        long user = newUser("l");
        String original = backtestIdOf(submit(user, "1").andExpect(status().isAccepted()));
        BacktestRun originalRun = runOf(original);
        fail(originalRun);

        supersedeWithVersion2();

        // 새 요청은 옛 버전을 고를 수 없다. 화면은 이 코드를 보고 두 등록표를 다시 받는다.
        submit(user, "1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("REGISTRY_VERSION_CHANGED"))
                .andExpect(jsonPath("$.error.details.requestedRulesVersion").value("1"))
                .andExpect(jsonPath("$.error.details.currentRulesVersion").value("2"));

        // 옛 버전으로 접수됐던 요청의 재시도는 같은 조건 그대로 다시 접수된다.
        String retried =
                objectMapper
                        .readTree(
                                mockMvc.perform(
                                                post("/backtests/" + original + "/retry")
                                                        .with(csrf())
                                                        .with(as(user)))
                                        .andExpect(status().isAccepted())
                                        .andExpect(jsonPath("$.data.retryOf").value(original))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .at("/data/backtestId")
                        .asText();
        BacktestRun retriedRun = runOf(retried);
        assertThat(retriedRun.getId()).isNotEqualTo(originalRun.getId());
        assertThat(retriedRun.getPlan().rulesVersion()).isEqualTo("1");
        assertThat(dedupKeyOf(retriedRun)).isEqualTo(dedupKeyOf(originalRun));

        // 발행(첫 발행·재발행)이 조립하는 메시지도 옛 명세다.
        EngineInput input = assembler.assemble(retriedRun);
        assertThat(input.allocation().rulesVersion()).isEqualTo("1");
        assertThat(input.allocation().rules()).containsEntry("marker", "v1");

        // 새 버전은 새 요청이 그대로 쓴다.
        String current = backtestIdOf(submit(user, "2").andExpect(status().isAccepted()));
        assertThat(assembler.assemble(runOf(current)).allocation().rules())
                .containsEntry("marker", "v2");
    }

    @Test
    @DisplayName("대체된 옛 버전은 화면용 조회에서 빠지고 전략당 지금 버전 한 행만 나간다")
    void registryListsOnlyTheCurrentVersion() throws Exception {
        supersedeWithVersion2();

        JsonNode rows =
                objectMapper
                        .readTree(
                                mockMvc.perform(get("/backtests/registry"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .at("/data/strategyRules");
        List<String> versions = new ArrayList<>();
        rows.forEach(
                row -> {
                    if (STRATEGY.equals(row.get("strategyId").asText())) {
                        versions.add(row.get("rulesVersion").asText());
                    }
                });

        assertThat(versions).containsExactly("2");
    }
}
