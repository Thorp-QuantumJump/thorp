package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * 판은 추가·조회만 한다. {@code JpaRepository}를 상속하지 않는 이유는 {@code delete*}·{@code deleteAll}이 딸려 오기 때문이다. 쓸
 * 수 있는 메서드를 여기 적은 것으로 한정한다(원칙 VIII).
 */
public interface LearningArticleRevisionJpaRepository
        extends Repository<LearningArticleRevisionJpaEntity, Long> {

    LearningArticleRevisionJpaEntity save(LearningArticleRevisionJpaEntity entity);

    Optional<LearningArticleRevisionJpaEntity> findById(Long id);

    List<LearningArticleRevisionJpaEntity> findByArticleIdOrderByRevisionNoAsc(Long articleId);

    /** 지금 공개 중인 판 전부. 글의 상태와 공개 판 포인터가 함께 맞는 것만 읽는다. */
    @Query(
            "select r from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED")
    List<LearningArticleRevisionJpaEntity> findCurrentlyPublished();

    /** 이 글의 공개 중인 판. */
    @Query(
            "select r from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED"
                    + " and a.articleKey = :articleKey")
    Optional<LearningArticleRevisionJpaEntity> findCurrentlyPublished(
            @Param("articleKey") String articleKey);
}
