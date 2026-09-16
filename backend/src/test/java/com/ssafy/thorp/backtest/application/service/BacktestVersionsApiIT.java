package com.ssafy.thorp.backtest.application.service;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 참조 버전 표시(spec 004 T018, data-model {@code backtest_run.versions}). <b>실제 등록표</b>(Flyway 초기 행)로 임시
 * 규칙 표시가 202 와 상세에 그대로 나가는지 고정한다.
 *
 * <p>목으로는 잡히지 않는 것을 본다 — 등록표의 {@code provisional} 열 값이 실제로 무엇인지(역변동성 true, 고정 비중 false)와 그 값이 JSON
 * 으로 어떻게 나가는지다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.backtest.liveness.interval=PT1H",
            "thorp.backtest.limits.max-queued-per-user=1000",
            "thorp.backtest.limits.max-queued-total=1000",
            "thorp.backtest.limits.max-run-seconds=1800",
            "thorp.backtest.limits.stale-run-seconds=300",
            "thorp.backtest.limits.cancel-grace-seconds=1",
            "thorp.backtest.limits.provisional=true",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@AutoConfigureMockMvc
class BacktestVersionsApiIT extends PostgresTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestService backtestService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long newUser() {
        String suffix = "ver" + (System.nanoTime() % 1_000_000_000L);
        String email = suffix + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                suffix,
                suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    /** 다른 IT 와 기간을 겹치지 않게 둔다 — 본문이 같으면 동일 버전 키가 같아 저쪽 실행에 합류한다. */
    private static CreateBacktestCommand request(
            long requesterId, String strategyId, BigDecimal weight, int day) {
        return new CreateBacktestCommand(
                requesterId,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", weight),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY",
                                "305720.XKRX",
                                weight == null ? null : BigDecimal.ONE.subtract(weight))),
                strategyId,
                "1",
                // registryVersion 을 싣지 않는다 — 이 픽스처의 parameters 는 워커가 읽는 이름이고
                // 003 등록표의 축 이름이 아니다. 값을 실으면 접수가 등록표 판정을 걸어 409 다.
                null,
                Map.of("rebalance", "MONTH_END"),
                LocalDate.of(2024, 5, day),
                LocalDate.of(2024, 10, 31),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }

    @Test
    @DisplayName("임시 규칙(역변동성, PD-02)으로 접수한 실행은 202 와 상세 모두 versions.rules.provisional=true 다")
    void provisionalRulesAreFlaggedInAcceptanceAndDetail() throws Exception {
        long user = newUser();

        BacktestJobResult accepted =
                backtestService.create(request(user, "INVERSE_VOLATILITY", null, 2));

        assertThat(accepted.versions().get("rules"))
                .isEqualTo(Map.of("version", "1", "provisional", true));
        assertThat(accepted.versions())
                .containsEntry("mapping", "SECTOR-ETF-2026-09")
                .containsEntry("evaluationSplit", "SPLIT-1");

        mockMvc.perform(get("/backtests/{id}", accepted.backtestId()).with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.rules.version").value("1"))
                .andExpect(jsonPath("$.data.versions.rules.provisional").value(true))
                .andExpect(jsonPath("$.data.versions.costProfile.provisional").value(true))
                .andExpect(jsonPath("$.data.versions.mapping").value("SECTOR-ETF-2026-09"))
                .andExpect(jsonPath("$.data.versions.worker").doesNotExist());
    }

    @Test
    @DisplayName("확정 규칙(고정 비중)으로 접수한 실행은 versions.rules.provisional=false 다")
    void registeredRulesAreNotFlagged() throws Exception {
        long user = newUser();

        BacktestJobResult accepted =
                backtestService.create(request(user, "FIXED_WEIGHT", new BigDecimal("0.4000"), 3));

        assertThat(accepted.versions().get("rules"))
                .isEqualTo(Map.of("version", "1", "provisional", false));
        mockMvc.perform(get("/backtests/{id}", accepted.backtestId()).with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.rules.provisional").value(false));
    }
}
