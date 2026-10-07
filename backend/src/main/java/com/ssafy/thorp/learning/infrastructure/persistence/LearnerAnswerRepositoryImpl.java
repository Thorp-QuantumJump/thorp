package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearnerAnswerRepository;
import com.ssafy.thorp.learning.domain.model.FirstAnswer;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link LearnerAnswerRepository}의 구현. JPA 엔티티 없이 SQL로 읽고 쓴다(tasks T178). */
@Repository
public class LearnerAnswerRepositoryImpl implements LearnerAnswerRepository {

    private static final String SELECT =
            """
            SELECT a.article_key, n.question_key, n.choice_key, n.correct, n.answered_at
              FROM learning_answer n
              JOIN learning_article a ON a.id = n.article_id
            """;

    private final JdbcTemplate jdbc;

    public LearnerAnswerRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FirstAnswer> findAll(long userId) {
        return jdbc.query(
                SELECT + " WHERE n.user_id = ? ORDER BY a.article_key, n.question_key",
                LearnerAnswerRepositoryImpl::toDomain,
                userId);
    }

    @Override
    public List<FirstAnswer> findByArticle(long userId, ArticleId articleId) {
        return jdbc.query(
                SELECT + " WHERE n.user_id = ? AND a.article_key = ? ORDER BY n.question_key",
                LearnerAnswerRepositoryImpl::toDomain,
                userId,
                articleId.value());
    }

    /**
     * 넣은 뒤 같은 트랜잭션에서 읽는다. 다른 요청이 같은 행을 먼저 넣고 아직 커밋하지 않았으면 PostgreSQL이 그 커밋을 기다린 뒤 충돌로 판정하므로, 읽는 행은
     * 먼저 커밋된 답이다.
     */
    @Override
    public Stored insertIfAbsent(long userId, FirstAnswer answer) {
        int inserted =
                jdbc.update(
                        "INSERT INTO learning_answer (user_id, article_id, question_key, choice_key,"
                                + " correct, answered_at)"
                                + " VALUES (?, (SELECT id FROM learning_article WHERE article_key = ?),"
                                + " ?, ?, ?, ?)"
                                + " ON CONFLICT (user_id, article_id, question_key) DO NOTHING",
                        userId,
                        answer.articleId().value(),
                        answer.questionId().value(),
                        answer.choiceId().value(),
                        answer.correct(),
                        Timestamp.from(answer.answeredAt()));
        FirstAnswer stored =
                jdbc.queryForObject(
                        SELECT
                                + " WHERE n.user_id = ? AND a.article_key = ?"
                                + " AND n.question_key = ?",
                        LearnerAnswerRepositoryImpl::toDomain,
                        userId,
                        answer.articleId().value(),
                        answer.questionId().value());
        return new Stored(stored, inserted == 1);
    }

    @Override
    public void deleteAllForUser(long userId) {
        jdbc.update("DELETE FROM learning_answer WHERE user_id = ?", userId);
    }

    private static FirstAnswer toDomain(ResultSet rs, int rowNum) throws SQLException {
        return new FirstAnswer(
                new ArticleId(rs.getString("article_key")),
                new QuestionId(rs.getString("question_key")),
                new ChoiceId(rs.getString("choice_key")),
                rs.getBoolean("correct"),
                rs.getTimestamp("answered_at").toInstant());
    }
}
