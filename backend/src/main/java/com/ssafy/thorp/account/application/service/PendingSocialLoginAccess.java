package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.CodeTarget;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 대기 상태를 꺼내고 인증번호를 대조하는 공통 규칙. 가입·연결·이메일 확인이 같은 판정을 쓰도록 한 곳에 둔다.
 *
 * <p>만료된 대기 상태는 꺼내는 순간 지운다. 남겨 두면 같은 세션에서 다시 시작해도 옛 상태가 섞인다.
 */
@Component
public class PendingSocialLoginAccess {

    private final PendingSocialLoginStore store;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public PendingSocialLoginAccess(
            PendingSocialLoginStore store, PasswordEncoder passwordEncoder, Clock clock) {
        this.store = store;
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

    /**
     * 대기 상태에 달린 인증번호를 대조한다. 오류 코드는 이메일 가입(FR-004)과 같다 — 화면이 같은 안내를 쓴다.
     *
     * <p>다른 주소로 보낸 번호는 없는 것으로 본다. 제공자 이메일로 받은 번호로 연결 대상 계정의 소유를 확인할 수 없어야 한다.
     */
    public void verifyCode(PendingSocialLogin pending, CodeTarget target, String code) {
        if (pending.codeHash() == null || pending.codeTarget() != target) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND);
        }
        if (!clock.instant().isBefore(pending.codeExpiresAt())) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_EXPIRED);
        }
        if (code == null || !passwordEncoder.matches(code, pending.codeHash())) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_INVALID);
        }
    }
}
