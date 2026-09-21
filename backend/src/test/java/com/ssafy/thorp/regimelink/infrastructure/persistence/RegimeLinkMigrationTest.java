package com.ssafy.thorp.regimelink.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.support.PostgresTestSupport;
import com.ssafy.thorp.support.RepositoryTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 사례 연결 스키마와 Entity 매핑이 어긋나지 않는지 운영과 같은 조건으로 본다 (T022·T024, data-model §2~§5·§9).
 *
 * <p>Flyway + {@code ddl-auto=validate}는 {@link PostgresTestSupport}가 준다 — 매핑이 어긋나면 <b>컨텍스트 로딩부터
 * 실패</b>한다. 로컬 프로필은 {@code ddl-auto=update} + Flyway 비활성이라 이 불일치를 잡지 못한다.
 *
 * <p>여기서 고정하는 것은 <b>data-model 이 못 박은 삭제 동작과 유일성</b>이다. 빈 DB 적용만 보는 검사는 FK 의 {@code ON DELETE} 규칙을
 * 통과시키므로, 005 정리 작업이 만료된 요청을 지울 때에야 운영에서 처음 드러난다.
 */
@DisplayName("사례 연결 마이그레이션")
class RegimeLinkMigrationTest extends RepositoryTestSupport {

    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("표 다섯이 마이그레이션으로 만들어진다")
    void createsFiveTables() {
        List<String> tables =
                jdbc.queryForList(
                        """
                        SELECT table_name FROM information_schema.tables
                        WHERE table_schema = 'public' AND table_name LIKE 'regime_link%'
                        ORDER BY table_name
                        """,
                        String.class);

        assertThat(tables)
                .containsExactly(
                        "regime_link",
                        "regime_link_filter",
                        "regime_link_result",
                        "regime_link_search",
                        "regime_link_use");
    }

    /**
     * 005 정리 작업이 만료된 비로그인 요청을 지워도 연결 기록이 남아야 하고, 그 정리가 011 때문에 막히면 안 된다(data-model §3·§4a·§9).
     *
     * <p>{@code snapshot_id} 도 같다 — 010 revision 이 철회되면 NULL 이 되고 {@code snapshot_key} 사본으로 무엇을 봤는지
     * 남는다.
     */
    @Test
    @DisplayName("snapshot·요청 참조는 지워질 때 NULL 이 된다")
    void optionalReferencesAreSetNull() {
        assertThat(deleteRule("regime_link_search", "snapshot_id")).isEqualTo("SET NULL");
        assertThat(deleteRule("regime_link_search", "base_request_id")).isEqualTo("SET NULL");
        assertThat(deleteRule("regime_link_search", "compare_request_id")).isEqualTo("SET NULL");
        assertThat(deleteRule("regime_link_use", "origin_request_id")).isEqualTo("SET NULL");
        assertThat(deleteRule("regime_link_use", "created_request_id")).isEqualTo("SET NULL");
    }

    /** 실행 참조는 남아야 한다 — 어느 실행의 결과였는지를 잃으면 링크가 가리키는 대상이 사라진다. */
    @Test
    @DisplayName("실행 참조는 NULL 로 풀리지 않는다")
    void runReferencesAreNotSetNull() {
        assertThat(deleteRule("regime_link", "run_id")).isEqualTo("NO ACTION");
        assertThat(deleteRule("regime_link_search", "base_run_id")).isEqualTo("NO ACTION");
        assertThat(deleteRule("regime_link_use", "origin_run_id")).isEqualTo("NO ACTION");
    }

    /** 결과는 링크와 같이 산다(data-model §8). */
    @Test
    @DisplayName("계산이 지워지면 결과도 함께 지워진다")
    void resultCascadesWithLink() {
        assertThat(deleteRule("regime_link_result", "link_id")).isEqualTo("CASCADE");
    }

    @Test
    @DisplayName("같은 단위는 두 번 계산되지 않도록 유일성이 걸려 있다")
    void uniquenessIsEnforced() {
        assertThat(uniqueColumns("uk_regime_link_unit"))
                .containsExactly("end_month", "run_id", "scenario", "snapshot_key");
        assertThat(uniqueColumns("uk_regime_link_search_client_request"))
                .containsExactly("client_request_id", "owner_user_id");
        assertThat(uniqueColumns("uk_regime_link_use"))
                .containsExactly("link_id", "link_search_id");
        assertThat(uniqueColumns("uk_regime_link_filter_key")).containsExactly("filter_key");
    }

    /**
     * 계산 결과 표에 사용자 식별자가 없다(원칙 VIII).
     *
     * <p>{@code regime_link} 는 여러 사용자·여러 검색이 공유하는 캐시다. 여기에 사용자 컬럼이 생기는 순간 같은 계산이 사용자 수만큼 늘어난다.
     */
    @Test
    @DisplayName("공유되는 계산 표에 사용자 컬럼이 없다")
    void sharedTablesHaveNoOwner() {
        assertThat(columnsOf("regime_link")).noneMatch(RegimeLinkMigrationTest::looksLikeUser);
        assertThat(columnsOf("regime_link_result"))
                .noneMatch(RegimeLinkMigrationTest::looksLikeUser);
        assertThat(columnsOf("regime_link_filter"))
                .noneMatch(RegimeLinkMigrationTest::looksLikeUser);
        // 기록 성격의 표에는 있어야 한다 — 없으면 006 이 누구의 기록인지 말할 수 없다.
        assertThat(columnsOf("regime_link_search")).contains("owner_user_id");
    }

    private static boolean looksLikeUser(String column) {
        return column.contains("user") || column.contains("requester") || column.contains("owner");
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ?
                """,
                String.class,
                table);
    }

    /** 그 컬럼에 걸린 외래 키의 삭제 규칙. 제약 이름이 아니라 컬럼으로 찾아 이름을 바꿔도 검사가 산다. */
    private String deleteRule(String table, String column) {
        return jdbc.queryForObject(
                """
                SELECT rc.delete_rule
                  FROM information_schema.referential_constraints rc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = rc.constraint_name
                   AND kcu.constraint_schema = rc.constraint_schema
                 WHERE kcu.table_schema = 'public'
                   AND kcu.table_name = ?
                   AND kcu.column_name = ?
                """,
                String.class,
                table,
                column);
    }

    private List<String> uniqueColumns(String constraint) {
        return jdbc.queryForList(
                """
                SELECT kcu.column_name
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                   AND kcu.constraint_schema = tc.constraint_schema
                 WHERE tc.table_schema = 'public'
                   AND tc.constraint_type = 'UNIQUE'
                   AND tc.constraint_name = ?
                 ORDER BY kcu.column_name
                """,
                String.class,
                constraint);
    }
}
