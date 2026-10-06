package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;

/** 변경 기록 한 줄 ↔ {@link LearningReleaseLogJpaEntity}. 단계와 글은 대리키로 가리키고 도메인은 고정 식별자로 가리킨다. */
final class ReleaseLogMapper {

    private ReleaseLogMapper() {}

    /**
     * @param stageId 그 단계의 대리키
     * @param articleId 그 글의 대리키. {@code ARTICLE_ADDED}가 아니면 null이다
     */
    static LearningReleaseLogJpaEntity toEntity(
            ReleaseLogEntry entry, Long stageId, Long articleId) {
        return new LearningReleaseLogJpaEntity(
                entry.entryKey(),
                entry.kind(),
                stageId,
                articleId,
                entry.newCount(),
                entry.rewrittenCount(),
                entry.rewrittenTitles(),
                entry.articleTitle(),
                entry.actorUserId(),
                entry.occurredAt());
    }

    /** 저장된 줄이 도메인 규칙을 어기면 저장 데이터의 문제라 {@link IllegalStateException}으로 실패한다. */
    static ReleaseLogEntry toDomain(KeyedReleaseLogRow row) {
        LearningReleaseLogJpaEntity log = row.log();
        return LearningContentJson.storedValue(
                "learning_release_log(" + log.getEntryKey() + ")",
                () ->
                        new ReleaseLogEntry(
                                log.getEntryKey(),
                                log.getKind(),
                                new StageKey(row.stageKey()),
                                row.articleKey() == null ? null : new ArticleId(row.articleKey()),
                                log.getNewCount(),
                                log.getRewrittenCount(),
                                LearningContentJson.required(
                                        log.getRewrittenTitles(),
                                        "learning_release_log.rewritten_titles"),
                                log.getArticleTitle(),
                                log.getActorUserId(),
                                log.getOccurredAt()));
    }
}
