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

    static ArticleRevision toDomain(LearningArticleRevisionJpaEntity entity) {
        return new ArticleRevision(
                entity.getId(),
                entity.getRevisionNo(),
                entity.getEdition(),
                LearningContentJson.snapshotFromJson(entity.getSnapshot()),
                entity.getCheckVersion(),
                entity.getSectorMapVersion(),
                entity.getPublishedByUserId(),
                entity.getPublishedAt());
    }
}
