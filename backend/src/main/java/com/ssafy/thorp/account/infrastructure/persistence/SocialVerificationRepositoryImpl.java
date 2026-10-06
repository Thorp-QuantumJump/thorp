package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.application.repository.SocialVerificationRepository;
import com.ssafy.thorp.account.domain.model.SocialVerification;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class SocialVerificationRepositoryImpl implements SocialVerificationRepository {

    private final SocialVerificationJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public SocialVerificationRepositoryImpl(
            SocialVerificationJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    /**
     * 잠근 기록을 읽는다.
     *
     * <p>잠근 뒤 {@code refresh}한다. 같은 트랜잭션의 1차 캐시에 남은 엔티티가 있으면 잠금으로 기다린 사이 다른 요청이 커밋한 시도 수를 놓친다.
     */
    @Override
    public SocialVerification lockOrOpen(String id, Instant now) {
        jpaRepository.insertIfAbsent(id, now);
        SocialVerificationEntity entity =
                jpaRepository
                        .findForUpdate(id)
                        .orElseThrow(() -> new IllegalStateException("verification row missing"));
        entityManager.refresh(entity);
        return entity.toDomain();
    }

    @Override
    public void save(SocialVerification verification) {
        jpaRepository
                .findById(verification.getId())
                .ifPresent(entity -> entity.apply(verification));
    }

    @Override
    public void delete(String id) {
        jpaRepository.deleteById(id);
    }

    @Override
    public int deleteCreatedBefore(Instant cutoff) {
        return jpaRepository.deleteCreatedBefore(cutoff);
    }
}
