package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.List;
import java.util.function.Function;

/**
 * 글 초안 작업본 ↔ {@link LearningArticleJpaEntity}·{@link LearningTermJpaEntity}. 단계는 {@link
 * StageLookup}으로 옮긴다.
 */
final class LearningArticleMapper {

    private LearningArticleMapper() {}

    /**
     * 저장된 행이 도메인 규칙을 어기면(예: 형식에 맞지 않는 용어) 글 키를 담은 {@link IllegalStateException}으로 실패한다. 요청 입력이 아니라
     * 저장 데이터의 문제이므로 400으로 내보내지 않는다({@link LearningContentJson#storedValue}).
     *
     * @param articleKeys 글 대리키 → {@code articleId}. 넘겨받는 중인 용어의 지금 소유 글을 옮긴다
     */
    static LearningArticle toDomain(
            LearningArticleJpaEntity entity,
            List<LearningTermJpaEntity> terms,
            StageLookup stages,
            Function<Long, ArticleId> articleKeys) {
        return LearningContentJson.storedValue(
                "learning_article(" + entity.getArticleKey() + ")",
                () -> build(entity, terms, stages, articleKeys));
    }

    private static LearningArticle build(
            LearningArticleJpaEntity entity,
            List<LearningTermJpaEntity> terms,
            StageLookup stages,
            Function<Long, ArticleId> articleKeys) {
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
                terms.stream().map(term -> termToDomain(term, articleKeys)).toList(),
                LearningContentJson.draftQuizFromJson(entity.getQuiz()),
                entity.getQuizSeq(),
                entity.getFirstReleasedAt());
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
                article.quizSeq(),
                article.firstReleasedAt());
    }

    /**
     * @param transferFromInternalId 넘겨받는 중이면 지금 소유 글의 대리키. 아니면 null
     */
    static LearningTermJpaEntity termToEntity(
            LearningTerm term, Long articleInternalId, Long transferFromInternalId) {
        return new LearningTermJpaEntity(
                term.termId().value(),
                articleInternalId,
                term.label(),
                term.sentence(),
                term.aliases(),
                term.meaning(),
                term.reading(),
                term.caveat(),
                transferFromInternalId);
    }

    private static LearningTerm termToDomain(
            LearningTermJpaEntity entity, Function<Long, ArticleId> articleKeys) {
        return new LearningTerm(
                new TermId(entity.getTermKey()),
                entity.getLabel(),
                entity.getSentence(),
                LearningContentJson.required(entity.getAliases(), "learning_term.aliases"),
                entity.getMeaning(),
                entity.getReading(),
                entity.getCaveat(),
                entity.getTransferFromArticleId() == null
                        ? null
                        : articleKeys.apply(entity.getTransferFromArticleId()));
    }
}
