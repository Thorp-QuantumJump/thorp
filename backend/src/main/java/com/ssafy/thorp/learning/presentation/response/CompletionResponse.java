package com.ssafy.thorp.learning.presentation.response;

import com.ssafy.thorp.learning.application.result.CompletionResult;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.ContinueReading;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.Gained;
import com.ssafy.thorp.learning.presentation.response.LearnerResponseParts.StageProgress;
import java.time.Instant;

/**
 * {@code POST /learning/me/articles/{articleId}/completion}의 {@code data}(learning-me-api §4, 예시
 * {@code me-completion.json}).
 */
public record CompletionResponse(
        Instant completedAt,
        boolean alreadyCompleted,
        StageProgress stageProgress,
        ContinueReading continueReading,
        Gained gained) {

    public static CompletionResponse from(CompletionResult result) {
        return new CompletionResponse(
                result.completedAt(),
                result.alreadyCompleted(),
                StageProgress.from(result.stageProgress()),
                ContinueReading.from(result.continueReading()),
                Gained.from(result.gained()));
    }
}
