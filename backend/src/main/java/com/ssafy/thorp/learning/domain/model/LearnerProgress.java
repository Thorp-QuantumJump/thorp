package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.time.Instant;
import java.util.Objects;

/**
 * 로그인 사용자 한 명의 글 하나 기록({@code learning_progress} 한 행, data-model §11-1).
 *
 * <p>행이 없는 글은 '안 읽음'이다. 행이 있으면 완료 시각이 있을 때 '완료', 없으면 '읽는 중'이다(§12-2). 완료 시각과 익힘은 '학습 초기화' 전에는 비우지
 * 않는다(FR-020, FR-022).
 *
 * @param furthestPercent 가장 멀리 읽은 위치, 0~100. 더 큰 값만 남는다
 * @param lastOpenedAt 마지막으로 연 시각. 위치 기록과 완료 때 바뀐다
 * @param completedAt 완료 시각. 완료 전이면 null
 * @param allAnsweredAt 처음 그 글의 문제에 모두 답한 시각. 그 전이면 null(R29)
 * @param masteredAt 익힘 시각. 익힘 전이면 null
 * @param masteredVia 익힘 경로. {@code masteredAt}과 함께 있거나 함께 없다
 */
public record LearnerProgress(
        ArticleId articleId,
        int furthestPercent,
        Instant lastOpenedAt,
        Instant completedAt,
        Instant allAnsweredAt,
        Instant masteredAt,
        MasteredVia masteredVia) {

    public LearnerProgress {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(lastOpenedAt, "lastOpenedAt must not be null");
        if (furthestPercent < 0 || furthestPercent > 100) {
            throw new IllegalArgumentException(
                    "furthestPercent must be within 0..100: " + furthestPercent);
        }
        if ((masteredAt == null) != (masteredVia == null)) {
            throw new IllegalArgumentException(
                    "masteredAt and masteredVia must be present together: " + articleId);
        }
    }

    public boolean completed() {
        return completedAt != null;
    }

    public boolean mastered() {
        return masteredAt != null;
    }

    public boolean allAnswered() {
        return allAnsweredAt != null;
    }

    /** 이 글의 상태. 행이 없는 글은 {@link #stateOf}로 {@code UNREAD}다. */
    public ArticleState state() {
        return completed() ? ArticleState.COMPLETED : ArticleState.READING;
    }

    /** 행이 없으면 {@code UNREAD}. */
    public static ArticleState stateOf(LearnerProgress progress) {
        return progress == null ? ArticleState.UNREAD : progress.state();
    }

    /**
     * 화면에 보일 비율. 안 읽음은 0, 완료는 100, 읽는 중은 기록된 값을 1~99로 묶는다(data-model §12-2). 0%에서 떠난 글도 '읽는 중'이므로
     * 1%이고, 끝까지 내렸지만 완료하지 않은 글은 99%다.
     */
    public static int displayPercent(LearnerProgress progress) {
        return switch (stateOf(progress)) {
            case UNREAD -> 0;
            case COMPLETED -> 100;
            case READING -> Math.max(1, Math.min(99, progress.furthestPercent()));
        };
    }

    /** 글의 상태(FR-020). */
    public enum ArticleState {
        UNREAD,
        READING,
        COMPLETED
    }

    /** 익힘 경로(FR-022). 첫 답에서 하나를 맞혔거나, 다시 풀기에서 맞혔다. */
    public enum MasteredVia {
        FIRST_ANSWER,
        RETRY
    }
}
