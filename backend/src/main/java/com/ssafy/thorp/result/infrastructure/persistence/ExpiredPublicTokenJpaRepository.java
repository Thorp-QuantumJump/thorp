package com.ssafy.thorp.result.infrastructure.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ExpiredPublicTokenJpaRepository
        extends JpaRepository<ExpiredPublicTokenJpaEntity, String> {

    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "INSERT INTO expired_public_token (token_hash, expired_at, created_at)"
                            + " VALUES (:tokenHash, :expiredAt, :recordedAt)"
                            + " ON CONFLICT (token_hash) DO NOTHING",
            nativeQuery = true)
    int insertIfAbsent(
            @Param("tokenHash") String tokenHash,
            @Param("expiredAt") Instant expiredAt,
            @Param("recordedAt") Instant recordedAt);

    /**
     * 인덱스 {@code idx_expired_public_token_expired_at}를 탄다. PostgreSQL 의 DELETE 에는 LIMIT 이 없어 하위 질의로
     * 자른다.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    "DELETE FROM expired_public_token WHERE token_hash IN ("
                            + " SELECT token_hash FROM expired_public_token"
                            + " WHERE expired_at < :cutoff ORDER BY expired_at LIMIT :limit)",
            nativeQuery = true)
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
