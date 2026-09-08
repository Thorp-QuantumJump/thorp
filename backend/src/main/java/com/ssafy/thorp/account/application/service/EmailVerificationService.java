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
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    private final SecureRandom secureRandom = new SecureRandom();

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
        String verificationCode = transactionTemplate.execute(status -> issueCode(email));

        verificationMailSender.sendVerificationCode(email, verificationCode);
    }

    /** 인증번호를 만들어 해시로 저장하고 원문을 돌려준다. DB 작업만 하는 트랜잭션 단위. */
    private String issueCode(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 가입된 이메일입니다.");
        }

        emailVerificationRepository.deleteByEmail(email);

        String verificationCode = String.format(CODE_FORMAT, secureRandom.nextInt(CODE_BOUND));

        String verificationCodeHash = passwordEncoder.encode(verificationCode);

        EmailVerification verification =
                EmailVerification.issue(email, verificationCodeHash, Instant.now().plus(CODE_TTL));

        emailVerificationRepository.save(verification);

        return verificationCode;
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
