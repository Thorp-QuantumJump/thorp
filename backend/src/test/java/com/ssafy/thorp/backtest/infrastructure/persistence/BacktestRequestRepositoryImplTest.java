package com.ssafy.thorp.backtest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 요청 행의 저장·복원과 취소 기록을 PostgreSQL에서 확인한다(R-02·R-04).
 *
 * <p>접수 유스케이스는 T005가 붙이지만 스키마·매핑은 여기서 확정된다. 특히 취소 스냅샷 두 컬럼은 DB 제약(취소하지 않았는데 부산물만 있는 행 금지)과 도메인 불변식이
 * 같은 규칙을 말하는지 확인한다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    BacktestRequestRepositoryImpl.class,
    BacktestRunRepositoryImpl.class
})
class BacktestRequestRepositoryImplTest extends PostgresTestSupport {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-11T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private BacktestRequestRepositoryImpl requests;
    @Autowired private BacktestRunRepositoryImpl runs;
    @Autowired private TestEntityManager entityManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** users FK를 만족시킬 최소 행. 회원 도메인을 끌어오지 않으려고 직접 넣는다. */
    private long newUser(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password_hash, nickname, phone_number, status,"
                        + " paper_ranking_enabled, live_ranking_enabled, ranking_display_nickname,"
                        + " created_at, updated_at)"
                        + " VALUES (?, 'x', ?, ?, 'ACTIVE', false, false, false, NOW(), NOW())",
                email,
                email,
                email);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private long newRun() {
        return runs.save(
                        BacktestRun.create(
                                "EQUAL_WEIGHT",
                                "1.0.0",
                                Map.of("rebalance", "MONTH_END"),
                                List.of(new InstrumentId("091160.XKRX")),
                                new BacktestPeriod(
                                        LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31)),
                                new BigDecimal("10000000.00"),
                                PositionSizingMode.STRATEGY_DEFINED,
                                null,
                                null,
                                42))
                .getId();
    }

    private BacktestRequest save(long runId, long requesterId, ResultSource source) {
        return requests.save(
                BacktestRequest.create(runId, requesterId, ExecutionType.SINGLE, source, null));
    }

    @Test
    @DisplayName("저장하면 요청에 backtestId가 붙고 값이 그대로 돌아온다")
    void savesAndRestores() {
        long runId = newRun();
        long userId = newUser("a@example.com");

        BacktestRequest saved = save(runId, userId, ResultSource.NEW);
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getBacktestId().value()).startsWith("BT-20260911-");
        BacktestRequest found =
                requests.findByBacktestId(saved.getBacktestId().value()).orElseThrow();
        assertThat(found.getRunId()).isEqualTo(runId);
        assertThat(found.getRequesterId()).isEqualTo(userId);
        assertThat(found.getExecutionType()).isEqualTo(ExecutionType.SINGLE);
        assertThat(found.getResultSource()).isEqualTo(ResultSource.NEW);
        assertThat(found.isCancelled()).isFalse();
        assertThat(found.isStoppedRun()).isFalse();
    }

    @Test
    @DisplayName("여러 요청이 한 실행을 가리키고, 취소하면 활성 수가 줄어든다 (합류·마지막 취소 판정의 근거)")
    void countsActiveRequestsPerRun() {
        long runId = newRun();
        BacktestRequest first = save(runId, newUser("b@example.com"), ResultSource.NEW);
        BacktestRequest second = save(runId, newUser("c@example.com"), ResultSource.JOINED);
        entityManager.flush();

        assertThat(requests.findByRunId(runId)).hasSize(2);
        assertThat(requests.countActiveByRunId(runId)).isEqualTo(2);

        requests.update(
                first.cancel(
                        Instant.parse("2026-09-11T03:10:00Z"), LocalDate.of(2021, 6, 30), false));
        entityManager.flush();
        entityManager.clear();

        assertThat(requests.countActiveByRunId(runId)).isEqualTo(1);
        assertThat(
                        requests.findByBacktestId(second.getBacktestId().value())
                                .orElseThrow()
                                .isCancelled())
                .isFalse();
    }

    @Test
    @DisplayName("먼저 취소한 요청은 스냅샷 날짜가 남고, 실행을 멈춘 취소는 남지 않는다")
    void persistsCancelSnapshot() {
        long runId = newRun();
        BacktestRequest early = save(runId, newUser("d@example.com"), ResultSource.JOINED);
        BacktestRequest last = save(runId, newUser("e@example.com"), ResultSource.JOINED);
        entityManager.flush();

        requests.update(
                early.cancel(
                        Instant.parse("2026-09-11T03:10:00Z"), LocalDate.of(2021, 6, 30), false));
        requests.update(
                last.cancel(
                        Instant.parse("2026-09-11T03:20:00Z"), LocalDate.of(2021, 7, 30), true));
        entityManager.flush();
        entityManager.clear();

        BacktestRequest reloadedEarly =
                requests.findByBacktestId(early.getBacktestId().value()).orElseThrow();
        assertThat(reloadedEarly.getCancelledLastTradingDate())
                .isEqualTo(LocalDate.of(2021, 6, 30));
        assertThat(reloadedEarly.isStoppedRun()).isFalse();

        BacktestRequest reloadedLast =
                requests.findByBacktestId(last.getBacktestId().value()).orElseThrow();
        assertThat(reloadedLast.getCancelledLastTradingDate())
                .as("실행을 멈춘 취소는 범위를 run에서 읽는다")
                .isNull();
        assertThat(reloadedLast.isStoppedRun()).isTrue();
        assertThat(requests.countActiveByRunId(runId)).isZero();
    }
}
