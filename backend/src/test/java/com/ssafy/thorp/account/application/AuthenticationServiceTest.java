package com.ssafy.thorp.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.account.application.command.LoginCommand;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.AuthenticatedUserResult;
import com.ssafy.thorp.account.application.service.AuthenticationService;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/**
 * 로그인 유스케이스의 판정 규칙을 단위로 고정한다.
 *
 * <p>계정 존재 여부가 드러나지 않는 것이 이 테스트의 핵심이다. 없는 이메일·틀린 비밀번호·비활성 계정은 모두 같은 {@link
 * ErrorCode#INVALID_CREDENTIALS}로 끝나야 하고, 그중 어느 것도 마지막 로그인 시각을 갱신하거나 인증 상태를 저장해서는 안 된다.
 */
class AuthenticationServiceTest {

    private static final String CANONICAL_EMAIL = "member@example.com";
    private static final String RAW_PASSWORD = "password123";
    private static final String PASSWORD_HASH = "password-hash";

    /** 계정을 찾지 못했을 때 대조할 버리는 해시. 서비스가 기동하며 임의 값으로 만든다. */
    private static final String ABSENT_ACCOUNT_HASH = "absent-account-hash";

    private static final Instant NOW = Instant.parse("2026-09-15T01:23:45Z");

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final SecurityContextRepository securityContextRepository =
            mock(SecurityContextRepository.class);
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy =
            mock(SessionAuthenticationStrategy.class);
    private final CsrfTokenRepository csrfTokenRepository = mock(CsrfTokenRepository.class);

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn(ABSENT_ACCOUNT_HASH);

