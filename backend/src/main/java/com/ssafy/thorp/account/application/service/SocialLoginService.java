package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.EmailNormalizer;
import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.application.port.PendingSocialLoginStore;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.SocialLoginOutcome;
import com.ssafy.thorp.account.application.result.SocialLoginOutcome.Type;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 제공자 콜백을 판정한다(spec 014 US6·US7, plan Outcome classification).
 *
 * <p><b>세션 인증은 여기서 정하지 않는다.</b> 판정만 돌려주고, 콜백 처리기가 그 판정으로 세션을 인증하거나 비운다. 대기 상태만 이 서비스가 저장한다.
 *
 * <p>소셜 계정은 제공자 사용자 식별자로 찾는다. 이메일로 찾지 않는다 — 제공자 쪽 이메일이 바뀌어도 연결은 유지되어야 하고(spec Edge Cases), 이메일이 같다는
 * 사실만으로는 같은 사람이라고 볼 수 없다(FR-024).
 */
@Service
public class SocialLoginService {

    private static final Logger log = LoggerFactory.getLogger(SocialLoginService.class);

    private static final int NICKNAME_MIN = 2;
    private static final int NICKNAME_MAX = 20;

    private final SocialAccountRepository socialAccountRepository;
    private final UserRepository userRepository;
    private final PendingSocialLoginStore store;
    private final PendingSocialLoginAccess access;
    private final SocialLinkService socialLinkService;
    private final Clock clock;

    public SocialLoginService(
            SocialAccountRepository socialAccountRepository,
            UserRepository userRepository,
            PendingSocialLoginStore store,
            PendingSocialLoginAccess access,
            SocialLinkService socialLinkService,
            Clock clock) {
        this.socialAccountRepository = socialAccountRepository;
        this.userRepository = userRepository;
        this.store = store;
        this.access = access;
        this.socialLinkService = socialLinkService;
        this.clock = clock;
    }

    /**
     * 콜백 하나를 판정한다.
     *
     * @param linkIntent 대기 중인 연결을 이미 연결된 다른 소셜 계정으로 확인하려고 시작한 로그인인가({@code intent=link}).
     */
    @Transactional
    public SocialLoginOutcome resolve(SocialProfile profile, boolean linkIntent) {
        Optional<SocialAccount> linked =
                socialAccountRepository.findByProviderAndProviderUserId(
                        profile.provider(), profile.providerUserId());

        if (linkIntent) {
            return confirmLinkWithSocial(profile.provider(), linked);
        }

        // 새 소셜 로그인이 시작되면 이전 대기 상태는 쓰지 않는다(FR-025).
        store.clear();

        if (linked.isPresent()) {
            return userRepository
                    .findById(linked.get().getUserId())
                    .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                    .map(user -> SocialLoginOutcome.loggedIn(profile.provider(), user.getId()))
                    .orElseGet(
                            () -> {
                                log.warn(
                                        "Social login rejected: reason=ACCOUNT_NOT_ACTIVE, userId={}",
                                        linked.get().getUserId());
                                return SocialLoginOutcome.of(
                                        Type.ACCOUNT_UNAVAILABLE, profile.provider());
                            });
        }

        if (!profile.hasEmail()) {
            return SocialLoginOutcome.of(Type.EMAIL_MISSING, profile.provider());
        }

        PendingSocialLogin pending =
                PendingSocialLogin.start(
                        profile.provider(),
                        profile.providerUserId(),
                        EmailNormalizer.normalize(profile.email()),
                        profile.emailStatus() == EmailStatus.VERIFIED,
                        usableNickname(profile.nickname()),
                        clock.instant());

        if (!pending.emailVerified()) {
            store.save(pending);
            return SocialLoginOutcome.of(Type.EMAIL_VERIFICATION, profile.provider());
        }
        return advance(pending, false);
    }

    /**
     * 확인된 이메일로 다음 단계를 정해 대기 상태에 저장한다.
     *
     * @param emailCheckedByThorp 같은 대기 상태에서 그 이메일을 Thorp 인증번호로 확인했는가. 그 이메일이 기존 계정 이메일과 같으면 소유 확인을
     *     마친 것으로 본다(FR-024 (2)).
     */
    @Transactional(readOnly = true)
    public SocialLoginOutcome advance(PendingSocialLogin pending, boolean emailCheckedByThorp) {
        Optional<User> owner = userRepository.findByEmail(pending.email());
        if (owner.isEmpty()) {
            store.save(pending.toSignup());
            return SocialLoginOutcome.of(Type.SIGNUP_REQUIRED, pending.provider());
        }

        User user = owner.get();
        if (user.getStatus() != UserStatus.ACTIVE) {
            store.clear();
            return SocialLoginOutcome.of(Type.ACCOUNT_UNAVAILABLE, pending.provider());
        }
        if (hasProvider(user.getId(), pending.provider())) {
            store.clear();
            return SocialLoginOutcome.of(Type.PROVIDER_CONFLICT, pending.provider());
        }
        store.save(pending.toLink(user.getId(), LinkReason.EMAIL, emailCheckedByThorp));
        return SocialLoginOutcome.of(Type.LINK_REQUIRED, pending.provider());
    }

    public boolean hasProvider(Long userId, SocialProvider provider) {
        return socialAccountRepository.findByUserId(userId).stream()
                .anyMatch(account -> account.getProvider() == provider);
    }

    /**
     * {@code intent=link}로 돌아온 콜백. 그 소셜 계정이 연결 대상 계정의 것일 때만 대기 중인 연결을 마친다(US7 시나리오 2).
     *
     * <p>대상 계정의 것이 아니면 연결하지 않는다. 대기 상태는 남겨 두어 사용자가 다른 방법으로 다시 확인할 수 있게 한다. 대기 상태가 없거나 만료됐으면 판정은 같고,
     * 화면이 대기 상태 조회로 만료를 안내한다.
     */
    private SocialLoginOutcome confirmLinkWithSocial(
            SocialProvider provider, Optional<SocialAccount> linked) {
        PendingSocialLogin pending;
        try {
            pending = access.require(Stage.LINK);
        } catch (BusinessException noPendingLink) {
            return SocialLoginOutcome.of(Type.LINK_FAILED, provider);
        }
        if (linked.isEmpty() || !linked.get().getUserId().equals(pending.linkTargetUserId())) {
            log.warn("Social link rejected: reason=OTHER_ACCOUNT, provider={}", provider);
            return SocialLoginOutcome.of(Type.LINK_FAILED, provider);
        }
        // 거절 사유는 attach를 부르기 전에 모두 거른다. 트랜잭션 메서드가 던진 예외를 여기서 삼키면 바깥 트랜잭션이 rollback-only로 남아
        // 커밋에서 터진다.
        User target;
        try {
            target = socialLinkService.activeTarget(pending);
        } catch (BusinessException unavailable) {
            return SocialLoginOutcome.of(Type.LINK_FAILED, provider);
        }
        if (hasProvider(target.getId(), pending.provider())) {
            store.clear();
            return SocialLoginOutcome.of(Type.LINK_FAILED, provider);
        }
        socialLinkService.attach(pending, target);
        return SocialLoginOutcome.loggedIn(pending.provider(), target.getId());
    }

    /** 제공자 닉네임이 가입 규칙(2~20자)에 맞을 때만 추가 정보 화면에 미리 채운다(US6 시나리오 3). */
    static String usableNickname(String nickname) {
        if (nickname == null) {
            return null;
        }
        String stripped = nickname.strip();
        int length = stripped.codePointCount(0, stripped.length());
        return length >= NICKNAME_MIN && length <= NICKNAME_MAX ? stripped : null;
    }
}
