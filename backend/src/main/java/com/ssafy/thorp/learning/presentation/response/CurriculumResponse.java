package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.CurriculumResult;
import com.ssafy.thorp.learning.application.result.CurriculumResult.LogLine;
import com.ssafy.thorp.learning.application.result.CurriculumResult.StageView;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /learning/curriculum}의 {@code data}(learning-public-api §1, 예시 {@code
 * curriculum.json}·{@code curriculum-pre-release.json}).
 *
 * <p>{@code releasedAt}·{@code preReleaseArticles}·{@code articleTitle}은 없으면 명시적 null이다. 화면은 키가
 * 있는지가 아니라 값으로 가른다.
 */
public record CurriculumResponse(
        int releasedStageCount,
        boolean allStagesReleased,
        List<Stage> stages,
        List<PreReleaseArticle> preReleaseArticles,
        List<LogEntry> notices,
        List<LogEntry> changeLog) {

    public static CurriculumResponse from(CurriculumResult result) {
        return new CurriculumResponse(
                result.releasedStageCount(),
                result.allStagesReleased(),
                result.stages().stream().map(CurriculumResponse::stage).toList(),
                result.preReleaseArticles() == null
                        ? null
                        : result.preReleaseArticles().stream()
                                .map(
                                        article ->
                                                new PreReleaseArticle(
                                                        article.articleId(),
                                                        article.title(),
                                                        article.question(),
                                                        article.readingOrder()))
                                .toList(),
                result.notices().stream().map(CurriculumResponse::logEntry).toList(),
                result.changeLog().stream().map(CurriculumResponse::logEntry).toList());
    }

    private static Stage stage(StageView stage) {
        return new Stage(
                stage.stageKey(),
                stage.number(),
                stage.name(),
                stage.levelLabel(),
                stage.summary(),
                stage.status().name(),
                stage.releasedAt(),
                stage.articles().stream()
                        .map(
                                article ->
                                        new Article(
                                                article.articleId(),
                                                article.number(),
                                                article.title(),
                                                article.question(),
                                                article.readingMinutes(),
                                                article.termCount(),
                                                article.hasExample()))
                        .toList(),
                stage.previousEditionArticles().stream()
                        .map(
                                article ->
                                        new PreviousEditionArticle(
                                                article.articleId(),
                                                article.title(),
                                                article.question(),
                                                article.readingMinutes()))
                        .toList());
    }

    private static LogEntry logEntry(LogLine line) {
        return new LogEntry(
                line.entryKey(),
                line.kind().name(),
                line.stageNumber(),
                line.stageName(),
                line.occurredAt(),
                line.newCount(),
                line.rewrittenCount(),
                line.rewrittenTitles(),
                line.articleTitle());
    }

    /**
     * @param status {@code OPEN}·{@code PREPARING}·{@code REPREPARING}
     * @param articles {@code OPEN} 단계의 공개된 글, 읽는 순서
     * @param previousEditionArticles {@code PREPARING} 단계에 남은 이전 판 글
     */
    public record Stage(
            String stageKey,
            int number,
            String name,
            String levelLabel,
            String summary,
            String status,
            Instant releasedAt,
            List<Article> articles,
            List<PreviousEditionArticle> previousEditionArticles) {}

    /**
     * @param termCount 0이면 화면이 용어 수를 보이지 않는다
     */
    public record Article(
            String articleId,
            String number,
            String title,
            String question,
            int readingMinutes,
            int termCount,
            boolean hasExample) {}

    public record PreviousEditionArticle(
            String articleId, String title, String question, int readingMinutes) {}

    /** B16에서 지운 9월 판 목록(learning-public-api §2)의 한 줄과 같은 모양이다. */
    public record PreReleaseArticle(
            String articleId, String title, String question, int readingOrder) {}

    public record LogEntry(
            String entryKey,
            String kind,
            int stageNumber,
            String stageName,
            Instant occurredAt,
            int newCount,
            int rewrittenCount,
            List<String> rewrittenTitles,
            String articleTitle) {}
}
