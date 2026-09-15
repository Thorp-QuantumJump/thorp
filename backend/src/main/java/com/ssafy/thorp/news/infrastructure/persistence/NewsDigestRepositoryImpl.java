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
                            titleSimilarity(digest, member),
                            sharedTopicKey(digest, member)));
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
        return loadAll(
                sectorId == null
                        ? digestRepository.findVerifiedBetween(from, to)
                        : digestRepository.findVerifiedBetweenAndSector(from, to, sectorId));
    }

    @Override
    public List<NewsDigest> findByArticleIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return List.of();
        }
        return loadAll(digestRepository.findByArticleIds(articleIds));
    }

    @Override
    @Transactional
    public void deleteByArticleIds(List<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return;
        }
        for (NewsDigestJpaEntity digest : digestRepository.findByArticleIds(articleIds)) {
            membershipRepository.deleteByKeyDigestId(digest.getId());
            digestRepository.delete(digest);
        }
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

    /**
     * 대표 기사와의 제목 유사도. 왜 이 기사가 이 묶음에 들어왔는지 남긴다(원칙 VIII).
     *
     * <p>대표 기사 자신은 비교 대상이 아니므로 값을 두지 않는다.
     */
    private Double titleSimilarity(NewsDigest digest, NewsArticle member) {
        NewsArticle representative = digest.getRepresentative();
        if (representative.getCanonicalUrl().equals(member.getCanonicalUrl())) {
            return null;
        }
        return com.ssafy.thorp.news.application.service.NewsGroupingPolicy.titleSimilarity(
                representative.getTitle(), member.getTitle());
    }

    /**
     * 묶음 판정에 쓴 공통 주제.
     *
     * <p>묶음을 만든 쪽이 실어 준 값을 그대로 쓴다. 주제를 여기서 다시 조회하면 판정에 쓴 값과 달라질 수 있어 근거로서 값어치가 없다. 근거를 싣지 않고 저장한
     * 묶음은 비워 둔다.
     */
    private String sharedTopicKey(NewsDigest digest, NewsArticle member) {
        return digest.sharedTopicKeyWith(member);
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
