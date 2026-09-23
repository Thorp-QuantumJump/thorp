package com.ssafy.thorp.news.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 기사-주제 연결. 원문 분류 값은 저장하지 않는다(spec FR-010). */
public interface ArticleTopicJpaRepository
        extends JpaRepository<NewsArticleTopicJpaEntity, NewsArticleTopicJpaEntity.Key> {

    List<NewsArticleTopicJpaEntity> findByKeyArticleIdIn(Collection<Long> articleIds);

    /** 같은 규칙 버전으로 만든 연결만 지운다. 다른 버전의 연결은 과거 결과의 근거라 남긴다. */
    void deleteByKeyArticleIdAndKeyRuleVersion(Long articleId, String ruleVersion);
}
