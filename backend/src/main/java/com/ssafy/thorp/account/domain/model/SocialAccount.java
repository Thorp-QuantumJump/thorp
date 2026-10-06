package com.ssafy.thorp.account.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Thorp 계정과 소셜 계정의 연결(spec 014 FR-021).
 *
 * <p>소셜 계정은 제공자의 사용자 고유 식별자로만 구분한다. 제공자 이메일은 담지 않는다 — 제공자 쪽에서 이메일이 바뀌어도 연결은 그대로여야 한다.
 */
public class SocialAccount {

    private final Long id;
    private final Long userId;
    private final SocialProvider provider;
    private final String providerUserId;
    private final Instant linkedAt;

    public SocialAccount(
            Long id,
            Long userId,
            SocialProvider provider,
            String providerUserId,
            Instant linkedAt) {
        this.id = id;
        this.userId = Objects.requireNonNull(userId, "userId");
        this.provider = Objects.requireNonNull(provider, "provider");
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("providerUserId must not be blank");
        }
        this.providerUserId = providerUserId;
        this.linkedAt = Objects.requireNonNull(linkedAt, "linkedAt");
    }

    public static SocialAccount link(
            Long userId, SocialProvider provider, String providerUserId, Instant linkedAt) {
        return new SocialAccount(null, userId, provider, providerUserId, linkedAt);
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
