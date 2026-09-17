package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.application.repository.PublishedTermRepository;
import com.ssafy.thorp.learning.application.result.LearningTermResult;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.Optional;
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
                                        row.getArticleTitle()));
    }

    @Override
    public void replaceForArticle(ArticleId articleId, long revisionId, List<LearningTerm> terms) {
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
                                                term.sentence()))
                        .toList());
    }

    @Override
    public void deleteForArticle(ArticleId articleId) {
        publishedTermJpaRepository.deleteByArticleIdInBulk(
                PersistenceLookups.requireArticleId(articleJpaRepository, articleId));
    }
}
