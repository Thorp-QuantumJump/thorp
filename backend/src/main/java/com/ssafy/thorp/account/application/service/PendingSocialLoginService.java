package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.application.port.SocialProviderCatalog;
import com.ssafy.thorp.account.application.port.VerificationMailSender;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.result.PendingSocialView;
import com.ssafy.thorp.account.application.result.SocialLoginOutcome;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 대기 상태의 조회·취소와 소셜 경로의 인증번호(spec 014 FR-022, FR-024 (2)).
 *
 * <p>인증번호 규칙은 이메일 가입과 같다 — 6자리, 3분, 재발송하면 앞의 번호는 맞지 않는다(FR-004). 발송 주소는 FE가 아니라 대기 상태가 정한다. 그래야 남의
 * 주소로 번호를 보내게 할 수 없다.
 */
@Service
public class PendingSocialLoginService {

    /** 휴대폰 번호로 찾은 계정의 확인 방법. 계정마다 달라지면 그 차이가 정보가 되므로 늘 같다. */
    private static final List<String> PHONE_MATCH_METHODS = List.of("PASSWORD", "EMAIL_CODE");

    private final PendingSocialLoginAccess access;
    private final PendingSocialLoginStore store;
    private final SocialLoginService socialLoginService;
    private final SocialLinkService socialLinkService;
    private final SocialAccountRepository socialAccountRepository;
    private final SocialProviderCatalog providerCatalog;
    private final PasswordEncoder passwordEncoder;
    private final VerificationMailSender mailSender;
    private final Clock clock;

    public PendingSocialLoginService(
            PendingSocialLoginAccess access,
            PendingSocialLoginStore store,
            SocialLoginService socialLoginService,
            SocialLinkService socialLinkService,
            SocialAccountRepository socialAccountRepository,
            SocialProviderCatalog providerCatalog,
            PasswordEncoder passwordEncoder,
            VerificationMailSender mailSender,
            Clock clock) {
        this.access = access;
        this.store = store;
        this.socialLoginService = socialLoginService;
        this.socialLinkService = socialLinkService;
        this.socialAccountRepository = socialAccountRepository;
        this.providerCatalog = providerCatalog;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.clock = clock;
    }

    public PendingSocialView view() {
        PendingSocialLogin pending = access.require();
        if (pending.stage() != Stage.LINK) {
            return new PendingSocialView(
                    pending.provider().registrationId(),
                    pending.stage().name(),
                    pending.email(),
                    pending.providerNickname(),
                    null,
                    null,
                    List.of(),
                    pending.expiresAt());
        }

        if (pending.linkReason() == LinkReason.PHONE) {
            // 휴대폰 번호로 찾은 계정이면 그 계정에 대해 아무것도 알려 주지 않고, 읽지도 않는다. 요청한 사람은 그 번호의 주인임이 확인되지
            // 않았다 — 남의 번호를 넣는 것만으로 이메일 일부·도메인·연결된 제공자·비밀번호 유무·계정 상태를 알아낼 수 있으면 안 된다.
            // 확인 방법은 고정이고, 인증번호는 서버가 그 계정 이메일로 보내므로 주소를 보여 주지 않아도 주인은 확인할 수 있다.
            return new PendingSocialView(
                    pending.provider().registrationId(),
                    pending.stage().name(),
                    pending.email(),
                    pending.providerNickname(),
                    pending.linkReason().name(),
                    null,
                    PHONE_MATCH_METHODS,
                    pending.expiresAt());
        }
        User target = socialLinkService.activeTarget(pending);
        return new PendingSocialView(
                pending.provider().registrationId(),
                pending.stage().name(),
                pending.email(),
                pending.providerNickname(),
                pending.linkReason().name(),
                maskEmail(target.getEmail()),
                linkMethods(pending, target),
                pending.expiresAt());
    }

    /** 대기 상태를 버린다. 없어도 같은 결과다. */
    public void cancel() {
        store.clear();
    }

