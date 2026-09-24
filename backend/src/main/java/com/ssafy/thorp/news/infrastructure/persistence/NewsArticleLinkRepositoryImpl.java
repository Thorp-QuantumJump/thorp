package com.ssafy.thorp.news.infrastructure.persistence;

import com.ssafy.thorp.news.application.repository.NewsArticleLinkRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** {@link NewsArticleLinkRepository} 구현. */
@Repository
public class NewsArticleLinkRepositoryImpl implements NewsArticleLinkRepository {

    private final NewsTopicJpaRepository topicRepository;
    private final ArticleTopicJpaRepository articleTopicRepository;
    private final ArticleSectorJpaRepository articleSectorRepository;

    public NewsArticleLinkRepositoryImpl(
            NewsTopicJpaRepository topicRepository,
            ArticleTopicJpaRepository articleTopicRepository,
            ArticleSectorJpaRepository articleSectorRepository) {
        this.topicRepository = topicRepository;
        this.articleTopicRepository = articleTopicRepository;
        this.articleSectorRepository = articleSectorRepository;
    }

    @Override
    @Transactional
    public Long ensureTopic(
            String topicKey, String displayName, boolean domesticLink, String ruleVersion) {
        return topicRepository
                .findByTopicKey(topicKey)
                .map(NewsTopicJpaEntity::getId)
                .orElseGet(
                        () ->
                                topicRepository
                                        .save(
                                                new NewsTopicJpaEntity(
                                                        null,
                                                        topicKey,
                                                        displayName,
                                                        domesticLink,
                                                        ruleVersion))
                                        .getId());
    }

    @Override
    @Transactional
    public void replaceLinks(
            Long articleId,
            Set<String> sectorIds,
            java.util.Map<Long, String> topicEvidenceByTopicId,
            String ruleVersion) {
        // 같은 규칙 버전의 연결만 지운다. 다른 버전으로 만든 연결은 과거 결과의 근거라 남긴다.
        articleTopicRepository.deleteByKeyArticleIdAndKeyRuleVersion(articleId, ruleVersion);
        articleSectorRepository.deleteByKeyArticleIdAndKeyRuleVersion(articleId, ruleVersion);

        List<NewsArticleTopicJpaEntity> topicLinks = new ArrayList<>();
        for (java.util.Map.Entry<Long, String> entry : topicEvidenceByTopicId.entrySet()) {
            topicLinks.add(
                    new NewsArticleTopicJpaEntity(
                            new NewsArticleTopicJpaEntity.Key(
                                    articleId, entry.getKey(), ruleVersion),
                            entry.getValue()));
        }
        articleTopicRepository.saveAll(topicLinks);

        List<NewsArticleSectorJpaEntity> sectorLinks = new ArrayList<>();
        for (String sectorId : sectorIds) {
            sectorLinks.add(
                    new NewsArticleSectorJpaEntity(
                            new NewsArticleSectorJpaEntity.Key(articleId, sectorId, ruleVersion)));
        }
        articleSectorRepository.saveAll(sectorLinks);
    }

    @Override
    @Transactional
    public void addLinks(
            Long articleId,
            Set<String> sectorIds,
            java.util.Map<Long, String> topicEvidenceByTopicId,
            String ruleVersion) {
        for (java.util.Map.Entry<Long, String> entry : topicEvidenceByTopicId.entrySet()) {
            NewsArticleTopicJpaEntity.Key key =
                    new NewsArticleTopicJpaEntity.Key(articleId, entry.getKey(), ruleVersion);
            if (!articleTopicRepository.existsById(key)) {
                articleTopicRepository.save(new NewsArticleTopicJpaEntity(key, entry.getValue()));
            }
        }
        for (String sectorId : sectorIds) {
            NewsArticleSectorJpaEntity.Key key =
                    new NewsArticleSectorJpaEntity.Key(articleId, sectorId, ruleVersion);
            if (!articleSectorRepository.existsById(key)) {
                articleSectorRepository.save(new NewsArticleSectorJpaEntity(key));
            }
        }
    }

    @Override
    public java.util.Map<Long, java.util.Set<String>> findSectorIdsByArticleIds(
            java.util.Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, java.util.Set<String>> result = new java.util.HashMap<>();
        for (NewsArticleSectorJpaEntity link :
                articleSectorRepository.findByKeyArticleIdIn(articleIds)) {
            result.computeIfAbsent(link.getKey().getArticleId(), k -> new java.util.HashSet<>())
                    .add(link.getKey().getSectorId());
        }
        return result;
    }

    @Override
    public java.util.Map<Long, java.util.Set<String>> findTopicKeysByArticleIds(
            java.util.Collection<Long> articleIds) {
        if (articleIds == null || articleIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, String> topicKeyById = new java.util.HashMap<>();
        topicRepository.findAll().forEach(t -> topicKeyById.put(t.getId(), t.getTopicKey()));

        java.util.Map<Long, java.util.Set<String>> result = new java.util.HashMap<>();
        for (NewsArticleTopicJpaEntity link :
                articleTopicRepository.findByKeyArticleIdIn(articleIds)) {
            String topicKey = topicKeyById.get(link.getKey().getTopicId());
            if (topicKey != null) {
                result.computeIfAbsent(link.getKey().getArticleId(), k -> new java.util.HashSet<>())
                        .add(topicKey);
            }
        }
        return result;
    }
}
