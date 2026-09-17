package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** 기록은 추가·조회만 한다. 삭제 메서드가 딸려 오지 않도록 {@code JpaRepository}를 상속하지 않는다. */
public interface LearningPublicationEventJpaRepository
        extends Repository<LearningPublicationEventJpaEntity, Long> {

    LearningPublicationEventJpaEntity save(LearningPublicationEventJpaEntity entity);

    Optional<LearningPublicationEventJpaEntity> findFirstByArticleIdOrderByIdDesc(Long articleId);
}
