package com.ssafy.thorp.learning.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningTermJpaRepository extends JpaRepository<LearningTermJpaEntity, Long> {

    List<LearningTermJpaEntity> findByArticleIdOrderByIdAsc(Long articleId);

    Optional<LearningTermJpaEntity> findByTermKey(String termKey);

    @Query("select t.termKey from LearningTermJpaEntity t")
    List<String> findAllTermKeys();

    /**
     * 벌크 삭제로 즉시 지운다. {@code deleteAll}(엔티티 삭제)을 쓰면 Hibernate가 flush 때 INSERT를 DELETE보다 먼저 보내 같은
     * {@code term_key}를 다시 넣는 순간 UNIQUE 제약에 걸린다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LearningTermJpaEntity t where t.articleId = :articleId")
    int deleteByArticleIdInBulk(@Param("articleId") Long articleId);
}
