package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.command.SocialSignupCommand;
import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.application.result.SocialLoginOutcome;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 처음 오는 소셜 사용자의 가입(spec 014 US6, FR-023).
 *
 * <p>닉네임·휴대폰 번호만 받는다. 이메일은 대기 상태의 확인된 값이고 비밀번호는 없다. 계정 생성과 소셜 계정 연결은 한 트랜잭션이라, 둘 중 하나만 남는 일이 없다.
 *
 * <p><b>휴대폰 번호가 겹치면 거절로 끝내지 않는다.</b> 이메일이 다른 같은 사람일 수 있으므로 그 계정의 연결 확인으로 보낸다(clarify 2026-10-01).
 * 번호 자체는 확인된 정보가 아니라서 번호만으로 합치지 않고, 연결 단계의 소유 확인을 반드시 거친다.
 */
@Service
public class SocialSignupService {

    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^01[016789]\\d{7,8}$");

    private final PendingSocialLoginAccess access;
    private final PendingSocialLoginStore store;
    private final SocialLoginService socialLoginService;
    private final UserRepository userRepository;
    private final SocialAccountRepository socialAccountRepository;
    private final AuthenticationService authenticationService;
    private final Clock clock;

    public SocialSignupService(
            PendingSocialLoginAccess access,
            PendingSocialLoginStore store,
            SocialLoginService socialLoginService,
            UserRepository userRepository,
            SocialAccountRepository socialAccountRepository,
            AuthenticationService authenticationService,
            Clock clock) {
        this.access = access;
        this.store = store;
        this.socialLoginService = socialLoginService;
        this.userRepository = userRepository;
        this.socialAccountRepository = socialAccountRepository;
        this.authenticationService = authenticationService;
        this.clock = clock;
    }

    @Transactional
    public AuthenticatedUserResult signup(
            SocialSignupCommand command, HttpServletRequest request, HttpServletResponse response) {
        PendingSocialLogin pending = access.require(Stage.SIGNUP);

        if (command.phoneNumber() == null
                || !PHONE_NUMBER_PATTERN.matcher(command.phoneNumber()).matches()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "국내 휴대폰 번호 형식이어야 합니다. 예: 01012345678");
        }

        // 추가 정보를 입력하는 사이에 같은 이메일로 누가 가입했을 수 있다. 판정을 다시 돌려 연결 확인으로 보낸다.
        if (userRepository.existsByEmail(pending.email())) {
            SocialLoginOutcome outcome = socialLoginService.advance(pending);
            throw new BusinessException(rejectionOf(outcome));
        }

        Optional<User> phoneOwner = userRepository.findByPhoneNumber(command.phoneNumber());
        if (phoneOwner.isPresent()) {
            // 번호의 주인이 어떤 계정이든 응답은 같다. 계정 상태(정지·탈퇴)나 같은 제공자 연결 여부에 따라 응답이 갈리면, 남의 번호를
            // 넣는 것만으로 그 사실을 알아낼 수 있다. 쓸 수 없는 계정이거나 같은 제공자가 이미 있으면 소유 확인 뒤에야 드러난다.
            User owner = phoneOwner.get();
            store.save(pending.toLink(owner.getId(), LinkReason.PHONE));
            throw new BusinessException(ErrorCode.SOCIAL_LINK_REQUIRED);
        }

        User user =
                userRepository.save(
                        User.registerSocial(
                                pending.email(), command.nickname(), command.phoneNumber()));
        socialAccountRepository.save(
                SocialAccount.link(
                        user.getId(),
                        pending.provider(),
                        pending.providerUserId(),
                        clock.instant()));
        store.clear();
        access.discardVerification(pending);
        return authenticationService.startSession(user, request, response);
    }

    private static ErrorCode rejectionOf(SocialLoginOutcome outcome) {
        return switch (outcome.type()) {
            case LINK_REQUIRED -> ErrorCode.SOCIAL_LINK_REQUIRED;
            case PROVIDER_CONFLICT -> ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED;
            default -> ErrorCode.SOCIAL_SIGNUP_REJECTED;
        };
    }
}
