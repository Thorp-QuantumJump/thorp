package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.SocialProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** {@code social_accounts}. 고유 제약 둘은 마이그레이션 {@code V20261001120000__social_accounts.sql}에 있다. */
@Entity
@Table(name = "social_accounts")
public class SocialAccountEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, length = 20)
    private SocialProvider provider;

    @Column(name = "provider_user_id", nullable = false, length = 255)
    private String providerUserId;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected SocialAccountEntity() {}

    SocialAccountEntity(
            Long id,
            Long userId,
            SocialProvider provider,
            String providerUserId,
            Instant linkedAt) {
        this.id = id;
        this.userId = userId;
        this.provider = provider;
        this.providerUserId = providerUserId;
        this.linkedAt = linkedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public SocialProvider getProvider() {
        return provider;
    }

    public String getProviderUserId() {
        return providerUserId;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }
}
