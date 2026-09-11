package com.ssafy.thorp.news.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 기사-주제 연결. 원문 분류 값은 저장하지 않는다(spec FR-010). */
public interface ArticleTopicJpaRepository
        extends JpaRepository<NewsArticleTopicJpaEntity, NewsArticleTopicJpaEntity.Key> {

    List<NewsArticleTopicJpaEntity> findByKeyArticleIdIn(Collection<Long> articleIds);
}
