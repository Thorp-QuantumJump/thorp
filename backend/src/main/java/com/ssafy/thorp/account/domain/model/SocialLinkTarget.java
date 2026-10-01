package com.ssafy.thorp.account.domain.model;

import com.ssafy.thorp.account.domain.model.SocialVerification.RejectedAttempt;
import com.ssafy.thorp.account.domain.model.SocialVerification.Rejection;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 기존 계정 하나를 향한 연결 확인 시도의 누적 기록(spec 014 FR-024, 2026-10-01 남은 위험 보완).
 *
 * <p>대기 상태마다 두는 상한({@link SocialVerification})은 제공자 로그인부터 다시 하면 새 기록으로 시작한다. 공격자가 그 일을 자동으로 반복하면 같은
 * 계정의 인증번호·비밀번호를 대기 상태 수만큼 더 대입하고 그 계정 이메일로 메일을 계속 보낼 수 있다. 그래서 <b>대상 계정 단위로도 센다.</b> 한 시간 창 안에서 확인
 * 시도 {@link #MAX_ATTEMPTS}회, 인증번호 발송 {@link #MAX_CODES_SENT}회를 넘으면 그 계정으로의 연결 확인을 창이 끝날 때까지 막는다.
 *
 * <p>막히는 것은 소셜 계정 연결뿐이다. 그 계정 주인의 이메일·소셜 로그인은 그대로다 — 남이 일부러 막아도 주인이 잃는 것은 한 시간 동안의 새 소셜 연결이다. 수치는
 * 별도 보안 작업 전까지의 임시값이다.
 */
public class SocialLinkTarget {

    public static final Duration WINDOW = Duration.ofHours(1);

    /** 한 창 안에서 그 계정을 향해 대조할 수 있는 인증번호·비밀번호 수. 대조 전에 센다. */
    public static final int MAX_ATTEMPTS = 10;

    /** 한 창 안에서 그 계정 이메일로 보낼 수 있는 인증번호 수. */
    public static final int MAX_CODES_SENT = 5;

    private final Long userId;
    private Instant windowStartedAt;
    private int attempts;
    private int codesSent;

    public SocialLinkTarget(Long userId, Instant windowStartedAt, int attempts, int codesSent) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.windowStartedAt = Objects.requireNonNull(windowStartedAt, "windowStartedAt");
        this.attempts = attempts;
        this.codesSent = codesSent;
    }

    public static SocialLinkTarget open(Long userId, Instant now) {
        return new SocialLinkTarget(userId, now, 0, 0);
    }

    public void reserveAttempt(Instant now) {
        roll(now);
        if (attempts >= MAX_ATTEMPTS) {
            throw new RejectedAttempt(Rejection.TARGET_LOCKED);
        }
        attempts++;
    }

    public void reserveSend(Instant now) {
        roll(now);
        if (codesSent >= MAX_CODES_SENT) {
            throw new RejectedAttempt(Rejection.TARGET_LOCKED);
        }
        codesSent++;
    }

    /** 주인임을 확인했다. 그동안의 실패는 더 셀 이유가 없다. */
    public void clearAttempts() {
        attempts = 0;
    }

    private void roll(Instant now) {
        if (!now.isBefore(windowStartedAt.plus(WINDOW))) {
            windowStartedAt = now;
            attempts = 0;
            codesSent = 0;
        }
    }

    public Long getUserId() {
        return userId;
    }

    public Instant getWindowStartedAt() {
        return windowStartedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getCodesSent() {
        return codesSent;
    }
}
