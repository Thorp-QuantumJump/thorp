package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.command.SocialLinkCommand;
import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.CodeTarget;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 같은 이메일·휴대폰 번호의 기존 계정에 소셜 계정을 연결한다(spec 014 US7, FR-024).
 *
 * <p><b>소유를 확인해야만 연결한다.</b> 버튼 한 번으로 합치면, 남의 이메일을 내세운 소셜 계정이 그 사람 계정에 들어갈 수 있다. 확인 방법은 셋이다 — 기존 계정
 * 비밀번호, 기존 계정 이메일로 보낸 인증번호, 같은 대기 상태에서 이미 그 이메일을 인증번호로 확인한 사실. 이미 연결된 다른 소셜 계정으로 확인하는 넷째 방법은 제공자
 * 로그인을 한 번 더 거치므로 {@link SocialLoginService}가 맡고 여기 {@link #attach}만 부른다.
 */
@Service
public class SocialLinkService {

    private static final Logger log = LoggerFactory.getLogger(SocialLinkService.class);

    private final PendingSocialLoginAccess access;
    private final PendingSocialLoginStore store;
    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationService authenticationService;
    private final Clock clock;

    public SocialLinkService(
            PendingSocialLoginAccess access,
            PendingSocialLoginStore store,
            UserRepository userRepository,
            SocialAccountRepository socialAccountRepository,
            PasswordEncoder passwordEncoder,
            AuthenticationService authenticationService,
            Clock clock) {
        this.access = access;
        this.store = store;
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationService = authenticationService;
        this.clock = clock;
    }

    @Transactional
    public AuthenticatedUserResult link(
            SocialLinkCommand command, HttpServletRequest request, HttpServletResponse response) {
        PendingSocialLogin pending = access.require(Stage.LINK);
        User target = activeTarget(pending);

        switch (command.method()) {
            case PASSWORD -> {
                // 소셜 전용 계정(비밀번호 없음)도 틀린 비밀번호와 같은 거절이다. 화면은 이 방법을 보여 주지 않지만 요청을 직접 보내도 차이가 없어야 한다.
                if (!target.hasPassword()
                        || command.password() == null
                        || !passwordEncoder.matches(command.password(), target.getPasswordHash())) {
                    log.warn(
                            "Social link rejected: reason=PASSWORD_MISMATCH, userId={}",
                            target.getId());
                    throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
                }
            }
            case EMAIL_CODE ->
                    access.verifyCode(
                            pending, CodeTarget.LINK_TARGET_EMAIL, command.verificationCode());
            case VERIFIED_EMAIL -> {
                // 휴대폰 번호로 찾은 계정에는 성립하지 않는다 — 제공자 이메일과 그 계정 이메일은 다르다(FR-024).
                if (pending.linkReason() != LinkReason.EMAIL
                        || !pending.linkTargetEmailVerified()) {
                    throw new BusinessException(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH);
                }
            }
        }

        attach(pending, target);
        return authenticationService.startSession(target, request, response);
    }

    /** 대기 중인 소셜 계정을 대상 계정에 붙이고 대기 상태를 지운다. 소유 확인은 호출하는 쪽이 마쳤어야 한다. */
    @Transactional
    public void attach(PendingSocialLogin pending, User target) {
        boolean sameProviderLinked =
                socialAccountRepository.findByUserId(target.getId()).stream()
                        .anyMatch(account -> account.getProvider() == pending.provider());
        if (sameProviderLinked) {
            store.clear();
            throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
        }
        socialAccountRepository.save(
                SocialAccount.link(
                        target.getId(),
                        pending.provider(),
                        pending.providerUserId(),
                        clock.instant()));
        store.clear();
        log.info(
                "Social account linked: userId={}, provider={}",
                target.getId(),
                pending.provider());
    }

    /** 연결 대상 계정. 그 사이에 정지·탈퇴됐으면 상태를 드러내지 않는 일반 거절이다(spec Edge Cases). */
    public User activeTarget(PendingSocialLogin pending) {
        return userRepository
                .findById(pending.linkTargetUserId())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .orElseThrow(
                        () -> {
                            store.clear();
                            return new BusinessException(ErrorCode.SOCIAL_SIGNUP_REJECTED);
                        });
    }
}
