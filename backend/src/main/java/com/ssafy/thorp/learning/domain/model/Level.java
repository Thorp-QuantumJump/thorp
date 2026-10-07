package com.ssafy.thorp.learning.domain.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 레벨(FR-029, data-model §12-3, research R31). 경험치 합계로만 정하고 문턱은 바꾸지 않는다.
 *
 * <p>보이는 가장 높은 레벨은 지금 공개된 콘텐츠로 받을 수 있는 경험치 최대치로 닿는 레벨이다. 게시 취소된 글로 받은 경험치가 남아 현재 레벨이 더 높으면 현재 레벨까지
 * 보인다. 그다음 레벨 N부터는 단계나 글이 더 공개된 뒤 닿는다. 사용자마다 따로 계산하지 않는다.
 *
 * @param current 현재 레벨. 합계 ≥ 문턱인 가장 높은 레벨
 * @param exp 장부 합계
 * @param visibleMax 보이는 가장 높은 레벨
 * @param unreachableReason N부터 닿을 수 없는 사유. {@code MORE_STAGES} 또는 {@code MORE_ARTICLES}
 */
public record Level(int current, int exp, int visibleMax, PreparingReason unreachableReason) {

    /** 레벨 1~10의 문턱. 11 이상은 4,500 + 900 × (레벨 − 10)이다. */
    private static final int[] THRESHOLDS = {0, 100, 300, 600, 1000, 1500, 2100, 2800, 3600, 4500};

    private static final int STEP_AFTER_TEN = 900;

    public Level {
        if (current < 1 || visibleMax < current) {
            throw new IllegalArgumentException(
                    "invalid level: current=" + current + ", visibleMax=" + visibleMax);
        }
        if (unreachableReason != PreparingReason.MORE_STAGES
                && unreachableReason != PreparingReason.MORE_ARTICLES) {
            throw new IllegalArgumentException("invalid unreachable reason: " + unreachableReason);
        }
    }

    /**
     * @param exp 장부 합계
     * @param maxExp 지금 공개된 콘텐츠로 받을 수 있는 경험치 최대치({@link ContentScope#maxExp})
     * @param allStagesReleased 여섯 단계를 모두 공개했는가
     */
    public static Level of(int exp, int maxExp, boolean allStagesReleased) {
        int current = levelFor(exp);
        return new Level(
                current,
                exp,
                Math.max(levelFor(maxExp), current),
                PreparingReason.forCounts(allStagesReleased));
    }

    /** 합계가 문턱 이상인 가장 높은 레벨. 합계가 정확히 100이면 레벨 2다. */
    public static int levelFor(int exp) {
        if (exp < 0) {
            throw new IllegalArgumentException("exp must be >= 0: " + exp);
        }
        int level = 1;
        while (threshold(level + 1) <= exp) {
            level++;
        }
        return level;
    }

    /** 그 레벨의 문턱. */
    public static int threshold(int level) {
        if (level < 1) {
            throw new IllegalArgumentException("level must be >= 1: " + level);
        }
        if (level <= THRESHOLDS.length) {
            return THRESHOLDS[level - 1];
        }
        return THRESHOLDS[THRESHOLDS.length - 1] + STEP_AFTER_TEN * (level - THRESHOLDS.length);
    }

    /** 보이는 가장 높은 레벨의 다음 레벨 N. */
    public int unreachableFrom() {
        return visibleMax + 1;
    }

    public int currentThreshold() {
        return threshold(current);
    }

    public int nextThreshold() {
        return threshold(current + 1);
    }

    public int remainingToNext() {
        return nextThreshold() - exp;
    }

    /** 레벨 1부터 N까지의 문턱. 화면이 레벨 길을 그린다. */
    public List<Milestone> milestones() {
        List<Milestone> milestones = new ArrayList<>();
        for (int level = 1; level <= unreachableFrom(); level++) {
            milestones.add(new Milestone(level, threshold(level)));
        }
        return milestones;
    }

    public record Milestone(int level, int threshold) {}
}
