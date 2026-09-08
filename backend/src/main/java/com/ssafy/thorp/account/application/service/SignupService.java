package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.command.SignupCommand;
import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.application.result.SignupResult;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {

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
        if (userRepository.existsByEmail(command.email())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 가입된 이메일입니다.");
        }

        if (userRepository.existsByPhoneNumber(command.phoneNumber())) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이미 가입된 전화번호입니다.");
        }

        EmailVerification verification =
                emailVerificationRepository
                        .findLatestByEmail(command.email())
                        .orElseThrow(
                                () ->
                                        new BusinessException(
                                                ErrorCode.INVALID_INPUT, "이메일 인증이 필요합니다."));

        if (!verification.isVerified()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "이메일 인증이 완료되지 않았습니다.");
        }

        String passwordHash = passwordEncoder.encode(command.password());

        User user =
                User.register(
                        command.email(), passwordHash, command.nickname(), command.phoneNumber());

        User savedUser = userRepository.save(user);

        emailVerificationRepository.deleteByEmail(command.email());

        return new SignupResult(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getNickname(),
                savedUser.getStatus().name());
    }
}
