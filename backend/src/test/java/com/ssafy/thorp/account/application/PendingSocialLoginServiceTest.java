package com.ssafy.thorp.account.application;

import static com.ssafy.thorp.account.application.SocialFixtures.NOW;
import static com.ssafy.thorp.account.application.SocialFixtures.activeUser;
import static com.ssafy.thorp.account.application.SocialFixtures.linked;
import static com.ssafy.thorp.account.application.SocialFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.account.application.port.SocialProviderCatalog;
import com.ssafy.thorp.account.application.port.VerificationMailSender;
import com.ssafy.thorp.account.application.repository.SocialAccountRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.PendingSocialView;
import com.ssafy.thorp.account.application.service.AuthenticationService;
import com.ssafy.thorp.account.application.service.PendingSocialLoginAccess;
import com.ssafy.thorp.account.application.service.PendingSocialLoginService;
import com.ssafy.thorp.account.application.service.SocialLinkService;
import com.ssafy.thorp.account.application.service.SocialLoginService;
import com.ssafy.thorp.account.application.service.SocialVerificationGuard;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.LinkReason;
import com.ssafy.thorp.account.domain.model.PendingSocialLogin.Stage;
import com.ssafy.thorp.account.domain.model.SocialProvider;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import com.ssafy.thorp.account.domain.model.SocialVerification.CodeTarget;
import com.ssafy.thorp.account.domain.model.UserStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 대기 상태의 만료·일회성·취소와 소셜 경로 인증번호 규칙(T064, FR-022·FR-025). */
class PendingSocialLoginServiceTest {

    private final InMemorySocialLinkTargetRepository targets =
            new InMemorySocialLinkTargetRepository();

    private static final String EMAIL = "member@example.com";

    private final MutableClock clock = new MutableClock(NOW);
    private final InMemoryPendingSocialLoginStore store = new InMemoryPendingSocialLoginStore();
    private final UserRepository users = mock(UserRepository.class);
    private final SocialAccountRepository socialAccounts = mock(SocialAccountRepository.class);
    private final VerificationMailSender mailSender = mock(VerificationMailSender.class);
    private final PasswordEncoder encoder = new PrefixPasswordEncoder();
    private final InMemorySocialVerificationRepository verifications =
            new InMemorySocialVerificationRepository();

    private PendingSocialLoginService service;

    @BeforeEach
    void setUp() {
        PendingSocialLoginAccess access =
                new PendingSocialLoginAccess(
                        store,
                        new SocialVerificationGuard(verifications, targets, clock),
                        encoder,
                        clock);
        SocialLinkService linkService =
                new SocialLinkService(
                        access,
                        store,
                        users,
                        socialAccounts,
                        encoder,
                        mock(AuthenticationService.class),
                        clock);
        SocialLoginService loginService =
                new SocialLoginService(socialAccounts, users, store, access, linkService, clock);
        SocialProviderCatalog catalog = () -> List.of(SocialProvider.KAKAO, SocialProvider.GOOGLE);
        service =
                new PendingSocialLoginService(
                        access,
                        store,
                        loginService,
                        linkService,
                        socialAccounts,
                        catalog,
                        encoder,
                        mailSender,
                        clock);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(socialAccounts.findByUserId(any())).thenReturn(List.of());
        when(socialAccounts.existsByUserIdAndProvider(any(), any())).thenCallRealMethod();
    }

    @Test
    @DisplayName("대기 상태가 없으면 SOCIAL_PENDING_NOT_FOUND다")
    void missingPendingIsNotFound() {
        assertThatThrownBy(() -> service.view())
                .satisfies(code(ErrorCode.SOCIAL_PENDING_NOT_FOUND));
    }

