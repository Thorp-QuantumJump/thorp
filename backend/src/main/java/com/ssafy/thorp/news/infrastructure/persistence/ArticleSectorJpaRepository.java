package com.ssafy.thorp.news.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 기사-섹터 연결. sectorId는 외부 식별자라 FK가 없다(spec FR-005). */
public interface ArticleSectorJpaRepository
        extends JpaRepository<NewsArticleSectorJpaEntity, NewsArticleSectorJpaEntity.Key> {

    List<NewsArticleSectorJpaEntity> findByKeyArticleIdIn(Collection<Long> articleIds);

    boolean existsByKeySectorId(String sectorId);

    /** 같은 규칙 버전으로 만든 연결만 지운다. 다른 버전의 연결은 과거 결과의 근거라 남긴다. */
    void deleteByKeyArticleIdAndKeyRuleVersion(Long articleId, String ruleVersion);
}
