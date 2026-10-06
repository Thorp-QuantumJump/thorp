package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.command.SocialLinkCommand;
import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
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

    /**
     * 소유를 확인하고 연결한 뒤 로그인시킨다.
     *
     * <p><b>바깥 트랜잭션을 열지 않는다.</b> 시도 기록은 {@link SocialVerificationGuard}가 따로 연 트랜잭션에서 센다. 바깥 트랜잭션이
     * DB 연결을 쥔 채 두 번째 연결을 기다리면, 동시 요청이 연결 풀 크기를 넘는 순간 모두가 서로를 기다리며 멈춘다(병렬 대입 통합 테스트가 잡았다). 연결 저장과
     * 세션 시작은 각자 짧은 트랜잭션이다 — 연결은 됐는데 세션 시작이 실패하면 사용자는 그 소셜 계정으로 다시 로그인하면 된다.
     */
    public AuthenticatedUserResult link(
            SocialLinkCommand command, HttpServletRequest request, HttpServletResponse response) {
        PendingSocialLogin pending = access.require(Stage.LINK);
        // 휴대폰 번호로 찾은 계정은 쓸 수 없어도 여기서 거절하지 않는다. 확인을 똑같이 거친 뒤 "틀렸다"로 끝낸다 — 상태가 다른 응답으로
        // 갈리면 남의 번호로 그 계정의 상태를 알아낼 수 있다.
        boolean phoneMatch = pending.linkReason() == LinkReason.PHONE;
        User target = phoneMatch ? usablePhoneTarget(pending).orElse(null) : activeTarget(pending);

        switch (command.method()) {
            case PASSWORD -> {
                // 비밀번호도 남의 계정에 들어가는 열쇠다. 대조 전에 시도를 세고, 상한을 넘으면 대기 상태를 버린다.
                access.reservePasswordAttempt(pending);
                // 소셜 전용 계정(비밀번호 없음)도 틀린 비밀번호와 같은 거절이다. 화면은 이 방법을 보여 주지 않지만 요청을 직접 보내도 차이가 없어야 한다.
                if (target == null
                        || !target.hasPassword()
                        || command.password() == null
                        || !passwordEncoder.matches(command.password(), target.getPasswordHash())) {
                    log.warn(
                            "Social link rejected: reason=PASSWORD_MISMATCH, userId={}",
                            pending.linkTargetUserId());
                    throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
                }
            }
            case EMAIL_CODE -> {
                access.verifyCode(
                        pending, CodeTarget.LINK_TARGET_EMAIL, command.verificationCode());
                // 쓸 수 없는 계정에는 메일을 보내지 않았으니 맞힐 수 없지만, 만에 하나 맞혀도 틀린 번호와 같게 끝낸다.
                if (target == null) {
                    throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_INVALID);
                }
            }
            case VERIFIED_EMAIL -> {
                // 휴대폰 번호로 찾은 계정에는 성립하지 않는다 — 제공자 이메일과 그 계정 이메일은 다르다(FR-024).
                if (pending.linkReason() != LinkReason.EMAIL
                        || !pending.linkTargetEmailVerified()) {
                    throw new BusinessException(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH);
                }
            }
        }

        access.ownershipConfirmed(pending);
        attach(pending, target);
        return authenticationService.startSession(target, request, response);
    }

    /**
     * 연결하지 않기로 했다(US7 시나리오 3·4).
     *
     * <p>휴대폰 번호가 겹쳐 온 연결이면 가입 단계로 돌아가 다른 번호로 가입을 이어 갈 수 있다. 이메일이 겹쳐 온 연결이면 그 이메일로는 새 계정을 만들 수 없으므로
     * 대기 상태를 지운다. 어느 쪽이든 아무것도 연결하거나 만들지 않는다.
     *
     * @return 가입 단계로 돌아갔으면 {@code true}
     */
    public boolean decline() {
        PendingSocialLogin pending = access.require(Stage.LINK);
        if (pending.linkReason() == LinkReason.PHONE) {
            store.save(pending.toSignup());
            return true;
        }
        store.clear();
        return false;
    }

    /**
     * 대기 중인 소셜 계정을 대상 계정에 붙이고 대기 상태를 지운다. 소유 확인은 호출하는 쪽이 마쳤어야 한다.
     *
     * <p>트랜잭션으로 묶지 않는다. {@link #link}가 같은 빈 안에서 부르므로 어노테이션을 달아도 프록시를 지나지 않고, 바깥 트랜잭션은 확인 기록의 {@code
     * REQUIRES_NEW}와 연결 풀을 두고 교착했다. 같은 제공자 중복은 DB 고유 제약이 최종 방어이고, 연결 뒤 기록 정리가 실패해도 정리는 한 시간마다 도는
     * 청소가 맡는다.
     */
    public void attach(PendingSocialLogin pending, User target) {
        if (socialAccountRepository.existsByUserIdAndProvider(target.getId(), pending.provider())) {
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
        access.discardVerification(pending);
        log.info(
                "Social account linked: userId={}, provider={}",
                target.getId(),
                pending.provider());
    }

    /** 휴대폰 번호로 찾은 연결 대상 계정 중 쓸 수 있는 것. 없거나 쓸 수 없으면 비어 있다 — 호출하는 쪽이 응답을 갈라서는 안 된다. */
    public java.util.Optional<User> usablePhoneTarget(PendingSocialLogin pending) {
        return userRepository
                .findById(pending.linkTargetUserId())
                .filter(user -> user.getStatus() == UserStatus.ACTIVE);
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
