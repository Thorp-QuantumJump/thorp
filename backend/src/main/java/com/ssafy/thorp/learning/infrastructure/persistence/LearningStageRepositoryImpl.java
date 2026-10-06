package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.LearningStageRepository;
import com.ssafy.thorp.learning.domain.model.LearningStage;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class LearningStageRepositoryImpl implements LearningStageRepository {

    private final LearningStageJpaRepository stageJpaRepository;

    public LearningStageRepositoryImpl(LearningStageJpaRepository stageJpaRepository) {
        this.stageJpaRepository = stageJpaRepository;
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
