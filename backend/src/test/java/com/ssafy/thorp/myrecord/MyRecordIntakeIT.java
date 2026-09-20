package com.ssafy.thorp.myrecord;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestCancelService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.myrecord.application.repository.MyRecordRepository;
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
    @Autowired private BacktestCancelService cancelService;
    @Autowired private MyRecordRepository records;
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
        // 기록은 요청이 아니라 실행을 가리킨다. 공개 토큰과 상태는 읽을 때 요청을 골라 읽는다.
        assertThat(analysisRunIdOf(owner)).isEqualTo(runIdOf(accepted));
        assertThat(records.findViewableRequestId(owner, runIdOf(accepted)))
                .hasValue(requestIdOf(accepted));
    }

    @Test
    @DisplayName("같은 조건을 다시 접수해도 기록은 하나다(FR-009)")
    void 같은_조건_재접수는_기록을_늘리지_않는다() {
        long owner = newUser("reuse");

        BacktestJobResult first = backtestService.create(request(owner, PERIOD_B));
        BacktestJobResult second = backtestService.create(request(owner, PERIOD_B));

        // 같은 실행을 공유하고 요청만 새로 생긴다 — 004의 동일 결과 재사용이다.
        assertThat(requestIdOf(second)).isNotEqualTo(requestIdOf(first));
        assertThat(runIdOf(second)).isEqualTo(runIdOf(first));
        assertThat(recordCount(owner)).isEqualTo(1);
    }

    @Test
    @DisplayName("요청 하나를 취소해도 살아 있는 요청이 있으면 그것을 본다 — 취소 순서와 무관하다")
    void 취소한_요청이_아니라_살아있는_요청을_본다() {
        long owner = newUser("cancel");
        BacktestJobResult first = backtestService.create(request(owner, PERIOD_D));
        BacktestJobResult second = backtestService.create(request(owner, PERIOD_D));
        long runId = runIdOf(first);

        // 나중 요청을 취소한다. 먼저 접수한 요청이 살아 있어 실행은 계속 돈다.
        cancelService.cancel(second.backtestId(), owner);

        // 취소는 실행이 아니라 요청에 붙는다(BacktestDetailResult). 취소한 요청을 보면 목록이
        // '취소'와 부분 결과를 보이지만 이 사용자의 분석은 아직 돌고 있다.
        assertThat(records.findViewableRequestId(owner, runId)).hasValue(requestIdOf(first));

        // 남은 요청까지 취소하면 그때는 취소가 맞다. 가장 최근 것을 본다.
        cancelService.cancel(first.backtestId(), owner);
        assertThat(records.findViewableRequestId(owner, runId)).hasValue(requestIdOf(second));
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
    private static final LocalDate[] PERIOD_D = {
        LocalDate.of(2024, 2, 8), LocalDate.of(2024, 11, 26)
    };

    private int recordCount(long owner) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM my_record WHERE owner_id = ?", Integer.class, owner);
    }

    private int totalRecordCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM my_record", Integer.class);
    }

    private long analysisRunIdOf(long owner) {
        return jdbcTemplate.queryForObject(
                "SELECT analysis_run_id FROM my_record WHERE owner_id = ?", Long.class, owner);
    }

    private long runIdOf(BacktestJobResult result) {
        return jdbcTemplate.queryForObject(
                "SELECT run_id FROM backtest_request WHERE backtest_id = ?",
                Long.class,
                result.backtestId());
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
