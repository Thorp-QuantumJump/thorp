package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.port.VerificationMailSender;
import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailVerificationService {

    /** 인증번호는 6자리. */
    private static final int CODE_BOUND = 1_000_000;

    private static final String CODE_FORMAT = "%06d";

    /** 인증번호 유효시간. */
    private static final Duration CODE_TTL = Duration.ofMinutes(3);

    private final EmailVerificationRepository emailVerificationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationMailSender verificationMailSender;

    private final SecureRandom secureRandom = new SecureRandom();

    public EmailVerificationService(
            EmailVerificationRepository emailVerificationRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            VerificationMailSender verificationMailSender) {
        this.emailVerificationRepository = emailVerificationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationMailSender = verificationMailSender;
    }

    /**
     * 인증번호를 새로 발급해 메일로 보낸다. 재발송이면 기존 인증 기록을 지운다.
     *
     * <p>이미 가입된 이메일이면 기존 인증 기록에 손대지 않고 바로 거절한다.
     */
    @Transactional
    public void createVerificationCode(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 가입된 이메일입니다.");
        }

        emailVerificationRepository.deleteByEmail(email);

        String verificationCode = String.format(CODE_FORMAT, secureRandom.nextInt(CODE_BOUND));

        String verificationCodeHash = passwordEncoder.encode(verificationCode);

        EmailVerification verification =
                EmailVerification.issue(email, verificationCodeHash, Instant.now().plus(CODE_TTL));

        emailVerificationRepository.save(verification);

        verificationMailSender.sendVerificationCode(email, verificationCode);
    }

    /** 인증번호가 일치하고 만료되지 않았을 때만 인증 완료로 기록한다. */
    @Transactional
    public void verifyCode(String email, String verificationCode) {
        EmailVerification verification =
                emailVerificationRepository
                        .findLatestByEmail(email)
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INVALID_INPUT, "이메일 인증 요청을 찾을 수 없습니다."));

        // 이미 인증을 마친 요청을 다시 확인해도 실패시키지 않는다.
        if (verification.isVerified()) {
            return;
        }

        Instant now = Instant.now();

        if (verification.isExpired(now)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "인증번호가 만료되었습니다.");
        }

        if (!passwordEncoder.matches(verificationCode, verification.getVerificationCodeHash())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "인증번호가 올바르지 않습니다.");
        }

        verification.verify(now);

        emailVerificationRepository.save(verification);
    }
}