    @Test
    @DisplayName("만든 지 10분이 지난 대기 상태는 없는 것으로 보고 지운다")
    void expiredPendingIsDropped() {
        store.save(signupPending());
        clock.advance(PendingSocialLogin.TTL);

        assertThatThrownBy(() -> service.view())
                .satisfies(code(ErrorCode.SOCIAL_PENDING_NOT_FOUND));
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("취소는 대기 상태를 지우고, 없어도 같은 결과다")
    void cancelIsIdempotent() {
        store.save(signupPending());
        service.cancel();
        service.cancel();
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("이메일 확인 단계의 인증번호는 제공자 이메일로 가고 3분 유효하다")
    void emailVerificationCodeGoesToProviderEmail() {
        store.save(verificationPending());

        String code = sendAndCapture(EMAIL);

        assertThat(code).matches("\\d{6}");
        SocialVerification verification = verifications.get(store.current().verificationId());
        assertThat(verification.getCodeTarget()).isEqualTo(CodeTarget.PROVIDER_EMAIL);
        assertThat(verification.getCodeExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
    }

    @Test
    @DisplayName("재발송하면 앞서 보낸 번호는 맞지 않는다")
    void resendInvalidatesPreviousCode() {
        store.save(verificationPending());
        String first = sendAndCapture(EMAIL);
        clock.advance(SocialVerification.RESEND_INTERVAL);
        String second = sendAndCapture(EMAIL);
        if (first.equals(second)) {
            return; // 같은 번호가 두 번 나올 확률(백만분의 일)이면 이 단언은 의미가 없다.
        }

        assertThatThrownBy(() -> service.confirmProviderEmail(first))
                .satisfies(code(ErrorCode.EMAIL_VERIFICATION_INVALID));
    }

    @Test
    @DisplayName("3분이 지난 번호는 만료다")
    void expiredCodeIsRejected() {
        store.save(verificationPending());
        String code = sendAndCapture(EMAIL);
        clock.advance(Duration.ofMinutes(3));

        assertThatThrownBy(() -> service.confirmProviderEmail(code))
                .satisfies(code(ErrorCode.EMAIL_VERIFICATION_EXPIRED));
    }

    @Test
    @DisplayName("번호를 보내지 않았으면 확인은 인증 요청 없음이다")
    void confirmWithoutCodeIsNotFound() {
        store.save(verificationPending());

        assertThatThrownBy(() -> service.confirmProviderEmail("123456"))
                .satisfies(code(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND));
    }

    @Test
    @DisplayName("제공자 이메일을 확인하면 겹치는 계정이 없을 때 가입 단계가 된다")
    void confirmedEmailWithoutOwnerGoesToSignup() {
        store.save(verificationPending());
        String code = sendAndCapture(EMAIL);

        PendingSocialView view = service.confirmProviderEmail(code);

        assertThat(view.stage()).isEqualTo("SIGNUP");
        assertThat(store.current().emailVerified()).isTrue();
    }

    @Test
    @DisplayName("확인한 이메일이 기존 계정과 같으면 연결 단계가 되고 그 확인을 소유 확인으로 인정한다")
    void confirmedEmailMatchingOwnerCountsAsOwnership() {
        store.save(verificationPending());
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(activeUser(42L, EMAIL)));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, EMAIL)));
        String code = sendAndCapture(EMAIL);

        PendingSocialView view = service.confirmProviderEmail(code);

