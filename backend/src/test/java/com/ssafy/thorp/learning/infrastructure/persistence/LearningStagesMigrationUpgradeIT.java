package com.ssafy.thorp.learning.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.ssafy.thorp.support.MigrationFiles;
import com.ssafy.thorp.support.MigrationFiles.MigrationFile;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 2판 B07 마이그레이션({@code *__learning_stages_and_v2_content.sql})을 <b>이미 쓰이던 DB</b>에 적용한 결과를
 * 본다(data-model §13-3, research R15, tasks T116·T161).
 *
 * <p>빈 DB 테스트({@code LearningMigrationTest})로는 보이지 않는 두 가지다. 운영에서 게시 중이던 글은 공개 배치가 초안 배치와 같은 값으로
 * 채워져야 하고, 마이그레이션이 모르는 글이 운영 DB에 있으면 {@code stage_id}를 채우지 못해 마이그레이션 전체가 되돌아가야 한다(데이터는 그대로다).
 *
 * <p>DB마다 그 마이그레이션 직전 버전까지 적용한 뒤 운영과 비슷한 상태를 심고 나머지를 적용한다. 직전 버전은 파일 목록에서 찾는다. <b>버전 번호를 적지
 * 않는다</b>(backend/CLAUDE.md).
 *
 * <p>{@code EtfMarketDataSchemaIT}처럼 Spring 컨텍스트를 띄우지 않고 공용 컨테이너도 쓰지 않는다. 이 클래스 전용 컨테이너에 DB를 시나리오마다
 * 하나씩 만들고 커넥션을 하나만 연다. Docker가 없으면 통째로 skip한다. 결과의 {@code skipped}를 확인한다.
 */
@Testcontainers(disabledWithoutDocker = true)
class LearningStagesMigrationUpgradeIT {

    private static final String MIGRATION_SUFFIX = "__learning_stages_and_v2_content.sql";

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    /** B07 마이그레이션 직전 버전. */
    private static String versionBefore() {
        List<MigrationFile> files = MigrationFiles.all();
        for (int index = 0; index < files.size(); index++) {
            if (files.get(index).fileName().endsWith(MIGRATION_SUFFIX)) {
                return files.get(index - 1).version();
            }
        }
        throw new AssertionError("no migration file ends with " + MIGRATION_SUFFIX);
    }

    private static String createDatabase(String name) {
        JdbcTemplate admin =
                new JdbcTemplate(
                        new SingleConnectionDataSource(
                                POSTGRES.getJdbcUrl(),
                                POSTGRES.getUsername(),
                                POSTGRES.getPassword(),
                                true));
        admin.execute("CREATE DATABASE " + name);
        return POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + name);
    }

    private static Flyway flyway(String url, String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .baselineOnMigrate(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(
                new SingleConnectionDataSource(
                        url, POSTGRES.getUsername(), POSTGRES.getPassword(), true));
    }

    /** 9월 판 글 하나를 게시 상태로 심는다. 게시 유스케이스가 남기는 것과 같은 행이다. */
    private static void publishLegacy(JdbcTemplate jdbc, String articleKey) {
        long articleId =
                jdbc.queryForObject(
                        "SELECT id FROM learning_article WHERE article_key = ?",
                        Long.class,
                        articleKey);
        long revisionId =
                jdbc.queryForObject(
                        "INSERT INTO learning_article_revision (article_id, revision_no, snapshot,"
                                + " check_version, sector_map_version, published_by_user_id,"
                                + " published_at) VALUES (?, 1, '{}'::jsonb,"
                                + " 'learning-publish-checks-v1', 'SEM-1', 7, now()) RETURNING id",
                        Long.class,
                        articleId);
        jdbc.update(
                "UPDATE learning_article SET status = 'PUBLISHED', published_revision_id = ?"
                        + " WHERE id = ?",
                revisionId,
                articleId);
    }

    @Test
    @DisplayName("게시 중이던 글은 공개 배치가 초안 배치로 채워지고, 판은 LEGACY가 된다")
    void publishedArticlesGetPublishedPlacement() {
        String url = createDatabase("learning_upgrade_published");
        flyway(url, versionBefore()).migrate();
        JdbcTemplate jdbc = jdbc(url);
        publishLegacy(jdbc, "returns-and-losses");
        publishLegacy(jdbc, "past-is-not-future");

        flyway(url, null).migrate();

        List<Map<String, Object>> published =
                jdbc.queryForList(
                        "SELECT a.article_key, s.stage_key, a.stage_position,"
                                + " ps.stage_key AS published_stage_key, a.published_stage_position"
                                + " FROM learning_article a"
                                + " JOIN learning_stage s ON s.id = a.stage_id"
                                + " JOIN learning_stage ps ON ps.id = a.published_stage_id"
                                + " ORDER BY a.article_key");
        assertThat(published)
                .extracting(
                        row -> row.get("article_key"),
                        row -> row.get("published_stage_key"),
                        row -> ((Number) row.get("published_stage_position")).intValue())
                .containsExactly(
                        tuple("past-is-not-future", "pitfalls-and-validation", 1),
                        tuple("returns-and-losses", "returns-and-risk", 1));
        assertThat(published)
                .allSatisfy(
                        row ->
                                assertThat(row.get("published_stage_key"))
                                        .isEqualTo(row.get("stage_key")));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM learning_article"
                                        + " WHERE status = 'DRAFT' AND published_stage_id IS NOT NULL",
                                Integer.class))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "SELECT DISTINCT edition FROM learning_article_revision",
                                String.class))
                .containsExactly("LEGACY");
    }

    @Test
    @DisplayName("마이그레이션이 모르는 글이 있으면 실패하고 전체가 되돌아가 데이터가 그대로다 (R15)")
    void unknownArticleRollsBackWholeMigration() {
        String url = createDatabase("learning_upgrade_unknown");
        String before = versionBefore();
        flyway(url, before).migrate();
        JdbcTemplate jdbc = jdbc(url);
        jdbc.update(
                "INSERT INTO learning_article (article_key, title, question, reading_order, status,"
                        + " created_at, updated_at)"
                        + " VALUES ('operator-made', '관리 화면에서 만든 글', '', 10, 'DRAFT', now(), now())");

        // 다른 이유(새 글 키 충돌, 뒤 문장의 오타)로 실패해도 되돌아가므로, 걸린 것이 stage_id NOT NULL인지 본다.
        assertThatThrownBy(() -> flyway(url, null).migrate())
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining(
                        "column \"stage_id\" of relation \"learning_article\" contains null values");

        assertThat(
                        jdbc.queryForObject(
                                "SELECT to_regclass('learning_stage') IS NULL", Boolean.class))
                .as("단계 테이블도 함께 되돌아간다")
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns"
                                        + " WHERE table_name = 'learning_article' AND column_name = 'stage_id'",
                                Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_article", Integer.class))
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT version FROM flyway_schema_history WHERE success"
                                        + " ORDER BY installed_rank DESC LIMIT 1",
                                String.class))
                .isEqualTo(before);
    }
}
