package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.util.function.Supplier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 대기 상태를 꺼내고 인증번호·비밀번호 시도를 다루는 공통 규칙. 가입·연결·이메일 확인이 같은 판정을 쓰도록 한 곳에 둔다.
 *
 * <p>만료된 대기 상태는 꺼내는 순간 지운다. 남겨 두면 같은 세션에서 다시 시작해도 옛 상태가 섞인다.
 */
@Component
public class PendingSocialLoginAccess {

    private final PendingSocialLoginStore store;
    private final SocialVerificationGuard guard;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PendingSocialLoginAccess(
            PendingSocialLoginStore store,
            SocialVerificationGuard guard,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.store = store;
        this.guard = guard;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    /** 살아 있는 대기 상태. 없거나 만료됐으면 {@code SOCIAL_PENDING_NOT_FOUND}다(FR-025). */
    public PendingSocialLogin require() {
        PendingSocialLogin pending =
                store.find()
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.SOCIAL_PENDING_NOT_FOUND));
        if (pending.isExpired(clock.instant())) {
            store.clear();
            throw new BusinessException(ErrorCode.SOCIAL_PENDING_NOT_FOUND);
        }
        return pending;
    }

    /** {@link #require()}에 더해 단계까지 맞아야 한다. 맞지 않으면 대기 상태는 그대로 두고 거절한다. */
    public PendingSocialLogin require(Stage stage) {
        PendingSocialLogin pending = require();
        if (pending.stage() != stage) {
            throw new BusinessException(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH);
        }
        return pending;
    }

    /** 새 인증번호의 hash를 기록한다. 간격·횟수 상한을 넘으면 거절한다. */
    public void issueCode(PendingSocialLogin pending, String codeHash, CodeTarget target) {
        guarded(
                () -> {
                    guard.issue(pending.verificationId(), codeHash, target);
                    // 그 계정 이메일로 가는 번호는 대상 계정 단위로도 센다. 제공자 로그인을 다시 해 새 대기 상태로 와도 같은 창에서 센다.
                    if (target == CodeTarget.LINK_TARGET_EMAIL) {
                        guard.reserveTargetSend(pending.linkTargetUserId());
                    }
                    return null;
                });
    }

    /**
     * 대기 상태에 달린 인증번호를 대조한다. 오류 코드는 이메일 가입(FR-004)과 같다 — 화면이 같은 안내를 쓴다.
     *
     * <p>시도는 대조 전에 센다. 번호 하나를 {@link SocialVerification#MAX_CODE_ATTEMPTS}번까지만 대조할 수 있고, 다 쓰면 새로
     * 받아야 한다. 다른 주소로 보낸 번호는 없는 것으로 본다 — 제공자 이메일로 받은 번호로 연결 대상 계정의 소유를 확인할 수 없어야 한다.
     */
    public void verifyCode(PendingSocialLogin pending, CodeTarget target, String code) {
        if (target == CodeTarget.LINK_TARGET_EMAIL) {
            guarded(
                    () -> {
                        guard.reserveTargetAttempt(pending.linkTargetUserId());
                        return null;
                    });
        }
        String hash = guarded(() -> guard.reserveCodeAttempt(pending.verificationId(), target));
        if (code == null || !passwordEncoder.matches(code, hash)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_INVALID);
        }
        guard.consumeCode(pending.verificationId());
    }

    /** 연결 단계 비밀번호 한 번의 대조 자격을 얻는다. 대조 전에 센다. */
    public void reservePasswordAttempt(PendingSocialLogin pending) {
        guarded(
                () -> {
                    guard.reserveTargetAttempt(pending.linkTargetUserId());
                    guard.reservePasswordAttempt(pending.verificationId());
                    return null;
                });
    }

    /** 연결 대상 계정의 주인임을 확인했다. 그 계정을 향한 실패 누적을 지운다. */
    public void ownershipConfirmed(PendingSocialLogin pending) {
        guard.clearTargetAttempts(pending.linkTargetUserId());
    }

    /** 가입·연결이 끝난 대기 상태의 확인 기록을 지운다. */
    public void discardVerification(PendingSocialLogin pending) {
        guard.discard(pending.verificationId());
    }

    private <T> T guarded(Supplier<T> action) {
        try {
            return action.get();
        } catch (SocialVerification.RejectedAttempt rejected) {
            throw new BusinessException(errorOf(rejected.rejection()));
        }
    }

    private ErrorCode errorOf(SocialVerification.Rejection rejection) {
        return switch (rejection) {
            case NO_CODE -> ErrorCode.EMAIL_VERIFICATION_NOT_FOUND;
            case EXPIRED -> ErrorCode.EMAIL_VERIFICATION_EXPIRED;
            case RESEND_TOO_SOON -> ErrorCode.EMAIL_VERIFICATION_RESEND_TOO_SOON;
            case TARGET_LOCKED -> ErrorCode.SOCIAL_LINK_TEMPORARILY_LOCKED;
            case LIMIT_REACHED -> {
                // 상한을 넘었으면 대기 상태를 버린다. 제공자 로그인부터 다시 해야 새 기록으로 시작한다.
                store.clear();
                yield ErrorCode.SOCIAL_VERIFICATION_LIMIT;
            }
        };
    }
}
