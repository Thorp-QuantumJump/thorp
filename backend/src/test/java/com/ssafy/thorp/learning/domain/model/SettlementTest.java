package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.learning.domain.model.BadgeStatus.Progress;
import com.ssafy.thorp.learning.domain.model.BadgeStatus.State;
import com.ssafy.thorp.learning.domain.model.LearnerProgress.MasteredVia;
import com.ssafy.thorp.learning.domain.model.Settlement.Outcome;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.BadgeKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 정산과 배지 상태(data-model §12-4·§12-5, research R30, quickstart 10-6·10-7). */
class SettlementTest {

    private static final Instant AT = Instant.parse("2026-11-20T03:10:00Z");

    @Test
    @DisplayName("운영자가 남은 글을 게시 취소해 단계의 공개된 글을 모두 읽은 상태가 되면 단계 완주와 배지가 생긴다")
    void unpublishCompletesStage() {
        // 2단계 네 편 가운데 세 편을 읽었다. 운영자가 남은 a2-4를 게시 취소해 2단계가 세 편이 됐다.
        ContentScope scope = LearnerScopes.scope(4, 3, -1, -1, -1, -1);
        LearnerFacts facts =
                facts(
                        completed(
                                LearnerScopes.article(2, 1),
                                LearnerScopes.article(2, 2),
                                LearnerScopes.article(2, 3)),
                        ExpLedger.empty(),
                        Map.of());

        Outcome outcome = settle(scope, facts);

        assertThat(outcome.stageEntries())
                .extracting(ExpEntry::stageKey)
                .containsExactly(LearnerScopes.stageKey(2));
        assertThat(outcome.badges())
                .extracting(BadgeKey::value)
                .contains("stage-returns-and-risk", "read-1")
                .doesNotContain("stage-etf-market-basics");
    }

    @Test
    @DisplayName("이미 받은 단계 완주와 배지는 다시 생기지 않는다")
    void settledOnce() {
        ContentScope scope = LearnerScopes.scope(1, -1, -1, -1, -1, -1);
        LearnerFacts first =
                facts(completed(LearnerScopes.article(1, 1)), ExpLedger.empty(), Map.of());
        Outcome outcome = settle(scope, first);
        Map<BadgeKey, Instant> badges = new HashMap<>();
        outcome.badges().forEach(badge -> badges.put(badge, AT));

        LearnerFacts second =
                facts(
                        completed(LearnerScopes.article(1, 1)),
                        first.ledger().plus(outcome.stageEntries()),
                        badges);

        Outcome again = settle(scope, second);
        assertThat(again.stageEntries()).isEmpty();
        assertThat(again.badges()).isEmpty();
    }

    @Test
    @DisplayName("공개된 글이 0편인 공개 단계는 완주로 보지 않는다")
    void emptyReleasedStageIsNotCompleted() {
        ContentScope scope = LearnerScopes.scope(1, 0, -1, -1, -1, -1);
        LearnerFacts facts =
                facts(completed(LearnerScopes.article(1, 1)), ExpLedger.empty(), Map.of());

        Outcome outcome = settle(scope, facts);

        assertThat(outcome.stageEntries())
                .extracting(ExpEntry::stageKey)
                .containsExactly(LearnerScopes.stageKey(1));
        assertThat(status(scope, facts, "stage-returns-and-risk").reason())
                .isEqualTo(PreparingReason.STAGE_EMPTY);
    }

    @Test
    @DisplayName("탐험은 여섯 단계를 모두 공개해도 비어 있지 않은 공개 단계가 없으면 받지 않는다")
    void explorationNeedsNonEmptyStages() {
        ContentScope empty = LearnerScopes.scope(0, 0, 0, 0, 0, 0);
        LearnerFacts nothing = facts(Map.of(), ExpLedger.empty(), Map.of());

        assertThat(settle(empty, nothing).badges()).isEmpty();
        assertThat(status(empty, nothing, "exploration").reason())
                .isEqualTo(PreparingReason.MORE_ARTICLES);

        // 한 편씩 공개된 두 단계에서 한 편씩 읽으면 받는다.
        ContentScope two = LearnerScopes.scope(1, 1, 0, 0, 0, 0);
        LearnerFacts read =
                facts(
                        completed(LearnerScopes.article(1, 1), LearnerScopes.article(2, 1)),
                        ExpLedger.empty(),
                        Map.of());
        assertThat(settle(two, read).badges()).extracting(BadgeKey::value).contains("exploration");
    }

