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

    /**
     * 내보내기 한 페이지(spec FR-017).
     *
     * <p>기간은 확인된 발행 시각으로 재되 없으면 수집 시각으로 대신한다. 시각을 확인하지 못한 기사도 내보낼 수 있어야 하고, 그 사실은 응답의 {@code
     * publicationTimeStatus}가 말한다.
     *
     * <p>{@code after}가 null이면 첫 페이지다. 허가 필터는 여기서 걸지 않는다 — 저장소는 있는 것을 돌려주고, 무엇을 내보낼지는 회신이 정한다.
     *
     * @param limit 읽을 최대 행 수. 다음 페이지가 있는지 알려면 호출부가 하나 더 요청한다.
     */
    List<NewsArticle> findForExport(
            Instant from,
            Instant to,
            String sectorId,
            com.ssafy.thorp.news.application.command.NewsArticleExportQuery.Cursor after,
            int limit);

    /** 사업 키가 다른데 외부 식별자가 겹쳤다. 기존 기록을 덮어쓰지 않는다(spec FR-025). */
    class NewsArticleIdCollisionException extends RuntimeException {

        public NewsArticleIdCollisionException(NewsArticleId articleId) {
            super("ID_COLLISION: " + articleId.value());
        }
    }
}
