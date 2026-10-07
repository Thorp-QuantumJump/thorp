package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.BadgeStatus.Progress;
import com.ssafy.thorp.learning.domain.model.BadgeStatus.State;
import com.ssafy.thorp.learning.domain.model.ContentScope.StageScope;
import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 정산(data-model §12-5, research R30). 사용자의 사실과 지금 공개된 콘텐츠로 새로 채워진 단계 완주와 배지를 찾는 순수 함수다.
 *
 * <p>학습 행동 요청(완료·첫 답·다시 풀기)과 {@code GET /learning/me}가 같은 순서로 부른다. ① 단계 완주 → ② 장부 합계로 레벨 → ③ 배지.
 * 운영자의 게시 취소나 단계 이동으로 채워진 기준도 다음 정산에서 생긴다(FR-024). 받은 배지는 콘텐츠가 바뀌어도 지우지 않는다.
 */
public final class Settlement {

    private Settlement() {}

    /**
     * @param now 새 장부 줄과 배지의 시각
     * @return 새로 생길 단계 완주 장부 줄과 배지. 저장은 호출자가 {@code ON CONFLICT DO NOTHING}으로 한다
     */
    public static Outcome settle(
            ContentScope scope, LearnerFacts facts, BadgeCatalog catalog, Instant now) {
        List<ExpEntry> stageEntries = new ArrayList<>();
        for (StageScope stage : scope.nonEmptyReleasedStages()) {
            if (facts.stageCompleted(stage)
                    && !facts.ledger().hasStageCompleted(stage.stageKey())) {
                stageEntries.add(ExpEntry.stageCompleted(stage.stageKey(), now));
            }
        }
        LearnerFacts settled = facts.withLedger(facts.ledger().plus(stageEntries));
        Level level = level(scope, settled);
        List<BadgeKey> badges = new ArrayList<>();
        for (BadgeDefinition definition : catalog.definitions()) {
            if (!settled.badges().containsKey(definition.key())
                    && met(definition, scope, settled, level)) {
                badges.add(definition.key());
            }
        }
        return new Outcome(stageEntries, badges);
    }

    /** 장부 합계와 지금 공개된 콘텐츠의 레벨. */
    public static Level level(ContentScope scope, LearnerFacts facts) {
        return Level.of(facts.ledger().total(), scope.maxExp(), scope.allStagesReleased());
    }

    /** 배지 하나의 지금 상태. 정산을 마친 사실로 부른다. */
    public static BadgeStatus evaluate(
            BadgeDefinition definition, ContentScope scope, LearnerFacts facts, Level level) {
        Instant earnedAt = facts.badges().get(definition.key());
        if (earnedAt != null) {
            return new BadgeStatus(definition, State.EARNED, earnedAt, null, null);
        }
        PreparingReason reason = preparing(definition, scope, level);
        if (reason != null) {
            return new BadgeStatus(definition, State.PREPARING, null, null, reason);
        }
        return new BadgeStatus(
                definition, State.NOT_YET, null, progress(definition, scope, facts, level), null);
    }

    /** 받는 조건. */
    static boolean met(
            BadgeDefinition definition, ContentScope scope, LearnerFacts facts, Level level) {
        int target = definition.target();
        return switch (definition.measure()) {
            case LEVEL -> level.current() >= target;
            case READ -> facts.readCount(scope) >= target;
            case READ_ALL_STAGES -> scope.allStagesReleased() && facts.readCount(scope) >= target;
            case CORRECT -> facts.correctCount(scope) >= target;
            case PERFECT -> facts.perfectCount(scope) >= target;
            case STAGE ->
                    scope.stages().stream()
                            .filter(stage -> stage.stageKey().equals(definition.stageKey()))
                            .anyMatch(facts::stageCompleted);
            case TERMS -> facts.masteredTermCount(scope) >= target;
            case TERMS_ALL ->
                    scope.allStagesReleased()
                            && scope.termCount() > 0
                            && facts.masteredTermCount(scope) == scope.termCount();
            case PERSISTENCE -> facts.anyRetryMastered();
            case EXPLORATION ->
                    scope.allStagesReleased()
                            && !scope.nonEmptyReleasedStages().isEmpty()
                            && scope.nonEmptyReleasedStages().stream()
                                    .allMatch(stage -> facts.completedIn(stage) > 0);
        };
    }

