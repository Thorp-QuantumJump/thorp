package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** 기사. canonical URL이 중복 판정의 1차 열쇠다(spec FR-011). */
public interface ArticleJpaRepository extends JpaRepository<NewsArticleJpaEntity, Long> {

    Optional<NewsArticleJpaEntity> findByCanonicalUrl(String canonicalUrl);

    Optional<NewsArticleJpaEntity> findByArticleId(String articleId);

    Optional<NewsArticleJpaEntity> findByPublisherIdAndSourceItemId(
            Long publisherId, String sourceItemId);

    /** 발행 시각이 확인됐고 구간 안인 기사만 읽는다. 미확인 기사는 과거 사례에 연결하지 않는다(spec FR-006). */
    List<NewsArticleJpaEntity>
            findByPublishedAtStatusAndPublishedAtBetweenOrderByPublishedAtAscIdAsc(
                    PublicationTimeStatus publishedAtStatus, Instant from, Instant to);

    List<NewsArticleJpaEntity> findByDisplayEligibleTrueAndCollectedAtGreaterThanEqual(
            Instant since);

    List<NewsArticleJpaEntity> findByIdIn(Collection<Long> ids);
}
