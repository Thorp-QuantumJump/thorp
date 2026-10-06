package com.ssafy.thorp.account.application;

import static com.ssafy.thorp.account.application.PendingSocialLoginServiceTest.code;
import static com.ssafy.thorp.account.application.SocialFixtures.NOW;
import static com.ssafy.thorp.account.application.SocialFixtures.activeUser;
import static com.ssafy.thorp.account.application.SocialFixtures.linked;
import static com.ssafy.thorp.account.application.SocialFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.account.application.PendingSocialLoginServiceTest.PrefixPasswordEncoder;
import com.ssafy.thorp.account.application.command.SocialLinkCommand;
import com.ssafy.thorp.account.application.command.SocialLinkCommand.Method;
import com.ssafy.thorp.account.application.command.SocialSignupCommand;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.application.service.AuthenticationService;
import com.ssafy.thorp.account.application.service.PendingSocialLoginAccess;
import com.ssafy.thorp.account.application.service.SocialLinkService;
import com.ssafy.thorp.account.application.service.SocialLoginService;
import com.ssafy.thorp.account.application.service.SocialSignupService;
import com.ssafy.thorp.account.application.service.SocialVerificationGuard;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 소셜 가입과 기존 계정 연결 규칙(T065, FR-023·FR-024).
 *
 * <p>계정 생성·연결이 일어나는지는 저장소 호출로 본다. 소유 확인 없이 {@code socialAccounts.save}가 불린 사례가 0건이어야 한다(SC-013).
 */
class SocialSignupAndLinkServiceTest {

    private final InMemorySocialLinkTargetRepository targets =
            new InMemorySocialLinkTargetRepository();

    private static final String EMAIL = "member@example.com";
    private static final String PHONE = "01055556666";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final InMemoryPendingSocialLoginStore store = new InMemoryPendingSocialLoginStore();
    private final UserRepository users = mock(UserRepository.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final AuthenticationService authenticationService = mock(AuthenticationService.class);
    private final PrefixPasswordEncoder encoder = new PrefixPasswordEncoder();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private SocialSignupService signupService;
    private SocialLinkService linkService;
    private PendingSocialLoginAccess access;
    private final InMemorySocialVerificationRepository verifications =
            new InMemorySocialVerificationRepository();

    @BeforeEach
    void setUp() {
        access =
                new PendingSocialLoginAccess(
                        store,
                        new SocialVerificationGuard(verifications, targets, clock),
                        users,
                        encoder,
                        clock);
        linkService =
                new SocialLinkService(
                        access,
                        store,
                        users,
                        socialAccounts,
                        encoder,
                        authenticationService,
                        clock);
        SocialLoginService loginService =
                new SocialLoginService(socialAccounts, users, store, access, linkService, clock);
        signupService =
                new SocialSignupService(
                        access,
                        store,
                        loginService,
                        users,
                        socialAccounts,
                        authenticationService,
                        clock);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(users.findByPhoneNumber(any())).thenReturn(Optional.empty());
        when(socialAccounts.findByUserId(any())).thenReturn(List.of());
        when(socialAccounts.existsByUserIdAndProvider(any(), any())).thenCallRealMethod();
        when(users.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 100L));
        when(authenticationService.startSession(any(User.class), any(), any()))
                .thenAnswer(
                        invocation -> {
                            User u = invocation.getArgument(0);
                            return new AuthenticatedUserResult(
                                    u.getId(), u.getEmail(), u.getNickname(), "ACTIVE");
                        });
    }

