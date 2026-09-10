package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsDigestRepository;
import com.ssafy.thorp.news.domain.model.NewsArticle;
import com.ssafy.thorp.news.domain.model.NewsDigest;
import com.ssafy.thorp.news.domain.vo.NewsDigestId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NewsDigestRepository} 구현.
 *
 * <p>읽어 오는 묶음은 <b>전체 멤버</b>를 담는다. 허가 필터를 저장소에서 적용하면 허가가 복원됐을 때 원래 구성을 되살릴 수 없다. 공개 응답에 쓸 대표·요약·매체
 * 수는 조회 서비스가 {@code NewsDigest#projectFor}로 계산한다(spec FR-016).
 */
@Repository
public class NewsDigestRepositoryImpl implements NewsDigestRepository {

    private final DigestJpaRepository digestRepository;
    private final DigestArticleJpaRepository membershipRepository;
    private final ArticleJpaRepository articleRepository;

    public NewsDigestRepositoryImpl(
            DigestJpaRepository digestRepository,
            DigestArticleJpaRepository membershipRepository,
            ArticleJpaRepository articleRepository) {
        this.digestRepository = digestRepository;
        this.membershipRepository = membershipRepository;
        this.articleRepository = articleRepository;
    }

    @Override
    @Transactional
    public NewsDigest save(NewsDigest digest) {
        NewsDigestJpaEntity entity =
                digestRepository
                        .findByDigestId(digest.getDigestId().value())
                        .orElseGet(
                                () ->
                                        new NewsDigestJpaEntity(
                                                null,
                                                digest.getDigestId().value(),
                                                digest.getRepresentative().getId(),
                                                digest.getRepresentativeTitle(),
                                                digest.getFactualSummary(),
                                                digest.firstPublishedAt(),
                                                digest.latestPublishedAt(),
                                                digest.distinctPublisherCount(),
                                                digest.getGroupingRuleVersion(),
                                                digest.getImportanceRuleVersion()));

        if (entity.getId() != null) {
            entity.refreshMembership(
                    digest.getRepresentative().getId(),
                    digest.getRepresentativeTitle(),
                    digest.getFactualSummary(),
                    digest.firstPublishedAt(),
                    digest.latestPublishedAt(),
                    digest.distinctPublisherCount());
        }
        NewsDigestJpaEntity saved = digestRepository.save(entity);

        // 멤버십은 통째로 다시 쓴다. 묶음 구성은 규칙이 결정론적으로 만들어 내므로 부분 갱신할 이유가 없다.
        membershipRepository.deleteByKeyDigestId(saved.getId());
        List<NewsDigestArticleJpaEntity> memberships = new ArrayList<>();
        for (NewsArticle member : digest.getArticles()) {
            memberships.add(
                    new NewsDigestArticleJpaEntity(
                            new NewsDigestArticleJpaEntity.Key(saved.getId(), member.getId()),
                            timeGapSeconds(digest, member),
                            null,
                            null));
        }
        membershipRepository.saveAll(memberships);

        return toDomain(saved, digest.getArticles());
    }

    @Override
    public Optional<NewsDigest> findByDigestId(NewsDigestId digestId) {
        return digestRepository.findByDigestId(digestId.value()).map(this::loadWithMembers);
    }

    @Override
    public List<NewsDigest> findRecent(Instant since) {
        return loadAll(digestRepository.findRecent(since));
    }

    @Override
    public List<NewsDigest> findRecentBySector(Instant since, String sectorId) {
        return loadAll(digestRepository.findRecentBySector(since, sectorId));
    }

    @Override
    public List<NewsDigest> findVerifiedBetween(Instant from, Instant to, String sectorId) {
        List<NewsDigestJpaEntity> found = digestRepository.findVerifiedBetween(from, to);
        if (sectorId == null) {
            return loadAll(found);
        }
        List<NewsDigestJpaEntity> bySector = digestRepository.findRecentBySector(from, sectorId);
        java.util.Set<Long> sectorIds =
                bySector.stream().map(NewsDigestJpaEntity::getId).collect(Collectors.toSet());
        return loadAll(found.stream().filter(d -> sectorIds.contains(d.getId())).toList());
    }

    @Override
    public List<NewsDigest> findByArticleIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        return loadAll(digestRepository.findByArticleIds(articleIds));
    }

    /** 여러 묶음의 멤버 기사를 한 번에 읽는다. 묶음마다 조회하면 N+1이 된다. */
    private List<NewsDigest> loadAll(List<NewsDigestJpaEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<Long> digestIds = entities.stream().map(NewsDigestJpaEntity::getId).toList();
        List<NewsDigestArticleJpaEntity> memberships =
                membershipRepository.findByKeyDigestIdIn(digestIds);

        List<Long> articleIds =
                memberships.stream().map(m -> m.getKey().getArticleId()).distinct().toList();
        Map<Long, NewsArticle> articles =
                articleRepository.findByIdIn(articleIds).stream()
                        .map(NewsPersistenceMapper::toDomain)
                        .collect(Collectors.toMap(NewsArticle::getId, Function.identity()));

        Map<Long, List<NewsArticle>> byDigest =
                memberships.stream()
                        .filter(m -> articles.containsKey(m.getKey().getArticleId()))
                        .collect(
                                Collectors.groupingBy(
                                        m -> m.getKey().getDigestId(),
                                        Collectors.mapping(
                                                m -> articles.get(m.getKey().getArticleId()),
                                                Collectors.toList())));

        List<NewsDigest> result = new ArrayList<>();
        for (NewsDigestJpaEntity entity : entities) {
            List<NewsArticle> members = byDigest.getOrDefault(entity.getId(), List.of());
            if (members.isEmpty()) {
                // 멤버가 사라진 묶음은 표시 후보가 아니다. 없는 결과를 만들어 내지 않는다(원칙 VI).
                continue;
            }
            result.add(toDomain(entity, members));
        }
        return result;
    }

    private NewsDigest loadWithMembers(NewsDigestJpaEntity entity) {
        return loadAll(List.of(entity)).stream().findFirst().orElse(null);
    }

    private NewsDigest toDomain(NewsDigestJpaEntity entity, Collection<NewsArticle> members) {
        List<NewsArticle> articles = List.copyOf(members);
        NewsArticle representative =
                articles.stream()
                        .filter(a -> a.getId().equals(entity.getRepresentativeArticleId()))
                        .findFirst()
                        .orElseGet(
                                () ->
                                        NewsDigest.selectRepresentative(articles)
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "대표 기사를 찾을 수 없다: "
                                                                                + entity
                                                                                        .getDigestId())));
        return NewsDigest.restore(
                entity.getId(),
                NewsDigestId.from(entity.getDigestId()),
                articles,
                representative,
                entity.getRepresentativeTitle(),
                entity.getFactualSummary(),
                entity.getGroupingRuleVersion(),
                entity.getImportanceRuleVersion());
    }

    /** 대표 기사와의 시간 차. 묶음 근거로 남긴다. 확인된 시각이 없으면 값을 만들지 않는다. */
    private Long timeGapSeconds(NewsDigest digest, NewsArticle member) {
        NewsArticle representative = digest.getRepresentative();
        if (!representative.getPublishedAtStatus().isUsableForHistory()
                || !member.getPublishedAtStatus().isUsableForHistory()) {
            return null;
        }
        return Math.abs(
                java.time.Duration.between(representative.getPublishedAt(), member.getPublishedAt())
                        .toSeconds());
    }
}
