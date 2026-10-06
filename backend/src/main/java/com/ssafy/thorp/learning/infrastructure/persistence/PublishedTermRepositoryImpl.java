package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Repository;

@Repository
public class PublishedTermRepositoryImpl implements PublishedTermRepository {

    private final LearningPublishedTermJpaRepository publishedTermJpaRepository;
    private final LearningArticleJpaRepository articleJpaRepository;

    public PublishedTermRepositoryImpl(
            LearningPublishedTermJpaRepository publishedTermJpaRepository,
            LearningArticleJpaRepository articleJpaRepository) {
        this.publishedTermJpaRepository = publishedTermJpaRepository;
        this.articleJpaRepository = articleJpaRepository;
    }

    @Override
    public Optional<LearningTermResult> findPublished(TermId termId) {
        return publishedTermJpaRepository
                .findPublished(termId.value())
                .map(
                        row ->
                                new LearningTermResult(
                                        row.getTermId(),
                                        row.getLabel(),
                                        row.getSentence(),
                                        row.getArticleId(),
                                        // 제목만 판 JSON에서 온다. 나머지는 NOT NULL 컬럼이다.
                                        // 키가 없으면 null 제목으로 200을 내보내지 않는다(T079와 같은 규칙).
                                        LearningContentJson.required(
                                                row.getArticleTitle(), "title")));
    }

    /** 공개 판 포인터와 색인 행의 판이 같은 행만 읽는다. {@link #findPublished}와 같은 규칙이다. */
    @Override
    public Map<TermId, List<String>> findPublishedAliases(Collection<TermId> termIds) {
        Map<TermId, List<String>> aliases = new LinkedHashMap<>();
        if (termIds.isEmpty()) {
            return aliases;
        }
        for (LearningPublishedTermJpaEntity row :
                publishedTermJpaRepository.findPublishedIn(
                        termIds.stream().map(TermId::value).distinct().toList())) {
            aliases.put(
                    new TermId(row.getId()),
                    List.copyOf(
                            LearningContentJson.required(
                                    row.getAliases(), "learning_published_term.aliases")));
        }
        return aliases;
    }

    @Override
    public Map<TermId, ArticleId> findOwnerArticleKeys(Collection<TermId> termIds) {
        Map<TermId, ArticleId> owners = new HashMap<>();
        if (termIds.isEmpty()) {
            return owners;
        }
        publishedTermJpaRepository
                .findOwners(termIds.stream().map(TermId::value).distinct().toList())
                .forEach(
                        row ->
                                owners.put(
                                        new TermId(row.getTermKey()),
                                        new ArticleId(row.getArticleKey())));
        return owners;
    }

    @Override
    public Set<TermId> findAllTermIds() {
        Set<TermId> termIds = new LinkedHashSet<>();
        publishedTermJpaRepository.findAllTermKeys().forEach(key -> termIds.add(new TermId(key)));
        return termIds;
    }

    @Override
    public void replaceForArticle(
            ArticleId articleId, long revisionId, List<LearningTerm> terms, ArticleFormat format) {
        Long articleInternalId =
                PersistenceLookups.requireArticleId(articleJpaRepository, articleId);
        publishedTermJpaRepository.deleteByArticleIdInBulk(articleInternalId);
        publishedTermJpaRepository.saveAll(
                terms.stream()
                        .map(
                                term ->
                                        new LearningPublishedTermJpaEntity(
                                                term.termId().value(),
                                                articleInternalId,
                                                revisionId,
                                                term.label(),
                                                term.helpSentence(format),
                                                term.aliases()))
                        .toList());
    }

    @Override
    public void deleteForArticle(ArticleId articleId) {
        publishedTermJpaRepository.deleteByArticleIdInBulk(
                PersistenceLookups.requireArticleId(articleJpaRepository, articleId));
    }

    @Override
    public void deleteTerm(ArticleId articleId, TermId termId) {
        publishedTermJpaRepository.deleteTermInBulk(
                PersistenceLookups.requireArticleId(articleJpaRepository, articleId),
                termId.value());
    }

    @Override
    public Map<ArticleId, Integer> countPublishedByArticle() {
        Map<ArticleId, Integer> counts = new HashMap<>();
        for (LearningPublishedTermJpaRepository.TermCountRow row :
                publishedTermJpaRepository.countPublishedByArticle()) {
            counts.put(new ArticleId(row.getArticleId()), Math.toIntExact(row.getTermCount()));
        }
        return counts;
    }
}
