package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.application.port.VerificationMailSender;
import com.ssafy.thorp.account.infrastructure.persistence.EmailVerificationEntity;
import com.ssafy.thorp.account.infrastructure.persistence.EmailVerificationJpaRepository;
import java.security.SecureRandom;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;

@Service
public class EmailVerificationService {

    private final EmailVerificationJpaRepository emailVerificationJpaRepository;
    private final PasswordEncoder passwordEncoder;
    private final VerificationMailSender verificationMailSender;

    private final SecureRandom secureRandom = new SecureRandom();

    public EmailVerificationService(
            EmailVerificationJpaRepository emailVerificationJpaRepository,
            PasswordEncoder passwordEncoder,
            VerificationMailSender verificationMailSender) {
        this.emailVerificationJpaRepository = emailVerificationJpaRepository;
        this.passwordEncoder = passwordEncoder;
        this.verificationMailSender = verificationMailSender;
    }

    @Transactional
    public void createVerificationCode(String email) {

        emailVerificationJpaRepository.deleteByEmail(email);

        String verificationCode = String.format("%06d", secureRandom.nextInt(1_000_000));

        String verificationCodeHash = passwordEncoder.encode(verificationCode);

        Instant expiresAt = Instant.now().plusSeconds(180);

        EmailVerificationEntity entity;
        entity = new EmailVerificationEntity(email, verificationCodeHash, expiresAt);

        emailVerificationJpaRepository.save(entity);

        verificationMailSender.sendVerificationCode(email, verificationCode);
    }

    @Transactional
    public void verifyCode(String email, String verificationCode) {
        EmailVerificationEntity entity =
            emailVerificationJpaRepository
                .findTopByEmailOrderByCreatedAtDesc(email)
                .orElseThrow(
                    () ->
                        new BusinessException(
                            ErrorCode.INVALID_INPUT,
                            "이메일 인증 요청을 찾을 수 없습니다."));

        if (entity.getVerifiedAt() != null) {
            return;
        }

        Instant now = Instant.now();

        if (!now.isBefore(entity.getExpiresAt())) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "인증번호가 만료되었습니다.");
        }

        if (!passwordEncoder.matches(
            verificationCode,
            entity.getVerificationCodeHash())) {
            throw new BusinessException(
                ErrorCode.INVALID_INPUT,
                "인증번호가 올바르지 않습니다.");
        }

        entity.verify(now);
    }
}
