package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/** 기록은 추가·조회만 한다. 삭제 메서드가 딸려 오지 않도록 {@code JpaRepository}를 상속하지 않는다. */
public interface LearningPublicationEventJpaRepository
        extends Repository<LearningPublicationEventJpaEntity, Long> {

    LearningPublicationEventJpaEntity save(LearningPublicationEventJpaEntity entity);

    Optional<LearningPublicationEventJpaEntity> findFirstByArticleIdOrderByIdDesc(Long articleId);

    /** 글마다 마지막 기록. 관리 목록이 글마다 따로 읽지 않게 한 번에 읽는다. */
    @Query(
            "select new com.ssafy.thorp.learning.infrastructure.persistence"
                    + ".KeyedEventRow(a.articleKey, e)"
                    + " from LearningPublicationEventJpaEntity e, LearningArticleJpaEntity a"
                    + " where a.id = e.articleId"
                    + " and e.id = (select max(e2.id) from LearningPublicationEventJpaEntity e2"
                    + " where e2.articleId = e.articleId)")
    List<KeyedEventRow> findLastForEachArticle();
}
