package com.ssafy.thorp.learning.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 단계는 마이그레이션만 만든다. 추가·삭제 메서드를 두지 않는다. 공개 열은 '단계 공개'가 잠근 행에 한 번 쓴다. */
public interface LearningStageJpaRepository extends Repository<LearningStageJpaEntity, Long> {

    List<LearningStageJpaEntity> findAllByOrderByNumberAsc();

    Optional<LearningStageJpaEntity> findById(Long id);

    Optional<LearningStageJpaEntity> findByStageKey(String stageKey);

    boolean existsByReleasedAtIsNotNull();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from LearningStageJpaEntity s where s.stageKey = :stageKey")
    Optional<LearningStageJpaEntity> lockByStageKey(@Param("stageKey") String stageKey);

    LearningStageJpaEntity saveAndFlush(LearningStageJpaEntity entity);
}
