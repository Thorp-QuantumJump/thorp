package com.ssafy.thorp.account.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "email_verifications")
public class EmailVerificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "verification_code_hash", nullable = false, length = 255)
    private String verificationCodeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "sends_in_window", nullable = false)
    private int sendsInWindow;

    @Column(name = "send_window_started_at", nullable = false)
    private Instant sendWindowStartedAt;

    protected EmailVerificationEntity() {}

    EmailVerificationEntity(
            Long id,
            String email,
            String verificationCodeHash,
            Instant expiresAt,
            Instant verifiedAt,
            Instant createdAt,
            int attempts,
            int sendsInWindow,
            Instant sendWindowStartedAt) {
        this.attempts = attempts;
        this.sendsInWindow = sendsInWindow;
        this.sendWindowStartedAt = sendWindowStartedAt;
        this.id = id;
        this.email = email;
        this.verificationCodeHash = verificationCodeHash;
        this.expiresAt = expiresAt;
        this.verifiedAt = verifiedAt;
        this.createdAt = createdAt;
    }

    @PrePersist
    private void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (sendWindowStartedAt == null) {
            sendWindowStartedAt = createdAt;
        }
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getVerificationCodeHash() {
        return verificationCodeHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getSendsInWindow() {
        return sendsInWindow;
    }

    public Instant getSendWindowStartedAt() {
        return sendWindowStartedAt;
    }
}
