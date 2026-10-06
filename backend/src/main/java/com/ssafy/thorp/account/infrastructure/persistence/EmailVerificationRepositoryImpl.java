package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.application.repository.EmailVerificationRepository;
import com.ssafy.thorp.account.domain.model.EmailVerification;
import jakarta.persistence.EntityManager;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EmailVerificationRepositoryImpl implements EmailVerificationRepository {

    private final EmailVerificationJpaRepository emailVerificationJpaRepository;
    private final EntityManager entityManager;

    public EmailVerificationRepositoryImpl(
            EmailVerificationJpaRepository emailVerificationJpaRepository,
            EntityManager entityManager) {
        this.emailVerificationJpaRepository = emailVerificationJpaRepository;
        this.entityManager = entityManager;
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

    /**
     * {@code pg_advisory_xact_lock(bigint)}. 잠금 식별자는 주소 문자열의 64비트 해시다(PostgreSQL {@code
     * hashtextextended}).
     */
    @Override
    public void lockEmail(String email) {
        entityManager
                .createNativeQuery("SELECT pg_advisory_xact_lock(hashtextextended(?1, 0))")
                .setParameter(1, "email-verification:" + email)
                .getSingleResult();
    }

    /** 서비스 트랜잭션 밖에서 직접 호출돼도 동작하도록 이 메서드에도 트랜잭션 경계를 둔다. */
    @Override
    @Transactional
    public void deleteByEmail(String email) {
        emailVerificationJpaRepository.deleteByEmail(email);
    }
}
