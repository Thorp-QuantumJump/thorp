package com.ssafy.thorp.account.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * 이메일 인증 요청 한 건.
 *
 * <p>인증번호 원문은 보관하지 않고 해시만 들고 있다. 해시 비교는 인코더가 필요해 application 계층에서 하고, 여기서는 만료·인증 완료 여부와 시도·발송 상한만
 * 판단한다.
 *
 * <p><b>대조와 발송에 상한을 둔다</b>(2026-10-01 남은 위험 보완). 상한이 없으면 6자리 번호를 끝까지 대입하거나 남의 주소로 메일을 계속 보낼 수 있다.
 * 대조 수는 대조 전에 세고, 저장소가 행을 잠근 채 센다. 발송 수는 재발송해도 한 시간 창 안에서 이어서 센다. 수치는 별도 보안 작업 전까지의 임시값이다.
 */
public class EmailVerification {

    /** 번호 하나를 대조할 수 있는 횟수. */
    public static final int MAX_ATTEMPTS = 5;

    /** 다시 받기 사이의 최소 간격. */
    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(30);

    /** 발송 수를 세는 창. */
    public static final Duration SEND_WINDOW = Duration.ofHours(1);

    /** 한 창 안에서 같은 주소로 보낼 수 있는 번호 수. */
    public static final int MAX_SENDS_IN_WINDOW = 5;

    private final Long id;
    private final String email;
    private final String verificationCodeHash;
    private final Instant expiresAt;
    private final Instant createdAt;
    private final int sendsInWindow;
    private final Instant sendWindowStartedAt;

    private Instant verifiedAt;
    private int attempts;

    public EmailVerification(
            Long id,
            String email,
            String verificationCodeHash,
            Instant expiresAt,
            Instant verifiedAt,
            Instant createdAt,
            int attempts,
            int sendsInWindow,
            Instant sendWindowStartedAt) {
        this.id = id;
        this.email = email;
        this.verificationCodeHash = verificationCodeHash;
        this.expiresAt = expiresAt;
        this.verifiedAt = verifiedAt;
        this.createdAt = createdAt;
        this.attempts = attempts;
        this.sendsInWindow = sendsInWindow;
        this.sendWindowStartedAt = sendWindowStartedAt;
    }

    /**
     * 새로 발급한 인증 요청. 아직 인증되지 않은 상태로 만든다.
     *
     * @param previous 같은 주소의 직전 요청. 있으면 발송 창을 이어서 센다.
     */
    public static EmailVerification issue(
            String email,
            String verificationCodeHash,
            Instant expiresAt,
            EmailVerification previous,
            Instant now) {
        boolean sameWindow =
                previous != null
                        && previous.sendWindowStartedAt != null
                        && now.isBefore(previous.sendWindowStartedAt.plus(SEND_WINDOW));
        return new EmailVerification(
                null,
                email,
                verificationCodeHash,
                expiresAt,
                null,
                now,
                0,
                sameWindow ? previous.sendsInWindow + 1 : 1,
                sameWindow ? previous.sendWindowStartedAt : now);
    }

    /** 직전 요청을 보고 지금 다시 보내도 되는지. 막을 이유가 없으면 {@code null}이다. */
    public static SendRejection checkResend(EmailVerification previous, Instant now) {
        if (previous == null) {
            return null;
        }
        if (previous.createdAt != null && now.isBefore(previous.createdAt.plus(RESEND_INTERVAL))) {
            return SendRejection.TOO_SOON;
        }
        boolean sameWindow =
                previous.sendWindowStartedAt != null
                        && now.isBefore(previous.sendWindowStartedAt.plus(SEND_WINDOW));
        if (sameWindow && previous.sendsInWindow >= MAX_SENDS_IN_WINDOW) {
            return SendRejection.LIMIT_REACHED;
        }
        return null;
    }

    public enum SendRejection {
        TOO_SOON,
        LIMIT_REACHED
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    /** 유효시간이 지났는지. 만료 시각과 같은 순간도 만료로 본다. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean attemptsExhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    /** 대조 한 번을 센다. 대조 전에 부른다. */
    public void recordAttempt() {
        attempts++;
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
