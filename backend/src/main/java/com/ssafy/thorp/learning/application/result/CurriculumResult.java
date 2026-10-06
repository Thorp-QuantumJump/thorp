package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.domain.model.ReleaseLogEntry.Kind;
import java.time.Instant;
import java.util.List;

/**
 * 커리큘럼(learning-public-api §1). 공개 상태(단계의 공개 여부, 글의 공개 배치, 공개 중인 판)와 변경 기록만 담는다.
 *
 * <p><b>공개되지 않은 단계의 새 글 제목·편수·날짜를 어떤 필드로도 싣지 않는다</b>(SC-017). 공개 전 단계에는 그 단계에 남은 이전 판(LEGACY) 글만
 * 있다.
 *
 * @param releasedStageCount 공개된 단계 수. 공개됐지만 공개된 글이 0편인 단계({@code REPREPARING})도 센다
 * @param stages 여섯 단계 전부, 번호 순서
 * @param preReleaseArticles 첫 단계를 공개하기 전에만 있다. 9월 판 목록과 같은 글·순서다. 공개한 뒤에는 null
 * @param notices {@code changeLog} 가운데 지금부터 7일 안의 줄, 최신 순서
 * @param changeLog 모든 변경 기록, 최신 순서
 */
public record CurriculumResult(
        int releasedStageCount,
        boolean allStagesReleased,
        List<StageView> stages,
        List<LearningArticleSummaryResult> preReleaseArticles,
        List<LogLine> notices,
        List<LogLine> changeLog) {

    public CurriculumResult {
        stages = List.copyOf(stages);
        preReleaseArticles = preReleaseArticles == null ? null : List.copyOf(preReleaseArticles);
        notices = List.copyOf(notices);
        changeLog = List.copyOf(changeLog);
    }

    /** 단계의 공개 상태(FR-031). */
    public enum StageStatus {
        /** 공개됐고 공개된 글이 한 편 이상이다. */
        OPEN,
        /** 공개 전이다. */
        PREPARING,
        /** 공개됐지만 공개된 글이 0편이다. 공개는 되돌리지 않으므로 공개된 단계로 센다. */
        REPREPARING
    }

    /**
     * @param releasedAt 공개 전이면 null
     * @param articles {@code OPEN} 단계의 공개된 글, 읽는 순서. 그 밖의 상태는 빈 목록이다
     * @param previousEditionArticles {@code PREPARING} 단계에 남은 이전 판 글. 첫 단계를 공개하기 전에는 빈 목록이다
     */
    public record StageView(
            String stageKey,
            int number,
            String name,
            String levelLabel,
            String summary,
            StageStatus status,
            Instant releasedAt,
            List<OpenArticle> articles,
            List<PreviousArticle> previousEditionArticles) {

        public StageView {
            articles = List.copyOf(articles);
            previousEditionArticles = List.copyOf(previousEditionArticles);
        }
    }

    /**
     * 공개된 단계의 공개된 글 하나.
     *
     * @param number 단계 안 번호(예: {@code 2-3})
     * @param termCount 그 글의 공개 용어 수
     */
    public record OpenArticle(
            String articleId,
            String number,
            String title,
            String question,
            int readingMinutes,
            int termCount,
            boolean hasExample) {}

    /** 공개 전 단계에 남은 이전 판 글. 번호·상태가 없다. */
    public record PreviousArticle(
            String articleId, String title, String question, int readingMinutes) {}

    /**
     * 변경 기록 한 줄. 단계 번호·이름은 지금 단계 정의에서 읽는다. 단계 정의는 마이그레이션만 바꾼다.
     *
     * @param articleTitle {@code ARTICLE_ADDED}의 그때 제목. {@code STAGE_RELEASED}는 null
     */
    public record LogLine(
            String entryKey,
            Kind kind,
            int stageNumber,
            String stageName,
            Instant occurredAt,
            int newCount,
            int rewrittenCount,
            List<String> rewrittenTitles,
            String articleTitle) {

        public LogLine {
            rewrittenTitles = List.copyOf(rewrittenTitles);
        }
    }
}
