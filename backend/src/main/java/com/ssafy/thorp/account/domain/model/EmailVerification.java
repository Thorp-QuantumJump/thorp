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
 * 대조 수는 대조 전에 센다. 같은 주소의 발급·대조는 서비스가 주소 단위 잠금으로 한 줄로 세운다.
 *
 * <p><b>발송은 막지 않고 늦춘다.</b> 한 시간 안에서 받을 때마다 다음 간격이 두 배로 늘어난다({@link #RESEND_INTERVAL}부터 {@link
 * #MAX_RESEND_INTERVAL}까지). 고정 상한(시간당 N통)을 두면 남이 그 주소로 N번 요청하는 것만으로 주인의 가입을 한 시간 막을 수 있다 — 늘어나는 간격은
 * 메일 폭탄을 시간당 열 통 남짓으로 묶으면서 주인을 길어야 몇 분만 기다리게 한다. 수치는 별도 보안 작업 전까지의 임시값이다.
 */
public class EmailVerification {

    /** 번호 하나를 대조할 수 있는 횟수. */
    public static final int MAX_ATTEMPTS = 5;

    /** 처음 다시 받기까지의 간격. 받을 때마다 두 배가 된다. */
    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(30);

    /** 늘어나는 간격의 상한. */
    public static final Duration MAX_RESEND_INTERVAL = Duration.ofMinutes(10);

    /** 발송 수를 세는 창. 지나면 간격이 처음으로 돌아간다. */
    public static final Duration SEND_WINDOW = Duration.ofHours(1);

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
        boolean sameWindow = inSameWindow(previous, now);
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

    /** 직전 요청을 보고 지금 다시 보내기엔 이른지. 간격은 그 창에서 보낸 수만큼 두 배씩 늘어난다. */
    public static boolean resendTooSoon(EmailVerification previous, Instant now) {
        if (previous == null || previous.createdAt == null) {
            return false;
        }
        int sends = inSameWindow(previous, now) ? previous.sendsInWindow : 0;
        return now.isBefore(previous.createdAt.plus(resendGap(sends)));
    }

    /** 그 창에서 {@code sends}통을 보낸 뒤의 다음 간격. */
    public static Duration resendGap(int sends) {
        if (sends <= 1) {
            return RESEND_INTERVAL;
        }
        Duration gap = RESEND_INTERVAL.multipliedBy(1L << Math.min(sends - 1, 20));
        return gap.compareTo(MAX_RESEND_INTERVAL) > 0 ? MAX_RESEND_INTERVAL : gap;
    }

    private static boolean inSameWindow(EmailVerification previous, Instant now) {
        return previous != null
                && previous.sendWindowStartedAt != null
                && now.isBefore(previous.sendWindowStartedAt.plus(SEND_WINDOW));
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
