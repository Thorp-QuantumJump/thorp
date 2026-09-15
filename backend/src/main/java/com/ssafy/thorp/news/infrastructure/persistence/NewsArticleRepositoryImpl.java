package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsArticleRepository;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.vo.CanonicalNewsUrl;
import com.ssafy.thorp.news.domain.vo.NewsArticleId;
import com.ssafy.thorp.news.domain.vo.PublicationTimeStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NewsArticleRepository} 구현.
 *
 * <p>canonical URL이 이미 있으면 최초 수집 시각과 외부 식별자를 유지한 채 메타데이터만 갱신한다. 그 값들이 바뀌면 재현 기록과 외부 참조가 깨진다(spec
 * FR-011·FR-025).
 */
@Repository
public class NewsArticleRepositoryImpl implements NewsArticleRepository {

    private final ArticleJpaRepository jpaRepository;

    public NewsArticleRepositoryImpl(ArticleJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public NewsArticle upsertByCanonicalUrl(NewsArticle article) {
        String canonicalUrl = article.getCanonicalUrl().value();
        Optional<NewsArticleJpaEntity> existing = jpaRepository.findByCanonicalUrl(canonicalUrl);

        if (existing.isPresent()) {
            NewsArticleJpaEntity entity = existing.get();
            entity.refreshMetadata(
                    article.getTitle(),
                    article.getPublishedAt(),
                    article.getPublishedAtStatus(),
                    article.getGdeltRecordedAt(),
                    article.getSourcePayloadHash(),
                    article.getIngestRunId(),
                    article.isDisplayEligible());
            return NewsPersistenceMapper.toDomain(jpaRepository.save(entity));
        }

        // 사업 키(canonical URL)는 다른데 외부 식별자가 겹치는 충돌. 기존 기록을 덮어쓰지 않고 이 항목만 버린다.
        Optional<NewsArticleJpaEntity> collision =
                jpaRepository.findByArticleId(article.getArticleId().value());
        if (collision.isPresent()) {
            throw new NewsArticleIdCollisionException(article.getArticleId());
        }

        return NewsPersistenceMapper.toDomain(
                jpaRepository.save(NewsPersistenceMapper.toEntity(article)));
    }

    @Override
    public Optional<NewsArticle> findByCanonicalUrl(CanonicalNewsUrl canonicalUrl) {
        return jpaRepository
                .findByCanonicalUrl(canonicalUrl.value())
                .map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public Optional<NewsArticle> findByArticleId(NewsArticleId articleId) {
        return jpaRepository
                .findByArticleId(articleId.value())
                .map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public Optional<NewsArticle> findByPublisherAndSourceItemId(
            Long publisherId, String sourceItemId) {
        if (sourceItemId == null) {
            return Optional.empty();
        }
        return jpaRepository
                .findByPublisherIdAndSourceItemId(publisherId, sourceItemId)
                .map(NewsPersistenceMapper::toDomain);
    }

    @Override
    public List<NewsArticle> findVerifiedBetween(Instant from, Instant to) {
        return jpaRepository
                .findByPublishedAtStatusAndPublishedAtBetweenOrderByPublishedAtAscIdAsc(
                        PublicationTimeStatus.VERIFIED, from, to)
                .stream()
                .map(NewsPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<NewsArticle> findDisplayEligibleSince(Instant since) {
        return jpaRepository.findByDisplayEligibleTrueAndCollectedAtGreaterThanEqual(since).stream()
                .map(NewsPersistenceMapper::toDomain)
                .toList();
    }

    @Override
    public List<NewsArticle> findForExport(
            Instant from,
            Instant to,
            String sectorId,
            com.ssafy.thorp.news.application.command.NewsArticleExportQuery.Cursor after,
            int limit) {
        return jpaRepository
                .findForExport(
                        from,
                        to,
                        sectorId == null || sectorId.isBlank() ? null : sectorId,
                        after == null ? null : after.orderingInstant(),
                        after == null ? null : after.articleId(),
                        org.springframework.data.domain.PageRequest.of(0, limit))
                .stream()
                .map(NewsPersistenceMapper::toDomain)
                .toList();
    }
}