    /** 받지 않은 배지가 지금 공개된 콘텐츠로는 받을 수 없는 사유(data-model §12-4 표). 받을 수 있으면 null이다. */
    static PreparingReason preparing(BadgeDefinition definition, ContentScope scope, Level level) {
        PreparingReason counts = PreparingReason.forCounts(scope.allStagesReleased());
        int target = definition.target();
        return switch (definition.measure()) {
            case LEVEL -> target > level.visibleMax() ? level.unreachableReason() : null;
            case READ -> target > scope.articleCount() ? counts : null;
            case CORRECT -> target > scope.questionCount() ? counts : null;
            case PERFECT -> target > scope.articleCount() ? counts : null;
            case TERMS -> target > scope.termCount() ? counts : null;
            case READ_ALL_STAGES ->
                    !scope.allStagesReleased()
                            ? PreparingReason.ALL_STAGES
                            : target > scope.articleCount() ? PreparingReason.MORE_ARTICLES : null;
            case TERMS_ALL ->
                    !scope.allStagesReleased()
                            ? PreparingReason.ALL_STAGES
                            : scope.termCount() == 0 ? PreparingReason.MORE_ARTICLES : null;
            case EXPLORATION ->
                    !scope.allStagesReleased()
                            ? PreparingReason.ALL_STAGES
                            : scope.nonEmptyReleasedStages().isEmpty()
                                    ? PreparingReason.MORE_ARTICLES
                                    : null;
            case STAGE -> {
                StageScope stage = stageOf(definition, scope);
                if (!stage.released()) {
                    yield PreparingReason.STAGE_NOT_RELEASED;
                }
                yield stage.articles().isEmpty() ? PreparingReason.STAGE_EMPTY : null;
            }
            case PERSISTENCE -> null;
        };
    }

    private static Progress progress(
            BadgeDefinition definition, ContentScope scope, LearnerFacts facts, Level level) {
        int target = definition.target();
        return switch (definition.measure()) {
            case LEVEL -> new Progress(level.exp(), Level.threshold(target));
            case READ, READ_ALL_STAGES -> new Progress(facts.readCount(scope), target);
            case CORRECT -> new Progress(facts.correctCount(scope), target);
            case PERFECT -> new Progress(facts.perfectCount(scope), target);
            case TERMS -> new Progress(facts.masteredTermCount(scope), target);
            case TERMS_ALL -> new Progress(facts.masteredTermCount(scope), scope.termCount());
            case STAGE -> {
                StageScope stage = stageOf(definition, scope);
                yield new Progress(facts.completedIn(stage), stage.articles().size());
            }
            case EXPLORATION ->
                    new Progress(
                            (int)
                                    scope.nonEmptyReleasedStages().stream()
                                            .filter(stage -> facts.completedIn(stage) > 0)
                                            .count(),
                            scope.nonEmptyReleasedStages().size());
            case PERSISTENCE -> null;
        };
    }

    private static StageScope stageOf(BadgeDefinition definition, ContentScope scope) {
        return scope.stages().stream()
                .filter(stage -> stage.stageKey().equals(definition.stageKey()))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "badge points to a missing stage: " + definition.key()));
    }

    /**
     * 정산 결과.
     *
     * @param stageEntries 새 단계 완주 장부 줄
     * @param badges 새로 채워진 배지. 카탈로그 순서다
     */
    public record Outcome(List<ExpEntry> stageEntries, List<BadgeKey> badges) {

        public Outcome {
            stageEntries = List.copyOf(stageEntries);
            badges = List.copyOf(badges);
        }
    }
}
