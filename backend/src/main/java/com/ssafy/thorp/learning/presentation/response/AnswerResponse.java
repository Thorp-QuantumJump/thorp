package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.AnswerResult;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.Gained;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.Quiz;

/**
 * {@code POST /learning/me/articles/{articleId}/answers}의 {@code data}(learning-me-api §5, 예시
 * {@code me-answer.json}).
 */
public record AnswerResponse(
        String questionId,
        String choiceId,
        boolean correct,
        String correctChoiceId,
        String explanation,
        boolean alreadyAnswered,
        Quiz quiz,
        Gained gained) {

    public static AnswerResponse from(AnswerResult result) {
        return new AnswerResponse(
                result.questionId(),
                result.choiceId(),
                result.correct(),
                result.correctChoiceId(),
                result.explanation(),
                Boolean.TRUE.equals(result.alreadyAnswered()),
                Quiz.from(result.quiz()),
                Gained.from(result.gained()));
    }
}
