package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import java.time.Instant;
import java.util.Objects;

/**
 * 경험치 장부 한 줄({@code learning_exp_entry}, data-model §11-3, research R29). 같은 (종류, 대상 키)로 한 번만
 * 생긴다(FR-028).
 *
 * @param refKey 대상 키. {@code article:{articleId}}, {@code answer:{articleId}:{questionId}}, {@code
 *     all:{articleId}}, {@code stage:{stageKey}}
 * @param articleId 글에 딸린 종류(완료·정답·모두 맞힘)의 글. 단계 완주는 null
 * @param stageKey 단계 완주의 단계. 그 밖은 null
 * @param points 1~100
 */
public record ExpEntry(
        Kind kind,
        String refKey,
        ArticleId articleId,
        StageKey stageKey,
        int points,
        Instant earnedAt) {

    public ExpEntry {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(refKey, "refKey must not be null");
        Objects.requireNonNull(earnedAt, "earnedAt must not be null");
        if (points < 1 || points > 100) {
            throw new IllegalArgumentException("points must be within 1..100: " + points);
        }
        if ((kind == Kind.STAGE_COMPLETED) != (stageKey != null)
                || (kind == Kind.STAGE_COMPLETED) == (articleId != null)) {
            throw new IllegalArgumentException(
                    "a stage entry points to a stage and the others to an article: " + refKey);
        }
    }

    /** 글을 처음 완료했다. */
    public static ExpEntry articleCompleted(ArticleId articleId, Instant at) {
        return new ExpEntry(
                Kind.ARTICLE_COMPLETED,
                "article:" + articleId.value(),
                articleId,
                null,
                ExpLedger.ARTICLE_COMPLETED_POINTS,
                at);
    }

    /** 첫 답이 맞았다. 점수는 정답 몫 상한으로 정한 값이다({@link ExpLedger#correctShare}). */
    public static ExpEntry answerCorrect(
            ArticleId articleId, QuestionId questionId, int points, Instant at) {
        return new ExpEntry(
                Kind.ANSWER_CORRECT,
                "answer:" + articleId.value() + ":" + questionId.value(),
                articleId,
                null,
                points,
                at);
    }

    /** 처음 모든 문제에 답한 순간 그 문제를 모두 첫 답에서 맞혔다. */
    public static ExpEntry allCorrect(ArticleId articleId, Instant at) {
        return new ExpEntry(
                Kind.ALL_CORRECT,
                allCorrectRef(articleId),
                articleId,
                null,
                ExpLedger.ALL_CORRECT_POINTS,
                at);
    }

    /** 비어 있지 않은 공개 단계의 공개된 글을 모두 완료했다. */
    public static ExpEntry stageCompleted(StageKey stageKey, Instant at) {
        return new ExpEntry(
                Kind.STAGE_COMPLETED,
                stageRef(stageKey),
                null,
                stageKey,
                ExpLedger.STAGE_COMPLETED_POINTS,
                at);
    }

    static String allCorrectRef(ArticleId articleId) {
        return "all:" + articleId.value();
    }

    static String stageRef(StageKey stageKey) {
        return "stage:" + stageKey.value();
    }

    /** 장부 줄의 종류(data-model §12-3). */
    public enum Kind {
        ARTICLE_COMPLETED,
        ANSWER_CORRECT,
        ALL_CORRECT,
        STAGE_COMPLETED
    }
}
