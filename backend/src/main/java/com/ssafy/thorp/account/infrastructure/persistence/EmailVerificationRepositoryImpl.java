package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class EmailVerificationRepositoryImpl implements EmailVerificationRepository {

    private final EmailVerificationJpaRepository emailVerificationJpaRepository;

    public EmailVerificationRepositoryImpl(
            EmailVerificationJpaRepository emailVerificationJpaRepository) {
        this.emailVerificationJpaRepository = emailVerificationJpaRepository;
    }

    @Override
    public EmailVerification save(EmailVerification emailVerification) {
        EmailVerificationEntity entity = EmailVerificationMapper.toEntity(emailVerification);

        EmailVerificationEntity savedEntity = emailVerificationJpaRepository.save(entity);

        return EmailVerificationMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<EmailVerification> findLatestByEmail(String email) {
        return emailVerificationJpaRepository
                .findTopByEmailOrderByCreatedAtDesc(email)
                .map(EmailVerificationMapper::toDomain);
    }

    @Override
    public void deleteByEmail(String email) {
        emailVerificationJpaRepository.deleteByEmail(email);
    }
}
