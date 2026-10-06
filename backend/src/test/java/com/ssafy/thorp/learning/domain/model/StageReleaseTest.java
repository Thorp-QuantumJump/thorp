package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.model.StageRelease.Blocker;
import com.ssafy.thorp.learning.domain.model.StageRelease.Tally;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 단계 공개의 전제 판정·변경 기록·넘기기 전이(data-model §3-7·§7-2·§10-3, research R23·R24·R27). */
class StageReleaseTest {

    private static final StageKey FIRST = new StageKey("etf-market-basics");
    private static final StageKey SECOND = new StageKey("returns-and-risk");
    private static final Instant AT = Instant.parse("2026-11-19T01:00:00Z");

    private static LearningStage stage(StageKey key, int number, boolean released) {
        return new LearningStage(
                (long) number,
                key,
                number,
                "단계 " + number,
                "입문",
                "s",
                released ? AT : null,
                released ? 7L : null);
    }

    private static LearningArticle draftIn(StageKey stageKey) {
        return LearningArticle.newDraft(
                new ArticleId("volatility-is-not-loss"),
                "변동성은 손실일까?",
                204,
                new Placement(stageKey, 4));
    }

    // --- 막는 것 ---

    @Test
    @DisplayName("1단계는 앞 단계 조건이 없고, 2단계는 1단계가 공개돼야 공개할 수 있다")
    void orderFollowsStageNumber() {
        LearningStage first = stage(FIRST, 1, false);
        LearningStage second = stage(SECOND, 2, false);
        List<LearningStage> stages = List.of(first, second);

        assertThat(StageRelease.releasable(first, stages)).isTrue();
        assertThat(StageRelease.releasable(second, stages)).isFalse();
        assertThat(StageRelease.blockers(second, stages, 4)).containsExactly(Blocker.ORDER);
        assertThat(StageRelease.blockers(first, stages, 4)).isEmpty();
    }

    @Test
    @DisplayName("이미 공개했거나 공개 집합이 비면 막는다. 여럿이면 판정 순서대로 모두 싣는다")
    void alreadyReleasedAndEmpty() {
        LearningStage first = stage(FIRST, 1, true);
        LearningStage second = stage(SECOND, 2, false);

        assertThat(StageRelease.blockers(first, List.of(first, second), 0))
                .containsExactly(Blocker.ALREADY_RELEASED, Blocker.EMPTY);
        assertThat(StageRelease.releasable(first, List.of(first, second))).isFalse();
        assertThat(StageRelease.releasable(second, List.of(first, second))).isTrue();
    }

    // --- 공개 집합과 글별 항목 ---

