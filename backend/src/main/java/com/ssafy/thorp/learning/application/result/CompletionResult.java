package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.application.result.LearnerParts.ContinueReading;
import com.ssafy.thorp.learning.application.result.LearnerParts.Gained;
import com.ssafy.thorp.learning.application.result.LearnerParts.StageProgress;
import java.time.Instant;

/**
 * 완료(learning-me-api §4).
 *
 * @param completedAt 저장된 완료 시각. 이미 완료한 글이면 그때의 시각이다
 * @param continueReading 이 완료를 반영한 값
 */
public record CompletionResult(
        Instant completedAt,
        boolean alreadyCompleted,
        StageProgress stageProgress,
        ContinueReading continueReading,
        Gained gained) {}
