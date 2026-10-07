package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.Edition;
import java.util.Collection;
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

    /** 이 글의 가장 최근 판. 게시가 다음 판 번호와 같은 문제 판정의 기준을 함께 얻는다. */
    Optional<LearningArticleRevisionJpaEntity> findFirstByArticleIdOrderByRevisionNoDesc(
            Long articleId);

    boolean existsByArticleIdAndEdition(Long articleId, Edition edition);

    /** 판 번호만. 본문 JSON을 읽지 않는다. */
    interface RevisionNoRow {
        Long getId();

        Integer getRevisionNo();
    }

    @Query(
            "select r.id as id, r.revisionNo as revisionNo"
                    + " from LearningArticleRevisionJpaEntity r where r.id in :ids")
    List<RevisionNoRow> findRevisionNos(@Param("ids") Collection<Long> ids);

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

    /** 새 기준 판이 게시 중인 글의 문제 하나. native alias는 따옴표로 감싼다. */
    interface CurrentQuestionRow {
        String getArticleId();

        String getQuestionId();
    }

    /**
     * {@code CURRENT} 판이 게시 중인 글마다 공개 판의 문제 식별자. 글, 판 안 순서다. 판 JSON에서 문제 식별자만 꺼내고 본문은 읽지 않는다. 나의
     * 학습이 공개된 문제 수와 첫 답 정답 수를 셀 때 쓴다(data-model §12-1).
     */
    @Query(
            value =
                    """
                    SELECT a.article_key AS "articleId",
                           q.value ->> 'questionId' AS "questionId"
                    FROM learning_article a
                    JOIN learning_article_revision r ON r.id = a.published_revision_id
                    CROSS JOIN LATERAL jsonb_array_elements(
                        COALESCE(r.snapshot -> 'quiz', CAST('[]' AS JSONB)))
                        WITH ORDINALITY AS q(value, ordinality)
                    WHERE a.status = 'PUBLISHED' AND r.edition = 'CURRENT'
                    ORDER BY a.article_key, q.ordinality
                    """,
            nativeQuery = true)
    List<CurrentQuestionRow> findCurrentQuestionIds();

    /** 이 글의 공개 중인 판의 종류. 판 JSON을 읽지 않는다. */
    @Query(
            "select r.edition from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED"
                    + " and a.articleKey = :articleKey")
    Optional<Edition> findPublishedEdition(@Param("articleKey") String articleKey);

    /** 이 글의 공개 중인 판. */
    @Query(
            "select r from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED"
                    + " and a.articleKey = :articleKey")
    Optional<LearningArticleRevisionJpaEntity> findCurrentlyPublished(
            @Param("articleKey") String articleKey);

    /** 이 글의 공개 중인 판과 공개 배치. 한 문장으로 읽는다. */
    @Query(
            "select new com.ssafy.thorp.learning.infrastructure.persistence.PublishedRevisionRow("
                    + "a.articleKey, r, a.publishedStageId, a.publishedStagePosition)"
                    + " from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED"
                    + " and a.articleKey = :articleKey")
    Optional<PublishedRevisionRow> findCurrentlyPublishedWithPlacement(
            @Param("articleKey") String articleKey);

    /** 커리큘럼 한 줄. native alias는 따옴표로 감싼다. */
    interface PublishedEntryRow {
        String getArticleId();

        String getEdition();

        String getStageKey();

        Integer getPosition();

        String getTitle();

        String getQuestion();

        /** {@code LEGACY} 판에는 읽기 값이 없어 null이다. */
        Integer getReadingMinutes();

        Boolean getHasExample();
    }

    /**
     * 공개 중인 글 전부의 커리큘럼 값. 공개 배치의 단계 번호, 단계 안 순서, {@code article_key} 순서다({@link
     * #findCurrentInReadingOrder}와 같은 순서라 커리큘럼 번호와 글 화면 번호가 같다). 판 JSON에서 제목·질문·읽기 분·예시 유무만 꺼내고 본문은
     * 읽지 않는다. 본문 하나가 깨져도 커리큘럼은 나간다.
     */
    @Query(
            value =
                    """
                    SELECT a.article_key AS "articleId",
                           r.edition AS "edition",
                           s.stage_key AS "stageKey",
                           CAST(a.published_stage_position AS INTEGER) AS "position",
                           r.snapshot ->> 'title' AS "title",
                           r.snapshot ->> 'question' AS "question",
                           CAST(r.snapshot -> 'reading' ->> 'readingMinutes' AS INTEGER)
                               AS "readingMinutes",
                           COALESCE(jsonb_typeof(r.snapshot -> 'example') = 'object', FALSE)
                               AS "hasExample"
                    FROM learning_article a
                    JOIN learning_article_revision r ON r.id = a.published_revision_id
                    JOIN learning_stage s ON s.id = a.published_stage_id
                    WHERE a.status = 'PUBLISHED'
                    ORDER BY s.number, a.published_stage_position, a.article_key
                    """,
            nativeQuery = true)
    List<PublishedEntryRow> findPublishedEntries();

    /** 이 글들의 공개 중인 판과 공개 배치. 한 문장으로 읽는다. */
    @Query(
            "select new com.ssafy.thorp.learning.infrastructure.persistence.PublishedRevisionRow("
                    + "a.articleKey, r, a.publishedStageId, a.publishedStagePosition)"
                    + " from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED"
                    + " and a.articleKey in :articleKeys")
    List<PublishedRevisionRow> findCurrentlyPublishedIn(
            @Param("articleKeys") Collection<String> articleKeys);

    /** 공개 중인 판 전부. 관리 목록이 글마다 따로 읽지 않게 한 번에 읽는다. */
    @Query(
            "select new com.ssafy.thorp.learning.infrastructure.persistence.PublishedRevisionRow("
                    + "a.articleKey, r, a.publishedStageId, a.publishedStagePosition)"
                    + " from LearningArticleRevisionJpaEntity r, LearningArticleJpaEntity a"
                    + " where a.publishedRevisionId = r.id"
                    + " and a.status = com.ssafy.thorp.learning.domain.model.ArticleStatus.PUBLISHED")
    List<PublishedRevisionRow> findAllCurrentlyPublished();
}