    /**
     * 대기 상태가 정한 주소로 인증번호를 보낸다. 이메일 확인 단계면 제공자 이메일, 연결 단계면 연결 대상 계정 이메일이다.
     *
     * <p>세션에 새 번호를 먼저 적고 메일을 보낸다. 메일이 실패해도 앞의 번호는 이미 무효다 — 재발송 규칙과 같다.
     */
    public void sendCode() {
        PendingSocialLogin pending = access.require();
        String address;
        CodeTarget target;
        switch (pending.stage()) {
            case EMAIL_VERIFICATION -> {
                address = pending.email();
                target = CodeTarget.PROVIDER_EMAIL;
            }
            case LINK -> {
                address = linkTargetAddress(pending);
                target = CodeTarget.LINK_TARGET_EMAIL;
            }
            default -> throw new BusinessException(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH);
        }

        // 받는 사람이 요청한 사람과 다를 수 있다(연결 대상 계정 이메일). 간격·횟수 상한은 확인 기록이 잠근 행 위에서 판정한다.
        String code = VerificationCodes.next();
        access.issueCode(pending, passwordEncoder.encode(code), target);
        // 번호로 찾은 계정이 쓸 수 없으면 번호만 기록하고 메일은 보내지 않는다. 응답은 똑같이 성공이다 — 보낼 수 없다고 알리면 그 계정
        // 상태가 드러난다. 받은 사람이 없으니 그 번호로는 아무도 확인하지 못한다.
        if (address != null) {
            mailSender.sendVerificationCode(address, code);
        }
    }

    /**
     * 제공자 이메일을 인증번호로 확인하고 다음 단계를 정한다(FR-022). 확인이 끝나면 가입 또는 연결 확인으로 이어진다.
     *
     * <p>이 확인은 그 이메일의 소유 확인이기도 하다. 이메일이 기존 계정과 같으면 연결 단계에서 인증번호를 다시 받지 않는다(US7 시나리오 6).
     */
    public PendingSocialView confirmProviderEmail(String code) {
        PendingSocialLogin pending = access.require(Stage.EMAIL_VERIFICATION);
        access.verifyCode(pending, CodeTarget.PROVIDER_EMAIL, code);

        SocialLoginOutcome outcome = socialLoginService.advance(pending.withEmailVerified());
        return switch (outcome.type()) {
            case ACCOUNT_UNAVAILABLE ->
                    throw new BusinessException(ErrorCode.SOCIAL_SIGNUP_REJECTED);
            case PROVIDER_CONFLICT ->
                    throw new BusinessException(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED);
            default -> view();
        };
    }

    /**
     * 연결 대상 계정 이메일. 휴대폰 번호로 찾은 계정이 쓸 수 없으면 {@code null}이다 — 그 사실을 응답으로 드러내지 않는다. 이메일로 찾은 계정은 요청한
     * 사람이 그 이메일의 주인이라 상태를 알려도 되고, 쓸 수 없으면 일반 거절이다.
     */
    private String linkTargetAddress(PendingSocialLogin pending) {
        if (pending.linkReason() != LinkReason.PHONE) {
            return socialLinkService.activeTarget(pending).getEmail();
        }
        return socialLinkService.usablePhoneTarget(pending).map(User::getEmail).orElse(null);
    }

    /** 화면에 보여 줄 소유 확인 방법. 대상 계정에 실제로 쓸 수 있는 것만 담는다. */
    private List<String> linkMethods(PendingSocialLogin pending, User target) {
        List<String> methods = new ArrayList<>();
        if (pending.linkTargetEmailVerified()) {
            methods.add("VERIFIED_EMAIL");
        }
        if (target.hasPassword()) {
            methods.add("PASSWORD");
        }
        methods.add("EMAIL_CODE");
        for (SocialAccount account : socialAccountRepository.findByUserId(target.getId())) {
            SocialProvider provider = account.getProvider();
            if (providerCatalog.isEnabled(provider)) {
                methods.add("SOCIAL_" + provider.name());
            }
        }
        return List.copyOf(methods);
    }

    /** {@code member@example.com} → {@code m***@example.com}. 연결 대상이 누구인지 짐작할 수 있을 만큼만 남긴다. */
    static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }
}
