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
                                        // 제목만 판 JSON에서 온다. 나머지는 NOT NULL 컬럼이다.
                                        // 키가 없으면 null 제목으로 200을 내보내지 않는다(T079와 같은 규칙).
                                        LearningContentJson.required(
                                                row.getArticleTitle(), "title")));
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
