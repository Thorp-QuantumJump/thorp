package com.ssafy.thorp.account.domain.model;

import java.time.Instant;

/**
 * 이메일 인증 요청 한 건.
 *
 * <p>인증번호 원문은 보관하지 않고 해시만 들고 있다. 해시 비교는 인코더가 필요해 application 계층에서 하고, 여기서는 만료·인증 완료 여부만 판단한다.
 */
public class EmailVerification {

    private final Long id;
    private final String email;
    private final String verificationCodeHash;
    private final Instant expiresAt;
    private final Instant createdAt;

    private Instant verifiedAt;

    public EmailVerification(
            Long id,
            String email,
            String verificationCodeHash,
            Instant expiresAt,
            Instant verifiedAt,
            Instant createdAt) {
        this.id = id;
        this.email = email;
        this.verificationCodeHash = verificationCodeHash;
        this.expiresAt = expiresAt;
        this.verifiedAt = verifiedAt;
        this.createdAt = createdAt;
    }

    /** 새로 발급한 인증 요청. 아직 인증되지 않은 상태로 만든다. */
    public static EmailVerification issue(
            String email, String verificationCodeHash, Instant expiresAt) {
        return new EmailVerification(null, email, verificationCodeHash, expiresAt, null, null);
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    /** 유효시간이 지났는지. 만료 시각과 같은 순간도 만료로 본다. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** 인증 완료 시각을 기록한다. 이미 인증된 요청은 최초 시각을 유지한다. */
    public void verify(Instant verifiedAt) {
        if (isVerified()) {
            return;
        }
        this.verifiedAt = verifiedAt;
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
}
