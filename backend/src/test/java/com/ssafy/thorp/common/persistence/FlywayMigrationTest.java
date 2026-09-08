package com.ssafy.thorp.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestJobRepositoryImpl;
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
 * 운영(prod)과 같은 조건으로 마이그레이션을 검증한다. Flyway·validate 설정과 PostgreSQL 컨테이너는 {@link PostgresTestSupport}가
 * 제공하므로, 마이그레이션과 Entity 매핑이 어긋나면 컨텍스트 로딩 자체가 실패한다.
 *
 * <p>여기서 확인하는 것은 컬럼과 시퀀스의 존재다. 타입 불일치는 Hibernate가 경고로만 남기고 통과시키기 때문에, 컬럼 목록을 눈으로 맞춰 두는 이 단언이 필요하다.
 */
@DataJpaTest
@Import({JpaAuditingConfig.class, BacktestJobRepositoryImpl.class})
class FlywayMigrationTest extends PostgresTestSupport {

    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-07T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BacktestJobRepositoryImpl repository;
    @Autowired private TestEntityManager entityManager;

    @Test
    @DisplayName("V1이 빈 DB에 적용되고 flyway_schema_history에 성공으로 기록된다")
    void v1IsAppliedToEmptyDatabase() {
        Integer applied =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history"
                                + " WHERE version = '1' AND success = TRUE",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("V1이 backtest_job을 Entity 매핑과 같은 컬럼으로 만든다")
    void v1CreatesBacktestJobColumns() {
        assertThat(columnsOf("backtest_job"))
                .containsExactlyInAnyOrder(
                        "id",
                        "backtest_id",
                        "strategy_id",
                        "strategy_version",
                        "parameters",
                        "instrument_ids",
                        "start_date",
                        "end_date",
                        "initial_cash",
                        "position_sizing_type",
                        "max_position_pct",
                        "benchmark_instrument_id",
                        "random_seed",
                        "status",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V1이 backtest_job PK용 시퀀스를 만든다")
    void v1CreatesBacktestJobSequence() {
        Integer sequences =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.sequences"
                                + " WHERE LOWER(sequence_name) = 'backtest_job_seq'",
                        Integer.class);

        assertThat(sequences).isEqualTo(1);
    }

    @Test
    @DisplayName("V1이 회원·이메일 인증 테이블을 Entity 매핑과 같은 컬럼으로 만든다")
    void v1CreatesAccountColumns() {
        assertThat(columnsOf("users"))
                .containsExactlyInAnyOrder(
                        "id",
                        "email",
                        "password_hash",
                        "nickname",
                        "phone_number",
                        "profile_image_url",
                        "status",
                        "paper_ranking_enabled",
                        "live_ranking_enabled",
                        "ranking_display_nickname",
                        "last_login_at",
                        "created_at",
                        "updated_at",
                        "withdrawn_at");

        assertThat(columnsOf("email_verifications"))
                .containsExactlyInAnyOrder(
                        "id",
                        "email",
                        "verification_code_hash",
                        "expires_at",
                        "verified_at",
                        "created_at");
    }

    @Test
    @DisplayName("V2가 적용되어 flyway_schema_history에 성공으로 기록된다")
    void v2IsApplied() {
        Integer applied =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history"
                                + " WHERE version = '2' AND success = TRUE",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("V2가 backtest_result를 Entity 매핑과 같은 컬럼으로 만든다")
    void v2CreatesBacktestResultColumns() {
        assertThat(columnsOf("backtest_result"))
                .containsExactlyInAnyOrder(
                        "backtest_job_id",
                        "execution_id",
                        "finished_event_id",
                        "started_at",
                        "finished_at",
                        "error_code",
                        "error_message",
                        "performance",
                        "execution",
                        "portfolio_series",
                        "benchmark",
                        "diagnostics",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V2가 backtest_trade를 Entity 매핑과 같은 컬럼으로 만들고 PK용 시퀀스를 둔다")
    void v2CreatesBacktestTradeColumnsAndSequence() {
        assertThat(columnsOf("backtest_trade"))
                .containsExactlyInAnyOrder(
                        "id",
                        "backtest_job_id",
                        "trade_id",
                        "instrument_id",
                        "side",
                        "status",
                        "entry_time",
                        "exit_time",
                        "detail");

        Integer sequences =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.sequences"
                                + " WHERE LOWER(sequence_name) = 'backtest_trade_seq'",
                        Integer.class);
        assertThat(sequences).isEqualTo(1);
    }

    @Test
    @DisplayName("V2가 결과·거래 테이블에 backtest_job FK와 거래 UNIQUE 제약을 만든다")
    void v2CreatesConstraints() {
        List<String> foreignKeyTables =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(table_name) FROM information_schema.table_constraints"
                                + " WHERE constraint_type = 'FOREIGN KEY'"
                                + " AND LOWER(table_name) IN ('backtest_result', 'backtest_trade')",
                        String.class);
        assertThat(foreignKeyTables).containsExactlyInAnyOrder("backtest_result", "backtest_trade");

        Integer unique =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.table_constraints"
                                + " WHERE LOWER(constraint_name) = 'uk_backtest_trade_job_trade'",
                        Integer.class);
        assertThat(unique).isEqualTo(1);
    }

    @Test
    @DisplayName("V1 스키마에 Entity를 저장하고 다시 읽을 수 있다")
    void entityRoundTripsOnMigratedSchema() {
        Map<String, Object> parameters = Map.of("shortWindow", 20, "threshold", 0.05);
        List<InstrumentId> instruments = List.of(new InstrumentId("005930.XKRX"));

        BacktestJob saved =
                repository.save(
                        BacktestJob.create(
                                "MA_CROSSOVER",
                                "1.0.0",
                                parameters,
                                instruments,
                                PERIOD,
                                new BigDecimal("10000000.00"),
                                PositionSizingMode.STRATEGY_DEFINED,
                                new BigDecimal("0.5000"),
                                new InstrumentId("069500.XKRX"),
                                42));
        entityManager.flush();
        entityManager.clear();

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findById(saved.getId()))
                .get()
                .satisfies(
                        job -> {
                            assertThat(job.getBacktestId()).isEqualTo(saved.getBacktestId());
                            assertThat(job.getStrategyId()).isEqualTo("MA_CROSSOVER");
                            assertThat(job.getStrategyVersion()).isEqualTo("1.0.0");
                            assertThat(job.getParameters()).isEqualTo(parameters);
                            assertThat(job.getInstrumentIds()).isEqualTo(instruments);
                            assertThat(job.getPeriod()).isEqualTo(PERIOD);
                            assertThat(job.getInitialCash())
                                    .isEqualByComparingTo(new BigDecimal("10000000.00"));
                            assertThat(job.getPositionSizingType())
                                    .isEqualTo(PositionSizingMode.STRATEGY_DEFINED);
                            assertThat(job.getMaxPositionPct())
                                    .isEqualByComparingTo(new BigDecimal("0.5000"));
                            assertThat(job.getBenchmarkInstrumentId())
                                    .isEqualTo(new InstrumentId("069500.XKRX"));
                            assertThat(job.getRandomSeed()).isEqualTo(42);
                            assertThat(job.getStatus()).isEqualTo(BacktestStatus.QUEUED);
                        });
    }

    /** 애플리케이션 스키마(public)의 컬럼만 센다. 시스템 카탈로그에 같은 이름의 테이블이 있어도 섞이지 않는다. */
    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT LOWER(column_name) FROM information_schema.columns"
                        + " WHERE LOWER(table_schema) = 'public' AND LOWER(table_name) = ?",
                String.class,
                table);
    }
}
