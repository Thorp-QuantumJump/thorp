package com.ssafy.thorp.news.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 기사-섹터 연결. sectorId는 외부 식별자라 FK가 없다(spec FR-005). */
public interface ArticleSectorJpaRepository
        extends JpaRepository<NewsArticleSectorJpaEntity, NewsArticleSectorJpaEntity.Key> {

    List<NewsArticleSectorJpaEntity> findByKeyArticleIdIn(Collection<Long> articleIds);

    boolean existsByKeySectorId(String sectorId);
}
