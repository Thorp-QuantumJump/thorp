package com.ssafy.thorp.account.application.service;

import com.ssafy.thorp.account.infrastructure.persistence.EmailVerificationEntity;
import com.ssafy.thorp.account.infrastructure.persistence.EmailVerificationJpaRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.security.SecureRandom;
import java.time.Instant;

@Service
public class EmailVerificationService {

    private final EmailVerificationJpaRepository emailVerificationJpaRepository;
    private final PasswordEncoder passwordEncoder;

    private final SecureRandom secureRandom = new SecureRandom();

    public EmailVerificationService(
        EmailVerificationJpaRepository emailVerificationJpaRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.emailVerificationJpaRepository = emailVerificationJpaRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public String createVerificationCode(String email) {

        emailVerificationJpaRepository.deleteByEmail(email);

        String verificationCode =
            String.format("%06d", secureRandom.nextInt(1_000_000));

        String verificationCodeHash =
            passwordEncoder.encode(verificationCode);

        Instant expiresAt =
            Instant.now().plusSeconds(180);

        EmailVerificationEntity entity;
        entity = new EmailVerificationEntity(
            email,
            verificationCodeHash,
            expiresAt
        );

        emailVerificationJpaRepository.save(entity);

        return verificationCode;
    }
}
