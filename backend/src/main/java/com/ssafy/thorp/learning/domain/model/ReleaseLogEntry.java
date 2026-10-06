package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 변경 기록 한 줄(data-model §3-7, research R27). 추가만 하고 고치지 않는다.
 *
 * <p>그때의 편수와 제목을 적어 둔다. 뒤에 제목을 고치거나 글을 다른 단계로 옮겨도 지난 기록은 바뀌지 않는다. 현재 상태에서 다시 계산하지 않는다.
 *
 * @param entryKey {@code stage:{stageKey}} 또는 {@code article:{articleId}}(data-model §2)
 * @param stageKey 공개한 단계 또는 글을 더한 단계
 * @param articleId {@code ARTICLE_ADDED}일 때만 있다
 * @param newCount 새 글 편수. {@code ARTICLE_ADDED}는 1이다
 * @param rewrittenCount 다시 쓴 글 편수. {@code ARTICLE_ADDED}는 0이다
 * @param rewrittenTitles 다시 쓴 글의 그때 제목
 * @param articleTitle {@code ARTICLE_ADDED}의 그때 제목
 * @param occurredAt 단계 공개일 또는 글의 첫 게시 시각
 */
public record ReleaseLogEntry(
        String entryKey,
        Kind kind,
        StageKey stageKey,
        ArticleId articleId,
        int newCount,
        int rewrittenCount,
        List<String> rewrittenTitles,
        String articleTitle,
        long actorUserId,
        Instant occurredAt) {

    public enum Kind {
        STAGE_RELEASED,
        ARTICLE_ADDED
    }

    public ReleaseLogEntry {
        Objects.requireNonNull(entryKey, "entryKey must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(stageKey, "stageKey must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        rewrittenTitles = List.copyOf(rewrittenTitles);
        if (newCount < 0 || rewrittenCount < 0) {
            throw new IllegalArgumentException(
                    "release log counts must be >= 0: " + newCount + ", " + rewrittenCount);
        }
        if (rewrittenTitles.size() != rewrittenCount) {
            throw new IllegalArgumentException(
                    "rewrittenTitles must list every rewritten article: " + entryKey);
        }
        switch (kind) {
            case STAGE_RELEASED -> {
                if (articleId != null || articleTitle != null) {
                    throw new IllegalArgumentException(
                            "a stage release entry does not point at an article: " + entryKey);
                }
                if (!entryKey.equals(stageEntryKey(stageKey))) {
                    throw new IllegalArgumentException("unexpected entryKey: " + entryKey);
                }
            }
            case ARTICLE_ADDED -> {
                if (articleId == null || articleTitle == null) {
                    throw new IllegalArgumentException(
                            "an article entry needs the article and its title: " + entryKey);
                }
                if (newCount != 1 || rewrittenCount != 0) {
                    throw new IllegalArgumentException(
                            "an article entry counts one new article: " + entryKey);
                }
                if (!entryKey.equals(articleEntryKey(articleId))) {
                    throw new IllegalArgumentException("unexpected entryKey: " + entryKey);
                }
            }
        }
    }

    /** 단계 공개 한 줄. 편수는 그 단계에서 처음 공개된 글만 센다. */
    public static ReleaseLogEntry stageReleased(
            StageKey stageKey,
            int newCount,
            List<String> rewrittenTitles,
            long actorUserId,
            Instant releasedAt) {
        return new ReleaseLogEntry(
                stageEntryKey(stageKey),
                Kind.STAGE_RELEASED,
                stageKey,
                null,
                newCount,
                rewrittenTitles.size(),
                rewrittenTitles,
                null,
                actorUserId,
                releasedAt);
    }

    /** 공개된 단계에 처음 게시된 글 한 줄. */
    public static ReleaseLogEntry articleAdded(
            StageKey stageKey,
            ArticleId articleId,
            String title,
            long actorUserId,
            Instant publishedAt) {
        return new ReleaseLogEntry(
                articleEntryKey(articleId),
                Kind.ARTICLE_ADDED,
                stageKey,
                articleId,
                1,
                0,
                List.of(),
                title,
                actorUserId,
                publishedAt);
    }

    public static String stageEntryKey(StageKey stageKey) {
        return "stage:" + stageKey.value();
    }

    public static String articleEntryKey(ArticleId articleId) {
        return "article:" + articleId.value();
    }
}
