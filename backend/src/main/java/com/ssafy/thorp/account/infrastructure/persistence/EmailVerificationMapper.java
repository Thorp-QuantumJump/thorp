package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.domain.model.EmailVerification;

public class EmailVerificationMapper {

    private EmailVerificationMapper() {}

    public static EmailVerificationEntity toEntity(EmailVerification emailVerification) {
        return new EmailVerificationEntity(
                emailVerification.getId(),
                emailVerification.getEmail(),
                emailVerification.getVerificationCodeHash(),
                emailVerification.getExpiresAt(),
                emailVerification.getVerifiedAt(),
                emailVerification.getCreatedAt(),
                emailVerification.getAttempts(),
                emailVerification.getSendsInWindow(),
                emailVerification.getSendWindowStartedAt());
    }

    public static EmailVerification toDomain(EmailVerificationEntity entity) {
        return new EmailVerification(
                entity.getId(),
                entity.getEmail(),
                entity.getVerificationCodeHash(),
                entity.getExpiresAt(),
                entity.getVerifiedAt(),
                entity.getCreatedAt(),
                entity.getAttempts(),
                entity.getSendsInWindow(),
                entity.getSendWindowStartedAt());
    }
}
