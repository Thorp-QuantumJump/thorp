package com.ssafy.thorp.backtest.presentation.controller;

import static com.ssafy.thorp.support.Requesters.as;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /backtests/used-strategies}(README §1.12, spec 005 비교 후보).
 *
 * <p>요청 이력에서 전략·설정값 조합을 접는지 본다. 실제 접수 경로({@link BacktestService#create})로 요청을 만들어 동일 버전
 * 재사용(REUSED)이 낀 이력도 한 조합으로 나오는지 함께 고정한다.
 */
class BacktestUsedStrategiesApiIT extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestService backtestService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private long newUser() {
        String suffix = "used" + (System.nanoTime() % 1_000_000_000L);
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

    /**
     * 다른 IT 와 기간을 겹치지 않게 둔다 — 본문이 같으면 동일 버전 키가 같아 저쪽 실행에 합류한다. {@code registryVersion}은 싣지 않는다 — 이
     * 픽스처의 parameters 는 등록표 축 이름이 아니라 접수가 등록표 판정을 걸면 409 다.
     */
    private static CreateBacktestCommand request(
            long requesterId, ExecutionType type, String strategyId, Map<String, Object> params) {
        return new CreateBacktestCommand(
                requesterId,
                type,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", null),
                        new CreateBacktestCommand.AllocationItem("BATTERY", "305720.XKRX", null)),
                strategyId,
                "1",
                null,
                params,
                LocalDate.of(2024, 3, 4),
                LocalDate.of(2024, 9, 30),
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }

    @Test
    @DisplayName("같은 조합의 반복 요청은 한 건으로 접고, 설정값이 다르면 별건이며, 최근 것이 앞이다")
    void collapsesRepeatedCombinationsNewestFirst() throws Exception {
        long user = newUser();
        Map<String, Object> monthly = Map.of("rebalance", "MONTH_END");
        Map<String, Object> quarterly = Map.of("rebalance", "QUARTER_END");

        backtestService.create(request(user, ExecutionType.SINGLE, "EQUAL_WEIGHT", monthly));
        backtestService.create(request(user, ExecutionType.SINGLE, "EQUAL_WEIGHT", quarterly));
        // 같은 조합을 다시 — 동일 버전 재사용으로 실행은 공유하지만 요청은 새로 생긴다.
        backtestService.create(request(user, ExecutionType.SINGLE, "EQUAL_WEIGHT", monthly));

        mockMvc.perform(get("/backtests/used-strategies").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].strategyId").value("EQUAL_WEIGHT"))
                .andExpect(jsonPath("$.data.items[0].parameters.rebalance").value("MONTH_END"))
                .andExpect(jsonPath("$.data.items[0].rulesVersion").value("1"))
                .andExpect(jsonPath("$.data.items[1].parameters.rebalance").value("QUARTER_END"));
    }

    @Test
    @DisplayName("남의 요청과 탐색 묶음이 만든 실행은 보이지 않는다")
    void excludesOtherUsersAndSearchBundles() throws Exception {
        long user = newUser();
        long other = newUser();

        backtestService.create(
                request(other, ExecutionType.SINGLE, "EQUAL_WEIGHT", Map.of("rebalance", "W")));
        backtestService.create(
                request(
                        user,
                        ExecutionType.SEARCH_BUNDLE,
                        "EQUAL_WEIGHT",
                        Map.of("rebalance", "BUNDLE")));

        mockMvc.perform(get("/backtests/used-strategies").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    @Test
    @DisplayName("로그인 없이는 401 AUTHENTICATION_REQUIRED 다")
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/backtests/used-strategies"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }
}
