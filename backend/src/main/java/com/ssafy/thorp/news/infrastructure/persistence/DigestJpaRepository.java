package com.ssafy.thorp.news.infrastructure.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 묶음. 저장된 값은 전체 멤버 기준이며 공개 투영은 조회 서비스가 계산한다(spec FR-016). */
public interface DigestJpaRepository extends JpaRepository<NewsDigestJpaEntity, Long> {

    Optional<NewsDigestJpaEntity> findByDigestId(String digestId);

    @Query(
            "SELECT d FROM NewsDigestJpaEntity d"
                    + " WHERE d.latestPublishedAt >= :since OR d.latestPublishedAt IS NULL"
                    + " ORDER BY d.latestPublishedAt DESC NULLS LAST, d.digestId ASC")
    List<NewsDigestJpaEntity> findRecent(@Param("since") Instant since);

    /** 그 섹터에 연결된 기사를 가진 묶음. 섹터 식별자는 spec 001이 소유하는 외부 값이다. */
    @Query(
            "SELECT DISTINCT d FROM NewsDigestJpaEntity d"
                    + " JOIN NewsDigestArticleJpaEntity m ON m.key.digestId = d.id"
                    + " JOIN NewsArticleSectorJpaEntity s ON s.key.articleId = m.key.articleId"
                    + " WHERE s.key.sectorId = :sectorId"
                    + " AND (d.latestPublishedAt >= :since OR d.latestPublishedAt IS NULL)"
                    + " ORDER BY d.latestPublishedAt DESC NULLS LAST, d.digestId ASC")
    List<NewsDigestJpaEntity> findRecentBySector(
            @Param("since") Instant since, @Param("sectorId") String sectorId);

    /** 검증된 발행 시각이 구간 안인 기사를 가진 묶음(spec FR-006). */
    @Query(
            "SELECT DISTINCT d FROM NewsDigestJpaEntity d"
                    + " JOIN NewsDigestArticleJpaEntity m ON m.key.digestId = d.id"
                    + " JOIN NewsArticleJpaEntity a ON a.id = m.key.articleId"
                    + " WHERE a.publishedAtStatus = com.ssafy.thorp.news.domain.vo.PublicationTimeStatus.VERIFIED"
                    + " AND a.publishedAt BETWEEN :from AND :to"
                    + " ORDER BY d.latestPublishedAt DESC NULLS LAST, d.digestId ASC")
    List<NewsDigestJpaEntity> findVerifiedBetween(
            @Param("from") Instant from, @Param("to") Instant to);

    /** 그 회차가 건드린 기사가 속한 묶음. 재생성 범위를 그 회차로 한정한다(spec FR-022). */
    @Query(
            "SELECT DISTINCT d FROM NewsDigestJpaEntity d"
                    + " JOIN NewsDigestArticleJpaEntity m ON m.key.digestId = d.id"
                    + " WHERE m.key.articleId IN :articleIds")
    List<NewsDigestJpaEntity> findByArticleIds(@Param("articleIds") Collection<Long> articleIds);
}
