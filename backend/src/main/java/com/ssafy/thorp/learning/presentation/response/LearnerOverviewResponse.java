package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.LearnerOverviewResult;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.ContinueReading;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.Gained;
import java.time.Instant;
import java.util.List;

/** {@code GET /learning/me}의 {@code data}(learning-me-api §1, 예시 {@code me.json}). */
public record LearnerOverviewResponse(
        Level level,
        Counts counts,
        ContinueReading continueReading,
        List<Article> articles,
        List<Stage> stages,
        List<MasteredTerm> masteredTerms,
        List<Badge> badges,
        Gained gained) {

    public static LearnerOverviewResponse from(LearnerOverviewResult result) {
        LearnerOverviewResult.LevelView level = result.level();
        LearnerOverviewResult.Counts counts = result.counts();
        return new LearnerOverviewResponse(
                new Level(
                        level.current(),
                        level.exp(),
                        level.currentThreshold(),
                        level.nextThreshold(),
                        level.remainingToNext(),
                        level.reachedAt(),
                        level.isNew(),
                        level.visibleMax(),
                        level.unreachableFrom(),
                        level.unreachableReason(),
                        level.milestones().stream()
                                .map(
                                        milestone ->
                                                new Milestone(
                                                        milestone.level(), milestone.threshold()))
                                .toList()),
                new Counts(
                        counts.completedArticles(),
                        counts.masteredTerms(),
                        counts.badges(),
                        counts.publishedArticles(),
                        counts.publishedTerms()),
                ContinueReading.from(result.continueReading()),
                result.articles().stream()
                        .map(
                                article ->
                                        new Article(
                                                article.articleId(),
                                                article.state(),
                                                article.percent(),
                                                article.completedAt(),
                                                article.termCount(),
                                                article.masteredTermCount()))
                        .toList(),
                result.stages().stream()
                        .map(
                                stage ->
                                        new Stage(
                                                stage.stageKey(),
                                                stage.number(),
                                                stage.status(),
                                                stage.completedCount(),
                                                stage.publishedCount(),
                                                stage.completed()))
                        .toList(),
                result.masteredTerms().stream()
                        .map(
                                term ->
                                        new MasteredTerm(
                                                term.termId(),
                                                term.label(),
                                                term.articleId(),
                                                term.articleNumber()))
                        .toList(),
                result.badges().stream()
                        .map(
                                badge ->
                                        new Badge(
                                                badge.badgeKey(),
                                                badge.family(),
                                                badge.name(),
                                                badge.criterion(),
                                                badge.status(),
                                                badge.earnedAt(),
                                                badge.isNew(),
                                                badge.progress() == null
                                                        ? null
                                                        : new Progress(
                                                                badge.progress().current(),
                                                                badge.progress().target()),
                                                badge.preparingReason(),
                                                badge.stageNumber()))
                        .toList(),
                Gained.from(result.gained()));
    }

    public record Level(
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

    public record Counts(
            int completedArticles,
            int masteredTerms,
            int badges,
            int publishedArticles,
            int publishedTerms) {}

    public record Article(
            String articleId,
            String state,
            int percent,
            Instant completedAt,
            int termCount,
            int masteredTermCount) {}

    public record Stage(
            String stageKey,
            int number,
            String status,
            Integer completedCount,
            Integer publishedCount,
            boolean completed) {}

    public record MasteredTerm(
            String termId, String label, String articleId, String articleNumber) {}

    public record Badge(
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