        authenticationService =
                new AuthenticationService(
                        userRepository,
                        passwordEncoder,
                        securityContextRepository,
                        sessionAuthenticationStrategy,
                        csrfTokenRepository,
                        new InMemoryPendingSocialLoginStore(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("로그인은 앞뒤 공백을 제거하고 소문자로 바꾼 이메일로 계정을 찾는다")
    void loginLooksUpAccountByCanonicalEmail() {
        User user = activeUser(42L);
        when(userRepository.findByEmail(CANONICAL_EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, PASSWORD_HASH)).thenReturn(true);

        AuthenticatedUserResult result =
                authenticationService.login(
                        new LoginCommand("  MEMBER@Example.COM  ", RAW_PASSWORD),
                        request,
                        response);

        verify(userRepository).findByEmail(CANONICAL_EMAIL);
        assertThat(result)
                .isEqualTo(
                        new AuthenticatedUserResult(
                                42L, CANONICAL_EMAIL, "회원", UserStatus.ACTIVE.name()));
    }

    @Test
    @DisplayName("비밀번호가 맞으면 마지막 로그인 시각을 갱신하고 인증 상태를 세션에 저장한다")
    void successfulLoginUpdatesLastLoginAndPersistsAuthentication() {
        User user = activeUser(42L);
        when(userRepository.findByEmail(CANONICAL_EMAIL)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, PASSWORD_HASH)).thenReturn(true);

        authenticationService.login(
                new LoginCommand(CANONICAL_EMAIL, RAW_PASSWORD), request, response);

        verify(userRepository).updateLastLoginAt(42L, NOW);

        // 기존 익명 세션 식별자 교체는 SessionAuthenticationStrategy가 맡는다(FR-018).
        ArgumentCaptor<Authentication> rotated = ArgumentCaptor.forClass(Authentication.class);
        verify(sessionAuthenticationStrategy)
                .onAuthentication(
                        rotated.capture(),
                        any(HttpServletRequest.class),
                        any(HttpServletResponse.class));

        ArgumentCaptor<SecurityContext> saved = ArgumentCaptor.forClass(SecurityContext.class);
        verify(securityContextRepository)
                .saveContext(
                        saved.capture(),
                        any(HttpServletRequest.class),
                        any(HttpServletResponse.class));

        Authentication authentication = saved.getValue().getAuthentication();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo("42");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(rotated.getValue().getName()).isEqualTo(authentication.getName());
    }

    @Test
    @DisplayName("가입되지 않은 이메일도 비밀번호를 대조해 응답 시간으로 계정 존재를 알려주지 않는다")
    void unknownEmailStillVerifiesAPasswordAndIsRejectedAsInvalidCredentials() {
        when(userRepository.findByEmail(CANONICAL_EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(
                        () ->
                                authenticationService.login(
                                        new LoginCommand(CANONICAL_EMAIL, RAW_PASSWORD),
                                        request,
                                        response))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(passwordEncoder).matches(RAW_PASSWORD, ABSENT_ACCOUNT_HASH);
        verifyNothingWasAuthenticated();
    }

    @Test
    @DisplayName("비밀번호가 틀리면 계정을 찾았더라도 같은 오류로 거절한다")
    void wrongPasswordIsRejectedAsInvalidCredentials() {
        when(userRepository.findByEmail(CANONICAL_EMAIL)).thenReturn(Optional.of(activeUser(42L)));
        when(passwordEncoder.matches(RAW_PASSWORD, PASSWORD_HASH)).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                authenticationService.login(
                                        new LoginCommand(CANONICAL_EMAIL, RAW_PASSWORD),
                                        request,
                                        response))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verifyNothingWasAuthenticated();
    }

    @ParameterizedTest
    @EnumSource(
            value = UserStatus.class,
            names = {"PENDING_VERIFICATION", "SUSPENDED", "WITHDRAWN"})
    @DisplayName("활성이 아닌 계정은 비밀번호가 맞아도 상태를 드러내지 않고 같은 오류로 거절한다")
    void inactiveAccountIsConcealedBehindTheSameRejection(UserStatus status) {
        when(userRepository.findByEmail(CANONICAL_EMAIL))
                .thenReturn(Optional.of(user(42L, status)));
        when(passwordEncoder.matches(RAW_PASSWORD, PASSWORD_HASH)).thenReturn(true);

        assertThatThrownBy(
                        () ->
                                authenticationService.login(
                                        new LoginCommand(CANONICAL_EMAIL, RAW_PASSWORD),
                                        request,
                                        response))
                .isInstanceOf(BusinessException.class)
                .satisfies(
                        error -> {
                            BusinessException exception = (BusinessException) error;
                            assertThat(exception.getErrorCode())
                                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS);
                            assertThat(exception.getMessage())
                                    .isEqualTo(ErrorCode.INVALID_CREDENTIALS.getMessage());
                        });

        verifyNothingWasAuthenticated();
    }

    @Test
    @DisplayName("현재 사용자 조회는 세션의 식별자로 계정을 다시 읽어 비밀번호 없이 돌려준다")
    void currentUserReadsTheAccountBehindTheSession() {
        when(userRepository.findById(42L)).thenReturn(Optional.of(activeUser(42L)));

        AuthenticatedUserResult result = authenticationService.currentUser(authenticationOf("42"));

        assertThat(result)
                .isEqualTo(
                        new AuthenticatedUserResult(
                                42L, CANONICAL_EMAIL, "회원", UserStatus.ACTIVE.name()));
    }

    @Test
    @DisplayName("세션은 살아 있어도 계정이 사라졌거나 활성이 아니면 로그인 상태로 보지 않는다")
    void currentUserRejectsSessionsWhoseAccountIsNoLongerUsable() {
        when(userRepository.findById(42L)).thenReturn(Optional.empty());
        when(userRepository.findById(43L)).thenReturn(Optional.of(user(43L, UserStatus.SUSPENDED)));

        assertThatThrownBy(() -> authenticationService.currentUser(authenticationOf("42")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);

        assertThatThrownBy(() -> authenticationService.currentUser(authenticationOf("43")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("인증 주체가 없거나 식별자 형식이 아니면 로그인 상태로 보지 않는다")
    void currentUserRejectsUnusablePrincipals() {
        assertThatThrownBy(() -> authenticationService.currentUser(null))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);

        assertThatThrownBy(
                        () -> authenticationService.currentUser(authenticationOf("not-a-user-id")))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("소셜로만 가입해 비밀번호가 없는 계정은 비교기에 닿지 않고 같은 오류로 거절한다(spec 014 T067)")
    void passwordlessAccountIsRejectedWithoutTouchingTheStoredHash() {
        User passwordless =
                new User(
                        42L,
                        CANONICAL_EMAIL,
                        null,
                        "회원",
                        "01012345678",
                        null,
                        UserStatus.ACTIVE,
                        false,
                        false,
                        false,
                        null,
                        NOW,
                        NOW,
                        null);
        when(userRepository.findByEmail(CANONICAL_EMAIL)).thenReturn(Optional.of(passwordless));

        assertThatThrownBy(
                        () ->
                                authenticationService.login(
                                        new LoginCommand(CANONICAL_EMAIL, RAW_PASSWORD),
                                        request,
                                        response))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        // 응답 시간을 맞추려고 버리는 hash와는 대조하지만, null hash를 비교기에 넘기지는 않는다.
        verify(passwordEncoder).matches(RAW_PASSWORD, ABSENT_ACCOUNT_HASH);
        verify(passwordEncoder, never()).matches(any(), org.mockito.ArgumentMatchers.isNull());
        verifyNothingWasAuthenticated();
    }

    @Test
    @DisplayName("이름이 숫자여도 로그인이 넣은 종류의 인증이 아니면 사용자로 보지 않는다(spec 014 analyze G1)")
    void currentUserRejectsForeignAuthenticationWithNumericName() {
        // 소셜 로그인 필터가 잠깐 넣는 token의 이름은 제공자 사용자 식별자다. 카카오는 숫자라 users.id로 읽히면 남의 계정이 된다.
        Authentication foreign =
                new org.springframework.security.authentication.TestingAuthenticationToken(
                        "42", null, "ROLE_USER");

        assertThatThrownBy(() -> authenticationService.currentUser(foreign))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED);
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("로그인하면 같은 브라우저에 남은 소셜 가입·연결 대기 상태를 버린다(공용 PC)")
    void loginDropsLeftoverPendingSocialState() {
        InMemoryPendingSocialLoginStore store = new InMemoryPendingSocialLoginStore();
        store.save(
                com.ssafy.thorp.account.domain.model.PendingSocialLogin.start(
                        com.ssafy.thorp.account.domain.model.SocialProvider.KAKAO,
                        "kakao-1",
                        "someone@example.com",
                        true,
                        null,
                        NOW));
        AuthenticationService service =
                new AuthenticationService(
                        userRepository,
                        passwordEncoder,
                        securityContextRepository,
                        sessionAuthenticationStrategy,
                        csrfTokenRepository,
                        store,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        when(userRepository.findByEmail(CANONICAL_EMAIL)).thenReturn(Optional.of(activeUser(42L)));
        when(passwordEncoder.matches(RAW_PASSWORD, PASSWORD_HASH)).thenReturn(true);

        service.login(new LoginCommand(CANONICAL_EMAIL, RAW_PASSWORD), request, response);

        assertThat(store.current()).isNull();
    }

    private void verifyNothingWasAuthenticated() {
        verify(userRepository, never()).updateLastLoginAt(any(), any());
        verify(sessionAuthenticationStrategy, never())
                .onAuthentication(
                        any(), any(HttpServletRequest.class), any(HttpServletResponse.class));
        verify(securityContextRepository, never())
                .saveContext(any(), any(HttpServletRequest.class), any(HttpServletResponse.class));
    }

    private static Authentication authenticationOf(String principalName) {
        return UsernamePasswordAuthenticationToken.authenticated(principalName, null, List.of());
    }

    private static User activeUser(Long id) {
        return user(id, UserStatus.ACTIVE);
    }

    private static User user(Long id, UserStatus status) {
        return new User(
                id,
                CANONICAL_EMAIL,
                PASSWORD_HASH,
                "회원",
                "01012345678",
                null,
                status,
                false,
                false,
                false,
                null,
                NOW,
                NOW,
                null);
    }
}
