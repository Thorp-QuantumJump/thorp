package com.ssafy.thorp.result.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code expired_public_token} 매핑. 쓰기는 {@code INSERT … ON CONFLICT DO NOTHING} 네이티브 질의로만 하므로 이 엔티티는
 * 스키마 검증과 조회를 위한 것이다. 수정 경로가 없어 {@code updated_at}도 없다.
 */
@Entity
@Table(name = "expired_public_token")
public class ExpiredPublicTokenJpaEntity {

    @Id
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExpiredPublicTokenJpaEntity() {}

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiredAt() {
        return expiredAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
