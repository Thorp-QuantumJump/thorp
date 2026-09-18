package com.ssafy.thorp.regime.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.support.PostgresTestSupport;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 경제환경 검색 스키마와 Entity 매핑이 어긋나지 않는지 운영과 같은 조건으로 본다 (tasks.md T106).
 *
 * <p>Flyway + {@code ddl-auto=validate}는 {@link PostgresTestSupport}가 준다 — 매핑이 어긋나면 <b>컨텍스트 로딩부터
 * 실패</b>한다. 로컬 프로필은 {@code ddl-auto=update} + Flyway 비활성이라 이 불일치를 잡지 못한다.
 *
 * <p>이 표들의 값을 넣는 것은 {@code data/} 배치다. 그래서 여기서는 <b>제약이 실제로 막는지</b>를 확인한다 — 배치가 SQLAlchemy로 들고 있는
 * 정의와 이 스키마가 갈리면, 적재는 운영에서만 깨지고 그때 원인은 이 MR이 아니라 배치 쪽으로 보인다.
 */
@DisplayName("경제환경 검색 마이그레이션")
class RegimeMigrationTest extends RepositoryTestSupport {

    @Autowired private JdbcTemplate jdbc;

    private static final String INSERT_SNAPSHOT =
            """
            INSERT INTO regime_search_snapshot (
                snapshot_key, mode, field_id, baseline_month, revision, content_hash,
                data_version, registry_version, model_version, status, status_reason,
                scale, comparison_months, ranked_candidates, candidate_counts, data_asof,
                computed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?::date, ?, ?, ?, ?, ?, ?, ?,
                '{}'::jsonb, '{}'::jsonb, '[]'::jsonb, '{}'::jsonb, '{}'::jsonb,
                now(), now(), now())
            """;

    private long insertSnapshot(
            String key, String mode, String fieldId, String month, int revision, String hash) {
        jdbc.update(
                INSERT_SNAPSHOT,
                key,
                mode,
                fieldId,
                month,
                revision,
                hash,
                "FIXTURE-1",
                "FIXTURE-REG-1",
                "REGIME-V1",
                "NEAREST_ONLY",
                "THRESHOLD_NOT_CALIBRATED");
        return jdbc.queryForObject(
                "SELECT id FROM regime_search_snapshot WHERE snapshot_key = ?", Long.class, key);
    }

    @Test
    @DisplayName("표 넷이 마이그레이션으로 만들어진다")
    void createsFourTables() {
        List<String> tables =
                jdbc.queryForList(
                        """
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name LIKE 'regime%'
                        ORDER BY table_name
                        """,
                        String.class);
        assertThat(tables)
                .containsExactly(
                        "regime_baseline_month",
                        "regime_search_case",
                        "regime_search_log",
                        "regime_search_snapshot");
    }

    @Test
    @DisplayName("사전 계산 표에 사용자 식별 컬럼이 없다")
    void precomputedTablesHaveNoUserColumn() {
        // 계산 결과는 여러 사용자가 공유하는 자원이다. 여기에 사용자 컬럼이 생기면 기록 삭제·탈퇴가
        // 남의 검색까지 지우는 경로가 열린다(spec FR-036, SC-013).
        List<String> columns =
                jdbc.queryForList(
                        """
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name IN ('regime_search_snapshot', 'regime_search_case',
                                             'regime_baseline_month')
                          AND (column_name LIKE '%user%' OR column_name LIKE '%owner%')
                        """,
                        String.class);
        assertThat(columns).isEmpty();
    }

    @Test
    @DisplayName("사용자 식별자는 기록 표에만 있다")
    void onlyLogCarriesUserId() {
        List<String> columns =
                jdbc.queryForList(
                        """
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'regime_search_log'
                          AND column_name = 'owner_user_id'
                        """,
                        String.class);
        assertThat(columns).containsExactly("owner_user_id");
    }

    @Test
    @DisplayName("공통 scale은 NOT NULL, 사례의 era_position은 nullable이다")
    void scaleIsRequiredAndEraPositionIsOptional() {
        assertThat(nullable("regime_search_snapshot", "scale")).isEqualTo("NO");
        // 시대별 보조 설명을 하나도 만들지 못한 사례가 있다. 0으로 채우지 않으려면 NULL 이어야 한다.
        assertThat(nullable("regime_search_case", "era_position")).isEqualTo("YES");
    }

