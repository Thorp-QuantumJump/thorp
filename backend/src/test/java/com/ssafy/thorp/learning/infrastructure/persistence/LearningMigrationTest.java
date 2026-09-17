package com.ssafy.thorp.learning.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.backtest.infrastructure.messaging.MessageJsonPersistenceConfig;
import com.ssafy.thorp.common.config.JpaAuditingConfig;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * spec 013 마이그레이션을 빈 DB에 적용한 결과를 확인한다(quickstart 1 "마이그레이션").
 *
 * <p>매핑과 스키마가 어긋나면 {@link PostgresTestSupport}의 {@code ddl-auto=validate}로 컨텍스트 로딩부터 실패한다. 여기서는 초기
 * 자리와 CHECK·UNIQUE 제약을 본다. <b>버전 번호를 적지 않는다</b>(backend/CLAUDE.md).
 *
 * <p>{@code @Import} 묶음은 {@link LearningRepositoryTest}와 같게 둔다. 컨텍스트 캐시를 공유해 DB 연결을 더 쓰지 않는다.
 *
 * <p>제약 위반 단언은 테스트마다 하나다. PostgreSQL은 실패한 문장 뒤 같은 트랜잭션의 다음 문장을 모두 거절하고, {@code @DataJpaTest}는 테스트
 * 하나를 한 트랜잭션으로 묶어 되돌린다.
 */
@DataJpaTest
@Import({
    JpaAuditingConfig.class,
    MessageJsonPersistenceConfig.class,
    LearningArticleRepositoryImpl.class,
    LearningTermRepositoryImpl.class,
    ArticleRevisionRepositoryImpl.class,
    PublicationEventRepositoryImpl.class,
    PublishedTermRepositoryImpl.class
})
class LearningMigrationTest extends PostgresTestSupport {

    /** data-model §8의 열네 용어. */
    private static final List<String> SEED_TERM_KEYS =
            List.of(
                    "sector",
                    "representative-etf",
                    "weight",
                    "rebalancing",
                    "fixed-weight",
                    "equal-weight",
                    "volatility",
                    "inverse-volatility",
                    "sector-momentum",
                    "minimum-variance",
                    "backtest",
                    "transaction-cost",
                    "net-total-return",
                    "max-drawdown");

    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("아홉 글이 모두 DRAFT이고 질문·본문·예시가 비어 있다")
    void seedsNineEmptyDraftArticles() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList(
                        "SELECT article_key, status, question, body_blocks::text AS blocks,"
                                + " example IS NULL AS no_example, published_revision_id, draft_version"
                                + " FROM learning_article ORDER BY reading_order");

        assertThat(rows).hasSize(9);
        assertThat(rows)
                .extracting(row -> row.get("article_key"))
                .containsExactly(
                        "sectors-and-etf",
                        "weights-and-rebalancing",
                        "inverse-volatility",
                        "sector-momentum",
                        "minimum-variance",
                        "backtest",
                        "transaction-cost",
                        "returns-and-losses",
                        "past-is-not-future");
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.get("status")).isEqualTo("DRAFT");
                            assertThat(row.get("question")).isEqualTo("");
                            assertThat(row.get("blocks")).isEqualTo("[]");
                            assertThat(row.get("no_example")).isEqualTo(true);
                            assertThat(row.get("published_revision_id")).isNull();
                            assertThat(row.get("draft_version")).isEqualTo(0);
                        });
    }

    @Test
    @DisplayName("열네 용어가 모두 있고 문장이 비어 있다")
    void seedsFourteenEmptyTerms() {
        List<Map<String, Object>> rows =
                jdbcTemplate.queryForList("SELECT term_key, sentence FROM learning_term");

        assertThat(rows)
                .extracting(row -> row.get("term_key"))
                .containsExactlyInAnyOrderElementsOf(SEED_TERM_KEYS);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("sentence")).isEqualTo(""));
    }

    @Test
    @DisplayName("공개 판·기록·색인은 비어 있다")
    void publicationTablesStartEmpty() {
        assertThat(count("learning_article_revision")).isZero();
        assertThat(count("learning_publication_event")).isZero();
        assertThat(count("learning_published_term")).isZero();
    }

    @Test
    @DisplayName("PUBLISHED인데 공개 판이 없는 행은 CHECK로 거절된다")
    void rejectsPublishedWithoutRevision() {
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO learning_article (article_key, title, question,"
                                                + " reading_order, status, created_at, updated_at)"
                                                + " VALUES ('no-revision', 't', '', 1, 'PUBLISHED', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("형식이 틀린 article_key는 CHECK로 거절된다")
    void rejectsInvalidArticleKey() {
        assertThatThrownBy(
                        () ->
                                jdbcTemplate.update(
                                        "INSERT INTO learning_article (article_key, title, question,"
                                                + " reading_order, status, created_at, updated_at)"
                                                + " VALUES ('Bad_Key', 't', '', 1, 'DRAFT', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 글의 판 번호가 겹치면 UNIQUE로 거절된다")
    void rejectsDuplicateRevisionNo() {
        long articleId =
                jdbcTemplate.queryForObject(
                        "SELECT id FROM learning_article WHERE article_key = 'backtest'",
                        Long.class);
        insertRevision(articleId, 1);

        assertThatThrownBy(() -> insertRevision(articleId, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertRevision(long articleId, int revisionNo) {
        jdbcTemplate.update(
                "INSERT INTO learning_article_revision (article_id, revision_no, snapshot,"
                        + " check_version, sector_map_version, published_by_user_id, published_at)"
                        + " VALUES (?, ?, '{}'::jsonb, 'learning-publish-checks-v1', 'SEM-1', 1, now())",
                articleId,
                revisionNo);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
