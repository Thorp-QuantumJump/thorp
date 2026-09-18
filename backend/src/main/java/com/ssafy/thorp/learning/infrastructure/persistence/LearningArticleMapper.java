package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;

/** 글 초안 작업본 ↔ {@link LearningArticleJpaEntity}·{@link LearningTermJpaEntity}. */
final class LearningArticleMapper {

    private LearningArticleMapper() {}

    static LearningArticle toDomain(
            LearningArticleJpaEntity entity, List<LearningTermJpaEntity> terms) {
        return new LearningArticle(
                entity.getId(),
                new ArticleId(entity.getArticleKey()),
                entity.getTitle(),
                entity.getQuestion(),
                entity.getReadingOrder(),
                LearningContentJson.blocksFromJson(entity.getBodyBlocks()),
                LearningContentJson.exampleFromJson(entity.getExample()),
                LearningContentJson.sourcesFromJson(entity.getSources()),
                entity.getStatus(),
                entity.getPublishedRevisionId(),
                entity.getDraftVersion(),
                terms.stream().map(LearningArticleMapper::termToDomain).toList());
    }

    /** 새 행이면 엔티티를 만들고, 있으면 관리 중인 엔티티에 글 컬럼을 옮긴다. {@code createdAt}을 덮지 않도록 merge를 쓰지 않는다. */
    static LearningArticleJpaEntity toEntity(LearningArticle article) {
        LearningArticleJpaEntity entity = new LearningArticleJpaEntity(article.articleId().value());
        apply(entity, article);
        return entity;
    }

    static void apply(LearningArticleJpaEntity entity, LearningArticle article) {
        entity.apply(
                article.title(),
                article.question(),
                article.readingOrder(),
                LearningContentJson.blocksToJson(article.blocks()),
                LearningContentJson.exampleToJson(article.example()),
                LearningContentJson.sourcesToJson(article.sources()),
                article.status(),
                article.publishedRevisionId(),
                article.draftVersion());
    }

    static LearningTermJpaEntity termToEntity(LearningTerm term, Long articleInternalId) {
        return new LearningTermJpaEntity(
                term.termId().value(), articleInternalId, term.label(), term.sentence());
    }

    private static LearningTerm termToDomain(LearningTermJpaEntity entity) {
        return new LearningTerm(
                new TermId(entity.getTermKey()), entity.getLabel(), entity.getSentence());
    }
}
