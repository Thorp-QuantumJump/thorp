package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class LearningStageRepositoryImpl implements LearningStageRepository {

    private final LearningStageJpaRepository stageJpaRepository;
    private final EntityManager entityManager;

    public LearningStageRepositoryImpl(
            LearningStageJpaRepository stageJpaRepository, EntityManager entityManager) {
        this.stageJpaRepository = stageJpaRepository;
        this.entityManager = entityManager;
    }

    @Override
    public List<LearningStage> findAllOrdered() {
        return stageJpaRepository.findAllByOrderByNumberAsc().stream()
                .map(LearningStageRepositoryImpl::toDomain)
                .toList();
    }

    @Override
    public Optional<LearningStage> findByStageKey(StageKey stageKey) {
        return stageJpaRepository
                .findByStageKey(stageKey.value())
                .map(LearningStageRepositoryImpl::toDomain);
    }

    /** 잠근 뒤 {@code refresh}한다. 이 트랜잭션이 행을 먼저 읽었다면 잠금 쿼리의 결과가 1차 캐시에 밀려 버려진다. */
    @Override
    public Optional<LearningStage> findByStageKeyForUpdate(StageKey stageKey) {
        return stageJpaRepository
                .lockByStageKey(stageKey.value())
                .map(
                        entity -> {
                            entityManager.refresh(entity);
                            return toDomain(entity);
                        });
    }

    @Override
    public void saveRelease(LearningStage stage) {
        LearningStageJpaEntity entity =
                stageJpaRepository
                        .findByStageKey(stage.stageKey().value())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "learning stage not found: " + stage.stageKey()));
        entity.release(stage.releasedAt(), stage.releasedByUserId());
        stageJpaRepository.saveAndFlush(entity);
    }

    @Override
    public boolean anyReleased() {
        return stageJpaRepository.existsByReleasedAtIsNotNull();
    }

    static LearningStage toDomain(LearningStageJpaEntity entity) {
        return new LearningStage(
                entity.getId(),
                new StageKey(entity.getStageKey()),
                entity.getNumber(),
                entity.getName(),
                entity.getLevelLabel(),
                entity.getSummary(),
                entity.getReleasedAt(),
                entity.getReleasedByUserId());
    }
}
