package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.SocialVerification;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * {@code social_pending_verifications}. 마이그레이션은 {@code
 * V20261006191000__social_pending_verifications.sql}.
 */
@Entity
@Table(name = "social_pending_verifications")
public class SocialVerificationEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "code_hash", length = 255)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_target", length = 30)
    private CodeTarget codeTarget;

    @Column(name = "code_expires_at")
    private Instant codeExpiresAt;

    @Column(name = "code_attempts", nullable = false)
    private int codeAttempts;

    @Column(name = "codes_sent", nullable = false)
    private int codesSent;

    @Column(name = "last_code_sent_at")
    private Instant lastCodeSentAt;

    @Column(name = "password_attempts", nullable = false)
    private int passwordAttempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SocialVerificationEntity() {}

    SocialVerification toDomain() {
        return new SocialVerification(
                id,
                codeHash,
                codeTarget,
                codeExpiresAt,
                codeAttempts,
                codesSent,
                lastCodeSentAt,
                passwordAttempts,
                createdAt);
    }

    void apply(SocialVerification verification) {
        this.codeHash = verification.getCodeHash();
        this.codeTarget = verification.getCodeTarget();
        this.codeExpiresAt = verification.getCodeExpiresAt();
        this.codeAttempts = verification.getCodeAttempts();
        this.codesSent = verification.getCodesSent();
        this.lastCodeSentAt = verification.getLastCodeSentAt();
        this.passwordAttempts = verification.getPasswordAttempts();
    }
}