    @Test
    @DisplayName("'그때 게시된 용어 전체'와 '읽은 글 27편'은 여섯 단계를 공개하기 전에는 기준을 채워도 받지 않는다")
    void wholeBadgesWaitForAllStages() {
        ContentScope scope = LearnerScopes.firstTwoStages();
        Map<ArticleId, LearnerProgress> all = new HashMap<>();
        for (ArticleId article : scope.articles()) {
            all.put(article, mastered(article));
        }
        LearnerFacts facts = facts(all, ExpLedger.empty(), Map.of());

        Outcome outcome = settle(scope, facts);

        assertThat(outcome.badges())
                .extracting(BadgeKey::value)
                .contains("terms-1", "read-5")
                .doesNotContain("terms-all", "read-27", "exploration");
        assertThat(status(scope, facts, "terms-all").reason())
                .isEqualTo(PreparingReason.ALL_STAGES);
        assertThat(status(scope, facts, "read-27").reason()).isEqualTo(PreparingReason.ALL_STAGES);

        // 여섯 단계를 모두 공개한 뒤 모든 용어를 익히면 받는다.
        ContentScope released = LearnerScopes.scope(1, 1, 1, 1, 1, 1);
        Map<ArticleId, LearnerProgress> allReleased = new HashMap<>();
        for (ArticleId article : released.articles()) {
            allReleased.put(article, mastered(article));
        }
        assertThat(settle(released, facts(allReleased, ExpLedger.empty(), Map.of())).badges())
                .extracting(BadgeKey::value)
                .contains("terms-all", "exploration")
                .doesNotContain("read-27");
    }

    @Test
    @DisplayName("여섯 단계 27편을 모두 읽으면 '읽은 글 27편'과 '탐험'을 받는다")
    void readAllAfterAllStages() {
        ContentScope scope = LearnerScopes.allStages();
        Map<ArticleId, LearnerProgress> all = new HashMap<>();
        for (ArticleId article : scope.articles()) {
            all.put(article, new LearnerProgress(article, 100, AT, AT, null, null, null));
        }

        Outcome outcome = settle(scope, facts(all, ExpLedger.empty(), Map.of()));

        assertThat(outcome.badges())
                .extracting(BadgeKey::value)
                .contains("read-20", "read-27", "exploration")
                .doesNotContain("terms-all");
        assertThat(outcome.stageEntries()).hasSize(6);
    }

    @Test
    @DisplayName("준비 중 사유는 다섯 가지이고, 같은 갈래에서는 문구가 같은 것끼리만 묶인다")
    void preparingReasons() {
        ContentScope partial = LearnerScopes.firstTwoStages();
        LearnerFacts none = facts(Map.of(), ExpLedger.empty(), Map.of());

        // 수를 세는 배지와 레벨 배지: 공개되지 않은 단계가 있으면 '단계가 더 공개된 뒤'.
        assertThat(status(partial, none, "read-10").reason())
                .isEqualTo(PreparingReason.MORE_STAGES);
        assertThat(status(partial, none, "read-20").reason())
                .isEqualTo(PreparingReason.MORE_STAGES);
        assertThat(status(partial, none, "level-5").reason())
                .isEqualTo(PreparingReason.MORE_STAGES);
        // 같은 갈래의 read-27은 사유가 달라 read-10·read-20과 한 줄로 묶이지 않는다.
        assertThat(status(partial, none, "read-27").reason()).isEqualTo(PreparingReason.ALL_STAGES);
        assertThat(status(partial, none, "stage-allocation-rules").reason())
                .isEqualTo(PreparingReason.STAGE_NOT_RELEASED);
        assertThat(status(partial, none, "stage-allocation-rules").definition().stageNumber())
                .isEqualTo(3);

        // 여섯 단계를 모두 공개한 뒤에는 '글이 더 공개된 뒤'.
        ContentScope released = LearnerScopes.scope(4, 4, 1, 0, 1, 1);
        assertThat(status(released, none, "read-20").reason())
                .isEqualTo(PreparingReason.MORE_ARTICLES);
        assertThat(status(released, none, "read-27").reason())
                .isEqualTo(PreparingReason.MORE_ARTICLES);
        assertThat(status(released, none, "stage-backtest-and-costs").reason())
                .isEqualTo(PreparingReason.STAGE_EMPTY);
        assertThat(status(released, none, "level-8").reason())
                .isEqualTo(PreparingReason.MORE_ARTICLES);
    }

