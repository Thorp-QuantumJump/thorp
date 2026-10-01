package com.ssafy.thorp.account.application;

import static com.ssafy.thorp.account.application.SocialFixtures.NOW;
import static com.ssafy.thorp.account.application.SocialFixtures.PROVIDER_USER_ID;
import static com.ssafy.thorp.account.application.SocialFixtures.activeUser;
import static com.ssafy.thorp.account.application.SocialFixtures.linked;
import static com.ssafy.thorp.account.application.SocialFixtures.profile;
import static com.ssafy.thorp.account.application.SocialFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.account.application.command.SocialProfile;
import com.ssafy.thorp.account.application.command.SocialProfile.EmailStatus;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.SocialLoginOutcome;
import com.ssafy.thorp.account.application.result.SocialLoginOutcome.Type;
import com.ssafy.thorp.account.application.service.AuthenticationService;
import com.ssafy.thorp.account.application.service.PendingSocialLoginAccess;
import com.ssafy.thorp.account.application.service.SocialLinkService;
import com.ssafy.thorp.account.application.service.SocialLoginService;
import com.ssafy.thorp.account.application.service.SocialVerificationGuard;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialAccount;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.account.domain.model.UserStatus;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 콜백 판정 일곱 가지(plan Outcome classification)와 {@code intent=link} 확인을 고정한다(T063).
 *
 * <p>핵심은 두 가지다. 소셜 계정은 제공자 사용자 식별자로만 찾고, 이메일이 같다는 이유로 로그인시키거나 연결하지 않는다(FR-024).
 */
class SocialLoginServiceTest {

    private final InMemorySocialLinkTargetRepository targets =
            new InMemorySocialLinkTargetRepository();

    private static final String EMAIL = "member@example.com";

    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final InMemoryPendingSocialLoginStore store = new InMemoryPendingSocialLoginStore();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private SocialLoginService service;

