package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.ExpLedgerRepository;
import com.ssafy.thorp.learning.domain.model.ExpEntry;
import com.ssafy.thorp.learning.domain.model.ExpEntry.Kind;
import com.ssafy.thorp.learning.domain.model.ExpLedger;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link ExpLedgerRepository}의 구현. JPA 엔티티 없이 SQL로 읽고 쓴다(tasks T178). */
@Repository
public class ExpLedgerRepositoryImpl implements ExpLedgerRepository {

    private final JdbcTemplate jdbc;

    public ExpLedgerRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 쌓인 순서({@code id})다. 레벨에 닿은 시각을 이 순서로 센다. */
    @Override
    public ExpLedger find(long userId) {
        return new ExpLedger(
                jdbc.query(
                        """
                        SELECT e.kind, e.ref_key, a.article_key, s.stage_key, e.points, e.earned_at
                          FROM learning_exp_entry e
                          LEFT JOIN learning_article a ON a.id = e.article_id
                          LEFT JOIN learning_stage s ON s.id = e.stage_id
                         WHERE e.user_id = ?
                         ORDER BY e.id
                        """,
                        ExpLedgerRepositoryImpl::toDomain,
                        userId));
    }

    @Override
    public boolean add(long userId, ExpEntry entry) {
        return jdbc.update(
                        "INSERT INTO learning_exp_entry (user_id, kind, ref_key, article_id,"
                                + " stage_id, points, earned_at)"
                                + " VALUES (?, ?, ?,"
                                + " (SELECT id FROM learning_article WHERE article_key = ?),"
                                + " (SELECT id FROM learning_stage WHERE stage_key = ?), ?, ?)"
                                + " ON CONFLICT (user_id, kind, ref_key) DO NOTHING",
                        userId,
                        entry.kind().name(),
                        entry.refKey(),
                        entry.articleId() == null ? null : entry.articleId().value(),
                        entry.stageKey() == null ? null : entry.stageKey().value(),
                        entry.points(),
                        Timestamp.from(entry.earnedAt()))
                == 1;
    }

    @Override
    public void deleteAllForUser(long userId) {
        jdbc.update("DELETE FROM learning_exp_entry WHERE user_id = ?", userId);
    }

    private static ExpEntry toDomain(ResultSet rs, int rowNum) throws SQLException {
        String article = rs.getString("article_key");
        String stage = rs.getString("stage_key");
        return new ExpEntry(
                Kind.valueOf(rs.getString("kind")),
                rs.getString("ref_key"),
                article == null ? null : new ArticleId(article),
                stage == null ? null : new StageKey(stage),
                rs.getInt("points"),
                rs.getTimestamp("earned_at").toInstant());
    }
}
