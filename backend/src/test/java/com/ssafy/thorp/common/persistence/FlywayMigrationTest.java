package com.ssafy.thorp.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.persistence.BacktestRunRepositoryImpl;
import com.ssafy.thorp.support.MigrationFiles;
import com.ssafy.thorp.support.PostgresTestSupport;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 운영(prod)과 같은 조건으로 마이그레이션을 검증한다. Flyway·validate 설정과 PostgreSQL 컨테이너는 {@link PostgresTestSupport}가
 * 제공하므로, 마이그레이션과 Entity 매핑이 어긋나면 컨텍스트 로딩 자체가 실패한다.
 *
 * <p>여기서 확인하는 것은 컬럼과 시퀀스의 존재다. 타입 불일치는 Hibernate가 경고로만 남기고 통과시키기 때문에, 컬럼 목록을 눈으로 맞춰 두는 이 단언이 필요하다.
 *
 * <p><b>적용 여부 단언에는 버전 번호를 적지 않는다.</b> {@link MigrationFiles}가 읽어온 클래스패스의 파일 목록과 대조한다. 예전에는 {@code
 * WHERE version = '4'}처럼 번호를 박고 있어서 버전을 하나 바꿀 때마다(V3 중복을 풀며 뉴스 마이그레이션을 V4로 개명한 일이 그랬다) 테스트까지 함께 고쳐야
 * 했다. 신규 마이그레이션이 타임스탬프 버전을 쓰게 된 뒤로는 14자리 숫자를 손으로 옮겨 적는 비용이 더 크다. 아래 단언은 파일이 몇 개든 그대로 둔다.
 *
 * <p>파일명 규칙과 버전 중복은 {@code MigrationFileConventionTest}가 Docker 없이 따로 검사한다. 이 테스트는 Docker가 없으면 통째로
 * 스킵되기 때문이다.
 */
class FlywayMigrationTest extends RepositoryTestSupport {

    private static final BacktestPeriod PERIOD =
            new BacktestPeriod(LocalDate.of(2019, 1, 1), LocalDate.of(2023, 12, 31));

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private BacktestRunRepositoryImpl repository;
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
    @DisplayName("backtest_run이 요청 원문과 실행 기록 컬럼을 모두 갖는다 (V1 + T003 개명·확장)")
    void backtestRunHasAllColumns() {
        assertThat(columnsOf("backtest_run"))
                .containsExactlyInAnyOrder(
                        // V1에서 온 요청 원문. T005가 request_spec 하나로 접는다
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
                        "updated_at",
                        // 실행 식별·재사용 (R-02·R-03). 접수 경로(T005)가 채울 때까지 nullable
                        "dedup_key",
                        "execution_type",
                        "rules_version",
                        "cost_profile_version",
                        "data_version",
                        "request_spec",
                        "versions",
                        "effective_start",
                        "effective_end",
                        // 진행 정도 (R-05)
                        "progress_stage",
                        "progress_last_trading_date",
                        "progress_completed_days",
                        "progress_total_days",
                        // 실행 시도·종결 (R-06·R-18)
                        "attempt",
                        "current_execution_id",
                        "partial",
                        "failure_code",
                        "failure_message",
                        "cancel_requested_at",
                        // 발행 보장 (R-18)
                        "published_at",
                        "publish_count",
                        "dlq_count",
                        // 측정 원천 (R-10)
                        "queued_at",
                        "started_at",
                        "finished_at",
                        // 정체 판정 기준과 취소 사유 (T014, R-19)
                        "last_event_at",
                        "cancel_reason",
                        // 어느 큐·어느 워커가 집었나 (T012, R-20). 측정 기록으로 복사된다
                        "served_queue",
                        "worker_role");
    }

    /**
     * 아직 엔티티가 매핑하지 않는 컬럼도 스키마에는 있어야 한다.
     *
     * <p>{@code ddl-auto=validate}는 <b>매핑된</b> 컬럼만 본다. T005·T010·T011·T012가 쓸 컬럼을 지금 지워도 컨텍스트는 그대로
     * 뜨므로, 그 누락을 잡아 줄 단언이 여기 말고는 없다.
     */
    @Test
    @DisplayName("아직 매핑하지 않은 실행 컬럼도 스키마에는 있다 (T005 이후가 쓴다)")
    void unmappedRunColumnsExistForLaterTasks() {
        assertThat(columnsOf("backtest_run"))
                .contains(
                        "dedup_key",
                        "request_spec",
                        "versions",
                        "queued_at",
                        "published_at",
                        "publish_count",
                        "dlq_count",
                        "cancel_requested_at",
                        // T014: 정체 판정 기준과 취소 사유(R-19)
                        "last_event_at",
                        "cancel_reason",
                        // T012: 매핑하지 않고 native 로만 쓰는 두 컬럼(R-20)
                        "served_queue",
                        "worker_role");
    }

