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

    /** 공개 목록 한 줄. native alias는 PostgreSQL이 소문자로 접으므로 따옴표로 감싼다. */
    interface PublishedSummaryRow {
        String getArticleId();

        String getTitle();

        String getQuestion();

        /** 래퍼 타입이다. 판에 키가 없으면 null이 오고, 호출자가 0으로 바꾸지 않고 실패시킨다. */
        Integer getReadingOrder();
    }

    /** 지금 공개 중인 글의 목록 항목. 판의 제목·질문·순서만 JSONB에서 꺼내고 본문은 읽지 않는다. 글의 상태와 공개 판 포인터가 함께 맞는 것만 읽는다. */
    @Query(
            value =
                    """
                    SELECT r.snapshot ->> 'articleId' AS "articleId",
                           r.snapshot ->> 'title' AS "title",
                           r.snapshot ->> 'question' AS "question",
                           CAST(r.snapshot ->> 'readingOrder' AS INTEGER) AS "readingOrder"
                    FROM learning_article a
                    JOIN learning_article_revision r ON r.id = a.published_revision_id
                    WHERE a.status = 'PUBLISHED'
                    ORDER BY CAST(r.snapshot ->> 'readingOrder' AS INTEGER), r.snapshot ->> 'articleId'
                    """,
            nativeQuery = true)
    List<PublishedSummaryRow> findPublishedSummaries();

    /** 새 기준 판이 게시 중인 글 한 줄. native alias는 따옴표로 감싼다. */
    interface CurrentArticleRow {
        String getArticleId();

        String getTitle();

        String getStageKey();

        Integer getStageNumber();

        Integer getPosition();
    }

    /**
     * {@code CURRENT} 판이 게시 중인 글. 공개 배치의 단계 번호, 단계 안 순서, {@code article_key} 순서다(data-model §12-2).
     * 제목만 판 JSON에서 꺼내고 본문은 읽지 않는다.
     */
    @Query(
            value =
                    """
                    SELECT a.article_key AS "articleId",
                           r.snapshot ->> 'title' AS "title",
                           s.stage_key AS "stageKey",
                           CAST(s.number AS INTEGER) AS "stageNumber",
                           CAST(a.published_stage_position AS INTEGER) AS "position"
                    FROM learning_article a
                    JOIN learning_article_revision r ON r.id = a.published_revision_id
                    JOIN learning_stage s ON s.id = a.published_stage_id
                    WHERE a.status = 'PUBLISHED' AND r.edition = 'CURRENT'
                    ORDER BY s.number, a.published_stage_position, a.article_key
                    """,
            nativeQuery = true)
    List<CurrentArticleRow> findCurrentInReadingOrder();

    /** 이 글의 공개 중인 판. */
    @Query(
            "select r from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED"
                    + " and a.articleKey = :articleKey")
    Optional<LearningArticleRevisionJpaEntity> findCurrentlyPublished(
            @Param("articleKey") String articleKey);
}
