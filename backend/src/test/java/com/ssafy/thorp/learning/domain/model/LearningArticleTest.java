package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.learning.domain.exception.DraftChangedException;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LearningArticleTest {

    private static final ArticleId ARTICLE = new ArticleId("returns-and-losses");
    private static final TermId MAX_DRAWDOWN = new TermId("max-drawdown");

    private static LearningArticle draft(int draftVersion) {
        return new LearningArticle(
                1L,
                ARTICLE,
                "수익과 손실",
                "",
                8,
                List.of(),
                null,
                List.of(),
                ArticleStatus.DRAFT,
                null,
                draftVersion,
                List.of(new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", "")));
    }

    private static LearningArticle saveSample(LearningArticle article, int expectedVersion) {
        return article.saveDraft(
                expectedVersion,
                "수익과 손실",
                "질문",
                8,
                List.of(new ParagraphBlock("문단"), new TermBlock(MAX_DRAWDOWN)),
                new ExampleAllocation(
                        List.of(
                                new SectorWeight("SEMICONDUCTOR", 500),
                                new SectorWeight("BATTERY", 500))),
                List.of(new ArticleSource("출처", null)),
                List.of(new LearningTerm(MAX_DRAWDOWN, "가장 크게 줄었던 폭", "문장")));
    }

    @Test
    @DisplayName("옛 초안 판 번호로 저장하면 DraftChangedException이고 details에 서버 값이 실린다")
    void saveDraftWithStaleVersionIsRejected() {
        LearningArticle article = draft(5);

        assertThatThrownBy(() -> saveSample(article, 4))
                .isInstanceOfSatisfying(
                        DraftChangedException.class,
                        e -> {
                            assertThat(e.getErrorCode())
                                    .isEqualTo(ErrorCode.LEARNING_DRAFT_CHANGED);
                            assertThat(e.getDetails()).containsEntry("currentDraftVersion", 5);
                        });
    }

    @Test
    @DisplayName("맞는 판 번호로 저장하면 내용을 통째로 바꾸고 draftVersion이 1 오른다")
    void saveDraftReplacesContentAndBumpsVersion() {
        LearningArticle saved = saveSample(draft(5), 5);

        assertThat(saved.draftVersion()).isEqualTo(6);
        assertThat(saved.question()).isEqualTo("질문");
        assertThat(saved.blocks()).hasSize(2);
        assertThat(saved.terms()).extracting(LearningTerm::sentence).containsExactly("문장");
        assertThat(saved.status()).isEqualTo(ArticleStatus.DRAFT);
    }

    @Test
    @DisplayName("publish는 PUBLISHED와 공개 판 대리키를 남기고, 옛 판 번호면 거절한다")
    void publishSetsRevision() {
        LearningArticle published = draft(3).publish(3, 42L);

        assertThat(published.status()).isEqualTo(ArticleStatus.PUBLISHED);
        assertThat(published.publishedRevisionId()).isEqualTo(42L);
        assertThat(published.draftVersion()).isEqualTo(3);
        assertThatThrownBy(() -> draft(3).publish(2, 42L))
                .isInstanceOf(DraftChangedException.class);
    }

    @Test
    @DisplayName("unpublish는 DRAFT와 null로 되돌린다")
    void unpublishClearsRevision() {
        LearningArticle unpublished = draft(3).publish(3, 42L).unpublish();

        assertThat(unpublished.status()).isEqualTo(ArticleStatus.DRAFT);
        assertThat(unpublished.publishedRevisionId()).isNull();
    }

    @Test
    @DisplayName("이미 DRAFT인 글의 unpublish는 아무것도 바꾸지 않는다")
    void unpublishOnDraftIsNoOp() {
        LearningArticle article = draft(3);

        assertThat(article.unpublish()).isSameAs(article);
    }

    @Test
    @DisplayName("PUBLISHED인데 공개 판이 없거나, DRAFT인데 공개 판이 있으면 만들 수 없다")
    void rejectsStatusRevisionMismatch() {
        assertThatThrownBy(
                        () ->
                                new LearningArticle(
                                        1L,
                                        ARTICLE,
                                        "t",
                                        "",
                                        1,
                                        List.of(),
                                        null,
                                        List.of(),
                                        ArticleStatus.PUBLISHED,
                                        null,
                                        0,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new LearningArticle(
                                        1L,
                                        ARTICLE,
                                        "t",
                                        "",
                                        1,
                                        List.of(),
                                        null,
                                        List.of(),
                                        ArticleStatus.DRAFT,
                                        7L,
                                        0,
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("블록 61개는 거절하고 60개는 받는다")
    void rejectsMoreThanSixtyBlocks() {
        List<ArticleBlock> sixty = Collections.nCopies(60, new ParagraphBlock("문단"));
        List<ArticleBlock> sixtyOne = Collections.nCopies(61, new ParagraphBlock("문단"));

        assertThat(saveWithBlocks(sixty).blocks()).hasSize(60);
        assertThatThrownBy(() -> saveWithBlocks(sixtyOne))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("한 글 안에서 같은 termId가 두 번 나오면 거절한다")
    void rejectsDuplicateTermsInArticle() {
        assertThatThrownBy(
                        () ->
                                draft(0).saveDraft(
                                                0,
                                                "t",
                                                "",
                                                1,
                                                List.of(),
                                                null,
                                                List.of(),
                                                List.of(
                                                        new LearningTerm(MAX_DRAWDOWN, "a", ""),
                                                        new LearningTerm(MAX_DRAWDOWN, "b", ""))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("블록 종류는 sealed라 네 가지뿐이다")
    void blockKindsAreSealed() {
        assertThat(ArticleBlock.class.isSealed()).isTrue();
        assertThat(ArticleBlock.class.getPermittedSubclasses()).hasSize(4);
    }

    @Test
    @DisplayName("블록 text의 길이 경계를 지킨다")
    void blockTextLengthBoundaries() {
        assertThatThrownBy(() -> new ParagraphBlock(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ParagraphBlock("가".repeat(2000)).text()).hasSize(2000);
        assertThatThrownBy(() -> new ParagraphBlock("가".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArticleBlock.HeadingBlock("가".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ArticleBlock.HypotheticalBlock("가".repeat(1001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LearningArticle saveWithBlocks(List<ArticleBlock> blocks) {
        return draft(0).saveDraft(0, "t", "", 1, blocks, null, List.of(), List.of());
    }
}
