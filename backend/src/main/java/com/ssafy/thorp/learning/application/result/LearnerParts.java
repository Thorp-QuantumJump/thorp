package com.ssafy.thorp.learning.application.result;

import java.util.List;

/** 나의 학습 응답 여럿이 같은 모양으로 쓰는 조각(learning-me-api §1~§6·§8). */
public final class LearnerParts {

    private LearnerParts() {}

    /**
     * '이어서 읽기'(data-model §12-2).
     *
     * @param kind {@code RESUME}·{@code START}·{@code ALL_DONE}
     * @param articleId {@code ALL_DONE}이면 null. 아래 셋도 같다
     * @param percent {@code RESUME}일 때만 있다(1~99)
     * @param moreStagesPreparing 공개되지 않은 단계가 있다
     */
    public record ContinueReading(
            String kind,
            String articleId,
            String number,
            String title,
            Integer percent,
            boolean moreStagesPreparing) {}

    /** 글이 공개 배치된 단계의 진행. */
    public record StageProgress(
            int stageNumber, int completedCount, int publishedCount, boolean stageCompleted) {}

    /**
     * 그 글의 확인 문제 요약. 공개 판의 문제와 그 첫 답만 센다.
     *
     * @param correct 첫 답 기준. 다시 풀어도 바뀌지 않는다
     * @param masteredVia {@code FIRST_ANSWER}·{@code RETRY}, 익힘이 아니면 null
     */
    public record QuizSummary(
            int total,
            int answered,
            int correct,
            boolean allAnswered,
            boolean mastered,
            String masteredVia,
            boolean retryAvailable) {}

    /**
     * 이번 요청에서 새로 생긴 것(learning-me-api §8).
     *
     * @param exp 새 장부 줄
     * @param badges 새로 받은 배지
     */
    public record Gained(
            List<GainedExp> exp,
            int expTotal,
            List<GainedBadge> badges,
            int levelBefore,
            int levelAfter) {

        public Gained {
            exp = List.copyOf(exp);
            badges = List.copyOf(badges);
        }

        /** 아무것도 생기지 않은 요청. */
        public static Gained none(int level) {
            return new Gained(List.of(), 0, List.of(), level, level);
        }
    }

    /**
     * @param stageNumber 단계 완주만 있다
     */
    public record GainedExp(String kind, int points, Integer stageNumber) {}

    public record GainedBadge(String badgeKey, String family, String name) {}
}