    @Test
    @DisplayName("소셜 가입은 비밀번호 없는 계정을 만들고 소셜 계정을 연결한 뒤 로그인시킨다")
    void signupCreatesPasswordlessAccountAndLinks() {
        store.save(signupPending());

        AuthenticatedUserResult result =
                signupService.signup(new SocialSignupCommand("새회원", PHONE), request, response);

        assertThat(result.userId()).isEqualTo(100L);
        verify(users)
                .save(
                        argThat(
                                (User u) ->
                                        u.getEmail().equals(EMAIL)
                                                && !u.hasPassword()
                                                && u.getPhoneNumber().equals(PHONE)));
        verify(socialAccounts)
                .save(
                        argThat(
                                account ->
                                        account.getUserId() == 100L
                                                && account.getProvider() == SocialProvider.KAKAO));
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("번호 없이 낸 소셜 가입은 번호 검사를 건너뛰고 번호 없는 계정을 만든다")
    void signupWithoutPhoneCreatesAccountWithoutNumber() {
        store.save(signupPending());

        signupService.signup(new SocialSignupCommand("새회원", null), request, response);

        verify(users).save(argThat((User u) -> u.getPhoneNumber() == null));
        verify(users, never()).findByPhoneNumber(any());
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("가입 단계가 아닌 대기 상태로 가입하면 단계 불일치다")
    void signupOutsideSignupStageIsMismatch() {
        store.save(
                PendingSocialLogin.start(SocialProvider.NAVER, "naver-1", EMAIL, false, null, NOW));

        assertThatThrownBy(
                        () ->
                                signupService.signup(
                                        new SocialSignupCommand("새회원", PHONE), request, response))
                .satisfies(code(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH));
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("휴대폰 번호가 다른 계정에서 쓰이면 계정을 만들지 않고 그 계정의 연결 확인으로 보낸다")
    void duplicatePhoneLeadsToLink() {
        store.save(signupPending());
        when(users.findByPhoneNumber(PHONE))
                .thenReturn(Optional.of(activeUser(42L, "owner@example.com")));

        assertThatThrownBy(
                        () ->
                                signupService.signup(
                                        new SocialSignupCommand("새회원", PHONE), request, response))
                .satisfies(code(ErrorCode.SOCIAL_LINK_REQUIRED));

        verify(users, never()).save(any());
        PendingSocialLogin pending = store.current();
        assertThat(pending.stage()).isEqualTo(Stage.LINK);
        assertThat(pending.linkTargetUserId()).isEqualTo(42L);
        assertThat(pending.linkReason()).isEqualTo(LinkReason.PHONE);
    }

    @Test
    @DisplayName("번호의 주인이 정지됐거나 같은 제공자가 이미 있어도 응답은 똑같이 연결 확인이다 — 계정 상태를 드러내지 않는다")
    void duplicatePhoneResponseDoesNotRevealOwnerState() {
        when(socialAccounts.findByUserId(42L))
                .thenReturn(List.of(linked(42L, SocialProvider.KAKAO, "kakao-other")));
        for (User owner :
                List.of(
                        activeUser(42L, "owner@example.com"),
                        user(42L, "owner@example.com", UserStatus.SUSPENDED, "h"),
                        user(42L, "owner@example.com", UserStatus.WITHDRAWN, null))) {
            store.save(signupPending());
            when(users.findByPhoneNumber(PHONE)).thenReturn(Optional.of(owner));

            assertThatThrownBy(
                            () ->
                                    signupService.signup(
                                            new SocialSignupCommand("새회원", PHONE),
                                            request,
                                            response))
                    .satisfies(code(ErrorCode.SOCIAL_LINK_REQUIRED));
            assertThat(store.current().stage()).isEqualTo(Stage.LINK);
        }
        verify(users, never()).save(any());
    }

    @Test
    @DisplayName("번호로 찾은 계정이 정지 상태면 맞는 비밀번호도 틀린 비밀번호와 같은 거절이다")
    void inactivePhoneTargetAnswersLikeWrongPassword() {
        store.save(linkPending(LinkReason.PHONE, false));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.SUSPENDED, "h:secret123")));

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.PASSWORD, "secret123", null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.INVALID_CREDENTIALS));
        // 대기 상태도 그대로다. 정지 계정이라고 대기 상태를 버리면 그것이 다른 응답이 된다.
        assertThat(store.current()).isNotNull();
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("기존 계정 비밀번호로 확인하면 연결하고 그 계정으로 로그인시킨다")
    void linkWithPassword() {
        store.save(linkPending(LinkReason.EMAIL, false));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.ACTIVE, "h:secret123")));

        AuthenticatedUserResult result =
                linkService.link(
                        new SocialLinkCommand(Method.PASSWORD, "secret123", null),
                        request,
                        response);

        assertThat(result.userId()).isEqualTo(42L);
        verify(socialAccounts).save(argThat(account -> account.getUserId() == 42L));
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("틀린 비밀번호는 연결하지 않고 대기 상태를 남겨 다시 시도할 수 있다")
    void wrongPasswordDoesNotLink() {
        store.save(linkPending(LinkReason.EMAIL, false));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.ACTIVE, "h:secret123")));

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.PASSWORD, "wrong", null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.INVALID_CREDENTIALS));
        verify(socialAccounts, never()).save(any());
        assertThat(store.current()).isNotNull();
    }

    @Test
    @DisplayName("비밀번호 없는 계정에 비밀번호로 확인하면 틀린 비밀번호와 같은 거절이다")
    void passwordMethodOnPasswordlessAccountIsRejected() {
        store.save(linkPending(LinkReason.EMAIL, false));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.ACTIVE, null)));

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.PASSWORD, "anything", null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.INVALID_CREDENTIALS));
    }

    @Test
    @DisplayName("기존 계정 이메일로 보낸 인증번호로 확인하면 연결한다")
    void linkWithTargetEmailCode() {
        PendingSocialLogin pending = linkPending(LinkReason.PHONE, false);
        store.save(pending);
        access.issueCode(pending, "h:654321", CodeTarget.LINK_TARGET_EMAIL);
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));

        linkService.link(
                new SocialLinkCommand(Method.EMAIL_CODE, null, "654321"), request, response);

        verify(socialAccounts).save(argThat(account -> account.getUserId() == 42L));
    }

    @Test
    @DisplayName("제공자 이메일로 받은 번호로는 연결 대상 계정의 소유를 확인할 수 없다")
    void providerEmailCodeCannotProveTargetOwnership() {
        PendingSocialLogin pending = linkPending(LinkReason.PHONE, false);
        store.save(pending);
        access.issueCode(pending, "h:654321", CodeTarget.PROVIDER_EMAIL);
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.EMAIL_CODE, null, "654321"),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND));
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("같은 대기 상태에서 이메일을 이미 확인했으면 그 확인으로 연결한다")
    void linkWithVerifiedEmail() {
        store.save(linkPending(LinkReason.EMAIL, true));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, EMAIL)));

        linkService.link(
                new SocialLinkCommand(Method.VERIFIED_EMAIL, null, null), request, response);

        verify(socialAccounts).save(argThat(account -> account.getUserId() == 42L));
    }

    @Test
    @DisplayName("휴대폰 번호로 찾은 계정에는 이메일 확인을 소유 확인으로 쓰지 못한다")
    void verifiedEmailDoesNotProvePhoneMatchedAccount() {
        store.save(linkPending(LinkReason.PHONE, true));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.VERIFIED_EMAIL, null, null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH));
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("연결 직전에 같은 제공자의 계정이 생겼으면 연결하지 않는다")
    void linkRaceWithSameProviderIsConflict() {
        store.save(linkPending(LinkReason.EMAIL, true));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, EMAIL)));
        when(socialAccounts.findByUserId(42L))
                .thenReturn(List.of(linked(42L, SocialProvider.KAKAO, "kakao-other")));

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.VERIFIED_EMAIL, null, null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.SOCIAL_PROVIDER_ALREADY_LINKED));
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("번호가 겹쳐 온 연결을 거절하면 가입 단계로 돌아가 다른 번호로 가입할 수 있다")
    void declinePhoneLinkReturnsToSignup() {
        store.save(linkPending(LinkReason.PHONE, false));

        assertThat(linkService.decline()).isTrue();

        assertThat(store.current().stage()).isEqualTo(Stage.SIGNUP);
        assertThat(store.current().linkTargetUserId()).isNull();
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("이메일이 겹쳐 온 연결을 거절하면 대기 상태를 지운다")
    void declineEmailLinkClearsPending() {
        store.save(linkPending(LinkReason.EMAIL, false));

        assertThat(linkService.decline()).isFalse();

        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("비밀번호를 상한만큼 틀리면 대기 상태를 버려 더 대입하지 못하게 한다")
    void passwordAttemptsAreCapped() {
        store.save(linkPending(LinkReason.PHONE, false));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.ACTIVE, "h:secret123")));

        for (int i = 0; i < SocialVerification.MAX_PASSWORD_ATTEMPTS; i++) {
            assertThatThrownBy(
                            () ->
                                    linkService.link(
                                            new SocialLinkCommand(Method.PASSWORD, "wrong", null),
                                            request,
                                            response))
                    .satisfies(code(ErrorCode.INVALID_CREDENTIALS));
        }

        // 상한 다음 시도는 맞는 비밀번호여도 대조하지 않는다.
        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.PASSWORD, "secret123", null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.SOCIAL_VERIFICATION_LIMIT));
        assertThat(store.current()).isNull();
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("연결 단계의 인증번호는 상한만큼 틀리면 지워져 맞는 번호로도 확인할 수 없다")
    void codeAttemptsAreCapped() {
        PendingSocialLogin pending = linkPending(LinkReason.PHONE, false);
        store.save(pending);
        access.issueCode(pending, "h:654321", CodeTarget.LINK_TARGET_EMAIL);
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));

        for (int i = 0; i < SocialVerification.MAX_CODE_ATTEMPTS; i++) {
            assertThatThrownBy(
                            () ->
                                    linkService.link(
                                            new SocialLinkCommand(
                                                    Method.EMAIL_CODE, null, "000000"),
                                            request,
                                            response))
                    .satisfies(code(ErrorCode.EMAIL_VERIFICATION_INVALID));
        }

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.EMAIL_CODE, null, "654321"),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED));
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("제공자 로그인을 다시 해 새 대기 상태로 와도 같은 계정을 향한 시도는 이어서 세어 막는다")
    void targetAttemptsAccumulateAcrossRestartedPendings() {
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.ACTIVE, "h:secret123")));
        int perPending = SocialVerification.MAX_PASSWORD_ATTEMPTS;
        int restarts =
                com.ssafy.thorp.account.domain.model.SocialLinkTarget.MAX_ATTEMPTS / perPending;

        for (int restart = 0; restart < restarts; restart++) {
            // 새 제공자 로그인 = 새 대기 상태(새 verificationId).
            store.save(linkPending(LinkReason.PHONE, false));
            for (int i = 0; i < perPending; i++) {
                assertThatThrownBy(
                                () ->
                                        linkService.link(
                                                new SocialLinkCommand(
                                                        Method.PASSWORD, "wrong", null),
                                                request,
                                                response))
                        .satisfies(code(ErrorCode.INVALID_CREDENTIALS));
            }
        }

        store.save(linkPending(LinkReason.PHONE, false));
        // 맞는 비밀번호여도 대조하지 않는다. 대기 상태는 남긴다 — 막힌 것은 이 계정으로의 연결뿐이다.
        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.PASSWORD, "secret123", null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.SOCIAL_LINK_TEMPORARILY_LOCKED));
        assertThat(store.current()).isNotNull();
        verify(socialAccounts, never()).save(any());
    }

    @Test
    @DisplayName("번호가 없거나 만료돼 대조할 수 없는 요청은 계정 단위 시도를 먹지 않는다")
    void undecidableCodeRequestsDoNotConsumeTargetAttempts() {
        store.save(linkPending(LinkReason.PHONE, false));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));

        for (int i = 0;
                i < com.ssafy.thorp.account.domain.model.SocialLinkTarget.MAX_ATTEMPTS + 2;
                i++) {
            assertThatThrownBy(
                            () ->
                                    linkService.link(
                                            new SocialLinkCommand(
                                                    Method.EMAIL_CODE, null, "123456"),
                                            request,
                                            response))
                    .satisfies(code(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND));
        }

        com.ssafy.thorp.account.domain.model.SocialLinkTarget target = targets.get(42L);
        assertThat(target == null ? 0 : target.getAttempts()).isZero();
    }

    @Test
    @DisplayName("주인이 확인하면 그 계정을 향한 실패 누적이 지워진다")
    void ownershipClearsTargetAttempts() {
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.ACTIVE, "h:secret123")));
        store.save(linkPending(LinkReason.EMAIL, false));
        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.PASSWORD, "wrong", null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.INVALID_CREDENTIALS));

        linkService.link(
                new SocialLinkCommand(Method.PASSWORD, "secret123", null), request, response);

        assertThat(targets.get(42L).getAttempts()).isZero();
    }

    @Test
    @DisplayName("연결을 마치면 확인 기록을 지운다")
    void completedLinkDiscardsVerification() {
        PendingSocialLogin pending = linkPending(LinkReason.EMAIL, false);
        store.save(pending);
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, EMAIL, UserStatus.ACTIVE, "h:secret123")));

        linkService.link(
                new SocialLinkCommand(Method.PASSWORD, "secret123", null), request, response);

        assertThat(verifications.get(pending.verificationId())).isNull();
    }

    @Test
    @DisplayName("연결 단계가 아닌 대기 상태로 연결하면 단계 불일치다")
    void linkOutsideLinkStageIsMismatch() {
        store.save(signupPending());

        assertThatThrownBy(
                        () ->
                                linkService.link(
                                        new SocialLinkCommand(Method.PASSWORD, "x", null),
                                        request,
                                        response))
                .satisfies(code(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH));
    }

    private static PendingSocialLogin signupPending() {
        return PendingSocialLogin.start(SocialProvider.KAKAO, "kakao-1", EMAIL, true, "닉", NOW);
    }

    private static PendingSocialLogin linkPending(LinkReason reason, boolean emailVerified) {
        // 같은 대기 상태에서 Thorp 인증번호로 이메일을 확인했으면 이메일이 겹친 연결의 소유 확인이 된다.
        PendingSocialLogin base =
                emailVerified ? signupPending().withEmailVerified() : signupPending();
        return base.toLink(42L, reason);
    }

    private static User withId(User user, Long id) {
        return new User(
                id,
                user.getEmail(),
                user.getPasswordHash(),
                user.getNickname(),
                user.getPhoneNumber(),
                null,
                user.getStatus(),
                false,
                false,
                false,
                null,
                NOW,
                NOW,
                null);
    }
}
