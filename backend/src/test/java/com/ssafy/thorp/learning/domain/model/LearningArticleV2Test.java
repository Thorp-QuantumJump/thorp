package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.exception.FormatOutdatedException;
import com.ssafy.thorp.learning.domain.exception.StageNotReleasedException;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.LearningArticle.DraftContent;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 2판 글 초안: 형식별 모양, V2→V1 전환, 식별자 주기, 공개 배치, 게시 방식(research R15·R16·R21). */
class LearningArticleV2Test {

    private static final ArticleId ARTICLE = new ArticleId("volatility-is-not-loss");
    private static final Placement PLACEMENT = new Placement(new StageKey("returns-and-risk"), 4);
    private static final TermId VOLATILITY = new TermId("volatility");

    private static LearningArticle v2Draft() {
        return LearningArticle.newDraft(ARTICLE, "변동성은 손실일까?", 204, PLACEMENT);
    }

    private static DraftContent content(
            ArticleFormat format,
            List<ArticleBlock> blocks,
            List<ArticleSource> sources,
            List<LearningTerm> terms,
            List<QuizQuestion> quiz) {
        return new DraftContent(
                "t", "q", 204, format, PLACEMENT, blocks, null, sources, terms, quiz);
    }

    private static QuizQuestion newQuestion() {
        return new QuizQuestion(
                null,
                "질문",
                List.of(new QuizChoice(null, "가", true), new QuizChoice(null, "나", false)),
                "풀이");
    }

    @Test
    @DisplayName("새 글은 본문이 빈 V2 초안이고 공개 배치가 없다")
    void newDraftIsEmptyV2() {
        LearningArticle draft = v2Draft();

        assertThat(draft.format()).isEqualTo(ArticleFormat.V2);
        assertThat(draft.status()).isEqualTo(ArticleStatus.DRAFT);
        assertThat(draft.placement()).isEqualTo(PLACEMENT);
        assertThat(draft.publishedPlacement()).isNull();
        assertThat(draft.quiz()).isEmpty();
        assertThat(draft.quizSeq()).isZero();
    }

