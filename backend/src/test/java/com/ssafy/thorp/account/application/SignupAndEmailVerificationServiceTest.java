package com.ssafy.thorp.account.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.account.application.command.SignupCommand;
import com.ssafy.thorp.account.application.port.VerificationMailSender;
import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.SignupResult;
import com.ssafy.thorp.account.application.service.EmailVerificationService;
import com.ssafy.thorp.account.application.service.SignupService;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SignupAndEmailVerificationServiceTest {

    private static final String CANONICAL_EMAIL = "member@example.com";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final EmailVerificationRepository verificationRepository =
            mock(EmailVerificationRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final VerificationMailSender mailSender = mock(VerificationMailSender.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);

    private SignupService signupService;
    private EmailVerificationService verificationService;

    @BeforeEach
    void setUp() {
        signupService = new SignupService(userRepository, verificationRepository, passwordEncoder);
        verificationService =
                new EmailVerificationService(
                        verificationRepository,
                        userRepository,
                        passwordEncoder,
                        mailSender,
                        transactionTemplate);

        when(transactionTemplate.execute(any()))
                .thenAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            TransactionCallback<String> callback = invocation.getArgument(0);
                            return callback.doInTransaction(mock(TransactionStatus.class));
                        });
    }

    @Test
    void signupUsesCanonicalEmailForEveryAccountOperation() {
        EmailVerification verified = verified(CANONICAL_EMAIL);
        when(verificationRepository.findLatestByEmail(CANONICAL_EMAIL))
                .thenReturn(Optional.of(verified));
        when(passwordEncoder.encode("password123")).thenReturn("password-hash");
        when(userRepository.save(any(User.class)))
                .thenAnswer(
                        invocation -> {
                            User user = invocation.getArgument(0);
                            return savedUser(user);
                        });

        SignupResult result =
                signupService.signup(
                        new SignupCommand(
                                "  MEMBER@Example.COM ", "password123", "회원", "01012345678"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).existsByEmail(CANONICAL_EMAIL);
        verify(verificationRepository).findLatestByEmail(CANONICAL_EMAIL);
        verify(userRepository).save(saved.capture());
        verify(verificationRepository).deleteByEmail(CANONICAL_EMAIL);
        assertThat(saved.getValue().getEmail()).isEqualTo(CANONICAL_EMAIL);
        assertThat(result.email()).isEqualTo(CANONICAL_EMAIL);
    }

    @Test
    void signupRejectsInvalidPhoneAtServiceBoundary() {
        assertBusinessCode(
                () ->
                        signupService.signup(
                                new SignupCommand(
                                        CANONICAL_EMAIL, "password123", "회원", "010-1234-5678")),
                ErrorCode.INVALID_INPUT);

        verify(userRepository, never()).save(any());
    }

    @Test
    void signupUsesStableDuplicateAndVerificationErrorCodes() {
        when(userRepository.existsByEmail(CANONICAL_EMAIL)).thenReturn(true);
        assertBusinessCode(() -> signup(CANONICAL_EMAIL, "01012345678"), ErrorCode.DUPLICATE_EMAIL);

        when(userRepository.existsByEmail(CANONICAL_EMAIL)).thenReturn(false);
        when(userRepository.existsByPhoneNumber("01012345678")).thenReturn(true);
        assertBusinessCode(() -> signup(CANONICAL_EMAIL, "01012345678"), ErrorCode.DUPLICATE_PHONE);

        when(userRepository.existsByPhoneNumber("01012345678")).thenReturn(false);
        when(verificationRepository.findLatestByEmail(CANONICAL_EMAIL))
                .thenReturn(Optional.empty());
        assertBusinessCode(
                () -> signup(CANONICAL_EMAIL, "01012345678"),
                ErrorCode.EMAIL_VERIFICATION_REQUIRED);

        when(verificationRepository.findLatestByEmail(CANONICAL_EMAIL))
                .thenReturn(Optional.of(issued(CANONICAL_EMAIL, Instant.now().plusSeconds(60))));
        assertBusinessCode(
                () -> signup(CANONICAL_EMAIL, "01012345678"),
                ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }

    @Test
    void requestingCodeUsesCanonicalEmailAndStableDuplicateCode() {
        when(passwordEncoder.encode(anyString())).thenReturn("code-hash");

        verificationService.createVerificationCode("  MEMBER@Example.COM ");

        ArgumentCaptor<EmailVerification> saved = ArgumentCaptor.forClass(EmailVerification.class);
        ArgumentCaptor<String> mailedCode = ArgumentCaptor.forClass(String.class);
        verify(userRepository).existsByEmail(CANONICAL_EMAIL);
        verify(verificationRepository).deleteByEmail(CANONICAL_EMAIL);
        verify(verificationRepository).save(saved.capture());
        verify(mailSender)
                .sendVerificationCode(
                        org.mockito.ArgumentMatchers.eq(CANONICAL_EMAIL), mailedCode.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo(CANONICAL_EMAIL);
        assertThat(mailedCode.getValue()).matches("\\d{6}");

        when(userRepository.existsByEmail(CANONICAL_EMAIL)).thenReturn(true);
        assertBusinessCode(
                () -> verificationService.createVerificationCode(CANONICAL_EMAIL),
                ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void confirmingCodeUsesCanonicalEmailAndDetailedLifecycleCodes() {
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(Optional.empty());
        assertBusinessCode(
                () -> verificationService.verifyCode(" MEMBER@example.com ", "123456"),
                ErrorCode.EMAIL_VERIFICATION_NOT_FOUND);

        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(Optional.of(issued(CANONICAL_EMAIL, Instant.now().minusSeconds(1))));
        assertBusinessCode(
                () -> verificationService.verifyCode(CANONICAL_EMAIL, "123456"),
                ErrorCode.EMAIL_VERIFICATION_EXPIRED);

        EmailVerification active = issued(CANONICAL_EMAIL, Instant.now().plusSeconds(60));
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(Optional.of(active));
        when(passwordEncoder.matches("123456", "code-hash")).thenReturn(false);
        assertBusinessCode(
                () -> verificationService.verifyCode(CANONICAL_EMAIL, "123456"),
                ErrorCode.EMAIL_VERIFICATION_INVALID);
    }

    @Test
    void confirmingValidCodeMarksRequestVerified() {
        EmailVerification active = issued(CANONICAL_EMAIL, Instant.now().plusSeconds(60));
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(Optional.of(active));
        when(passwordEncoder.matches("123456", "code-hash")).thenReturn(true);

        verificationService.verifyCode(" MEMBER@example.com ", "123456");

        // 대조 전에 시도 수를 한 번 저장하고, 맞으면 인증 완료를 저장한다.
        ArgumentCaptor<EmailVerification> saved = ArgumentCaptor.forClass(EmailVerification.class);
        verify(verificationRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getValue().isVerified()).isTrue();
        assertThat(saved.getValue().getAttempts()).isEqualTo(1);
    }

    @Test
    void confirmingAlreadyVerifiedRequestIsIdempotent() {
        EmailVerification verified = verified(CANONICAL_EMAIL);
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(Optional.of(verified));

        verificationService.verifyCode(" MEMBER@example.com ", "000000");

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(verificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("인증번호는 5번까지만 대조하고, 그 뒤엔 맞는 번호도 다시 받으라고 거절한다")
    void verificationAttemptsAreCapped() {
        EmailVerification verification = issued(CANONICAL_EMAIL, Instant.now().plusSeconds(60));
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(Optional.of(verification));
        when(passwordEncoder.matches("000000", "code-hash")).thenReturn(false);
        when(passwordEncoder.matches("123456", "code-hash")).thenReturn(true);

        for (int i = 0; i < EmailVerification.MAX_ATTEMPTS; i++) {
            assertBusinessCode(
                    () -> verificationService.verifyCode(CANONICAL_EMAIL, "000000"),
                    ErrorCode.EMAIL_VERIFICATION_INVALID);
        }

        assertBusinessCode(
                () -> verificationService.verifyCode(CANONICAL_EMAIL, "123456"),
                ErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
        assertThat(verification.isVerified()).isFalse();
    }

    @Test
    @DisplayName("다시 받기 간격 안에 또 요청하면 메일을 보내지 않는다")
    void resendWithinIntervalIsRejected() {
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(
                        Optional.of(
                                new EmailVerification(
                                        1L,
                                        CANONICAL_EMAIL,
                                        "code-hash",
                                        Instant.now().plusSeconds(170),
                                        null,
                                        Instant.now().minusSeconds(5),
                                        0,
                                        1,
                                        Instant.now().minusSeconds(5))));

        assertBusinessCode(
                () -> verificationService.createVerificationCode(CANONICAL_EMAIL),
                ErrorCode.EMAIL_VERIFICATION_RESEND_TOO_SOON);
        verify(mailSender, never()).sendVerificationCode(any(), any());
    }

    @Test
    @DisplayName("한 시간에 같은 주소로 5통을 넘게 보내지 않는다")
    void sendsPerWindowAreCapped() {
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(
                        Optional.of(
                                new EmailVerification(
                                        1L,
                                        CANONICAL_EMAIL,
                                        "code-hash",
                                        Instant.now().plusSeconds(60),
                                        null,
                                        Instant.now().minusSeconds(120),
                                        0,
                                        EmailVerification.MAX_SENDS_IN_WINDOW,
                                        Instant.now().minusSeconds(600))));

        assertBusinessCode(
                () -> verificationService.createVerificationCode(CANONICAL_EMAIL),
                ErrorCode.EMAIL_VERIFICATION_SEND_LIMIT);
        verify(mailSender, never()).sendVerificationCode(any(), any());
    }

    @Test
    @DisplayName("재발송한 번호는 직전 발송 수를 이어서 센다")
    void resendCarriesSendCount() {
        when(verificationRepository.findLatestByEmailForUpdate(CANONICAL_EMAIL))
                .thenReturn(
                        Optional.of(
                                new EmailVerification(
                                        1L,
                                        CANONICAL_EMAIL,
                                        "code-hash",
                                        Instant.now().plusSeconds(60),
                                        null,
                                        Instant.now().minusSeconds(120),
                                        3,
                                        2,
                                        Instant.now().minusSeconds(600))));
        when(passwordEncoder.encode(any())).thenReturn("new-hash");

        verificationService.createVerificationCode(CANONICAL_EMAIL);

        org.mockito.ArgumentCaptor<EmailVerification> saved =
                org.mockito.ArgumentCaptor.forClass(EmailVerification.class);
        verify(verificationRepository).save(saved.capture());
        assertThat(saved.getValue().getSendsInWindow()).isEqualTo(3);
        assertThat(saved.getValue().getAttempts()).isZero();
    }

    private void signup(String email, String phoneNumber) {
        signupService.signup(new SignupCommand(email, "password123", "회원", phoneNumber));
    }

    private static EmailVerification issued(String email, Instant expiresAt) {
        return new EmailVerification(
                1L,
                email,
                "code-hash",
                expiresAt,
                null,
                Instant.now().minusSeconds(10),
                0,
                1,
                Instant.now().minusSeconds(10));
    }

    private static EmailVerification verified(String email) {
        return new EmailVerification(
                1L,
                email,
                "code-hash",
                Instant.now().plusSeconds(60),
                Instant.now(),
                Instant.now().minusSeconds(10),
                0,
                1,
                Instant.now().minusSeconds(10));
    }

    private static User savedUser(User user) {
        return new User(
                1L,
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
                Instant.now(),
                Instant.now(),
                null);
    }

    private static void assertBusinessCode(Runnable invocation, ErrorCode expected) {
        assertThatThrownBy(invocation::run)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
