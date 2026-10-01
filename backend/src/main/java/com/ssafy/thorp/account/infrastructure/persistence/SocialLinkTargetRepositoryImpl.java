package com.ssafy.thorp.account.infrastructure.persistence;

import com.ssafy.thorp.account.application.repository.SocialLinkTargetRepository;
import com.ssafy.thorp.account.domain.model.SocialLinkTarget;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Repository;

@Repository
public class SocialLinkTargetRepositoryImpl implements SocialLinkTargetRepository {

    private final SocialLinkTargetJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public SocialLinkTargetRepositoryImpl(
            SocialLinkTargetJpaRepository jpaRepository, EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
    }

    /** 잠근 뒤 {@code refresh}한다. 1차 캐시가 남아 있으면 잠금으로 기다린 사이 커밋된 시도 수를 놓친다. */
    @Override
    public SocialLinkTarget lockOrOpen(Long userId, Instant now) {
        jpaRepository.insertIfAbsent(userId, now);
        SocialLinkTargetEntity entity =
                jpaRepository
                        .findForUpdate(userId)
                        .orElseThrow(() -> new IllegalStateException("link target row missing"));
        entityManager.refresh(entity);
        return entity.toDomain();
    }

    @Override
    public void save(SocialLinkTarget target) {
        jpaRepository.findById(target.getUserId()).ifPresent(entity -> entity.apply(target));
    }

    @Override
    public int deleteWindowsStartedBefore(Instant cutoff) {
        return jpaRepository.deleteWindowsStartedBefore(cutoff);
    }
}
