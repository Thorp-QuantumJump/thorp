package com.ssafy.thorp.myrecord;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.myrecord.application.result.MyRecordDetail;
import com.ssafy.thorp.myrecord.application.service.MyRecordQueryService;
import com.ssafy.thorp.support.IntegrationTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 설정을 바꿔 다시 분석했을 때 기록이 어떻게 남는가(spec 006 T032, FR-009·SC-003, quickstart S4).
 *
 * <p>보는 것은 셋이다 — 조건을 바꾸면 <b>새 항목</b>이 생기고, 완전히 같은 조건이면 <b>늘지 않으며</b>, 어느 쪽이든 원본의 계산 결과는 그대로다. 마지막이
 * SC-003이고, 비교하려고 다시 돌렸더니 원본이 덮이는 일이 없어야 한다.
 *
 * <p><b>기간을 다른 IT와 겹치지 않게 둔다.</b> PostgreSQL 컨테이너를 스위트가 공유하므로 본문이 같으면 동일 버전 키도 같아 다른 IT가 만든 실행에
 * 합류한다({@code MyRecordIntakeIT}가 2024-02-05~09로 시작하는 기간을 쓴다).
 */
class MyRecordReopenIT extends IntegrationTestSupport {

    private static final LocalDate[] PERIOD = {
        LocalDate.of(2024, 4, 15), LocalDate.of(2024, 10, 18)
    };

    @Autowired private BacktestService backtestService;
    @Autowired private MyRecordQueryService queryService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("축 하나를 바꿔 다시 분석하면 새 항목이 생기고 원본 결과는 그대로다(SC-003)")
    void 조건을_바꾸면_새_항목이_생긴다() {
        long owner = newUser("reopen");
        BacktestJobResult first = backtestService.create(request(owner, "MONTH_END"));
        long firstRun = runIdOf(first);
        insertResult(firstRun);
        String resultHash = resultDigest(firstRun);

        // 리밸런싱 주기만 바꾼다 — 005의 '설정 바꿔 비교'가 003을 거쳐 보내는 모양이다.
        BacktestJobResult second = backtestService.create(request(owner, "QUARTER_END"));

        assertThat(runIdOf(second)).as("조건이 다르면 실행도 다르다").isNotEqualTo(firstRun);
        assertThat(recordCount(owner)).isEqualTo(2);
        assertThat(resultDigest(firstRun))
                .as("비교하려고 다시 돌렸다고 원본 결과가 바뀌면 SC-003 위반이다")
                .isEqualTo(resultHash);
    }

    @Test
    @DisplayName("완전히 같은 조건이면 항목이 늘지 않는다(FR-009)")
    void 같은_조건이면_항목이_늘지_않는다() {
        long owner = newUser("same");
        BacktestJobResult first = backtestService.create(request(owner, "MONTH_END"));

        BacktestJobResult second = backtestService.create(request(owner, "MONTH_END"));

        assertThat(runIdOf(second)).as("같은 조건은 실행을 공유한다").isEqualTo(runIdOf(first));
        assertThat(recordCount(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("상세가 저장 당시 실행 조건을 005와 같은 모양으로 돌려주고 '설정 바꿔 비교'를 연다")
    void 상세가_인계할_조건을_담는다() {
        long owner = newUser("payload");
        backtestService.create(request(owner, "MONTH_END"));

        MyRecordDetail detail = queryService.detail(owner, recordIdOf(owner));

        assertThat(detail.reopenSettings().available()).isTrue();
        assertThat(detail.reopenPayload())
                .as("005 ResultConfig 와 같은 모양이라야 prefillStrategySettings 에 그대로 넘어간다")
                .containsKeys("strategyId", "parameters", "start", "end", "currency");
        assertThat(detail.reopenPayload()).containsEntry("scenario", "FULL_PERIOD");
        // 지금 등록표로 실제 판정이 돈다. 저장한 값이 등록표 그대로이므로 범위 밖이 아니다 — 판정
        // 자체를 건너뛰어 null 이 되는 것과 다르다(그때는 응답에서 키가 빠진다).
        assertThat(detail.registryDrift()).isNotNull();
        assertThat(detail.registryDrift().outOfRange()).isFalse();
        assertThat(detail.registryDrift().fields()).isEmpty();
    }

    private int recordCount(long owner) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM my_record WHERE owner_id = ? AND deleted_at IS NULL",
                Integer.class,
                owner);
    }

    private String recordIdOf(long owner) {
        return jdbcTemplate.queryForObject(
                "SELECT record_id FROM my_record WHERE owner_id = ?", String.class, owner);
    }

    private long runIdOf(BacktestJobResult result) {
        return jdbcTemplate.queryForObject(
                "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                Long.class,
                result.backtestId());
    }

    /** 결과 행이 그대로인지 보는 지문. 성과 원문과 갱신 시각을 함께 본다. */
    private String resultDigest(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT performance::text || '|' || updated_at FROM backtest_result WHERE run_id"
                        + " = ?",
                String.class,
                runId);
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
                "EXEC-REOPEN-" + runId,
                "EVT-REOPEN-" + runId,
                """
                {"netTotalReturn": "0.1200", "maxDrawdown": "-0.0800", "totalTradingCost": "0.0050"}
                """);
    }

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "my-record-reopen-" + suffix + "@t.test";
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

    private static CreateBacktestCommand request(Long requesterId, String rebalance) {
        return new CreateBacktestCommand(
                requesterId,
                ExecutionType.SINGLE,
                "SECTOR-ETF-2026-09",
                List.of(
                        new CreateBacktestCommand.AllocationItem(
                                "SEMICONDUCTOR", "091160.XKRX", new BigDecimal("0.4000")),
                        new CreateBacktestCommand.AllocationItem(
                                "BATTERY", "305720.XKRX", new BigDecimal("0.6000"))),
                "FIXED_WEIGHT",
                "1",
                null,
                Map.of("rebalance", rebalance),
                PERIOD[0],
                PERIOD[1],
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }
}