    @Test
    @DisplayName("받을 수 있는 배지는 기준과 진행을 보이고, 끈기는 준비 중이 되지 않는다")
    void notYetShowsProgress() {
        ContentScope scope = LearnerScopes.firstTwoStages();
        LearnerFacts facts =
                facts(
                        completed(
                                LearnerScopes.article(1, 1),
                                LearnerScopes.article(1, 2),
                                LearnerScopes.article(1, 3)),
                        new ExpLedger(
                                List.of(
                                        ExpEntry.articleCompleted(LearnerScopes.article(1, 1), AT),
                                        ExpEntry.articleCompleted(LearnerScopes.article(1, 2), AT),
                                        ExpEntry.articleCompleted(
                                                LearnerScopes.article(1, 3), AT))),
                        Map.of());

        assertThat(status(scope, facts, "read-5").progress()).isEqualTo(new Progress(3, 5));
        assertThat(status(scope, facts, "level-2").progress()).isEqualTo(new Progress(60, 100));
        assertThat(status(scope, facts, "stage-etf-market-basics").progress())
                .isEqualTo(new Progress(3, 4));
        BadgeStatus persistence = status(scope, facts, "persistence");
        assertThat(persistence.state()).isEqualTo(State.NOT_YET);
        assertThat(persistence.progress()).isNull();
    }

    @Test
    @DisplayName("끈기는 다시 풀기로 익힌 글이 하나 이상일 때 받는다")
    void persistenceFromRetry() {
        ContentScope scope = LearnerScopes.firstTwoStages();
        ArticleId article = LearnerScopes.article(1, 1);
        LearnerProgress retried =
                new LearnerProgress(article, 100, AT, AT, AT, AT, MasteredVia.RETRY);

        assertThat(
                        settle(scope, facts(Map.of(article, retried), ExpLedger.empty(), Map.of()))
                                .badges())
                .extracting(BadgeKey::value)
                .contains("persistence");
    }

    @Test
    @DisplayName("받은 배지는 콘텐츠가 바뀌어 기준을 다시 못 채워도 받음으로 남는다")
    void earnedStaysEarned() {
        ContentScope scope = LearnerScopes.firstTwoStages();
        LearnerFacts facts = facts(Map.of(), ExpLedger.empty(), Map.of(new BadgeKey("read-1"), AT));

        BadgeStatus status = status(scope, facts, "read-1");

        assertThat(status.state()).isEqualTo(State.EARNED);
        assertThat(status.earnedAt()).isEqualTo(AT);
    }

    private static Outcome settle(ContentScope scope, LearnerFacts facts) {
        return Settlement.settle(scope, facts, BadgeCatalog.of(scope.stages()), AT);
    }

    private static BadgeStatus status(ContentScope scope, LearnerFacts facts, String badgeKey) {
        BadgeDefinition definition =
                BadgeCatalog.of(scope.stages()).definitions().stream()
                        .filter(candidate -> candidate.key().value().equals(badgeKey))
                        .findFirst()
                        .orElseThrow();
        return Settlement.evaluate(definition, scope, facts, Settlement.level(scope, facts));
    }

    private static LearnerFacts facts(
            Map<ArticleId, LearnerProgress> progress,
            ExpLedger ledger,
            Map<BadgeKey, Instant> badges) {
        return new LearnerFacts(progress, List.of(), ledger, badges);
    }

    private static Map<ArticleId, LearnerProgress> completed(ArticleId... articles) {
        Map<ArticleId, LearnerProgress> progress = new HashMap<>();
        for (ArticleId article : articles) {
            progress.put(article, new LearnerProgress(article, 100, AT, AT, null, null, null));
        }
        return progress;
    }

    private static LearnerProgress mastered(ArticleId article) {
        return new LearnerProgress(article, 100, AT, AT, AT, AT, MasteredVia.FIRST_ANSWER);
    }
}
