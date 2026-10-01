package com.ssafy.thorp.account.domain.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 제공자 로그인은 끝났지만 추가 정보 입력·이메일 확인·기존 계정 연결 확인을 기다리는 상태(spec 014 FR-025).
 *
 * <p><b>서버 세션에만 둔다.</b> 식별자를 URL로 넘기지 않고, 제공자 token은 담지 않는다. 만든 지 {@link #TTL} 뒤에 만료되고, 가입·연결이 끝나거나
 * 취소되면 바로 지운다(research D12).
 *
 * <p><b>인증번호와 시도 횟수는 여기 두지 않는다.</b> 세션은 동시에 들어온 요청이 같은 사본을 읽으므로, 오답 수를 세션에 세면 병렬 요청으로 상한을 넘길 수 있다.
 * 그 값은 {@link #verificationId()}로 찾는 {@link SocialVerification}이 DB 행 잠금 아래에서 센다(2026-10-01 code
 * review).
 *
 * @param emailCheckedByThorp 제공자가 아니라 같은 대기 상태의 Thorp 인증번호로 이메일을 확인했는가. 그 이메일의 소유 확인이 된다(FR-024
 *     (2)). 대기 상태가 들고 다니므로 단계를 다시 판정해도 잃지 않는다.
 */
public record PendingSocialLogin(
        String verificationId,
        SocialProvider provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        boolean emailCheckedByThorp,
        String providerNickname,
        Stage stage,
        Long linkTargetUserId,
        LinkReason linkReason,
        boolean linkTargetEmailVerified,
        Instant createdAt)
        implements Serializable {

    @Serial private static final long serialVersionUID = 2L;

    /** 대기 상태의 수명(FR-025). */
    public static final Duration TTL = Duration.ofMinutes(10);

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

    public PendingSocialLogin {
        Objects.requireNonNull(verificationId, "verificationId");
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
                UUID.randomUUID().toString(),
                provider,
                providerUserId,
                email,
                emailVerified,
                false,
                providerNickname,
                emailVerified ? Stage.SIGNUP : Stage.EMAIL_VERIFICATION,
                null,
                null,
                false,
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
        return copy(Stage.SIGNUP, null, null, false, true, true);
    }

    public PendingSocialLogin toSignup() {
        return copy(Stage.SIGNUP, null, null, false, emailVerified, emailCheckedByThorp);
    }

    /**
     * 기존 계정 연결 확인 단계로 간다.
     *
     * <p>이메일이 겹친 연결이면 {@link #emailCheckedByThorp}를 소유 확인으로 인정한다. 휴대폰 번호로 찾은 계정이면 인정하지 않는다 — 제공자
     * 이메일과 그 계정 이메일은 다르다(FR-024).
     */
    public PendingSocialLogin toLink(Long targetUserId, LinkReason reason) {
        return copy(
                Stage.LINK,
                targetUserId,
                reason,
                reason == LinkReason.EMAIL && emailCheckedByThorp,
                emailVerified,
                emailCheckedByThorp);
    }

    private PendingSocialLogin copy(
            Stage nextStage,
            Long targetUserId,
            LinkReason reason,
            boolean targetEmailVerified,
            boolean nextEmailVerified,
            boolean nextEmailCheckedByThorp) {
        // 확인 기록(verificationId)은 단계를 바꿔도 같다. 단계를 오가며 발송·시도 상한을 초기화하지 못하게 한다.
        return new PendingSocialLogin(
                verificationId,
                provider,
                providerUserId,
                email,
                nextEmailVerified,
                nextEmailCheckedByThorp,
                providerNickname,
                nextStage,
                targetUserId,
                reason,
                targetEmailVerified,
                createdAt);
    }
}
