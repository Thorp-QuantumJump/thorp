package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.AnswerResult;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.Gained;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.Quiz;

/**
 * {@code POST /learning/me/articles/{articleId}/retry-answers}의 {@code data}(learning-me-api §6, 예시
 * {@code me-retry.json}). 다시 풀기의 답은 저장하지 않으므로 {@code alreadyAnswered}가 없다.
 */
public record RetryResponse(
        String questionId,
        String choiceId,
        boolean correct,
        String correctChoiceId,
        String explanation,
        Quiz quiz,
        Gained gained) {

    public static RetryResponse from(AnswerResult result) {
        return new RetryResponse(
                result.questionId(),
                result.choiceId(),
                result.correct(),
                result.correctChoiceId(),
                result.explanation(),
                Quiz.from(result.quiz()),
                Gained.from(result.gained()));
    }
}
