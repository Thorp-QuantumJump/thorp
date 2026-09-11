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
    @DisplayName("V4가 적용되어 flyway_schema_history에 성공으로 기록된다")
    void v4IsApplied() {
        Integer applied =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history"
                                + " WHERE version = '4' AND success = TRUE",
                        Integer.class);

        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("V4가 뉴스 기사 테이블을 만들고 본문 컬럼을 두지 않는다")
    void v4CreatesNewsArticleWithoutBodyColumns() {
        assertThat(columnsOf("news_article"))
                .containsExactlyInAnyOrder(
                        "id",
                        "article_id",
                        "source_type",
                        "publisher_id",
                        "source_item_id",
                        "original_url",
                        "canonical_url",
                        "title",
                        "language",
                        "published_at",
                        "published_at_status",
                        "collected_at",
                        "gdelt_recorded_at",
                        "duplicate_key",
                        "source_payload_hash",
                        "ingest_run_id",
                        "display_eligible",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V4의 어떤 뉴스 테이블에도 본문·Tone 컬럼이 없다 (spec FR-002·SC-001)")
    void v4HasNoBodyOrToneColumnAnywhere() {
        List<String> forbidden =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(table_name) || '.' || LOWER(column_name)"
                                + " FROM information_schema.columns"
                                + " WHERE LOWER(table_schema) = 'public'"
                                + " AND LOWER(table_name) LIKE 'news_%'"
                                + " AND (LOWER(column_name) LIKE '%body%'"
                                + " OR LOWER(column_name) LIKE '%content%'"
                                + " OR LOWER(column_name) LIKE '%full_text%'"
                                + " OR LOWER(column_name) LIKE '%html%'"
                                + " OR LOWER(column_name) LIKE '%tone%')",
                        String.class);

        assertThat(forbidden).isEmpty();
    }

    @Test
    @DisplayName("V4가 매체의 등록 도메인과 현재 허가 상태를 둔다 (spec FR-009·FR-015)")
    void v4CreatesPublisherColumns() {
        assertThat(columnsOf("news_publisher"))
                .containsExactlyInAnyOrder(
                        "id",
                        "publisher_key",
                        "display_name",
                        "language",
                        "registered_domain",
                        "rss_url",
                        "permission_status",
                        "permission_updated_at",
                        "enabled",
                        "etag",
                        "last_modified",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V4가 허가 이력을 별도 테이블로 둔다 (spec FR-015)")
    void v4CreatesPermissionHistory() {
        assertThat(columnsOf("news_publisher_permission_history"))
                .containsExactlyInAnyOrder(
                        "id",
                        "publisher_id",
                        "event_type",
                        "permission_status",
                        "allowed_fields",
                        "occurred_at",
                        "reference",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V4가 수집 상태에 INCOMPLETE와 점유 컬럼을 둔다 (spec FR-013·FR-022)")
    void v4CreatesCollectionState() {
        assertThat(columnsOf("news_collection_state"))
                .containsExactlyInAnyOrder(
                        "source_key",
                        "status",
                        "cursor_value",
                        "last_attempt_at",
                        "last_success_at",
                        "next_attempt_at",
                        "retry_count",
                        "error_category",
                        "lease_owner",
                        "lease_until",
                        "created_at",
                        "updated_at");

        Integer incompleteAllowed =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.check_constraints"
                                + " WHERE LOWER(constraint_name) = 'ck_news_collection_state_status'"
                                + " AND check_clause LIKE '%INCOMPLETE%'",
                        Integer.class);
        assertThat(incompleteAllowed).isEqualTo(1);
    }

    @Test
    @DisplayName("V4가 정리 작업을 수집 뉴스원과 같은 점유 테이블에 seed한다 (spec FR-022)")
    void v4SeedsRetentionCleanupLease() {
        Integer seeded =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_collection_state"
                                + " WHERE source_key = 'retention-cleanup'",
                        Integer.class);

        assertThat(seeded).isEqualTo(1);
    }

    @Test
    @DisplayName("V4가 규칙 버전 네 개를 seed한다 (원칙 VIII)")
    void v4SeedsRuleVersions() {
        List<String> versions =
                jdbcTemplate.queryForList(
                        "SELECT rule_version FROM news_rule_version ORDER BY rule_version",
                        String.class);

        assertThat(versions)
                .containsExactly(
                        "news-grouping-v1",
                        "news-importance-v1",
                        "news-link-v1",
                        "news-url-normalization-v1");
    }

    @Test
    @DisplayName("V4가 canonical URL과 외부 식별자에 UNIQUE를 건다 (spec FR-011·FR-025)")
    void v4CreatesUniqueKeys() {
        List<String> uniques =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(constraint_name) FROM information_schema.table_constraints"
                                + " WHERE constraint_type = 'UNIQUE'"
                                + " AND LOWER(table_name) LIKE 'news_%'",
                        String.class);

        assertThat(uniques)
                .contains(
                        "uk_news_article_canonical_url",
                        "uk_news_article_article_id",
                        "uk_news_digest_digest_id",
                        "uk_news_publisher_key");
    }

    @Test
    @DisplayName("V4는 섹터 마스터 테이블을 만들지 않는다 (spec FR-005)")
    void v4DoesNotOwnSectorIdentifiers() {
        List<String> sectorTables =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(table_name) FROM information_schema.tables"
                                + " WHERE LOWER(table_schema) = 'public'"
                                + " AND LOWER(table_name) IN"
                                + " ('news_sector', 'sector', 'sectors')",
                        String.class);

        // 이 단언의 취지는 "뉴스(V4)가 섹터 마스터를 따로 만들지 않는다"이다. spec 001이
        // sector를 소유하게 되었으므로 sector가 있는 것은 정상이고, 뉴스가 만들었을
        // news_sector·sectors가 없어야 한다.
        assertThat(sectorTables)
                .as("뉴스는 섹터 마스터를 따로 만들지 않는다")
                .doesNotContain("news_sector", "sectors");
        assertThat(sectorTables).as("섹터 식별자는 spec 001이 소유한다").contains("sector");
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
