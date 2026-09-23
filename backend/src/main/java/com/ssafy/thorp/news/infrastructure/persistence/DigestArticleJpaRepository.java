package com.ssafy.thorp.news.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 묶음 멤버십. */
public interface DigestArticleJpaRepository
        extends JpaRepository<NewsDigestArticleJpaEntity, NewsDigestArticleJpaEntity.Key> {

    List<NewsDigestArticleJpaEntity> findByKeyDigestId(Long digestId);

    List<NewsDigestArticleJpaEntity> findByKeyDigestIdIn(Collection<Long> digestIds);

    /**
     * 한 묶음의 멤버십을 지운다.
     *
     * <p>파생 삭제(select 후 remove)를 쓰지 않고 벌크 DELETE로 즉시 실행한다. 파생 삭제는 영속성 컨텍스트에 삭제를 예약만 하는데, Hibernate가
     * flush 때 INSERT를 DELETE보다 먼저 내보내므로 같은 멤버를 다시 넣는 재저장에서 복합 기본키 충돌이 난다.
     *
     * <p>{@code flushAutomatically}로 앞선 변경을 먼저 반영하고 {@code clearAutomatically}로 벌크 연산과 어긋난 1차 캐시를
     * 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM NewsDigestArticleJpaEntity m WHERE m.key.digestId = :digestId")
    int deleteByKeyDigestId(@Param("digestId") Long digestId);
}