    @BeforeEach
    void setUp() {
        PendingSocialLoginAccess access =
                new PendingSocialLoginAccess(
                        store,
                        new SocialVerificationGuard(
                                new InMemorySocialVerificationRepository(), targets, clock),
                        mock(PasswordEncoder.class),
                        clock);
        SocialLinkService linkService =
                new SocialLinkService(
                        access,
                        store,
                        users,
                        socialAccounts,
                        mock(PasswordEncoder.class),
                        mock(AuthenticationService.class),
                        clock);
        service = new SocialLoginService(socialAccounts, users, store, access, linkService, clock);
        when(socialAccounts.findByProviderAndProviderUserId(any(), any()))
                .thenReturn(Optional.empty());
        when(socialAccounts.findByUserId(any())).thenReturn(List.of());
        when(socialAccounts.existsByUserIdAndProvider(any(), any())).thenCallRealMethod();
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("이미 연결된 소셜 계정은 활성 계정이면 그 계정으로 로그인 판정이다")
    void linkedAccountLogsIn() {
        when(socialAccounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.of(linked(42L, SocialProvider.KAKAO, PROVIDER_USER_ID)));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, EMAIL)));

        SocialLoginOutcome outcome = service.resolve(kakao(EMAIL, EmailStatus.VERIFIED), false);

        assertThat(outcome.type()).isEqualTo(Type.LOGGED_IN);
        assertThat(outcome.userId()).isEqualTo(42L);
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("연결된 계정이 정지·탈퇴 상태면 로그인시키지 않는다")
    void linkedButInactiveAccountIsUnavailable() {
        when(socialAccounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.of(linked(42L, SocialProvider.KAKAO, PROVIDER_USER_ID)));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.SUSPENDED, "hash")));

        SocialLoginOutcome outcome = service.resolve(kakao(EMAIL, EmailStatus.VERIFIED), false);

        assertThat(outcome.type()).isEqualTo(Type.ACCOUNT_UNAVAILABLE);
        assertThat(outcome.userId()).isNull();
    }

    @Test
    @DisplayName("제공자가 이메일을 주지 않으면 대기 상태 없이 거절 판정이다")
    void missingEmailIsRejectedWithoutPendingState() {
        SocialLoginOutcome outcome = service.resolve(kakao(null, EmailStatus.UNKNOWN), false);

        assertThat(outcome.type()).isEqualTo(Type.EMAIL_MISSING);
        assertThat(store.current()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = EmailStatus.class,
            names = {"UNVERIFIED", "UNKNOWN"})
    @DisplayName("확인 여부를 모르거나 미확인인 이메일은 Thorp 인증번호 단계로 간다")
    void unverifiedEmailGoesToVerification(EmailStatus status) {
        SocialLoginOutcome outcome =
                service.resolve(
                        profile(SocialProvider.NAVER, " Member@Example.COM ", status), false);

        assertThat(outcome.type()).isEqualTo(Type.EMAIL_VERIFICATION);
        assertThat(store.current().stage()).isEqualTo(Stage.EMAIL_VERIFICATION);
        assertThat(store.current().email()).isEqualTo(EMAIL);
        assertThat(store.current().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("확인 여부를 모르는 이메일이 기존 계정과 같아도 확인 전에는 연결 단계로 가지 않는다")
    void unverifiedEmailNeverReachesLinkBeforeCheck() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(42L, EMAIL)));

        SocialLoginOutcome outcome =
                service.resolve(profile(SocialProvider.NAVER, EMAIL, EmailStatus.UNKNOWN), false);

        assertThat(outcome.type()).isEqualTo(Type.EMAIL_VERIFICATION);
        assertThat(store.current().linkTargetUserId()).isNull();
    }

    @Test
    @DisplayName("확인된 이메일이 기존 계정과 같으면 로그인하지 않고 연결 확인 단계로 간다")
    void verifiedEmailMatchingAccountRequiresLink() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(42L, EMAIL)));

        SocialLoginOutcome outcome = service.resolve(kakao(EMAIL, EmailStatus.VERIFIED), false);

        assertThat(outcome.type()).isEqualTo(Type.LINK_REQUIRED);
        assertThat(outcome.userId()).isNull();
        PendingSocialLogin pending = store.current();
        assertThat(pending.stage()).isEqualTo(Stage.LINK);
        assertThat(pending.linkTargetUserId()).isEqualTo(42L);
        assertThat(pending.linkReason()).isEqualTo(LinkReason.EMAIL);
        assertThat(pending.linkTargetEmailVerified()).isFalse();
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("이메일로 찾은 계정에 같은 제공자의 다른 계정이 이미 있으면 연결을 시도하지 않는다")
    void sameProviderAlreadyLinkedIsConflict() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(42L, EMAIL)));
        when(socialAccounts.findByUserId(42L))
                .thenReturn(List.of(linked(42L, SocialProvider.KAKAO, "other-kakao")));

        SocialLoginOutcome outcome = service.resolve(kakao(EMAIL, EmailStatus.VERIFIED), false);

        assertThat(outcome.type()).isEqualTo(Type.PROVIDER_CONFLICT);
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("이메일로 찾은 계정이 정지 상태면 연결을 권하지 않는다")
    void inactiveEmailOwnerIsUnavailable() {
        when(users.findByEmail(EMAIL))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.WITHDRAWN, "hash")));

        SocialLoginOutcome outcome = service.resolve(kakao(EMAIL, EmailStatus.VERIFIED), false);

        assertThat(outcome.type()).isEqualTo(Type.ACCOUNT_UNAVAILABLE);
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("겹치는 계정이 없으면 가입 단계로 가고 기준에 맞는 제공자 닉네임만 남긴다")
    void noMatchRequiresSignup() {
        SocialLoginOutcome outcome = service.resolve(kakao(EMAIL, EmailStatus.VERIFIED), false);

        assertThat(outcome.type()).isEqualTo(Type.SIGNUP_REQUIRED);
        assertThat(store.current().stage()).isEqualTo(Stage.SIGNUP);
        assertThat(store.current().providerNickname()).isEqualTo("소셜회원");
        assertThat(store.current().expiresAt()).isEqualTo(NOW.plus(PendingSocialLogin.TTL));
    }

    @Test
    @DisplayName("가입 규칙에 맞지 않는 제공자 닉네임은 미리 채우지 않는다")
    void unusableNicknameIsDropped() {
        service.resolve(
                new SocialProfile(
                        SocialProvider.KAKAO, PROVIDER_USER_ID, EMAIL, EmailStatus.VERIFIED, "가"),
                false);

        assertThat(store.current().providerNickname()).isNull();
    }

    @Test
    @DisplayName("새 소셜 로그인은 남아 있던 대기 상태를 지운다")
    void newLoginDropsPreviousPendingState() {
        store.save(
                PendingSocialLogin.start(
                        SocialProvider.GOOGLE, "old", "old@example.com", true, null, NOW));
        when(socialAccounts.findByProviderAndProviderUserId(SocialProvider.KAKAO, PROVIDER_USER_ID))
                .thenReturn(Optional.of(linked(42L, SocialProvider.KAKAO, PROVIDER_USER_ID)));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, EMAIL)));

        service.resolve(kakao(EMAIL, EmailStatus.VERIFIED), false);

        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("intent=link로 대상 계정에 연결된 소셜 계정을 확인하면 대기 중인 연결을 마친다")
    void linkIntentWithTargetsSocialAccountCompletesLink() {
        store.save(
                PendingSocialLogin.start(SocialProvider.KAKAO, "kakao-new", EMAIL, true, null, NOW)
                        .toLink(42L, LinkReason.EMAIL));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, EMAIL)));
        when(socialAccounts.findByProviderAndProviderUserId(
                        SocialProvider.GOOGLE, PROVIDER_USER_ID))
                .thenReturn(Optional.of(linked(42L, SocialProvider.GOOGLE, PROVIDER_USER_ID)));
        when(socialAccounts.findByUserId(42L))
                .thenReturn(List.of(linked(42L, SocialProvider.GOOGLE, PROVIDER_USER_ID)));

        SocialLoginOutcome outcome =
                service.resolve(profile(SocialProvider.GOOGLE, EMAIL, EmailStatus.VERIFIED), true);

        assertThat(outcome.type()).isEqualTo(Type.LOGGED_IN);
        assertThat(outcome.userId()).isEqualTo(42L);
        verify(socialAccounts)
                .save(
                        org.mockito.ArgumentMatchers.argThat(
                                (SocialAccount saved) ->
                                        saved.getUserId() == 42L
                                                && saved.getProvider() == SocialProvider.KAKAO
                                                && saved.getProviderUserId().equals("kakao-new")));
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("intent=link로 다른 계정의 소셜 계정을 확인하면 연결하지 않고 대기 상태를 남긴다")
    void linkIntentWithOtherAccountsSocialFails() {
        store.save(
                PendingSocialLogin.start(SocialProvider.KAKAO, "kakao-new", EMAIL, true, null, NOW)
                        .toLink(42L, LinkReason.EMAIL));
        when(socialAccounts.findByProviderAndProviderUserId(
                        SocialProvider.GOOGLE, PROVIDER_USER_ID))
                .thenReturn(Optional.of(linked(77L, SocialProvider.GOOGLE, PROVIDER_USER_ID)));

        SocialLoginOutcome outcome =
                service.resolve(profile(SocialProvider.GOOGLE, EMAIL, EmailStatus.VERIFIED), true);

        assertThat(outcome.type()).isEqualTo(Type.LINK_FAILED);
        verify(socialAccounts, never()).save(any());
        assertThat(store.current()).isNotNull();
    }

    @Test
    @DisplayName("intent=link인데 대기 중인 연결이 없으면 실패 판정이고 아무것도 만들지 않는다")
    void linkIntentWithoutPendingFails() {
        SocialLoginOutcome outcome =
                service.resolve(profile(SocialProvider.GOOGLE, EMAIL, EmailStatus.VERIFIED), true);

        assertThat(outcome.type()).isEqualTo(Type.LINK_FAILED);
        verify(socialAccounts, never()).save(any());
    }

    private static SocialProfile kakao(String email, EmailStatus status) {
        return profile(SocialProvider.KAKAO, email, status);
    }
}
