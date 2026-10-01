package com.ssafy.thorp.account.domain.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 제공자 로그인은 끝났지만 추가 정보 입력·이메일 확인·기존 계정 연결 확인을 기다리는 상태(spec 014 FR-025).
 *
 * <p><b>서버 세션에만 둔다.</b> 식별자를 URL로 넘기지 않고, 제공자 token은 담지 않는다. 만든 지 {@link #TTL} 뒤에 만료되고, 가입·연결이 끝나거나
 * 취소되면 바로 지운다(research D12).
 *
 * <p>인증번호는 원문이 아니라 hash로만 담는다. 소셜 경로의 인증번호를 기존 {@code email_verifications}에 두지 않는 이유는 그 테이블이 "가입되지
 * 않은 이메일" 전용이기 때문이다 — 연결 확인은 이미 가입된 이메일로 보내야 한다.
 */
public record PendingSocialLogin(
        SocialProvider provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String providerNickname,
        Stage stage,
        Long linkTargetUserId,
        LinkReason linkReason,
        boolean linkTargetEmailVerified,
        String codeHash,
        CodeTarget codeTarget,
        Instant codeExpiresAt,
        Instant createdAt)
        implements Serializable {

    @Serial private static final long serialVersionUID = 1L;

    /** 대기 상태의 수명(FR-025). */
    public static final Duration TTL = Duration.ofMinutes(10);

    /** 소셜 경로 인증번호의 수명. 이메일 가입(FR-004)과 같다. */
    public static final Duration CODE_TTL = Duration.ofMinutes(3);

    public enum Stage {
        /** 제공자가 이메일 확인 여부를 알려주지 않았거나 미확인이라고 알렸다. Thorp 인증번호로 확인해야 한다(FR-022). */
        EMAIL_VERIFICATION,
        /** 새 계정을 만들 차례다. 닉네임·휴대폰 번호를 받는다(FR-023). */
        SIGNUP,
        /** 이메일이나 휴대폰 번호가 기존 계정과 겹쳤다. 소유를 확인해야 연결한다(FR-024). */
        LINK
    }

    public enum LinkReason {
        EMAIL,
        PHONE
    }

    /** 인증번호를 보낸 주소. */
    public enum CodeTarget {
        PROVIDER_EMAIL,
        LINK_TARGET_EMAIL
    }

    public PendingSocialLogin {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(createdAt, "createdAt");
        if (providerUserId == null || providerUserId.isBlank()) {
            throw new IllegalArgumentException("providerUserId must not be blank");
        }
        if (stage == Stage.LINK && (linkTargetUserId == null || linkReason == null)) {
            throw new IllegalArgumentException("LINK stage needs a target account and a reason");
        }
    }

    /** 제공자 로그인 직후의 상태. 이메일 확인 여부에 따라 시작 단계가 갈린다. */
    public static PendingSocialLogin start(
            SocialProvider provider,
            String providerUserId,
            String email,
            boolean emailVerified,
            String providerNickname,
            Instant now) {
        return new PendingSocialLogin(
                provider,
                providerUserId,
                email,
                emailVerified,
                providerNickname,
                emailVerified ? Stage.SIGNUP : Stage.EMAIL_VERIFICATION,
                null,
                null,
                false,
                null,
                null,
                null,
                now);
    }

    public Instant expiresAt() {
        return createdAt.plus(TTL);
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt());
    }

    /** 제공자 이메일을 Thorp 인증번호로 확인했다. 단계는 판정을 다시 돌려 정한다. */
    public PendingSocialLogin withEmailVerified() {
        return new PendingSocialLogin(
                provider,
                providerUserId,
                email,
                true,
                providerNickname,
                Stage.SIGNUP,
                null,
                null,
                false,
                null,
                null,
                null,
                createdAt);
    }

    public PendingSocialLogin toSignup() {
        return new PendingSocialLogin(
                provider,
                providerUserId,
                email,
                emailVerified,
                providerNickname,
                Stage.SIGNUP,
                null,
                null,
                false,
                null,
                null,
                null,
                createdAt);
    }

    /**
     * 기존 계정 연결 확인 단계로 간다.
     *
     * @param targetEmailVerified 같은 대기 상태에서 대상 계정 이메일을 인증번호로 이미 확인했는가. 이메일이 겹친 경우에만 의미가 있다 — 휴대폰
     *     번호로 찾은 계정이면 제공자 이메일 확인을 소유 확인으로 인정하지 않는다(FR-024).
     */
    public PendingSocialLogin toLink(
            Long targetUserId, LinkReason reason, boolean targetEmailVerified) {
        return new PendingSocialLogin(
                provider,
                providerUserId,
                email,
                emailVerified,
                providerNickname,
                Stage.LINK,
                targetUserId,
                reason,
                reason == LinkReason.EMAIL && targetEmailVerified,
                null,
                null,
                null,
                createdAt);
    }

    /** 새 인증번호를 단다. 앞서 보낸 번호는 더 이상 맞지 않는다. */
    public PendingSocialLogin withCode(String hash, CodeTarget target, Instant codeExpiresAt) {
        return new PendingSocialLogin(
                provider,
                providerUserId,
                email,
                emailVerified,
                providerNickname,
                stage,
                linkTargetUserId,
                linkReason,
                linkTargetEmailVerified,
                Objects.requireNonNull(hash, "hash"),
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(codeExpiresAt, "codeExpiresAt"),
                createdAt);
    }
}
