package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 단계는 마이그레이션만 만든다. 추가·삭제 메서드를 두지 않는다. */
public interface LearningStageJpaRepository extends Repository<LearningStageJpaEntity, Long> {

    List<LearningStageJpaEntity> findAllByOrderByNumberAsc();

    Optional<LearningStageJpaEntity> findById(Long id);

    Optional<LearningStageJpaEntity> findByStageKey(String stageKey);

    boolean existsByReleasedAtIsNotNull();
}
