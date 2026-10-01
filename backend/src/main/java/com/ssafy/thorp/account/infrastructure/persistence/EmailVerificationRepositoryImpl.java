package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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
    public Optional<EmailVerification> findLatestByEmailForUpdate(String email) {
        return emailVerificationJpaRepository
                .findLatestForUpdate(email)
                .map(EmailVerificationMapper::toDomain);
    }

    /** 서비스 트랜잭션 밖에서 직접 호출돼도 동작하도록 이 메서드에도 트랜잭션 경계를 둔다. */
    @Override
    @Transactional
    public void deleteByEmail(String email) {
        emailVerificationJpaRepository.deleteByEmail(email);
    }
}