        assertThat(view.stage()).isEqualTo("LINK");
        assertThat(view.linkMethods()).first().isEqualTo("VERIFIED_EMAIL");
        assertThat(store.current().linkTargetEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("다시 받기 간격 안에 또 보내면 거절하고 메일을 보내지 않는다")
    void resendWithinIntervalIsRejected() {
        store.save(verificationPending());
        sendAndCapture(EMAIL);

        assertThatThrownBy(() -> service.sendCode())
                .satisfies(code(ErrorCode.EMAIL_VERIFICATION_RESEND_TOO_SOON));
        verify(mailSender, org.mockito.Mockito.times(1))
                .sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("보낼 수 있는 횟수를 넘으면 대기 상태를 버린다")
    void sendLimitDropsPending() {
        store.save(verificationPending());
        for (int i = 0; i < SocialVerification.MAX_CODES_SENT; i++) {
            service.sendCode();
            clock.advance(SocialVerification.RESEND_INTERVAL);
        }

        assertThatThrownBy(() -> service.sendCode())
                .satisfies(code(ErrorCode.SOCIAL_VERIFICATION_LIMIT));
        assertThat(store.current()).isNull();
    }

    @Test
    @DisplayName("제공자 이메일 번호도 상한만큼 틀리면 지워져 맞는 번호로 확인할 수 없다")
    void providerEmailCodeAttemptsAreCapped() {
        store.save(verificationPending());
        String code = sendAndCapture(EMAIL);
        String wrong = code.equals("000000") ? "111111" : "000000";
        for (int i = 0; i < SocialVerification.MAX_CODE_ATTEMPTS; i++) {
            assertThatThrownBy(() -> service.confirmProviderEmail(wrong))
                    .satisfies(code(ErrorCode.EMAIL_VERIFICATION_INVALID));
        }

        assertThatThrownBy(() -> service.confirmProviderEmail(code))
                .satisfies(code(ErrorCode.EMAIL_VERIFICATION_NOT_FOUND));
        assertThat(store.current().emailVerified()).isFalse();
    }

    @Test
    @DisplayName("같은 계정 이메일로 가는 인증번호는 대기 상태가 바뀌어도 한 시간에 5통까지만 보낸다")
    void targetEmailSendsAreCappedAcrossPendings() {
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));
        for (int i = 0;
                i < com.ssafy.thorp.account.domain.model.SocialLinkTarget.MAX_CODES_SENT;
                i++) {
            // 매번 새 제공자 로그인으로 온 새 대기 상태다. 대기 상태 단위 상한으로는 막히지 않는다.
            store.save(signupPending().toLink(42L, LinkReason.PHONE));
            service.sendCode();
        }

        store.save(signupPending().toLink(42L, LinkReason.PHONE));
        assertThatThrownBy(() -> service.sendCode())
                .satisfies(code(ErrorCode.SOCIAL_LINK_TEMPORARILY_LOCKED));
        verify(
                        mailSender,
                        org.mockito.Mockito.times(
                                com.ssafy.thorp.account.domain.model.SocialLinkTarget
                                        .MAX_CODES_SENT))
                .sendVerificationCode(eq("owner@example.com"), anyString());

        // 창이 지나면 다시 보낼 수 있다.
        clock.advance(com.ssafy.thorp.account.domain.model.SocialLinkTarget.WINDOW);
        store.save(
                PendingSocialLogin.start(
                                SocialProvider.KAKAO, "kakao-1", EMAIL, true, null, clock.instant())
                        .toLink(42L, LinkReason.PHONE));
        service.sendCode();
    }

