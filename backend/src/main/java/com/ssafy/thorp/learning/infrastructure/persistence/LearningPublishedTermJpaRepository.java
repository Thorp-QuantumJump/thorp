package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningPublishedTermJpaRepository
        extends JpaRepository<LearningPublishedTermJpaEntity, String> {

    /** 색인 행 하나와 그 행이 가리키는 판의 글 식별자·제목. native alias는 PostgreSQL이 소문자로 접으므로 따옴표로 감싼다. */
    interface PublishedTermRow {
        String getTermId();

        String getLabel();

        String getSentence();

        String getArticleId();

        String getArticleTitle();
    }

    /**
     * 게시 용어 색인만 읽는다. 글의 공개 판 포인터와 색인 행의 판이 같을 때만 돌려준다. 색인은 게시·게시 취소와 같은 트랜잭션에서 다시 쓰이므로 평소에는 늘 같지만,
     * 어긋난 행을 공개하느니 '설명 준비 중'으로 두는 편이 SC-003을 깨지 않는다.
     */
    @Query(
            value =
                    """
                    SELECT pt.term_key AS "termId",
                           pt.label AS "label",
                           pt.sentence AS "sentence",
                           a.article_key AS "articleId",
                           r.snapshot ->> 'title' AS "articleTitle"
                    FROM learning_published_term pt
                    JOIN learning_article a
                      ON a.id = pt.article_id
                     AND a.status = 'PUBLISHED'
                     AND a.published_revision_id = pt.revision_id
                    JOIN learning_article_revision r ON r.id = pt.revision_id
                    WHERE pt.term_key = :termKey
                    """,
            nativeQuery = true)
    Optional<PublishedTermRow> findPublished(@Param("termKey") String termKey);

    /** 벌크 삭제로 즉시 지운다. 같은 {@code term_key}를 곧바로 다시 넣으므로 flush 순서에 맡기지 않는다. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LearningPublishedTermJpaEntity t where t.articleId = :articleId")
    int deleteByArticleIdInBulk(@Param("articleId") Long articleId);
}
