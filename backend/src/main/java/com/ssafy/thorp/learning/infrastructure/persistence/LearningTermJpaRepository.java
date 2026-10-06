package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningTermJpaRepository extends JpaRepository<LearningTermJpaEntity, Long> {

    List<LearningTermJpaEntity> findByArticleIdOrderByIdAsc(Long articleId);

    List<LearningTermJpaEntity> findByArticleIdInOrderByIdAsc(Collection<Long> articleIds);

    /** 모든 글의 용어. 관리 목록이 글마다 따로 읽지 않게 한 번에 읽는다. */
    List<LearningTermJpaEntity> findAllByOrderByIdAsc();

    /** 용어와 그 행이 속한 글의 키. */
    interface TermArticleRow {
        String getTermKey();

        String getArticleKey();
    }

    /** 이 용어들을 소유 중인 행의 글. 소유 중인 행은 {@code term_key}마다 하나다({@code uq_learning_term_key_owned}). */
    @Query(
            "select t.termKey as termKey, a.articleKey as articleKey"
                    + " from LearningTermJpaEntity t, LearningArticleJpaEntity a"
                    + " where a.id = t.articleId and t.transferFromArticleId is null"
                    + " and t.termKey in :termKeys")
    List<TermArticleRow> findOwners(@Param("termKeys") Collection<String> termKeys);

    /**
     * 이 용어들을 넘겨받는 중인 행의 글. 넘겨받는 중인 행은 {@code term_key}마다 하나다({@code
     * uq_learning_term_key_incoming}).
     */
    @Query(
            "select t.termKey as termKey, a.articleKey as articleKey"
                    + " from LearningTermJpaEntity t, LearningArticleJpaEntity a"
                    + " where a.id = t.articleId and t.transferFromArticleId is not null"
                    + " and t.termKey in :termKeys")
    List<TermArticleRow> findIncoming(@Param("termKeys") Collection<String> termKeys);

    /** 이 글에서 넘겨받는 중인 행. */
    List<LearningTermJpaEntity> findByArticleIdAndTransferFromArticleIdIsNotNull(Long articleId);

    @Query("select t.termKey from LearningTermJpaEntity t")
    List<String> findAllTermKeys();

    /**
     * 벌크 삭제로 즉시 지운다. {@code deleteAll}(엔티티 삭제)을 쓰면 Hibernate가 flush 때 INSERT를 DELETE보다 먼저 보내 같은
     * {@code term_key}를 다시 넣는 순간 UNIQUE 제약에 걸린다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LearningTermJpaEntity t where t.articleId = :articleId")
    int deleteByArticleIdInBulk(@Param("articleId") Long articleId);

    /**
     * 이 글이 소유한 용어 행 하나를 벌크 삭제로 즉시 지운다. 넘겨받는 글의 행이 바로 뒤에 소유 행이 되므로 flush 순서에 맡기지 않는다({@code
     * uq_learning_term_key_owned}).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "delete from LearningTermJpaEntity t where t.articleId = :articleId"
                    + " and t.termKey = :termKey and t.transferFromArticleId is null")
    int deleteOwnedInBulk(@Param("articleId") Long articleId, @Param("termKey") String termKey);
}
