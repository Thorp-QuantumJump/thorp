package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.EmailNormalizer;
import com.ssafy.thorp.account.application.port.VerificationMailSender;
import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EmailVerificationService {

    /** 인증번호 유효시간. */
    private static final Duration CODE_TTL = Duration.ofMinutes(3);

    private final EmailVerificationRepository emailVerificationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationMailSender verificationMailSender;
    private final TransactionTemplate transactionTemplate;

    public EmailVerificationService(
            EmailVerificationRepository emailVerificationRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            VerificationMailSender verificationMailSender,
            TransactionTemplate transactionTemplate) {
        this.emailVerificationRepository = emailVerificationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationMailSender = verificationMailSender;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 인증번호를 새로 발급해 메일로 보낸다. 재발송이면 기존 인증 기록을 지운다.
     *
     * <p>이미 가입된 이메일이면 기존 인증 기록에 손대지 않고 바로 거절한다.
     *
     * <p>이 메서드에는 {@code @Transactional}을 붙이지 않는다. SMTP 통신은 수 초가 걸리고 메일 서버가 응답하지 않으면 더 길어지는데, 그 시간
     * 동안 DB 커넥션을 잡고 있으면 커넥션 풀이 마른다. 그래서 DB 작업만 트랜잭션으로 묶어 먼저 커밋하고, 메일 발송은 트랜잭션 밖에서 한다.
     */
    public void createVerificationCode(String email) {
        String canonicalEmail = EmailNormalizer.normalize(email);
        String verificationCode = transactionTemplate.execute(status -> issueCode(canonicalEmail));

        verificationMailSender.sendVerificationCode(canonicalEmail, verificationCode);
    }

    /** 인증번호를 만들어 해시로 저장하고 원문을 돌려준다. DB 작업만 하는 트랜잭션 단위. */
    private String issueCode(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        Instant now = Instant.now();

        // 이 주소의 발급·대조를 한 줄로 세운다. 동시에 들어온 재발송이 간격 판정을 함께 넘지 못하게 한다.
        emailVerificationRepository.lockEmail(email);
        EmailVerification previous =
                emailVerificationRepository.findLatestByEmail(email).orElse(null);
        if (EmailVerification.resendTooSoon(previous, now)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_RESEND_TOO_SOON);
        }

        emailVerificationRepository.deleteByEmail(email);

        String verificationCode = VerificationCodes.next();

        String verificationCodeHash = passwordEncoder.encode(verificationCode);

        // 재발송해도 발송 수는 한 시간 창 안에서 이어서 센다. 직전 기록을 지우기 전에 읽어 둔 값으로 잇는다.
        EmailVerification verification =
                EmailVerification.issue(
                        email, verificationCodeHash, now.plus(CODE_TTL), previous, now);

        emailVerificationRepository.save(verification);

        return verificationCode;
    }

    /**
     * 인증번호를 대조한다. 일치하고 만료되지 않았을 때만 인증 완료로 기록한다.
     *
     * <p>대조 수는 대조 전에 센다. 번호 하나를 {@link EmailVerification#MAX_ATTEMPTS}번까지만 대조하고, 넘으면 새로 받아야 한다. 최신
     * 요청을 잠가 읽으므로 같은 주소로 동시에 보내도 상한을 함께 넘지 못한다. 틀린 대조도 센 수가 남아야 하므로 {@link BusinessException}에서
     * 롤백하지 않는다.
     */
    @Transactional(noRollbackFor = BusinessException.class)
    public void verifyCode(String email, String verificationCode) {
        String canonicalEmail = EmailNormalizer.normalize(email);
        // 발급과 같은 잠금이다. 재발송이 행을 바꾸는 중이면 끝난 뒤의 최신 요청을 본다.
        emailVerificationRepository.lockEmail(canonicalEmail);
        EmailVerification verification =
                emailVerificationRepository
                        .findLatestByEmail(canonicalEmail)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.EMAIL_VERIFICATION_NOT_FOUND));

        // 이미 인증을 마친 요청을 다시 확인해도 실패시키지 않는다.
        if (verification.isVerified()) {
            return;
        }

        Instant now = Instant.now();

        if (verification.isExpired(now)) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_EXPIRED);
        }

        if (verification.attemptsExhausted()) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_ATTEMPTS_EXCEEDED);
        }
        verification.recordAttempt();
        emailVerificationRepository.save(verification);

        if (!passwordEncoder.matches(verificationCode, verification.getVerificationCodeHash())) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_INVALID);
        }

        verification.verify(now);

        emailVerificationRepository.save(verification);
    }
}