    @Test
    @DisplayName("초안 배치나 공개 배치가 이 단계면 공개 집합이다. 공개 배치만 이 단계인 글은 PLACEMENT, V1 초안은 FORMAT이다")
    void releaseSetAndArticleItems() {
        LearningArticle v2InStage = draftIn(SECOND);
        LearningArticle elsewhere = draftIn(FIRST);
        LearningArticle movedAway = elsewhere.publish(0, 10L);

        assertThat(StageRelease.inReleaseSet(v2InStage, SECOND)).isTrue();
        assertThat(StageRelease.inReleaseSet(elsewhere, SECOND)).isFalse();
        assertThat(StageRelease.articleFailures(v2InStage, SECOND)).isEmpty();
        assertThat(StageRelease.articleFailures(movedAway, SECOND))
                .extracting(PublishCheckFailure::check, failure -> failure.location().part())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                Check.PLACEMENT, CheckLocation.Part.ARTICLE));

        LearningArticle v1 =
                new LearningArticle(
                        null,
                        new ArticleId("returns-and-losses"),
                        "수익과 손실",
                        "",
                        8,
                        ArticleFormat.V1,
                        new Placement(SECOND, 1),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        ArticleStatus.DRAFT,
                        null,
                        0,
                        List.of(),
                        List.of(),
                        0,
                        null);
        assertThat(StageRelease.articleFailures(v1, SECOND))
                .extracting(PublishCheckFailure::check)
                .containsExactly(Check.FORMAT);
    }

    @Test
    @DisplayName("편수는 처음 공개되는 글만 세고, LEGACY 판이 있던 글은 다시 쓴 글로 그때 제목과 함께 센다")
    void tallyCountsFirstReleasesOnly() {
        Tally tally =
                Tally.empty()
                        .add(true, true, "수익과 손실")
                        .add(true, false, "같은 수익률인데 왜 느낌이 다를까?")
                        .add(true, false, "변동성은 손실일까?")
                        .add(false, true, "옮겨 온 글");

        assertThat(tally.newCount()).isEqualTo(2);
        assertThat(tally.rewrittenTitles()).containsExactly("수익과 손실");
    }

    private static LearningArticle v2With(String articleId, LearningTerm... terms) {
        return new LearningArticle(
                null,
                new ArticleId(articleId),
                articleId,
                "",
                204,
                ArticleFormat.V2,
                new Placement(SECOND, 1),
                null,
                List.of(),
                null,
                List.of(),
                ArticleStatus.DRAFT,
                null,
                1,
                List.of(terms),
                List.of(),
                0,
                null);
    }

    private static LearningTerm owned(String termId) {
        return LearningTerm.v2(new TermId(termId), termId, List.of(), "뜻", "읽는 법", "한계");
    }

    private static LearningTerm incoming(String termId, String from) {
        return new LearningTerm(
                new TermId(termId), termId, "", List.of(), "뜻", "읽는 법", "한계", new ArticleId(from));
    }

    @Test
    @DisplayName("같은 공개의 옛 소유 글이 넘겨줄 용어를 초안에 두면 찾고, 이미 뺐거나 옛 글이 공개 밖이면 찾지 않는다")
    void termsKeptByGiversInTheSameRelease() {
        LearningArticle giver =
                v2With("returns-and-losses", owned("max-drawdown"), owned("net-total-return"));
        LearningArticle receiver =
                v2With("recovery-period", incoming("max-drawdown", "returns-and-losses"));
        LearningArticle dropped = v2With("returns-and-losses", owned("net-total-return"));
        LearningArticle outsider =
                v2With("volatility-is-not-loss", incoming("volatility", "inverse-volatility"));

        assertThat(StageRelease.termsKeptByGivers(List.of(giver, receiver, outsider)))
                .containsExactly(
                        java.util.Map.entry(
                                new ArticleId("returns-and-losses"),
                                java.util.Map.of(
                                        new TermId("max-drawdown"),
                                        new ArticleId("recovery-period"))));
        assertThat(StageRelease.termsKeptByGivers(List.of(dropped, receiver, outsider))).isEmpty();
        assertThat(
                        StageRelease.keptTermFailure(
                                        new TermId("max-drawdown"),
                                        new ArticleId("recovery-period"))
                                .check())
                .isEqualTo(Check.TERM_TRANSFER);
    }

    @Test
    @DisplayName("두 글이 서로의 용어를 넘겨받으며 둘 다 남겨 두면 두 글 모두 찾는다")
    void crossingTransfersAreBothFound() {
        LearningArticle first =
                v2With(
                        "returns-and-losses",
                        owned("max-drawdown"),
                        incoming("path-dependence", "same-return-different-feel"));
        LearningArticle second =
                v2With(
                        "same-return-different-feel",
                        owned("path-dependence"),
                        incoming("max-drawdown", "returns-and-losses"));

        assertThat(StageRelease.termsKeptByGivers(List.of(first, second)).keySet())
                .containsExactlyInAnyOrder(
                        new ArticleId("returns-and-losses"),
                        new ArticleId("same-return-different-feel"));
    }

    @Test
    @DisplayName("넘겨주는 글을 넘겨받는 글보다 먼저 게시하고, 그 밖에는 공개 집합 순서다")
    void publishOrderPutsGiversFirst() {
        LearningArticle receiver =
                v2With("recovery-period", incoming("max-drawdown", "returns-and-losses"));
        LearningArticle giver = v2With("returns-and-losses", owned("net-total-return"));
        LearningArticle other = v2With("same-return-different-feel", owned("path-dependence"));

        assertThat(StageRelease.publishOrder(List.of(receiver, other, giver)))
                .extracting(article -> article.articleId().value())
                .containsExactly(
                        "same-return-different-feel", "returns-and-losses", "recovery-period");
    }

    @Test
    @DisplayName("고리는 고리 안의 글 하나로 끊고, 고리의 글에게서 넘겨받는 고리 밖의 글은 계속 그 글 다음이다")
    void publishOrderBreaksACycleInsideTheCycle() {
        // A와 B는 서로 넘겨줄 용어를 이미 초안에서 뺀 채 맞바꾼다(판정을 통과하는 고리). C는 A에게서 넘겨받는다.
        LearningArticle a =
                v2With(
                        "returns-and-losses",
                        incoming("path-dependence", "same-return-different-feel"));
        LearningArticle b =
                v2With(
                        "same-return-different-feel",
                        incoming("max-drawdown", "returns-and-losses"));
        LearningArticle c =
                v2With("recovery-period", incoming("net-total-return", "returns-and-losses"));

        List<String> order =
                StageRelease.publishOrder(List.of(c, a, b)).stream()
                        .map(article -> article.articleId().value())
                        .toList();

        assertThat(order)
                .hasSize(3)
                .first()
                .isIn("returns-and-losses", "same-return-different-feel");
        assertThat(order.indexOf("recovery-period"))
                .as("고리 밖의 받는 글은 옛 소유 글 다음이다")
                .isGreaterThan(order.indexOf("returns-and-losses"));
    }

    // --- 변경 기록 ---

    @Test
    @DisplayName("변경 기록의 entryKey는 stage:{stageKey}·article:{articleId}이고, 글 추가는 새 글 하나다")
    void releaseLogEntryKeys() {
        ReleaseLogEntry stage = ReleaseLogEntry.stageReleased(SECOND, 3, List.of("수익과 손실"), 7L, AT);
        ReleaseLogEntry article =
                ReleaseLogEntry.articleAdded(
                        FIRST, new ArticleId("etf-fees"), "ETF 보수는 어디로 빠져나갈까?", 7L, AT);

        assertThat(stage.entryKey()).isEqualTo("stage:returns-and-risk");
        assertThat(stage.rewrittenCount()).isEqualTo(1);
        assertThat(stage.articleId()).isNull();
        assertThat(article.entryKey()).isEqualTo("article:etf-fees");
        assertThat(article.newCount()).isEqualTo(1);
        assertThat(article.rewrittenCount()).isZero();
    }

    @Test
    @DisplayName("단계 줄이 글을 가리키거나 글 줄에 글이 없으면 만들 수 없다")
    void releaseLogEntryInvariants() {
        assertThatThrownBy(
                        () ->
                                new ReleaseLogEntry(
                                        "stage:returns-and-risk",
                                        ReleaseLogEntry.Kind.STAGE_RELEASED,
                                        SECOND,
                                        new ArticleId("etf-fees"),
                                        3,
                                        0,
                                        List.of(),
                                        null,
                                        7L,
                                        AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ReleaseLogEntry(
                                        "article:etf-fees",
                                        ReleaseLogEntry.Kind.ARTICLE_ADDED,
                                        FIRST,
                                        null,
                                        1,
                                        0,
                                        List.of(),
                                        "t",
                                        7L,
                                        AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ReleaseLogEntry(
                                        "stage:returns-and-risk",
                                        ReleaseLogEntry.Kind.STAGE_RELEASED,
                                        SECOND,
                                        null,
                                        3,
                                        2,
                                        List.of("하나"),
                                        null,
                                        7L,
                                        AT))
                .as("다시 쓴 글 수와 제목 수가 다르다")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 단계와 글의 전이 ---

    @Test
    @DisplayName("단계 공개는 한 번뿐이고 공개일과 공개한 계정을 함께 쓴다")
    void stageReleasesOnce() {
        LearningStage released = stage(SECOND, 2, false).release(AT, 7L);

        assertThat(released.released()).isTrue();
        assertThat(released.releasedByUserId()).isEqualTo(7L);
        assertThatThrownBy(() -> released.release(AT.plusSeconds(1), 8L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("첫 공개 시각은 한 번 쓴 뒤 바뀌지 않고, 게시 취소·다시 게시에도 남는다")
    void firstReleasedAtIsWrittenOnce() {
        LearningArticle published = draftIn(SECOND).publish(0, 10L).firstReleased(AT);

        LearningArticle again =
                published.unpublish().publish(0, 11L).firstReleased(AT.plusSeconds(60));

        assertThat(published.firstReleasedAt()).isEqualTo(AT);
        assertThat(again.firstReleasedAt()).isEqualTo(AT);
    }

    @Test
    @DisplayName("넘겨받기를 마치면 transferFrom이 비고 초안 번호는 그대로다. 넘겨준 글은 그 용어를 빼고 초안 번호가 한 번만 오른다")
    void transferTransitions() {
        TermId volatility = new TermId("volatility");
        TermId other = new TermId("inverse-volatility");
        ArticleId oldOwner = new ArticleId("inverse-volatility");
        LearningTerm incoming =
                new LearningTerm(volatility, "변동성", "", List.of(), "뜻", "읽는 법", "한계", oldOwner);
        LearningArticle receiver =
                draftIn(SECOND)
                        .saveDraft(
                                0,
                                new LearningArticle.DraftContent(
                                        "변동성은 손실일까?",
                                        "",
                                        204,
                                        ArticleFormat.V2,
                                        new Placement(SECOND, 4),
                                        List.of(),
                                        null,
                                        List.of(),
                                        List.of(incoming),
                                        List.of()),
                                false);

        LearningArticle completed = receiver.withTransfersCompleted();

        assertThat(receiver.terms().getFirst().transferring()).isTrue();
        assertThat(completed.terms().getFirst().transferFrom()).isNull();
        assertThat(completed.draftVersion()).isEqualTo(receiver.draftVersion());

        LearningArticle giver =
                new LearningArticle(
                        null,
                        oldOwner,
                        "변동성에 따라 나누기",
                        "",
                        3,
                        ArticleFormat.V1,
                        new Placement(new StageKey("allocation-rules"), 3),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        ArticleStatus.DRAFT,
                        null,
                        4,
                        List.of(
                                new LearningTerm(volatility, "변동성", ""),
                                new LearningTerm(other, "변동성에 따라 나누기", "")),
                        List.of(),
                        0,
                        null);
        LearningArticle afterTransfer = giver.termsTransferredAway(List.of(volatility));

        assertThat(afterTransfer.terms()).extracting(LearningTerm::termId).containsExactly(other);
        assertThat(afterTransfer.draftVersion()).isEqualTo(5);
    }

    @Test
    @DisplayName("V1 용어는 넘겨받을 수 없다")
    void v1TermCannotTransfer() {
        LearningTerm term =
                new LearningTerm(
                        new TermId("volatility"),
                        "변동성",
                        "문장",
                        List.of(),
                        "",
                        "",
                        "",
                        new ArticleId("inverse-volatility"));

        assertThat(term.fitsV1()).isFalse();
    }
}
