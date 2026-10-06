package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;

/**
 * 글 초안 작업본 ↔ {@link LearningArticleJpaEntity}·{@link LearningTermJpaEntity}. 단계는 {@link
 * StageLookup}으로 옮긴다.
 */
final class LearningArticleMapper {

    private LearningArticleMapper() {}

    static LearningArticle toDomain(
            LearningArticleJpaEntity entity,
            List<LearningTermJpaEntity> terms,
            StageLookup stages) {
        return new LearningArticle(
                entity.getId(),
                new ArticleId(entity.getArticleKey()),
                entity.getTitle(),
                entity.getQuestion(),
                entity.getReadingOrder(),
                entity.getFormat(),
                new Placement(stages.keyOf(entity.getStageId()), entity.getStagePosition()),
                entity.getPublishedStageId() == null
                        ? null
                        : new Placement(
                                stages.keyOf(entity.getPublishedStageId()),
                                LearningContentJson.required(
                                        entity.getPublishedStagePosition(),
                                        "published_stage_position")),
                LearningContentJson.blocksFromJson(entity.getBodyBlocks(), entity.getFormat()),
                LearningContentJson.exampleFromJson(entity.getExample()),
                LearningContentJson.sourcesFromJson(entity.getSources()),
                entity.getStatus(),
                entity.getPublishedRevisionId(),
                entity.getDraftVersion(),
                terms.stream().map(LearningArticleMapper::termToDomain).toList(),
                LearningContentJson.draftQuizFromJson(entity.getQuiz()),
                entity.getQuizSeq());
    }

    /** 새 행이면 엔티티를 만든다. {@code createdAt}을 덮지 않도록 있는 행에는 merge 대신 {@link #apply}를 쓴다. */
    static LearningArticleJpaEntity toEntity(LearningArticle article, StageLookup stages) {
        LearningArticleJpaEntity entity = new LearningArticleJpaEntity(article.articleId().value());
        apply(entity, article, stages);
        return entity;
    }

    static void apply(
            LearningArticleJpaEntity entity, LearningArticle article, StageLookup stages) {
        entity.apply(
                article.title(),
                article.question(),
                article.readingOrder(),
                LearningContentJson.blocksToJson(article.blocks(), article.format()),
                LearningContentJson.exampleToJson(article.example()),
                LearningContentJson.sourcesToJson(article.sources()),
                article.status(),
                article.publishedRevisionId(),
                article.draftVersion(),
                article.format(),
                stages.idOf(article.placement().stageKey()),
                article.placement().position(),
                article.publishedPlacement() == null
                        ? null
                        : stages.idOf(article.publishedPlacement().stageKey()),
                article.publishedPlacement() == null
                        ? null
                        : article.publishedPlacement().position(),
                LearningContentJson.draftQuizToJson(article.quiz()),
                article.quizSeq());
    }

    static LearningTermJpaEntity termToEntity(LearningTerm term, Long articleInternalId) {
        return new LearningTermJpaEntity(
                term.termId().value(),
                articleInternalId,
                term.label(),
                term.sentence(),
                term.aliases(),
                term.meaning(),
                term.reading(),
                term.caveat());
    }

    private static LearningTerm termToDomain(LearningTermJpaEntity entity) {
        return new LearningTerm(
                new TermId(entity.getTermKey()),
                entity.getLabel(),
                entity.getSentence(),
                LearningContentJson.required(entity.getAliases(), "learning_term.aliases"),
                entity.getMeaning(),
                entity.getReading(),
                entity.getCaveat());
    }
}
