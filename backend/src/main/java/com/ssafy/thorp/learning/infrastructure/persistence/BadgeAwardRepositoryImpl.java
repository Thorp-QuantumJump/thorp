package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.BadgeAwardRepository;
import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link BadgeAwardRepository}의 구현. JPA 엔티티 없이 SQL로 읽고 쓴다(tasks T178). */
@Repository
public class BadgeAwardRepositoryImpl implements BadgeAwardRepository {

    private final JdbcTemplate jdbc;

    public BadgeAwardRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<BadgeKey, Instant> findAll(long userId) {
        Map<BadgeKey, Instant> badges = new LinkedHashMap<>();
        jdbc.query(
                "SELECT badge_key, earned_at FROM learning_badge_award WHERE user_id = ?"
                        + " ORDER BY earned_at, badge_key",
                rs -> {
                    badges.put(
                            new BadgeKey(rs.getString("badge_key")),
                            rs.getTimestamp("earned_at").toInstant());
                },
                userId);
        return badges;
    }

    @Override
    public boolean award(long userId, BadgeKey badgeKey, Instant at) {
        return jdbc.update(
                        "INSERT INTO learning_badge_award (user_id, badge_key, earned_at)"
                                + " VALUES (?, ?, ?) ON CONFLICT (user_id, badge_key) DO NOTHING",
                        userId,
                        badgeKey.value(),
                        Timestamp.from(at))
                == 1;
    }

    @Override
    public void deleteAllForUser(long userId) {
        jdbc.update("DELETE FROM learning_badge_award WHERE user_id = ?", userId);
    }
}
