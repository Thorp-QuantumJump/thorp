package com.ssafy.thorp.learning.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningArticleJpaRepository
        extends JpaRepository<LearningArticleJpaEntity, Long> {

    Optional<LearningArticleJpaEntity> findByArticleKey(String articleKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from LearningArticleJpaEntity a where a.articleKey = :articleKey")
    Optional<LearningArticleJpaEntity> lockByArticleKey(@Param("articleKey") String articleKey);

    /** 모든 글. 초안 단계 번호, 단계 안 순서, {@code article_key} 순서다(learning-admin-api §2). */
    @Query(
            "select a from LearningArticleJpaEntity a, LearningStageJpaEntity s"
                    + " where s.id = a.stageId"
                    + " order by s.number, a.stagePosition, a.articleKey")
    List<LearningArticleJpaEntity> findAllInStageOrder();

    boolean existsByArticleKey(String articleKey);
}
