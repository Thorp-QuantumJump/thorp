package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.ArticleRevision;

/** 불변 게시 판 ↔ {@link LearningArticleRevisionJpaEntity}. */
final class ArticleRevisionMapper {

    private ArticleRevisionMapper() {}

    static LearningArticleRevisionJpaEntity toEntity(
            ArticleRevision revision, Long articleInternalId) {
        return new LearningArticleRevisionJpaEntity(
                articleInternalId,
                revision.revisionNo(),
                LearningContentJson.snapshotToJson(revision.snapshot()),
                revision.checkVersion(),
                revision.sectorMapVersion(),
                revision.publishedByUserId(),
                revision.publishedAt(),
                revision.edition());
    }

    /** 저장된 판이 도메인 규칙을 어기면 판 대리키를 담은 {@link IllegalStateException}으로 실패한다(400으로 내보내지 않는다). */
    static ArticleRevision toDomain(LearningArticleRevisionJpaEntity entity) {
        return LearningContentJson.storedValue(
                "learning_article_revision(" + entity.getId() + ")",
                () ->
                        new ArticleRevision(
                                entity.getId(),
                                entity.getRevisionNo(),
                                entity.getEdition(),
                                LearningContentJson.snapshotFromJson(entity.getSnapshot()),
                                entity.getCheckVersion(),
                                entity.getSectorMapVersion(),
                                entity.getPublishedByUserId(),
                                entity.getPublishedAt()));
    }
}
