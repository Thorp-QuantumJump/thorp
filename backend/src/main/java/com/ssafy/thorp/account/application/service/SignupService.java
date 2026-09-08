package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.repository.UserRepository;
import com.ssafy.thorp.account.domain.model.User;
import com.ssafy.thorp.account.presentation.request.SignupRequest;
import com.ssafy.thorp.account.presentation.response.SignupResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import com.ssafy.thorp.account.infrastructure.persistence.EmailVerificationEntity;
import com.ssafy.thorp.account.infrastructure.persistence.EmailVerificationJpaRepository;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SignupService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationJpaRepository emailVerificationJpaRepository;

    public SignupService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        EmailVerificationJpaRepository emailVerificationJpaRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationJpaRepository = emailVerificationJpaRepository;
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }

        if (userRepository.existsByPhoneNumber(request.getPhoneNumber())) {
            throw new IllegalArgumentException("이미 가입된 전화번호입니다.");
        }

        EmailVerificationEntity verification =
            emailVerificationJpaRepository
                .findTopByEmailOrderByCreatedAtDesc(request.getEmail())
                .orElseThrow(
                    () ->
                        new BusinessException(
                            ErrorCode.INVALID_INPUT,
                            "이메일 인증이 필요합니다."));

        if (verification.getVerifiedAt() == null) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "이메일 인증이 완료되지 않았습니다.");
        }

        String passwordHash = passwordEncoder.encode(request.getPassword());

        User user =
            User.register(
                request.getEmail(),
                passwordHash,
                request.getNickname(),
                request.getPhoneNumber());

        User savedUser = userRepository.save(user);

        // 인증 기록 재사용 방지
        emailVerificationJpaRepository.deleteByEmail(request.getEmail());

        return new SignupResponse(
            savedUser.getId(),
            savedUser.getEmail(),
            savedUser.getNickname(),
            savedUser.getStatus().name());
    }


}
