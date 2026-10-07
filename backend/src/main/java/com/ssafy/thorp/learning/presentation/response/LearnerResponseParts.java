package com.ssafy.thorp.learning.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.learning.application.result.LearnerParts;
import java.util.List;

/**
 * 나의 학습 응답 여럿이 같은 모양으로 쓰는 조각(learning-me-api §1~§6·§8). 값이 없는 필드는 명시적 null이다. 예외는 {@code
 * gained.exp[].stageNumber} 하나로, 단계 완주가 아니면 키를 싣지 않는다(계약 §4 예시).
 */
public final class LearnerResponseParts {

    private LearnerResponseParts() {}

    public record ContinueReading(
            String kind,
            String articleId,
            String number,
            String title,
            Integer percent,
            boolean moreStagesPreparing) {

        static ContinueReading from(LearnerParts.ContinueReading value) {
            return new ContinueReading(
                    value.kind(),
                    value.articleId(),
                    value.number(),
                    value.title(),
                    value.percent(),
                    value.moreStagesPreparing());
        }
    }

    public record StageProgress(
            int stageNumber, int completedCount, int publishedCount, boolean stageCompleted) {

        static StageProgress from(LearnerParts.StageProgress value) {
            return new StageProgress(
                    value.stageNumber(),
                    value.completedCount(),
                    value.publishedCount(),
                    value.stageCompleted());
        }
    }

    public record Quiz(
            int total,
            int answered,
            int correct,
            boolean allAnswered,
            boolean mastered,
            String masteredVia,
            boolean retryAvailable) {

        static Quiz from(LearnerParts.QuizSummary value) {
            return new Quiz(
                    value.total(),
                    value.answered(),
                    value.correct(),
                    value.allAnswered(),
                    value.mastered(),
                    value.masteredVia(),
                    value.retryAvailable());
        }
    }

    public record Gained(
            List<GainedExp> exp,
            int expTotal,
            List<GainedBadge> badges,
            int levelBefore,
            int levelAfter) {

        static Gained from(LearnerParts.Gained value) {
            return new Gained(
                    value.exp().stream()
                            .map(exp -> new GainedExp(exp.kind(), exp.points(), exp.stageNumber()))
                            .toList(),
                    value.expTotal(),
                    value.badges().stream()
                            .map(
                                    badge ->
                                            new GainedBadge(
                                                    badge.badgeKey(), badge.family(), badge.name()))
                            .toList(),
                    value.levelBefore(),
                    value.levelAfter());
        }
    }

    /**
     * @param stageNumber 단계 완주만 싣는다
     */
    public record GainedExp(
            String kind,
            int points,
            @JsonInclude(JsonInclude.Include.NON_NULL) Integer stageNumber) {}

    public record GainedBadge(String badgeKey, String family, String name) {}
}