    @Test
    @DisplayName("V2 저장은 새 문제·보기에 식별자를 주고 카운터를 올린다")
    void saveAssignsQuizIds() {
        LearningArticle saved =
                v2Draft()
                        .saveDraft(
                                0,
                                content(
                                        ArticleFormat.V2,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(newQuestion())),
                                false);

        assertThat(saved.quiz().getFirst().questionId()).isEqualTo(new QuestionId("q1"));
        assertThat(saved.quiz().getFirst().choices())
                .extracting(QuizChoice::choiceId)
                .containsExactly(new ChoiceId("c2"), new ChoiceId("c3"));
        assertThat(saved.quizSeq()).isEqualTo(3);
        assertThat(saved.draftVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("LEGACY 판이 없으면 V2에서 V1로 되돌릴 수 없고, 있으면 된다")
    void revertingToV1NeedsLegacyRevision() {
        DraftContent v1 =
                content(
                        ArticleFormat.V1,
                        List.of(new ParagraphBlock("문단")),
                        List.of(),
                        List.of(),
                        List.of());

        assertThatThrownBy(() -> v2Draft().saveDraft(0, v1, false))
                .isInstanceOfSatisfying(
                        FormatOutdatedException.class,
                        e ->
                                assertThat(e.getErrorCode())
                                        .isEqualTo(ErrorCode.LEARNING_FORMAT_OUTDATED));
        assertThat(v2Draft().saveDraft(0, v1, true).format()).isEqualTo(ArticleFormat.V1);
    }

    @Test
    @DisplayName("V1 초안은 V2 블록·V2 용어·확인 문제를 담지 못한다")
    void v1RejectsV2Shapes() {
        LearningArticle draft = v2Draft();

        assertThatThrownBy(
                        () ->
                                draft.saveDraft(
                                        0,
                                        content(
                                                ArticleFormat.V1,
                                                List.of(new KeySummaryBlock(List.of())),
                                                List.of(),
                                                List.of(),
                                                List.of()),
                                        true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                draft.saveDraft(
                                        0,
                                        content(
                                                ArticleFormat.V1,
                                                List.of(),
                                                List.of(),
                                                List.of(
                                                        LearningTerm.v2(
                                                                VOLATILITY,
                                                                "변동성",
                                                                List.of(),
                                                                "뜻",
                                                                "",
                                                                "")),
                                                List.of()),
                                        true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                draft.saveDraft(
                                        0,
                                        content(
                                                ArticleFormat.V1,
                                                List.of(),
                                                List.of(),
                                                List.of(),
                                                List.of(newQuestion())),
                                        true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("V2 초안은 출처 목록과 설명 문장(sentence)을 담지 못한다")
    void v2RejectsV1Shapes() {
        LearningArticle draft = v2Draft();

        assertThatThrownBy(
                        () ->
                                draft.saveDraft(
                                        0,
                                        content(
                                                ArticleFormat.V2,
                                                List.of(),
                                                List.of(new ArticleSource("출처", null)),
                                                List.of(),
                                                List.of()),
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                draft.saveDraft(
                                        0,
                                        content(
                                                ArticleFormat.V2,
                                                List.of(),
                                                List.of(),
                                                List.of(new LearningTerm(VOLATILITY, "변동성", "문장")),
                                                List.of()),
                                        false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("같은 문제 판정 결과는 초안 번호를 올리지 않고 초안에 반영된다")
    void reconciledQuizKeepsDraftVersion() {
        LearningArticle saved =
                v2Draft()
                        .saveDraft(
                                0,
                                content(
                                        ArticleFormat.V2,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(newQuestion())),
                                false);
        QuizQuestion renamed =
                new QuizQuestion(
                        new QuestionId("q4"), "질문", saved.quiz().getFirst().choices(), "풀이");

        LearningArticle reconciled =
                saved.withReconciledQuiz(
                        new QuizIdentity.Reconciled(
                                List.of(renamed),
                                List.of(
                                        new QuizIdentity.Renamed(
                                                new QuestionId("q1"), new QuestionId("q4"))),
                                4));

        assertThat(reconciled.draftVersion()).isEqualTo(saved.draftVersion());
        assertThat(reconciled.quiz().getFirst().questionId()).isEqualTo(new QuestionId("q4"));
        assertThat(reconciled.quizSeq()).isEqualTo(4);
    }

    @Test
    @DisplayName("게시 방식: V2·공개됨은 CURRENT, V1·공개 전은 LEGACY, 나머지 둘은 409다 (data-model §10-1)")
    void publishModeTable() {
        assertThat(PublishMode.decide(ArticleFormat.V2, true)).isEqualTo(PublishMode.CURRENT);
        assertThat(PublishMode.decide(ArticleFormat.V2, true).edition()).isEqualTo(Edition.CURRENT);
        assertThat(PublishMode.decide(ArticleFormat.V1, false)).isEqualTo(PublishMode.LEGACY_FIX);
        assertThat(PublishMode.decide(ArticleFormat.V1, false).edition()).isEqualTo(Edition.LEGACY);
        assertThatThrownBy(() -> PublishMode.decide(ArticleFormat.V2, false))
                .isInstanceOf(StageNotReleasedException.class);
        assertThatThrownBy(() -> PublishMode.decide(ArticleFormat.V1, true))
                .isInstanceOf(FormatOutdatedException.class);
    }

    @Test
    @DisplayName("판은 V1이면 LEGACY, V2면 CURRENT이고 V1 판에는 확인 문제와 읽기 값이 없다")
    void snapshotAndRevisionInvariants() {
        ArticleSnapshot legacy =
                ArticleSnapshot.legacy(
                        ARTICLE, "t", "q", 1, List.of(), null, List.of(), List.of(), 0);
        ArticleSnapshot current =
                new ArticleSnapshot(
                        ArticleFormat.V2,
                        ARTICLE,
                        "t",
                        "q",
                        204,
                        List.of(),
                        null,
                        List.of(),
                        List.of(),
                        0,
                        List.of(),
                        new ReadingEstimate(2000, 4, 80));

        assertThatThrownBy(
                        () ->
                                new ArticleRevision(
                                        null,
                                        1,
                                        Edition.CURRENT,
                                        legacy,
                                        "v",
                                        "m",
                                        1L,
                                        Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ArticleSnapshot(
                                        ArticleFormat.V2,
                                        ARTICLE,
                                        "t",
                                        "q",
                                        204,
                                        List.of(),
                                        null,
                                        List.of(),
                                        List.of(),
                                        0,
                                        List.of(),
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new ArticleRevision(
                                        null,
                                        1,
                                        Edition.CURRENT,
                                        current,
                                        "v",
                                        ArticleRevision.NOT_CHECKED,
                                        1L,
                                        Instant.EPOCH)
                                .sectorMapVersion())
                .isEqualTo("NOT_CHECKED");
    }
}
