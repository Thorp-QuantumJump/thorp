package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.application.result.LearnerParts.ContinueReading;
import com.ssafy.thorp.learning.application.result.LearnerParts.QuizSummary;
import com.ssafy.thorp.learning.application.result.LearnerParts.StageProgress;
import java.time.Instant;
import java.util.List;

/**
 * 글 하나의 기록(learning-me-api §2). 정산하지 않는다.
 *
 * @param state {@code UNREAD}·{@code READING}·{@code COMPLETED}
 * @param percent 안 읽음 0, 읽는 중 1~99, 완료 100
 * @param answers 공개 판에 남은 문제의 첫 답, 판의 문제 순서
 */
public record LearnerArticleResult(
        String articleId,
        String state,
        int percent,
        Instant completedAt,
        List<Answer> answers,
        QuizSummary quiz,
        StageProgress stageProgress,
        ContinueReading continueReading) {

    public record Answer(String questionId, String choiceId, boolean correct, Instant answeredAt) {}
}
