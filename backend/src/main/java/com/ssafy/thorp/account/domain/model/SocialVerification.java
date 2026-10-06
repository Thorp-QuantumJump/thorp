package com.ssafy.thorp.account.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 소셜 대기 상태 하나의 인증번호와 시도 기록(spec 014 FR-022·FR-024, 2026-10-01 code review).
 *
 * <p>연결 단계의 인증번호와 비밀번호는 남의 계정에 들어가는 열쇠다. 휴대폰 번호만 알면 누구나 그 계정의 연결 단계에 들어올 수 있으므로, 상한이 없으면 6자리 번호를
 * 끝까지 대입해 계정을 차지할 수 있다. 그래서 <b>대조하기 전에 시도를 먼저 센다.</b> 병렬로 보내도 번호 하나를 {@link #MAX_CODE_ATTEMPTS}번보다
 * 많이 대조하지 못한다. 저장소가 행을 잠근 채 이 규칙을 적용한다.
 *
 * <p>수치는 별도 보안 작업(spec Dependencies)이 정책을 정하기 전까지의 임시값이다.
 */
public class SocialVerification {

    /** 소셜 경로 인증번호의 수명. 이메일 가입(FR-004)과 같다. */
    public static final Duration CODE_TTL = Duration.ofMinutes(3);

    /** 번호 하나를 대조할 수 있는 횟수. 넘으면 그 번호는 쓸 수 없고 새로 받아야 한다. */
    public static final int MAX_CODE_ATTEMPTS = 5;

    /** 대기 상태 하나에서 보낼 수 있는 인증번호 수. 넘으면 대기 상태를 버린다. */
    public static final int MAX_CODES_SENT = 5;

    /** 다시 받기 사이의 최소 간격. 받는 사람이 요청한 사람과 다를 수 있어 메일이 쏟아지지 않게 한다. */
    public static final Duration RESEND_INTERVAL = Duration.ofSeconds(30);

    /** 연결 단계에서 비밀번호를 대조할 수 있는 횟수. 넘으면 대기 상태를 버린다. */
    public static final int MAX_PASSWORD_ATTEMPTS = 5;

    /** 인증번호를 보낸 주소. */
    public enum CodeTarget {
        PROVIDER_EMAIL,
        LINK_TARGET_EMAIL
    }

    /** 시도를 거절한 이유. 서비스가 사용자 안내로 바꾼다. */
    public enum Rejection {
        NO_CODE,
        EXPIRED,
        RESEND_TOO_SOON,
        LIMIT_REACHED,
        /** 번호 하나의 대조 횟수를 다 썼다. 번호는 지웠고 새로 받아야 한다 — 이메일 가입의 ATTEMPTS_EXCEEDED와 같은 안내다. */
        ATTEMPTS_EXHAUSTED,
        /** 대상 계정 단위 누적 상한({@link SocialLinkTarget})에 걸렸다. 대기 상태는 그대로 둔다. */
        TARGET_LOCKED
    }

    /** 규칙을 어긴 시도. 저장소는 잠근 행의 변경을 커밋한 뒤 이 예외를 그대로 올린다. */
    public static final class RejectedAttempt extends RuntimeException {
        private final Rejection rejection;

        public RejectedAttempt(Rejection rejection) {
            super(rejection.name());
            this.rejection = rejection;
        }

        public Rejection rejection() {
            return rejection;
        }
    }

    private final String id;
    private String codeHash;
    private CodeTarget codeTarget;
    private Instant codeExpiresAt;
    private int codeAttempts;
    private int codesSent;
    private Instant lastCodeSentAt;
    private int passwordAttempts;
    private final Instant createdAt;

    public SocialVerification(
            String id,
            String codeHash,
            CodeTarget codeTarget,
            Instant codeExpiresAt,
            int codeAttempts,
            int codesSent,
            Instant lastCodeSentAt,
            int passwordAttempts,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.codeHash = codeHash;
        this.codeTarget = codeTarget;
        this.codeExpiresAt = codeExpiresAt;
        this.codeAttempts = codeAttempts;
        this.codesSent = codesSent;
        this.lastCodeSentAt = lastCodeSentAt;
        this.passwordAttempts = passwordAttempts;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static SocialVerification open(String id, Instant now) {
        return new SocialVerification(id, null, null, null, 0, 0, null, 0, now);
    }

    /** 새 번호를 단다. 앞서 보낸 번호는 더 이상 맞지 않고 대조 횟수는 새 번호 기준으로 다시 센다. */
    public void issue(String hash, CodeTarget target, Instant now) {
        if (lastCodeSentAt != null && now.isBefore(lastCodeSentAt.plus(RESEND_INTERVAL))) {
            throw new RejectedAttempt(Rejection.RESEND_TOO_SOON);
        }
        if (codesSent >= MAX_CODES_SENT) {
            throw new RejectedAttempt(Rejection.LIMIT_REACHED);
        }
        this.codeHash = Objects.requireNonNull(hash, "hash");
        this.codeTarget = Objects.requireNonNull(target, "target");
        this.codeExpiresAt = now.plus(CODE_TTL);
        this.codeAttempts = 0;
        this.codesSent++;
        this.lastCodeSentAt = now;
    }

    /**
     * 번호 하나를 대조할 자격을 얻는다. <b>대조 전에 시도를 센다.</b> 대조할 hash를 돌려준다.
     *
     * <p>다른 주소로 보낸 번호는 없는 것으로 본다 — 제공자 이메일로 받은 번호로 연결 대상 계정의 소유를 확인할 수 없어야 한다. 시도를 다 쓴 번호는 지운다.
     */
    public String reserveCodeAttempt(CodeTarget target, Instant now) {
        if (codeHash == null || codeTarget != target) {
            throw new RejectedAttempt(Rejection.NO_CODE);
        }
        if (!now.isBefore(codeExpiresAt)) {
            throw new RejectedAttempt(Rejection.EXPIRED);
        }
        if (codeAttempts >= MAX_CODE_ATTEMPTS) {
            discardCode();
            throw new RejectedAttempt(Rejection.ATTEMPTS_EXHAUSTED);
        }
        codeAttempts++;
        return codeHash;
    }

    /** 맞힌 번호는 다시 쓰지 못하게 지운다. */
    public void consumeCode() {
        discardCode();
    }

    /** 비밀번호 하나를 대조할 자격을 얻는다. 대조 전에 센다. */
    public void reservePasswordAttempt() {
        if (passwordAttempts >= MAX_PASSWORD_ATTEMPTS) {
            throw new RejectedAttempt(Rejection.LIMIT_REACHED);
        }
        passwordAttempts++;
    }

    private void discardCode() {
        codeHash = null;
        codeTarget = null;
        codeExpiresAt = null;
    }

    public String getId() {
        return id;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public CodeTarget getCodeTarget() {
        return codeTarget;
    }

    public Instant getCodeExpiresAt() {
        return codeExpiresAt;
    }

    public int getCodeAttempts() {
        return codeAttempts;
    }

    public int getCodesSent() {
        return codesSent;
    }

    public Instant getLastCodeSentAt() {
        return lastCodeSentAt;
    }

    public int getPasswordAttempts() {
        return passwordAttempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
