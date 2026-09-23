package com.ssafy.thorp.backtest.application.service;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
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
 * 참조 버전 표시(spec 004 T018, data-model {@code backtest_run.versions}). <b>실제 등록표</b>(Flyway 초기 행)로 임시
 * 규칙 표시가 202 와 상세에 그대로 나가는지 고정한다.
 *
 * <p>목으로는 잡히지 않는 것을 본다 — 등록표의 {@code provisional} 열 값이 실제로 무엇인지(역변동성 true, 고정 비중 false)와 그 값이 JSON
 * 으로 어떻게 나가는지다. 비용 조건도 같다 — 설정의 기본 버전(T021 확정값)과 명시한 데모 버전이 각자 자기 행의 수치로 조립되는지 본다.
 */
class BacktestVersionsApiIT extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private BacktestService backtestService;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private EngineInputAssembler assembler;
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

    private static CreateBacktestCommand request(
            long requesterId, String strategyId, BigDecimal weight, int day) {
        return request(requesterId, strategyId, weight, day, null);
    }

    /**
     * 다른 IT 와 기간을 겹치지 않게 둔다 — 본문이 같으면 동일 버전 키가 같아 저쪽 실행에 합류한다.
     *
     * @param costProfileVersion null = 서버 기본 비용 조건
     */
    private static CreateBacktestCommand request(
            long requesterId,
            String strategyId,
            BigDecimal weight,
            int day,
            String costProfileVersion) {
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
                costProfileVersion,
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
                // 규칙이 임시여도 비용은 확정 기본값이다(T021) — 두 표시는 서로 독립이다.
                .andExpect(jsonPath("$.data.versions.costProfile.provisional").value(false))
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

    /**
     * 비용 버전을 비운 요청은 PD-07 확정값으로 계산된다(T021).
     *
     * <p>워커는 비용을 자체 기본값으로 갖지 않고 작업 메시지의 {@code execution.costProfile}만 읽는다. 그래서 여기서는 접수가 기록한 버전과
     * <b>조립된 작업 메시지의 수치</b>를 함께 본다 — 버전 이름만 맞고 수치가 옛 값이면 결과 비용이 틀린다. 워커가 이 수치로 한쪽 체결마다 9.5bps를 매기는
     * 것은 {@code worker/tests/test_costs.py}가 본다.
     */
    @Test
    @DisplayName("비용 버전을 비운 요청은 확정 비용 KR-ETF-2026-09 로 접수되고 작업 메시지에 그 수치가 실린다")
    void defaultRequestUsesConfirmedCostProfile() throws Exception {
        long user = newUser();

        BacktestJobResult accepted =
                backtestService.create(request(user, "FIXED_WEIGHT", new BigDecimal("0.4000"), 6));

        assertThat(accepted.versions().get("costProfile"))
                .isEqualTo(Map.of("version", "KR-ETF-2026-09", "provisional", false));
        mockMvc.perform(get("/backtests/{id}", accepted.backtestId()).with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versions.costProfile.version").value("KR-ETF-2026-09"))
                .andExpect(jsonPath("$.data.versions.costProfile.provisional").value(false));

        EngineInput.CostProfile cost = assembledCost(accepted.backtestId());
        assertThat(cost.version()).isEqualTo("KR-ETF-2026-09");
        assertThat(cost.commissionBps()).isEqualByComparingTo("1.5");
        assertThat(cost.spreadBps()).isEqualByComparingTo("3");
        assertThat(cost.slippageBps()).isEqualByComparingTo("5");
        assertThat(cost.taxBps()).isEqualByComparingTo("0");
        assertThat(cost.provisional()).isFalse();
    }

    /** 데모 버전은 등록표에 그대로 남아, 명시한 요청은 예전 값으로 계산된다 — 기본값 교체가 옛 버전을 재해석하지 않는다. */
    @Test
    @DisplayName("데모 비용 버전을 명시한 요청은 예전 값(슬리피지 5bps만)과 임시 표시 그대로다")
    void explicitDemoVersionKeepsOldValues() throws Exception {
        long user = newUser();

        BacktestJobResult accepted =
                backtestService.create(
                        request(
                                user,
                                "FIXED_WEIGHT",
                                new BigDecimal("0.4000"),
                                7,
                                "PROVISIONAL-DEMO-1"));

        assertThat(accepted.versions().get("costProfile"))
                .isEqualTo(Map.of("version", "PROVISIONAL-DEMO-1", "provisional", true));

        EngineInput.CostProfile cost = assembledCost(accepted.backtestId());
        assertThat(cost.version()).isEqualTo("PROVISIONAL-DEMO-1");
        assertThat(cost.commissionBps()).isEqualByComparingTo("0");
        assertThat(cost.spreadBps()).isEqualByComparingTo("0");
        assertThat(cost.slippageBps()).isEqualByComparingTo("5");
        assertThat(cost.taxBps()).isEqualByComparingTo("0");
        assertThat(cost.provisional()).isTrue();
    }

    /** 사용자가 보는 식별자는 요청의 것이다(R-02). 그 요청이 가리키는 실행을 발행 경로와 같은 조립기로 조립한다. */
    private EngineInput.CostProfile assembledCost(String backtestId) {
        Long runId =
                jdbcTemplate.queryForObject(
                        "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                        Long.class,
                        backtestId);
        BacktestRun run = runRepository.findById(runId).orElseThrow();
        return assembler.assemble(run).execution().costProfile();
    }
}
