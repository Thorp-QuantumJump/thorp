package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 내보내기 첫 페이지(spec FR-017).
     *
     * <p><b>기간은 확인된 발행 시각으로 재되, 없으면 수집 시각으로 대신한다.</b> 발행 시각만 쓰면 시각을 확인하지 못한 기사가 통째로 빠지는데, 계약은 그 기사도
     * {@code publicationTimeStatus=UNKNOWN}으로 담을 수 있게 열어 두었다. 대신 시각을 지어내지는 않는다 — 응답의 {@code
     * publishedAt}은 확인된 경우에만 값이 있고, 미확인 기사는 "이 기간에 우리가 받은 기사"라는 뜻이다.
     *
     * <p>정렬은 그 기준 시각과 기사 식별자 둘로 한다. 시각만으로는 같은 시각의 기사들 사이 자리가 정해지지 않아, 페이지를 넘길 때 어떤 기사는 두 번 나오고 어떤
     * 기사는 빠진다.
     */
    @Query(
            "SELECT a FROM NewsArticleJpaEntity a"
                    + " WHERE COALESCE(a.publishedAt, a.collectedAt) BETWEEN :from AND :to"
                    + " AND (:sectorId IS NULL OR EXISTS ("
                    + "   SELECT 1 FROM NewsArticleSectorJpaEntity s"
                    + "   WHERE s.key.articleId = a.id AND s.key.sectorId = :sectorId))"
                    + " ORDER BY COALESCE(a.publishedAt, a.collectedAt) ASC, a.articleId ASC")
    List<NewsArticleJpaEntity> findForExportFirstPage(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("sectorId") String sectorId,
            org.springframework.data.domain.Pageable page);

    /**
     * cursor 뒤부터 이어 읽는 내보내기 페이지(spec FR-017).
     *
     * <p><b>첫 페이지와 질의를 나눈 것은 취향이 아니라 필요다.</b> 하나로 두면 {@code :afterInstant IS NULL}로 분기해야 하는데,
     * PostgreSQL은 비교 상대가 없는 자리의 파라미터 타입을 정하지 못해 그 질의 자체를 거부한다({@code could not determine data type
     * of parameter}). 대역 저장소를 쓰는 시험은 이것을 잡지 못한다 — 질의를 돌리는 것이 DB이기 때문이다.
     *
     * <p>행 값 비교를 JPQL이 받지 않아 두 조건으로 편다.
     */
    @Query(
            "SELECT a FROM NewsArticleJpaEntity a"
                    + " WHERE COALESCE(a.publishedAt, a.collectedAt) BETWEEN :from AND :to"
                    + " AND (:sectorId IS NULL OR EXISTS ("
                    + "   SELECT 1 FROM NewsArticleSectorJpaEntity s"
                    + "   WHERE s.key.articleId = a.id AND s.key.sectorId = :sectorId))"
                    + " AND (COALESCE(a.publishedAt, a.collectedAt) > :afterInstant"
                    + "   OR (COALESCE(a.publishedAt, a.collectedAt) = :afterInstant"
                    + "       AND a.articleId > :afterArticleId))"
                    + " ORDER BY COALESCE(a.publishedAt, a.collectedAt) ASC, a.articleId ASC")
    List<NewsArticleJpaEntity> findForExportAfter(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("sectorId") String sectorId,
            @Param("afterInstant") Instant afterInstant,
            @Param("afterArticleId") String afterArticleId,
            org.springframework.data.domain.Pageable page);
}
