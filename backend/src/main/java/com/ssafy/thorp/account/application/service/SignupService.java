package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.EmailNormalizer;
import com.ssafy.thorp.account.application.command.SignupCommand;
import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.SignupResult;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.util.regex.Pattern;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {

    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^01[016789]\\d{7,8}$");

    private final UserRepository userRepository;
    private final EmailVerificationRepository emailVerificationRepository;
    private final PasswordEncoder passwordEncoder;

    public SignupService(
            UserRepository userRepository,
            EmailVerificationRepository emailVerificationRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.emailVerificationRepository = emailVerificationRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 이메일 인증을 마친 사용자만 가입시킨다. 가입에 쓴 인증 기록은 재사용하지 못하도록 지운다. */
    @Transactional
    public SignupResult signup(SignupCommand command) {
        String email = EmailNormalizer.normalize(command.email());

        if (command.phoneNumber() == null
                || !PHONE_NUMBER_PATTERN.matcher(command.phoneNumber()).matches()) {
            throw new BusinessException(
                    ErrorCode.INVALID_INPUT, "국내 휴대폰 번호 형식이어야 합니다. 예: 01012345678");
        }

        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(ErrorCode.DUPLICATE_EMAIL);
        }

        if (userRepository.existsByPhoneNumber(command.phoneNumber())) {
            throw new BusinessException(ErrorCode.DUPLICATE_PHONE);
        }

        EmailVerification verification =
                emailVerificationRepository
                        .findLatestByEmail(email)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED));

        if (!verification.isVerified()) {
            throw new BusinessException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        }

        String passwordHash = passwordEncoder.encode(command.password());

        User user = User.register(email, passwordHash, command.nickname(), command.phoneNumber());

        User savedUser = userRepository.save(user);

        emailVerificationRepository.deleteByEmail(email);

        return new SignupResult(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getNickname(),
                savedUser.getStatus().name());
    }
}
