package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.ExpEntry.Kind;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 한 사용자의 경험치 장부(data-model §11-3·§12-3, research R29). 레벨은 장부 합계로만 정한다. 장부에서 줄을 지우는 경로는 '학습
 * 초기화'뿐이다.
 *
 * <p>글 하나로 받을 수 있는 경험치는 100이다. 완료 20, 정답 몫 합 60, 모두 맞힘 20이다. 정답 몫은 답할 때의 문제 수로 나누고, 그 글에서 이미 받은 몫과
 * 합해 60을 넘지 않는다. 운영자가 문제를 바꿔 새 식별자의 문제가 생겨도 이 상한이 지킨다(SC-015).
 *
 * @param entries 쌓인 순서. 레벨에 닿은 시각({@link #reachedAt})을 이 순서로 센다
 */
public record ExpLedger(List<ExpEntry> entries) {

    public static final int ARTICLE_COMPLETED_POINTS = 20;
    public static final int CORRECT_SHARE_TOTAL = 60;
    public static final int ALL_CORRECT_POINTS = 20;
    public static final int STAGE_COMPLETED_POINTS = 50;

    /** 글 하나로 받을 수 있는 경험치. 레벨의 경험치 최대치를 셀 때 쓴다(FR-029). */
    public static final int ARTICLE_MAX_POINTS =
            ARTICLE_COMPLETED_POINTS + CORRECT_SHARE_TOTAL + ALL_CORRECT_POINTS;

    public ExpLedger {
        entries = List.copyOf(entries);
    }

    public static ExpLedger empty() {
        return new ExpLedger(List.of());
    }

    /** 줄을 더한 새 장부. 같은 (종류, 대상 키)가 이미 있으면 더하지 않는다(DB 유일 제약과 같은 규칙). */
    public ExpLedger plus(List<ExpEntry> added) {
        List<ExpEntry> merged = new ArrayList<>(entries);
        for (ExpEntry entry : added) {
            boolean seen =
                    merged.stream()
                            .anyMatch(
                                    existing ->
                                            existing.kind() == entry.kind()
                                                    && existing.refKey().equals(entry.refKey()));
            if (!seen) {
                merged.add(entry);
            }
        }
        return new ExpLedger(merged);
    }

    public int total() {
        return entries.stream().mapToInt(ExpEntry::points).sum();
    }

    public boolean has(Kind kind, String refKey) {
        return entries.stream()
                .anyMatch(entry -> entry.kind() == kind && entry.refKey().equals(refKey));
    }

    /** 이 글에서 '모두 맞힘'을 받았는가. */
    public boolean hasAllCorrect(ArticleId articleId) {
        return has(Kind.ALL_CORRECT, ExpEntry.allCorrectRef(articleId));
    }

    /** 이 단계의 완주 경험치를 받았는가. */
    public boolean hasStageCompleted(StageKey stageKey) {
        return has(Kind.STAGE_COMPLETED, ExpEntry.stageRef(stageKey));
    }

    /** 이 글에서 이미 받은 정답 몫의 합. */
    public int correctShareAwarded(ArticleId articleId) {
        return entries.stream()
                .filter(entry -> entry.kind() == Kind.ANSWER_CORRECT)
                .filter(entry -> articleId.equals(entry.articleId()))
                .mapToInt(ExpEntry::points)
                .sum();
    }

    /**
     * 맞은 첫 답 하나의 정답 몫 = min(60 ÷ 답할 때 그 글의 문제 수, 60 − 그 글에서 이미 받은 정답 몫). 문제 수가 2·3뿐이라 30·20의 정수다. 그
     * 글의 문제에 이미 모두 답한 적이 있으면({@code all_answered_at}) 주지 않는다. 그 뒤 운영자가 더한 문제는 첫 답과 정답 배지 수에는 들지만
     * 경험치를 주지 않는다(R29).
     *
     * @param questionCount 답할 때 공개 판의 문제 수
     * @param allAnsweredBefore 이 답 전에 그 글의 문제에 모두 답한 적이 있다
     * @return 0이면 장부에 쓰지 않는다
     */
    public int correctShare(ArticleId articleId, int questionCount, boolean allAnsweredBefore) {
        if (questionCount < 1) {
            throw new IllegalArgumentException("questionCount must be >= 1: " + questionCount);
        }
        if (allAnsweredBefore) {
            return 0;
        }
        int remaining = CORRECT_SHARE_TOTAL - correctShareAwarded(articleId);
        return Math.max(0, Math.min(CORRECT_SHARE_TOTAL / questionCount, remaining));
    }

    /**
     * 합계가 {@code threshold}에 처음 닿은 줄의 시각. 쌓인 순서로 센다. 문턱이 0이면(레벨 1) 닿은 순간이 없으므로 null이다.
     *
     * @return 아직 닿지 않았으면 null
     */
    public Instant reachedAt(int threshold) {
        if (threshold <= 0) {
            return null;
        }
        int sum = 0;
        for (ExpEntry entry : entries) {
            sum += entry.points();
            if (sum >= threshold) {
                return entry.earnedAt();
            }
        }
        return null;
    }
}
