package com.ssafy.thorp.news.application.repository;

import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsArticleId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 기사 저장소 경계. 구현은 infrastructure에 둔다. */
public interface NewsArticleRepository {

    /**
     * canonical URL 기준으로 저장하거나 갱신한다. 이미 있으면 최초 수집 시각과 외부 식별자를 유지한다(spec FR-011).
     *
     * <p>사업 키가 다른데 외부 식별자가 겹치는 충돌은 UNIQUE 제약이 감지한다. 이때 기존 행을 덮어쓰지 않고 {@link
     * NewsArticleIdCollisionException}을 던져 호출부가 그 항목만 제외하게 한다(spec FR-025).
     */
    NewsArticle upsertByCanonicalUrl(NewsArticle article);

    Optional<NewsArticle> findByCanonicalUrl(CanonicalNewsUrl canonicalUrl);

    Optional<NewsArticle> findByArticleId(NewsArticleId articleId);

    /** 같은 GUID라도 매체가 다르면 별개 기사다(spec FR-011). */
    Optional<NewsArticle> findByPublisherAndSourceItemId(Long publisherId, String sourceItemId);

    /**
     * 발행 시각이 확인됐고 구간 안인 기사만 읽는다(spec FR-006).
     *
     * <p>발행 시각 미확인 기사는 과거 사례에 연결하지 않으므로 여기서 제외된다.
     */
    List<NewsArticle> findVerifiedBetween(Instant from, Instant to);

    /** 표시 가능한 최근 기사. 현재 뉴스 묶음의 후보다. */
    List<NewsArticle> findDisplayEligibleSince(Instant since);

    /** 사업 키가 다른데 외부 식별자가 겹쳤다. 기존 기록을 덮어쓰지 않는다(spec FR-025). */
    class NewsArticleIdCollisionException extends RuntimeException {

        public NewsArticleIdCollisionException(NewsArticleId articleId) {
            super("ID_COLLISION: " + articleId.value());
        }
    }
}
