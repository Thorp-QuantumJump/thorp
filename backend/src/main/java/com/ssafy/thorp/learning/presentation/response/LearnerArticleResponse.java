package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.LearnerArticleResult;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.ContinueReading;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.Quiz;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.StageProgress;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /learning/me/articles/{articleId}}의 {@code data}(learning-me-api §2, 예시 {@code
 * me-article.json}).
 */
public record LearnerArticleResponse(
        String articleId,
        String state,
        int percent,
        Instant completedAt,
        List<Answer> answers,
        Quiz quiz,
        StageProgress stageProgress,
        ContinueReading continueReading) {

    public static LearnerArticleResponse from(LearnerArticleResult result) {
        return new LearnerArticleResponse(
                result.articleId(),
                result.state(),
                result.percent(),
                result.completedAt(),
                result.answers().stream()
                        .map(
                                answer ->
                                        new Answer(
                                                answer.questionId(),
                                                answer.choiceId(),
                                                answer.correct(),
                                                answer.answeredAt()))
                        .toList(),
                Quiz.from(result.quiz()),
                StageProgress.from(result.stageProgress()),
                ContinueReading.from(result.continueReading()));
    }

    public record Answer(String questionId, String choiceId, boolean correct, Instant answeredAt) {}
}
