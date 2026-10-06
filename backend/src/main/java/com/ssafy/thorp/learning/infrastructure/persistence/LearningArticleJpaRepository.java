package com.ssafy.thorp.learning.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningArticleJpaRepository
        extends JpaRepository<LearningArticleJpaEntity, Long> {

    Optional<LearningArticleJpaEntity> findByArticleKey(String articleKey);

    List<LearningArticleJpaEntity> findByArticleKeyIn(Collection<String> articleKeys);

    /** 대리키만. 본문 JSON을 읽지 않는다. */
    @Query("select a.id from LearningArticleJpaEntity a where a.articleKey = :articleKey")
    Optional<Long> findIdByArticleKey(@Param("articleKey") String articleKey);

    /** {@code articleId}만. 본문 JSON을 읽지 않는다. */
    @Query("select a.articleKey from LearningArticleJpaEntity a where a.id = :id")
    Optional<String> findArticleKeyById(@Param("id") Long id);

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

    /** 단계 관리 한 줄. native alias는 PostgreSQL이 소문자로 접으므로 따옴표로 감싼다. */
    interface StageArticleRow {
        String getArticleId();

        String getTitle();

        String getFormat();

        String getStatus();

        /** 게시되지 않았으면 null이다. */
        String getPublishedEdition();

        Integer getDraftVersion();

        String getStageKey();

        Integer getPosition();

        String getPublishedStageKey();

        Integer getPublishedPosition();
    }

    /**
     * 모든 글의 단계 관리 값. 초안 단계 번호, 단계 안 순서, {@code article_key} 순서다({@link #findAllInStageOrder}와 같다).
     * 본문·용어·퀴즈와 판 JSON을 읽지 않는다. 판은 종류만 본다.
     */
    @Query(
            value =
                    """
                    SELECT a.article_key AS "articleId",
                           a.title AS "title",
                           a.format AS "format",
                           a.status AS "status",
                           r.edition AS "publishedEdition",
                           a.draft_version AS "draftVersion",
                           ds.stage_key AS "stageKey",
                           CAST(a.stage_position AS INTEGER) AS "position",
                           ps.stage_key AS "publishedStageKey",
                           CAST(a.published_stage_position AS INTEGER) AS "publishedPosition"
                    FROM learning_article a
                    JOIN learning_stage ds ON ds.id = a.stage_id
                    LEFT JOIN learning_stage ps ON ps.id = a.published_stage_id
                    LEFT JOIN learning_article_revision r
                           ON r.id = a.published_revision_id AND a.status = 'PUBLISHED'
                    ORDER BY ds.number, a.stage_position, a.article_key
                    """,
            nativeQuery = true)
    List<StageArticleRow> findStageRows();
}