    @Test
    @DisplayName("같은 (모드·분야·기준월)에 같은 revision이 두 번 들어가지 않는다")
    void revisionIsUniquePerScope() {
        insertSnapshot(
                "FIELD:LABOR:2020-01-01:D:R:M", "FIELD", "LABOR", "2020-01-01", 1, "a".repeat(64));
        assertThatThrownBy(
                        () ->
                                insertSnapshot(
                                        "FIELD:LABOR:2020-01-01:D2:R:M",
                                        "FIELD",
                                        "LABOR",
                                        "2020-01-01",
                                        1,
                                        "b".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("종합 행도 revision 중복이 막힌다")
    void compositeRevisionIsUniqueEvenWithNullFieldId() {
        // field_id 가 NULL 이라 일반 UNIQUE 로는 묶이지 않는다(NULL 은 서로 구별된다).
        // COALESCE 표현식 인덱스가 없으면 이 시험이 통과해 버린다.
        insertSnapshot(
                "COMPOSITE:ALL:2020-02-01:D:R:M",
                "COMPOSITE",
                null,
                "2020-02-01",
                1,
                "c".repeat(64));
        assertThatThrownBy(
                        () ->
                                insertSnapshot(
                                        "COMPOSITE:ALL:2020-02-01:D2:R:M",
                                        "COMPOSITE",
                                        null,
                                        "2020-02-01",
                                        1,
                                        "d".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 내용이 두 번 저장되지 않는다")
    void contentIsUniquePerScope() {
        String hash = "e".repeat(64);
        insertSnapshot("FIELD:LABOR:2021-01-01:D:R:M", "FIELD", "LABOR", "2021-01-01", 1, hash);
        assertThatThrownBy(
                        () ->
                                insertSnapshot(
                                        "FIELD:LABOR:2021-01-01:D2:R:M",
                                        "FIELD",
                                        "LABOR",
                                        "2021-01-01",
                                        2,
                                        hash))
                .as("내용이 같은 항목이 둘 이상 저장되면 SC-002 가 깨진다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 제약 위반은 트랜잭션을 중단시킨다(SQLSTATE 25P02). 한 시험에 둘을 넣으면 뒤엣것이
    // 제약이 아니라 "이미 중단된 트랜잭션"에 걸려, 막고 싶었던 것이 정말 막히는지 알 수 없다.

    @Test
    @DisplayName("종합에 분야 id를 넣으면 막는다")
    void compositeMustNotCarryFieldId() {
        assertThatThrownBy(
                        () ->
                                insertSnapshot(
                                        "COMPOSITE:ALL:2022-01-01:D:R:M",
                                        "COMPOSITE",
                                        "LABOR",
                                        "2022-01-01",
                                        1,
                                        "f".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("분야 검색에 분야 id가 없으면 막는다")
    void fieldSearchRequiresFieldId() {
        assertThatThrownBy(
                        () ->
                                insertSnapshot(
                                        "FIELD:none:2022-02-01:D:R:M",
                                        "FIELD",
                                        null,
                                        "2022-02-01",
                                        1,
                                        "g".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("계산 불가에는 사유가 반드시 있다")
    void notComputableRequiresReason() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        INSERT_SNAPSHOT,
                                        "FIELD:LABOR:2023-01-01:D:R:M",
                                        "FIELD",
                                        "LABOR",
                                        "2023-01-01",
                                        1,
                                        "h".repeat(64),
                                        "FIXTURE-1",
                                        "FIXTURE-REG-1",
                                        "REGIME-V1",
                                        "NOT_COMPUTABLE",
                                        null))
                .as("사유 없는 계산 불가는 화면이 '결과 없음'과 구분할 수 없다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("한 snapshot에 같은 종료월 사례가 두 번 들어가지 않는다")
    void caseMonthIsUniquePerSnapshot() {
        long snapshotId =
                insertSnapshot(
                        "FIELD:LABOR:2024-01-01:D:R:M",
                        "FIELD",
                        "LABOR",
                        "2024-01-01",
                        1,
                        "i".repeat(64));
        insertCase(snapshotId, 1, "2012-03-01");
        // ±6개월 건너뛰기가 같은 달을 두 번 담지 않음을 스키마가 고정한다.
        assertThatThrownBy(() -> insertCase(snapshotId, 2, "2012-03-01"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("snapshot을 지우면 사례가 함께 사라진다")
    void deletingSnapshotCascadesToCases() {
        long snapshotId =
                insertSnapshot(
                        "FIELD:LABOR:2025-01-01:D:R:M",
                        "FIELD",
                        "LABOR",
                        "2025-01-01",
                        1,
                        "j".repeat(64));
        insertCase(snapshotId, 1, "2013-03-01");
        jdbc.update("DELETE FROM regime_search_snapshot WHERE id = ?", snapshotId);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM regime_search_case WHERE snapshot_id = ?",
                                Integer.class,
                                snapshotId))
                .isZero();
    }

    @Test
    @DisplayName("철회하면 기록은 남고 참조만 끊긴다")
    void withdrawingKeepsLogRowAndNullsReference() {
        long snapshotId =
                insertSnapshot(
                        "FIELD:LABOR:2026-01-01:D:R:M",
                        "FIELD",
                        "LABOR",
                        "2026-01-01",
                        1,
                        "k".repeat(64));
        jdbc.update(
                """
                INSERT INTO regime_search_log (
                    owner_user_id, snapshot_id, snapshot_key, entry_path, baseline_origin, viewed_at)
                VALUES (?, ?, ?, ?, ?, now())
                """,
                7L,
                snapshotId,
                "FIELD:LABOR:2026-01-01:D:R:M",
                "MARKET_FLOW",
                "T_G");
        jdbc.update("DELETE FROM regime_search_snapshot WHERE id = ?", snapshotId);

        // 기록 행은 남고 snapshot_id 만 NULL 이 된다 — snapshot_key 사본으로 무엇을 봤는지는
        // 드러난다(spec FR-035, data-model §5).
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regime_search_log", Integer.class))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT snapshot_id FROM regime_search_log", Long.class))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT snapshot_key FROM regime_search_log", String.class))
                .isEqualTo("FIELD:LABOR:2026-01-01:D:R:M");
    }

    private void insertCase(long snapshotId, int rank, String endMonth) {
        jdbc.update(
                """
                INSERT INTO regime_search_case (
                    snapshot_id, rank, end_month, distance, indicator_values, explanation,
                    era_position, created_at, updated_at)
                VALUES (?, ?, ?::date, '{}'::jsonb, '{}'::jsonb, '{}'::jsonb, NULL, now(), now())
                """,
                snapshotId,
                rank,
                endMonth);
    }

    private String nullable(String table, String column) {
        return jdbc.queryForObject(
                """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """,
                String.class,
                table,
                column);
    }
}