    @Test
    @DisplayName("backtest_request가 요청/실행 분리에 필요한 컬럼을 갖는다 (R-02·R-04)")
    void backtestRequestHasAllColumns() {
        assertThat(columnsOf("backtest_request"))
                .containsExactlyInAnyOrder(
                        "id",
                        "backtest_id",
                        "run_id",
                        "requester_id",
                        "execution_type",
                        "result_source",
                        "retry_of_request_id",
                        "cancelled_at",
                        "cancelled_last_trading_date",
                        "stopped_run",
                        "public_token",
                        "public_token_expires_at",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName(
            "backtest_request 가 비로그인 요청과 공개 토큰을 받는다 — 요청자 nullable·토큰 유일·익명 만료 필수 (spec 005 B01)")
    void backtestRequestAcceptsAnonymousRequestsWithPublicToken() {
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT is_nullable FROM information_schema.columns"
                                        + " WHERE table_name = 'backtest_request'"
                                        + " AND column_name = ?",
                                String.class,
                                "requester_id"))
                .isEqualTo("YES");
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT is_nullable FROM information_schema.columns"
                                        + " WHERE table_name = 'backtest_request'"
                                        + " AND column_name = ?",
                                String.class,
                                "public_token"))
                .isEqualTo("NO");

        List<String> constraints =
                jdbcTemplate.queryForList(
                        "SELECT conname FROM pg_constraint"
                                + " WHERE conrelid = 'backtest_request'::regclass",
                        String.class);
        assertThat(constraints)
                .contains(
                        "ck_backtest_request_public_expiry",
                        "ck_backtest_request_public_token_format");

        List<String> indexes =
                jdbcTemplate.queryForList(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'backtest_request'",
                        String.class);
        assertThat(indexes)
                .contains("uk_backtest_request_public_token", "idx_backtest_request_public_expiry");
    }

    @Test
    @DisplayName("backtest_reuse_event 가 재사용·합류 기록에 필요한 컬럼만 갖는다 (FR-008)")
    void backtestReuseEventHasAllColumns() {
        // append-only 라 updated_at 이 없다. 발생 시각은 at 하나다.
        assertThat(columnsOf("backtest_reuse_event"))
                .containsExactlyInAnyOrder(
                        "id", "run_id", "request_id", "kind", "request_execution_type", "at");
    }

    @Test
    @DisplayName("backtest_run_dlq_attempt 가 DLQ 멱등 판정에 필요한 컬럼만 갖는다 (R-18 ③)")
    void backtestRunDlqAttemptHasAllColumns() {
        assertThat(columnsOf("backtest_run_dlq_attempt"))
                .containsExactlyInAnyOrder("run_id", "attempt", "at");
    }

    @Test
    @DisplayName("backtest_run_metric 이 종결 측정에 필요한 컬럼을 갖고 run_id 가 유일하다 (R-10, T012)")
    void backtestRunMetricHasAllColumns() {
        assertThat(columnsOf("backtest_run_metric"))
                .containsExactlyInAnyOrder(
                        "id",
                        "run_id",
                        "execution_type",
                        "status",
                        "failure_code",
                        "queued_at",
                        "started_at",
                        "finished_at",
                        "wait_ms",
                        "run_ms",
                        "concurrent_running",
                        "peak_rss_mb",
                        "cpu_seconds",
                        "auto_retried",
                        "result_bytes",
                        // 데이터 원가 (FR-008, T019)
                        "data_rows",
                        "data_bytes",
                        // 어느 큐·어느 워커가 집었나 (R-20)
                        "served_queue",
                        "worker_role",
                        "recorded_at");
        // 종결은 한 번이다 — 늦은 중복 종결은 DB 가 막는다.
        Integer unique =
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE tablename = 'backtest_run_metric'"
                                + " AND indexname = 'uk_backtest_run_metric_run'",
                        Integer.class);
        assertThat(unique).isEqualTo(1);
    }

    @Test
    @DisplayName("backtest_limit_hit 가 접수 거절·실행 정지 둘을 한 표에 담는다 (FR-006, R-10 ②)")
    void backtestLimitHitHasAllColumns() {
        assertThat(columnsOf("backtest_limit_hit"))
                .containsExactlyInAnyOrder(
                        "id", "kind", "execution_type", "reason", "requester_id", "run_id", "at");
    }

    @Test
    @DisplayName("v_run_metric_summary 뷰가 있고 PD-06·R-20 결정에 필요한 열을 낸다")
    void runMetricSummaryViewExists() {
        assertThat(columnsOf("v_run_metric_summary"))
                .containsExactlyInAnyOrder(
                        "execution_type",
                        "count",
                        "fail_rate",
                        "cancel_rate",
                        "auto_retry_rate",
                        "wait_p50_ms",
                        "wait_p95_ms",
                        "wait_p99_ms",
                        "run_p50_ms",
                        "run_p95_ms",
                        "peak_rss_p95_mb",
                        "flex_served_rate",
                        "reuse_count",
                        "limit_hit_count",
                        // 데이터 원가 중앙값 (T019) — 뷰는 끝에만 열을 더할 수 있다
                        "data_rows_p50",
                        "data_bytes_p50");
    }

    @Test
    @DisplayName("등록표 두 개가 시드 행과 함께 만들어진다 (R-07·R-08)")
    void registriesAreSeeded() {
        assertThat(columnsOf("cost_profile"))
                .containsExactlyInAnyOrder(
                        "version",
                        "commission_bps",
                        "spread_bps",
                        "slippage_bps",
                        "tax_bps",
                        "escalation",
                        "provisional",
                        "note",
                        "created_at");
        assertThat(columnsOf("strategy_rules"))
                .containsExactlyInAnyOrder(
                        "strategy_id",
                        "rules_version",
                        "status",
                        "provisional",
                        "spec",
                        "decision_ref",
                        "created_at");

        // 데모 비용은 임시값으로 표시돼 있어야 한다. false로 들어가면 결과가 확정 비용인 척한다.
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT provisional FROM cost_profile"
                                        + " WHERE version = 'PROVISIONAL-DEMO-1'",
                                Boolean.class))
                .isTrue();

        // PD 미확정 전략은 명세 없이 PENDING_DECISION으로만 있어야 한다
        // (constitution "실행 전 결정 임의 확정 금지"). PD-04가 확정돼 최소분산은 빠졌다(T022).
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT strategy_id FROM strategy_rules"
                                        + " WHERE status = 'PENDING_DECISION' AND spec IS NULL",
                                String.class))
                .containsExactly("SECTOR_MOMENTUM");

        // 역변동성은 등록하되 PD-02 대기라 임시 표시가 붙어야 한다(analyze C1).
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT provisional FROM strategy_rules"
                                        + " WHERE strategy_id = 'INVERSE_VOLATILITY'",
                                Boolean.class))
                .isTrue();
    }

    @Test
    @DisplayName("공통 출력 규칙 두 키가 상한을 쓰는 전략 모두에 있다 — 한 전략만 빠지면 그 전략이 다른 규칙으로 돈다")
    void commonOutputKeysAreOnEveryStrategyThatUsesACap() {
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT strategy_id FROM strategy_rules"
                                        + " WHERE spec -> 'weightCap' IS NOT NULL"
                                        + " AND (spec -> 'capOverflow' IS NULL"
                                        + "      OR spec -> 'weightUnit' IS NULL)",
                                String.class))
                .as("상한이 있는데 잔여 정책·출력 단위가 없는 규칙 행")
                .isEmpty();

        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT spec ->> 'estimationMethod' FROM strategy_rules"
                                        + " WHERE strategy_id = 'MINIMUM_VARIANCE'"
                                        + " AND status = 'REGISTERED' AND provisional = false",
                                String.class))
                .isEqualTo("SAMPLE_COV");
    }

    @Test
    @DisplayName("가드: 옛 의미로 계산한 INVERSE_VOLATILITY/1 결과가 남아 있으면 마이그레이션이 기동을 막는다")
    void theGuardRefusesToRunWhenOldResultsWouldBeReinterpreted() {
        // 재현 키에 rulesVersion이 들어가므로 옛 결과가 한 건이라도 남으면 새 의미의 요청이
        // 옛 의미의 결과를 REUSED로 받는다 — 오류도 표시도 없이. 그래서 기동을 막는다.
        repository.save(
                BacktestRun.create(
                        "INVERSE_VOLATILITY",
                        "1",
                        Map.of("rebalance", "MONTH_END"),
                        List.of(new InstrumentId("069500.XKRX")),
                        PERIOD,
                        new BigDecimal("10000000.00"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42));
        entityManager.flush();
        jdbcTemplate.update(
                "UPDATE backtest_run SET rules_version = '1'"
                        + " WHERE strategy_id = 'INVERSE_VOLATILITY'");

        assertThatThrownBy(() -> jdbcTemplate.execute(guardStatement()))
                .hasMessageContaining("INVERSE_VOLATILITY/1 결과가 남아 있다");
    }

    /** 마이그레이션 파일의 가드 블록 그대로. 문장을 여기 옮겨 적으면 파일이 바뀌어도 테스트만 통과한다. */
    private static String guardStatement() {
        String sql = MigrationFiles.read("V20260917190000__minimum_variance_rules.sql");
        int begin = sql.indexOf("DO $$");
        int end = sql.indexOf("$$;", begin) + "$$;".length();
        return sql.substring(begin, end);
    }

    @Test
    @DisplayName("실행 PK용 시퀀스가 개명된 이름으로 있다")
    void backtestRunSequenceExists() {
        Integer sequences =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.sequences"
                                + " WHERE LOWER(sequence_name) = 'backtest_run_seq'",
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
    @DisplayName("Spring Session 테이블이 공식 JDBC 스키마와 같은 컬럼을 갖는다")
    void springSessionTablesHaveRequiredColumns() {
        assertThat(columnsOf("spring_session"))
                .containsExactlyInAnyOrder(
                        "primary_id",
                        "session_id",
                        "creation_time",
                        "last_access_time",
                        "max_inactive_interval",
                        "expiry_time",
                        "principal_name");
        assertThat(columnsOf("spring_session_attributes"))
                .containsExactlyInAnyOrder(
                        "session_primary_id", "attribute_name", "attribute_bytes");
    }

    @Test
    @DisplayName("Spring Session 조회 인덱스와 속성 삭제용 외래 키가 생성된다")
    void springSessionIndexesAndForeignKeyExist() {
        List<String> indexes =
                jdbcTemplate.queryForList(
                        "SELECT LOWER(indexname) FROM pg_indexes"
                                + " WHERE schemaname = 'public'"
                                + " AND LOWER(tablename) = 'spring_session'",
                        String.class);
        assertThat(indexes)
                .contains("spring_session_ix1", "spring_session_ix2", "spring_session_ix3");

        Integer foreignKeys =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.table_constraints"
                                + " WHERE constraint_type = 'FOREIGN KEY'"
                                + " AND LOWER(table_name) = 'spring_session_attributes'"
                                + " AND LOWER(constraint_name) = 'spring_session_attributes_fk'",
                        Integer.class);
        assertThat(foreignKeys).isEqualTo(1);
    }

    @Test
    @DisplayName("V2가 backtest_result를 Entity 매핑과 같은 컬럼으로 만든다")
    void v2CreatesBacktestResultColumns() {
        assertThat(columnsOf("backtest_result"))
                .containsExactlyInAnyOrder(
                        "run_id",
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
                        // T003이 더한 v2 산출 항목(R-11). 저장·조회는 T009가 붙인다
                        "weights_series",
                        "weights_source",
                        "costs",
                        "liquidation",
                        "analytics",
                        "exceptions",
                        "versions",
                        "created_at",
                        "updated_at");
    }

    @Test
    @DisplayName("V2가 backtest_trade를 Entity 매핑과 같은 컬럼으로 만들고 PK용 시퀀스를 둔다")
    void v2CreatesBacktestTradeColumnsAndSequence() {
        assertThat(columnsOf("backtest_trade"))
                .containsExactlyInAnyOrder(
                        "id",
                        "run_id",
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
    @DisplayName("결과·거래 테이블이 실행을 FK로 가리키고 거래 UNIQUE 제약이 있다")
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
                                + " WHERE LOWER(constraint_name) = 'uk_backtest_trade_run_trade'",
                        Integer.class);
        assertThat(unique).isEqualTo(1);
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
    @DisplayName("규칙 종류마다 지금 유효한 버전이 정확히 하나다 (원칙 VIII)")
    void ruleVersionsAreSeededPerType() {
        // 개수를 세지 않는다. 규칙은 덮어쓰지 않고 새 버전을 더하므로 행 수는 계속 늘어난다.
        // 지켜야 할 것은 "종류마다 지금 쓰는 규칙이 하나"라는 것이다 — 둘이면 어느 것으로 판정했는지
        // 알 수 없고, 없으면 그 단계가 규칙 없이 돈다.
        List<String> effective =
                jdbcTemplate.queryForList(
                        "SELECT rule_type FROM news_rule_version WHERE retired_at IS NULL"
                                + " ORDER BY rule_type",
                        String.class);

        assertThat(effective)
                .containsExactlyInAnyOrder("GROUPING", "IMPORTANCE", "LINK", "URL_NORMALIZATION");

        // 물러난 버전도 남아 있어야 그때의 판정을 재현할 수 있다.
        assertThat(
                        jdbcTemplate.queryForList(
                                "SELECT rule_version FROM news_rule_version"
                                        + " WHERE retired_at IS NOT NULL",
                                String.class))
                .as("규칙을 바꿀 때 옛 행을 지우면 그 규칙으로 만든 묶음의 근거가 사라진다")
                .contains("news-grouping-v1");
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

        BacktestRun saved =
                repository.save(
                        BacktestRun.create(
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
                            assertThat(job.getStatus()).isEqualTo(RunStatus.QUEUED);
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