    @Test
    @DisplayName("계정 단위로 발송이 막혀도 이미 받은 번호는 그대로 쓸 수 있다")
    void targetSendLockKeepsExistingCode() {
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));
        PendingSocialLogin pending = signupPending().toLink(42L, LinkReason.PHONE);
        store.save(pending);
        sendAndCapture("owner@example.com");
        SocialVerification before = verifications.get(pending.verificationId());
        // 다른 대기 상태들이 이미 그 계정 이메일로 이번 창의 몫을 다 보냈다.
        targets.save(
                new com.ssafy.thorp.account.domain.model.SocialLinkTarget(
                        42L,
                        NOW,
                        0,
                        com.ssafy.thorp.account.domain.model.SocialLinkTarget.MAX_CODES_SENT));
        clock.advance(SocialVerification.RESEND_INTERVAL);

        assertThatThrownBy(() -> service.sendCode())
                .satisfies(code(ErrorCode.SOCIAL_LINK_TEMPORARILY_LOCKED));

        SocialVerification after = verifications.get(pending.verificationId());
        assertThat(after.getCodeHash()).isEqualTo(before.getCodeHash());
        assertThat(after.getCodesSent()).isEqualTo(before.getCodesSent());
        assertThat(after.getLastCodeSentAt()).isEqualTo(before.getLastCodeSentAt());
    }

    @Test
    @DisplayName("단계를 바꿔도 발송 횟수는 이어서 센다")
    void stageChangeKeepsSendCount() {
        PendingSocialLogin pending = verificationPending();
        store.save(pending);
        sendAndCapture(EMAIL);

        assertThat(pending.toSignup().verificationId()).isEqualTo(pending.verificationId());
        assertThat(verifications.get(pending.verificationId()).getCodesSent()).isEqualTo(1);
    }

    @Test
    @DisplayName("가입 단계에서는 인증번호를 보낼 수 없다")
    void signupStageCannotSendCode() {
        store.save(signupPending());

        assertThatThrownBy(() -> service.sendCode())
                .satisfies(code(ErrorCode.SOCIAL_PENDING_STAGE_MISMATCH));
        verify(mailSender, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    @DisplayName("연결 단계의 인증번호는 제공자 이메일이 아니라 연결 대상 계정 이메일로 간다")
    void linkStageCodeGoesToTargetEmail() {
        store.save(signupPending().toLink(42L, LinkReason.PHONE));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));

        sendAndCapture("owner@example.com");

        assertThat(verifications.get(store.current().verificationId()).getCodeTarget())
                .isEqualTo(CodeTarget.LINK_TARGET_EMAIL);
    }

    @Test
    @DisplayName("연결 화면은 대상 이메일을 가리고 쓸 수 있는 확인 방법만 보여 준다")
    void linkViewMasksEmailAndListsUsableMethods() {
        store.save(signupPending().toLink(42L, LinkReason.EMAIL));
        when(users.findById(42L)).thenReturn(Optional.of(activeUser(42L, "owner@example.com")));
        when(socialAccounts.findByUserId(42L))
                .thenReturn(
                        List.of(
                                linked(42L, SocialProvider.GOOGLE, "g-1"),
                                linked(42L, SocialProvider.NAVER, "n-1")));

        PendingSocialView view = service.view();

        assertThat(view.linkTargetMaskedEmail()).isEqualTo("o***@example.com");
        // 네이버는 켜지지 않은 제공자라 확인 방법으로 내놓지 않는다.
        assertThat(view.linkMethods()).containsExactly("PASSWORD", "EMAIL_CODE", "SOCIAL_GOOGLE");
    }

    @Test
    @DisplayName("비밀번호가 없는 대상 계정에는 비밀번호 확인을 내놓지 않는다")
    void passwordlessTargetHasNoPasswordMethod() {
        store.save(signupPending().toLink(42L, LinkReason.EMAIL));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, "owner@example.com", UserStatus.ACTIVE, null)));

        assertThat(service.view().linkMethods()).containsExactly("EMAIL_CODE");
    }

    @Test
    @DisplayName("연결 대상 계정이 그 사이 정지되면 일반 거절이고 대기 상태를 지운다")
    void inactiveTargetIsRejected() {
        store.save(signupPending().toLink(42L, LinkReason.EMAIL));
        when(users.findById(42L))
                .thenReturn(Optional.of(user(42L, "owner@example.com", UserStatus.SUSPENDED, "h")));

        assertThatThrownBy(() -> service.view()).satisfies(code(ErrorCode.SOCIAL_SIGNUP_REJECTED));
        assertThat(store.current()).isNull();
    }

    private String sendAndCapture(String expectedAddress) {
        service.sendCode();
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mailSender, org.mockito.Mockito.atLeastOnce())
                .sendVerificationCode(eq(expectedAddress), code.capture());
        return code.getValue();
    }

    private static PendingSocialLogin verificationPending() {
        return PendingSocialLogin.start(SocialProvider.NAVER, "naver-1", EMAIL, false, null, NOW);
    }

    private static PendingSocialLogin signupPending() {
        PendingSocialLogin pending =
                PendingSocialLogin.start(SocialProvider.KAKAO, "kakao-1", EMAIL, true, "닉", NOW);
        assertThat(pending.stage()).isEqualTo(Stage.SIGNUP);
        return pending;
    }

    static java.util.function.Consumer<Throwable> code(ErrorCode expected) {
        return thrown ->
                assertThat(thrown)
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(expected);
    }

    /** 시각을 앞으로 보낼 수 있는 시계. 만료 판정을 기다리지 않고 본다. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** 원문 앞에 표시만 붙이는 비교기. BCrypt의 비용 없이 "hash가 맞는가"만 본다. */
    static final class PrefixPasswordEncoder implements PasswordEncoder {
        @Override
        public String encode(CharSequence rawPassword) {
            return "h:" + rawPassword;
        }

        @Override
        public boolean matches(CharSequence rawPassword, String encodedPassword) {
            return encodedPassword != null && encodedPassword.equals("h:" + rawPassword);
        }
    }
}
