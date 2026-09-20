package com.ssafy.thorp.myrecord;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
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

/**
 * 접수가 실제로 기록 항목을 남기는지 접수 경로로 확인한다(spec 006 FR-009·FR-020, research R16).
 *
 * <p>등록기 단위 검사는 이벤트를 직접 넣어 <b>만들지 않는 조건</b>을 본다. 여기서 보는 것은 그 앞이다 — 004 접수가 이벤트를 실제로 발행하고, 그 이벤트가 같은
 * 트랜잭션에서 행이 되는지.
 *
 * <p><b>기간을 다른 IT와 겹치지 않게 둔다.</b> PostgreSQL 컨테이너를 스위트가 공유하므로 본문이 같으면 동일 버전 키도 같아 다른 IT가 만든 실행에
 * 합류한다.
 */
class MyRecordIntakeIT extends IntegrationTestSupport {

    @Autowired private BacktestService backtestService;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("로그인 요청을 접수하면 분석 기록이 하나 생긴다")
    void 로그인_접수는_기록을_남긴다() {
        long owner = newUser("mine");

        BacktestJobResult accepted = backtestService.create(request(owner, PERIOD_A));

        assertThat(recordCount(owner)).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT kind FROM my_record WHERE owner_id = ?",
                                String.class,
                                owner))
                .isEqualTo("ANALYSIS");
        assertThat(analysisRequestIdOf(owner)).isEqualTo(requestIdOf(accepted));
    }

    @Test
    @DisplayName("같은 조건을 다시 접수하면 항목은 그대로이고 가리키는 요청만 최신으로 바뀐다(FR-009)")
    void 같은_조건_재접수는_최신_요청으로_다시_잇는다() {
        long owner = newUser("relink");

        BacktestJobResult first = backtestService.create(request(owner, PERIOD_B));
        BacktestJobResult second = backtestService.create(request(owner, PERIOD_B));

        // 같은 실행을 공유하고 요청만 새로 생긴다 — 004의 동일 결과 재사용이다.
        assertThat(requestIdOf(second)).isNotEqualTo(requestIdOf(first));
        assertThat(recordCount(owner)).as("새 기록을 만들지 않는다").isEqualTo(1);
        // 처음 요청에 묶인 채로 두면, 그 요청만 취소했을 때 실행이 계속 도는데도 목록이 '취소'를 보인다
        // (BacktestDetailResult 는 요청의 취소 여부로 상태를 정한다).
        assertThat(analysisRequestIdOf(owner)).as("가장 최근 요청을 가리킨다").isEqualTo(requestIdOf(second));
    }

    @Test
    @DisplayName("비로그인 접수는 기록을 남기지 않는다 — 기록은 식별된 사용자 단위로만 있다(FR-020)")
    void 비로그인_접수는_기록을_남기지_않는다() {
        long before = totalRecordCount();

        backtestService.create(request(null, PERIOD_C));

        assertThat(totalRecordCount()).isEqualTo(before);
    }

    private static final LocalDate[] PERIOD_A = {
        LocalDate.of(2024, 2, 5), LocalDate.of(2024, 11, 29)
    };
    private static final LocalDate[] PERIOD_B = {
        LocalDate.of(2024, 2, 6), LocalDate.of(2024, 11, 28)
    };
    private static final LocalDate[] PERIOD_C = {
        LocalDate.of(2024, 2, 7), LocalDate.of(2024, 11, 27)
    };

    private int recordCount(long owner) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM my_record WHERE owner_id = ?", Integer.class, owner);
    }

    private int totalRecordCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM my_record", Integer.class);
    }

    private long analysisRequestIdOf(long owner) {
        return jdbcTemplate.queryForObject(
                "SELECT analysis_request_id FROM my_record WHERE owner_id = ?", Long.class, owner);
    }

    private long requestIdOf(BacktestJobResult result) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM backtest_request WHERE backtest_id = ?",
                Long.class,
                result.backtestId());
    }

    private long newUser(String tag) {
        String suffix = tag + (System.nanoTime() % 1_000_000_000L);
        String email = "my-record-" + suffix + "@t.test";
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

    private static CreateBacktestCommand request(Long requesterId, LocalDate[] period) {
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
                Map.of("rebalance", "MONTH_END"),
                period[0],
                period[1],
                new BigDecimal("10000000.00"),
                null,
                "SPLIT-1",
                null,
                Scenario.FULL_PERIOD,
                null);
    }
}
