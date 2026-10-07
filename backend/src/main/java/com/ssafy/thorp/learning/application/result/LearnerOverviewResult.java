package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.application.result.LearnerParts.ContinueReading;
import com.ssafy.thorp.learning.application.result.LearnerParts.Gained;
import java.time.Instant;
import java.util.List;

/** 나의 학습 전체(learning-me-api §1, {@code GET /learning/me}). */
public record LearnerOverviewResult(
        LevelView level,
        Counts counts,
        ContinueReading continueReading,
        List<ArticleRecord> articles,
        List<StageRecord> stages,
        List<MasteredTerm> masteredTerms,
        List<BadgeView> badges,
        Gained gained) {

    /**
     * @param reachedAt 현재 레벨에 닿은 시각. 레벨 1이면 null
     * @param isNew 현재 레벨에 닿은 지 7일 안
     * @param unreachableReason {@code MORE_STAGES}·{@code MORE_ARTICLES}
     */
    public record LevelView(
            int current,
            int exp,
            int currentThreshold,
            int nextThreshold,
            int remainingToNext,
            Instant reachedAt,
            boolean isNew,
            int visibleMax,
            int unreachableFrom,
            String unreachableReason,
            List<Milestone> milestones) {}

    public record Milestone(int level, int threshold) {}

    /** 분모 없이 보이는 수와 공개 수. */
    public record Counts(
            int completedArticles,
            int masteredTerms,
            int badges,
            int publishedArticles,
            int publishedTerms) {}

    /**
     * 공개된 글 하나의 기록.
     *
     * @param state {@code UNREAD}·{@code READING}·{@code COMPLETED}
     * @param percent 안 읽음 0, 읽는 중 1~99, 완료 100
     */
    public record ArticleRecord(
            String articleId,
            String state,
            int percent,
            Instant completedAt,
            int termCount,
            int masteredTermCount) {}

    /**
     * 단계 하나. {@code OPEN}은 두 수가 있고, {@code REPREPARING}은 둘 다 0, {@code PREPARING}은 둘 다 null이다.
     *
     * @param completed 비어 있지 않은 공개 단계의 공개된 글을 모두 완료했다
     */
    public record StageRecord(
            String stageKey,
            int number,
            String status,
            Integer completedCount,
            Integer publishedCount,
            boolean completed) {}

    /** 익힌 용어. 공개된 글의 공개 용어만이다. */
    public record MasteredTerm(
            String termId, String label, String articleId, String articleNumber) {}

    /**
     * 배지 하나.
     *
     * @param status {@code EARNED}·{@code NOT_YET}·{@code PREPARING}
     * @param progress {@code NOT_YET}의 진행. 그 밖은 null
     * @param preparingReason {@code PREPARING}의 사유. 그 밖은 null
     * @param stageNumber 단계 완주 배지만 있다
     */
    public record BadgeView(
            String badgeKey,
            String family,
            String name,
            String criterion,
            String status,
            Instant earnedAt,
            boolean isNew,
            Progress progress,
            String preparingReason,
            Integer stageNumber) {}

    public record Progress(int current, int target) {}
}
