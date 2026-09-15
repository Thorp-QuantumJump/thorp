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

    /**
     * 현재 뉴스 후보.
     *
     * <p><b>발행 시각을 확인하지 못한 묶음도 같은 창으로 자른다.</b> 예전에는 {@code latestPublishedAt IS NULL}이면 무조건 통과시켰는데,
     * 그러면 몇 달 전에 들어온 미확인 묶음이 영영 '현재 뉴스' 후보로 남는다. 정렬이 뒤로 밀어 주지만 볼 것이 적은 날에는 그대로 화면에 올라온다.
     *
     * <p>확인된 발행 시각이 없으면 대표 기사의 수집 시각을 대신 쓴다. 주요도 정렬이 미확인 묶음끼리 비교할 때 쓰는 값과 같아서, 창을 자르는 기준과 줄 세우는 기준이
     * 어긋나지 않는다(spec FR-004).
     */
    @Query(
            "SELECT d FROM NewsDigestJpaEntity d"
                    + " JOIN NewsArticleJpaEntity r ON r.id = d.representativeArticleId"
                    + " WHERE COALESCE(d.latestPublishedAt, r.collectedAt) >= :since"
                    + " ORDER BY d.latestPublishedAt DESC NULLS LAST, d.digestId ASC")
    List<NewsDigestJpaEntity> findRecent(@Param("since") Instant since);

    /** 그 섹터에 연결된 기사를 가진 묶음. 섹터 식별자는 spec 001이 소유하는 외부 값이다. 창을 자르는 기준은 {@link #findRecent}와 같다. */
    @Query(
            "SELECT DISTINCT d FROM NewsDigestJpaEntity d"
                    + " JOIN NewsArticleJpaEntity r ON r.id = d.representativeArticleId"
                    + " JOIN NewsDigestArticleJpaEntity m ON m.key.digestId = d.id"
                    + " JOIN NewsArticleSectorJpaEntity s ON s.key.articleId = m.key.articleId"
                    + " WHERE s.key.sectorId = :sectorId"
                    + " AND COALESCE(d.latestPublishedAt, r.collectedAt) >= :since"
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

    /**
     * 과거 구간 조회에 섹터까지 걸어 한 번에 읽는다(spec FR-005·FR-006).
     *
     * <p>구간 전체를 읽은 뒤 섹터 목록과 메모리에서 교차하면 결과는 같지만 읽는 양이 섹터와 무관하게 구간 전체다. 기사가 쌓일수록 섹터 조회가 전체 조회만큼 비싸진다.
     */
    @Query(
            "SELECT DISTINCT d FROM NewsDigestJpaEntity d"
                    + " JOIN NewsDigestArticleJpaEntity m ON m.key.digestId = d.id"
                    + " JOIN NewsArticleJpaEntity a ON a.id = m.key.articleId"
                    + " JOIN NewsArticleSectorJpaEntity s ON s.key.articleId = a.id"
                    + " WHERE s.key.sectorId = :sectorId"
                    + " AND a.publishedAtStatus ="
                    + " com.ssafy.thorp.news.domain.vo.PublicationTimeStatus.VERIFIED"
                    + " AND a.publishedAt BETWEEN :from AND :to"
                    + " ORDER BY d.latestPublishedAt DESC NULLS LAST, d.digestId ASC")
    List<NewsDigestJpaEntity> findVerifiedBetweenAndSector(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("sectorId") String sectorId);

    /** 그 회차가 건드린 기사가 속한 묶음. 재생성 범위를 그 회차로 한정한다(spec FR-022). */
    @Query(
            "SELECT DISTINCT d FROM NewsDigestJpaEntity d"
                    + " JOIN NewsDigestArticleJpaEntity m ON m.key.digestId = d.id"
                    + " WHERE m.key.articleId IN :articleIds")
    List<NewsDigestJpaEntity> findByArticleIds(@Param("articleIds") Collection<Long> articleIds);
}
