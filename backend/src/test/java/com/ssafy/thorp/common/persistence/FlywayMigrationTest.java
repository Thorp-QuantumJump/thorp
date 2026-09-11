package com.ssafy.thorp.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestJobRepositoryImpl;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.support.MigrationFiles;
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
 *
 * <p><b>단언에 버전 번호를 적지 않는다.</b> 적용 여부는 {@link MigrationFiles}가 읽어온 클래스패스의 파일 목록과 대조해서 확인하고, 구조 단언은
 * 테이블 이름으로만 건다. 예전에는 {@code WHERE version = '4'}처럼 번호를 박고 있어서 버전을 하나 바꿀 때마다(V3 중복을 풀며 뉴스 마이그레이션을
 * V4로 개명한 일이 그랬다) 테스트까지 함께 고쳐야 했다. 뒤에 버전 체계를 timestamp로 바꾸더라도 이 파일은 그대로 둔다.
 *
 * <p>파일명 규칙과 버전 중복은 {@code MigrationFileConventionTest}가 Docker 없이 따로 검사한다. 이 테스트는 Docker가 없으면 통째로
 * 스킵되기 때문이다.
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
    @DisplayName("클래스패스의 모든 마이그레이션이 빈 DB에 적용되고 성공으로 기록된다")
    void allMigrationsAreAppliedToEmptyDatabase() {
        // BASELINE 행은 실제로 실행한 마이그레이션이 아니라 기존 스키마를 인정한 기록이고,
        // version이 비어 있는 행은 반복 실행(R__) 마이그레이션이다. 둘 다 파일 목록과 대조할 대상이 아니다.
        List<String> appliedVersions =
                jdbcTemplate.queryForList(
                        "SELECT version FROM flyway_schema_history"
                                + " WHERE success = TRUE AND version IS NOT NULL"
                                + " AND type <> 'BASELINE'",
                        String.class);

        assertThat(appliedVersions)
                .as("적용된 버전이 classpath:db/migration 의 파일 목록과 다르다")
                .containsExactlyInAnyOrderElementsOf(MigrationFiles.versions());
    }

    @Test
    @DisplayName("실패로 기록된 마이그레이션이 없다")
    void noMigrationIsRecordedAsFailed() {
        List<String> failed =
                jdbcTemplate.queryForList(
                        "SELECT script FROM flyway_schema_history WHERE success = FALSE",
                        String.class);

        assertThat(failed).as("실패한 마이그레이션이 남아 있으면 다음 배포가 repair 없이는 진행되지 않는다").isEmpty();
    }

    @Test
    @DisplayName("backtest_job이 Entity 매핑과 같은 컬럼을 가진다")
    void backtestJobHasColumnsMatchingEntity() {
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
    @DisplayName("backtest_job PK용 시퀀스가 있다")
    void backtestJobSequenceExists() {
        Integer sequences =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.sequences"
                                + " WHERE LOWER(sequence_name) = 'backtest_job_seq'",
                        Integer.class);

        assertThat(sequences).isEqualTo(1);
    }

    @Test
    @DisplayName("회원·이메일 인증 테이블이 Entity 매핑과 같은 컬럼을 가진다")
    void accountTablesHaveColumnsMatchingEntity() {
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
    @DisplayName("backtest_result가 Entity 매핑과 같은 컬럼을 가진다")
    void backtestResultHasColumnsMatchingEntity() {
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
    @DisplayName("backtest_trade가 Entity 매핑과 같은 컬럼과 PK용 시퀀스를 가진다")
    void backtestTradeHasColumnsAndSequenceMatchingEntity() {
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
    @DisplayName("결과·거래 테이블에 backtest_job FK와 거래 UNIQUE 제약이 있다")
    void resultAndTradeHaveJobForeignKeysAndTradeUnique() {
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
    @DisplayName("뉴스 기사 테이블에 본문 컬럼이 없다")
    void newsArticleHasNoBodyColumns() {
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
    @DisplayName("어떤 뉴스 테이블에도 본문·Tone 컬럼이 없다 (spec FR-002·SC-001)")
    void noNewsTableHasBodyOrToneColumn() {
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
    @DisplayName("매체 테이블에 등록 도메인과 현재 허가 상태가 있다 (spec FR-009·FR-015)")
    void newsPublisherHasRegisteredDomainAndPermission() {
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
    @DisplayName("허가 이력이 별도 테이블로 있다 (spec FR-015)")
    void newsPermissionHistoryIsSeparateTable() {
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
    @DisplayName("수집 상태에 INCOMPLETE와 점유 컬럼이 있다 (spec FR-013·FR-022)")
    void newsCollectionStateAllowsIncompleteAndLease() {
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
    @DisplayName("정리 작업이 수집 뉴스원과 같은 점유 테이블에 seed된다 (spec FR-022)")
    void retentionCleanupLeaseIsSeeded() {
        Integer seeded =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM news_collection_state"
                                + " WHERE source_key = 'retention-cleanup'",
                        Integer.class);

        assertThat(seeded).isEqualTo(1);
    }

    @Test
    @DisplayName("규칙 버전 네 개가 seed된다 (원칙 VIII)")
    void ruleVersionsAreSeeded() {
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
    @DisplayName("canonical URL과 외부 식별자에 UNIQUE가 걸려 있다 (spec FR-011·FR-025)")
    void newsUniqueKeysExist() {
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
    @DisplayName("섹터 마스터 테이블을 만들지 않는다 (spec FR-005)")
    void sectorMasterTableIsNotCreated() {
        List<String> sectorTables =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(table_name) FROM information_schema.tables"
                                + " WHERE LOWER(table_schema) = 'public'"
                                + " AND LOWER(table_name) IN ('news_sector', 'sector', 'sectors')",
                        String.class);

        assertThat(sectorTables).as("섹터 식별자는 spec 001이 소유한다").isEmpty();
    }

    @Test
    @DisplayName("마이그레이션이 만든 스키마에 Entity를 저장하고 다시 읽을 수 있다")
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
