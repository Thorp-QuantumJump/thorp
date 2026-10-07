package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearnerProgressRepository;
import com.ssafy.thorp.learning.domain.model.LearnerProgress;
import com.ssafy.thorp.learning.domain.model.LearnerProgress.MasteredVia;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

/**
 * {@link LearnerProgressRepository}의 구현. JPA 엔티티 없이 SQL로 읽고 쓴다(tasks T178). 글은 {@code article_key}로
 * 가리키고 대리키는 문장 안에서 찾는다.
 *
 * <p>위치·완료는 {@code INSERT … ON CONFLICT DO UPDATE} 한 문장이다. 읽은 뒤 쓰면 같은 글을 두 탭이 동시에 기록할 때 한쪽이 상대의 값을
 * 되돌린다.
 */
@Repository
public class LearnerProgressRepositoryImpl implements LearnerProgressRepository {

    private static final String SELECT =
            """
            SELECT a.article_key, p.furthest_percent, p.last_opened_at, p.completed_at,
                   p.all_answered_at, p.mastered_at, p.mastered_via
              FROM learning_progress p
              JOIN learning_article a ON a.id = p.article_id
            """;

    /** 사용자 잠금 식별자의 접두어. 테스트가 같은 잠금을 잡을 때도 이 값을 쓴다. */
    public static final String LOCK_PREFIX = "learning-learner:";

    private static final String ARTICLE_ID =
            "(SELECT id FROM learning_article WHERE article_key = ?)";

    private final JdbcTemplate jdbc;

    public LearnerProgressRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code pg_advisory_xact_lock(bigint)}. 잠금 식별자는 {@code learning-learner:{userId}} 문자열의 64비트
     * 해시다(PostgreSQL {@code hashtextextended}, {@code EmailVerificationRepositoryImpl}과 같은 방식).
     * 접두어가 다른 기능의 잠금과 식별자를 가른다.
     */
    @Override
    public void lockLearner(long userId) {
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                (RowCallbackHandler) row -> {},
                LOCK_PREFIX + userId);
    }

    @Override
    public List<LearnerProgress> findAll(long userId) {
        return jdbc.query(
                SELECT + " WHERE p.user_id = ? ORDER BY a.article_key",
                LearnerProgressRepositoryImpl::toDomain,
                userId);
    }

    @Override
    public Optional<LearnerProgress> find(long userId, ArticleId articleId) {
        return jdbc
                .query(
                        SELECT + " WHERE p.user_id = ? AND a.article_key = ?",
                        LearnerProgressRepositoryImpl::toDomain,
                        userId,
                        articleId.value())
                .stream()
                .findFirst();
    }

    /** 진행 행만 잠근다({@code FOR UPDATE OF p}). 글 행까지 잠그면 운영자의 게시와 서로 기다린다. */
    @Override
    public Optional<LearnerProgress> findForUpdate(long userId, ArticleId articleId) {
        return jdbc
                .query(
                        SELECT + " WHERE p.user_id = ? AND a.article_key = ? FOR UPDATE OF p",
                        LearnerProgressRepositoryImpl::toDomain,
                        userId,
                        articleId.value())
                .stream()
                .findFirst();
    }

    @Override
    public void recordPosition(long userId, ArticleId articleId, int percent, Instant at) {
        Timestamp now = Timestamp.from(at);
        jdbc.update(
                "INSERT INTO learning_progress (user_id, article_id, furthest_percent,"
                        + " last_opened_at, created_at, updated_at) VALUES (?, "
                        + ARTICLE_ID
                        + ", ?, ?, ?, ?)"
                        + " ON CONFLICT (user_id, article_id) DO UPDATE SET"
                        + " furthest_percent = GREATEST(learning_progress.furthest_percent,"
                        + " EXCLUDED.furthest_percent),"
                        + " last_opened_at = EXCLUDED.last_opened_at,"
                        + " updated_at = EXCLUDED.updated_at",
                userId,
                articleId.value(),
                percent,
                now,
                now,
                now);
    }

    @Override
    public Instant recordCompletion(long userId, ArticleId articleId, Instant at) {
        Timestamp now = Timestamp.from(at);
        Timestamp stored =
                jdbc.queryForObject(
                        "INSERT INTO learning_progress (user_id, article_id, furthest_percent,"
                                + " last_opened_at, completed_at, created_at, updated_at)"
                                + " VALUES (?, "
                                + ARTICLE_ID
                                + ", 100, ?, ?, ?, ?)"
                                + " ON CONFLICT (user_id, article_id) DO UPDATE SET"
                                + " furthest_percent = 100,"
                                + " last_opened_at = EXCLUDED.last_opened_at,"
                                + " completed_at = COALESCE(learning_progress.completed_at,"
                                + " EXCLUDED.completed_at),"
                                + " updated_at = EXCLUDED.updated_at"
                                + " RETURNING completed_at",
                        Timestamp.class,
                        userId,
                        articleId.value(),
                        now,
                        now,
                        now,
                        now);
        return stored.toInstant();
    }

    @Override
    public void markAllAnswered(long userId, ArticleId articleId, Instant at) {
        requireOne(
                jdbc.update(
                        "UPDATE learning_progress SET"
                                + " all_answered_at = COALESCE(all_answered_at, ?), updated_at = ?"
                                + " WHERE user_id = ? AND article_id = "
                                + ARTICLE_ID,
                        Timestamp.from(at),
                        Timestamp.from(at),
                        userId,
                        articleId.value()),
                articleId);
    }

    @Override
    public void markMastered(long userId, ArticleId articleId, MasteredVia via, Instant at) {
        requireOne(
                jdbc.update(
                        "UPDATE learning_progress SET"
                                + " mastered_via = CASE WHEN mastered_at IS NULL THEN ?"
                                + " ELSE mastered_via END,"
                                + " mastered_at = COALESCE(mastered_at, ?), updated_at = ?"
                                + " WHERE user_id = ? AND article_id = "
                                + ARTICLE_ID,
                        via.name(),
                        Timestamp.from(at),
                        Timestamp.from(at),
                        userId,
                        articleId.value()),
                articleId);
    }

    @Override
    public void deleteAllForUser(long userId) {
        jdbc.update("DELETE FROM learning_progress WHERE user_id = ?", userId);
    }

    private static void requireOne(int updated, ArticleId articleId) {
        if (updated != 1) {
            throw new IllegalStateException("no learning progress row for " + articleId);
        }
    }

    private static LearnerProgress toDomain(ResultSet rs, int rowNum) throws SQLException {
        String via = rs.getString("mastered_via");
        return new LearnerProgress(
                new ArticleId(rs.getString("article_key")),
                rs.getInt("furthest_percent"),
                instant(rs, "last_opened_at"),
                instant(rs, "completed_at"),
                instant(rs, "all_answered_at"),
                instant(rs, "mastered_at"),
                via == null ? null : MasteredVia.valueOf(via));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
