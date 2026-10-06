package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock.Series;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramKind;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.InstitutionNoteBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ListBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.MisconceptionBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.RichTextField;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** V2 블록의 저장 시 검사와 형식별 허용(data-model §5-3, research R16·R17). */
class ArticleBlockV2Test {

    private static final RichText MENTION =
            new RichText(
                    List.of(new TextRun("가"), new TermMentionRun(new TermId("volatility"), "변동성")));
    private static final RichText WITH_SOURCE =
            new RichText(
                    List.of(
                            new TextRun("가"),
                            new SourceRun(new SourceRef("출처", "https://example.com"))));

    /** 글자 수만큼의 조각 하나. 0이면 조각이 없는 빈 칸이다(글자 조각은 1자 이상이다). */
    private static RichText text(int length) {
        return length == 0 ? new RichText(List.of()) : RichText.text("가".repeat(length));
    }

    @Test
    @DisplayName("V1은 9월 판 네 종류와 글자 조각 하나짜리 문단만 받고, V2는 열 종류를 모두 받는다")
    void allowedByFormat() {
        List<ArticleBlock> v1Blocks =
                List.of(
                        new HeadingBlock("소제목"),
                        new ParagraphBlock("문단"),
                        new TermBlock(new TermId("volatility")),
                        new HypotheticalBlock("가상"));
        List<ArticleBlock> v2Only =
                List.of(
                        new KeySummaryBlock(List.of(text(1))),
                        new ParagraphBlock(MENTION),
                        new HypotheticalBlock(MENTION),
                        new MisconceptionBlock(text(1), text(1)),
                        new ListBlock(false, List.of(text(1))),
                        new TableBlock("", List.of("가", "나"), List.of(List.of(text(1), text(0)))),
                        new DiagramBlock(
                                DiagramKind.BAR,
                                "",
                                "",
                                List.of("가", "나"),
                                List.of(new Series("계열", List.of(BigDecimal.ONE, BigDecimal.TEN))),
                                ""),
                        new InstitutionNoteBlock(text(1), null, null));

        assertThat(v1Blocks).allMatch(block -> ArticleBlock.allowedIn(block, ArticleFormat.V1));
        assertThat(v2Only).noneMatch(block -> ArticleBlock.allowedIn(block, ArticleFormat.V1));
        assertThat(v2Only).allMatch(block -> ArticleBlock.allowedIn(block, ArticleFormat.V2));
        assertThat(ArticleBlock.maxBlocks(ArticleFormat.V1)).isEqualTo(60);
        assertThat(ArticleBlock.maxBlocks(ArticleFormat.V2)).isEqualTo(80);
    }

    @Test
    @DisplayName("핵심 요약은 줄 0~3개, 줄마다 1~120자다")
    void keySummaryLimits() {
        assertThat(new KeySummaryBlock(List.of()).lines()).isEmpty();
        assertThat(new KeySummaryBlock(List.of(text(120), text(1), text(1))).lines()).hasSize(3);
        assertThatThrownBy(() -> new KeySummaryBlock(List.of(text(1), text(1), text(1), text(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new KeySummaryBlock(List.of(text(121))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("문단은 조각 40개 이하, 가상의 계산 예는 출처 표시를 담지 못한다")
    void paragraphAndHypotheticalRuns() {
        List<RichText.Run> forty = Collections.nCopies(40, new TextRun("가"));
        List<RichText.Run> fortyOne = Collections.nCopies(41, new TextRun("가"));

        assertThat(new ParagraphBlock(new RichText(forty)).content().runs()).hasSize(40);
        assertThatThrownBy(() -> new ParagraphBlock(new RichText(fortyOne)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HypotheticalBlock(WITH_SOURCE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new HypotheticalBlock(MENTION).content()).isEqualTo(MENTION);
    }

    @Test
    @DisplayName("오해는 틀린 생각 1~300자, 바로잡기 1~600자다")
    void misconceptionLimits() {
        assertThat(new MisconceptionBlock(text(300), text(600)).wrong().length()).isEqualTo(300);
        assertThatThrownBy(() -> new MisconceptionBlock(text(301), text(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MisconceptionBlock(text(1), text(601)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("목록은 항목 1~8개, 항목마다 1~300자이고 용어 언급을 담지 못한다(FR-026)")
    void listLimits() {
        assertThatThrownBy(() -> new ListBlock(true, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListBlock(true, Collections.nCopies(9, text(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListBlock(true, List.of(text(301))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ListBlock(true, List.of(MENTION)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ListBlock(true, List.of(WITH_SOURCE)).items()).hasSize(1);
    }

    @Test
    @DisplayName("표는 열 2~5개, 행 1~8개이고 행마다 칸 수가 열 수와 같다")
    void tableLimits() {
        List<String> columns = List.of("가", "나");
        assertThatThrownBy(() -> new TableBlock("", List.of("가"), List.of(List.of(text(1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableBlock("", columns, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableBlock("", columns, List.of(List.of(text(1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TableBlock(
                                        "",
                                        List.of("가".repeat(31), "나"),
                                        List.of(List.of(text(1), text(1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new TableBlock(
                                        "",
                                        columns,
                                        Collections.nCopies(9, List.of(text(1), text(1)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TableBlock("", columns, List.of(List.of(text(101), text(1)))))
                .isInstanceOf(IllegalArgumentException.class);

        TableBlock table = new TableBlock("제목", columns, List.of(List.of(text(1), text(0))));
        assertThat(table.richTextFields())
                .extracting(RichTextField::field)
                .containsExactly("rows[0][0]", "rows[0][1]");
    }

    @Test
    @DisplayName("도식은 칸 2~12개, 계열 1~3개, 계열마다 값 수가 칸 수와 같고 절댓값 10⁹ 이하다")
    void diagramLimits() {
        List<String> categories = List.of("가", "나");
        Series two = new Series("계열", List.of(BigDecimal.ONE, BigDecimal.TEN));

        assertThatThrownBy(() -> diagram(List.of("가"), List.of(two)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> diagram(categories, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> diagram(categories, List.of(two, two, two, two)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                diagram(
                                        categories,
                                        List.of(new Series("계열", List.of(BigDecimal.ONE)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new Series(
                                        "계열",
                                        List.of(new BigDecimal("-1000000000.5"), BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Series("계열", List.of(new BigDecimal("-1000000000"))).values()).hasSize(1);
        assertThat(diagram(categories, List.of(two)).richTextFields()).isEmpty();
    }

    @Test
    @DisplayName("표 제목은 100자 이하, 열은 5개 이하다")
    void tableCaptionAndColumnLimits() {
        List<List<RichText>> twoCells = List.of(List.of(text(1), text(1)));
        assertThat(new TableBlock("가".repeat(100), List.of("가", "나"), twoCells).caption())
                .hasSize(100);
        assertThatThrownBy(() -> new TableBlock("가".repeat(101), List.of("가", "나"), twoCells))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(
                        new TableBlock(
                                        "",
                                        List.of("가", "나", "다", "라", "마"),
                                        List.of(Collections.nCopies(5, text(1))))
                                .columns())
                .hasSize(5);
        assertThatThrownBy(
                        () ->
                                new TableBlock(
                                        "",
                                        List.of("가", "나", "다", "라", "마", "바"),
                                        List.of(Collections.nCopies(6, text(1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("도식 제목 60자·단위 10자·설명 300자·칸 이름 1~12자·칸 12개·계열 이름 1~20자가 상한이다")
    void diagramTextLimits() {
        List<String> categories = List.of("가", "나");
        Series two = new Series("계열", List.of(BigDecimal.ONE, BigDecimal.TEN));

        assertThat(
                        new DiagramBlock(
                                        DiagramKind.BAR,
                                        "가".repeat(60),
                                        "가".repeat(10),
                                        categories,
                                        List.of(two),
                                        "가".repeat(300))
                                .title())
                .hasSize(60);
        assertThatThrownBy(
                        () ->
                                new DiagramBlock(
                                        DiagramKind.BAR,
                                        "가".repeat(61),
                                        "",
                                        categories,
                                        List.of(two),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DiagramBlock(
                                        DiagramKind.BAR,
                                        "",
                                        "가".repeat(11),
                                        categories,
                                        List.of(two),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new DiagramBlock(
                                        DiagramKind.BAR,
                                        "",
                                        "",
                                        categories,
                                        List.of(two),
                                        "가".repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(diagram(List.of("가".repeat(12), "나"), List.of(two)).categories()).hasSize(2);
        assertThatThrownBy(() -> diagram(List.of("가".repeat(13), "나"), List.of(two)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> diagram(List.of("", "나"), List.of(two)))
                .isInstanceOf(IllegalArgumentException.class);
        List<String> twelve = Collections.nCopies(12, "가");
        Series twelveValues = new Series("계열", Collections.nCopies(12, BigDecimal.ONE));
        assertThat(diagram(twelve, List.of(twelveValues)).categories()).hasSize(12);
        assertThatThrownBy(
                        () ->
                                diagram(
                                        Collections.nCopies(13, "가"),
                                        List.of(
                                                new Series(
                                                        "계열",
                                                        Collections.nCopies(13, BigDecimal.ONE)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new Series("가".repeat(20), List.of(BigDecimal.ONE)).name()).hasSize(20);
        assertThatThrownBy(() -> new Series("가".repeat(21), List.of(BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Series("", List.of(BigDecimal.ONE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("제도 안내는 1~600자이고 기준 시점·출처는 초안에서 비어 있어도 된다")
    void institutionNoteLimits() {
        assertThat(new InstitutionNoteBlock(text(600), null, null).asOf()).isNull();
        assertThatThrownBy(() -> new InstitutionNoteBlock(text(601), LocalDate.EPOCH, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("글자 조각 필드의 위치는 data-model §8-4의 field 표기다")
    void richTextFieldNames() {
        assertThat(new KeySummaryBlock(List.of(text(1), text(1))).richTextFields())
                .extracting(RichTextField::field)
                .containsExactly("lines[0]", "lines[1]");
        assertThat(new MisconceptionBlock(text(1), text(1)).richTextFields())
                .extracting(RichTextField::field)
                .containsExactly("wrong", "correction");
        assertThat(new ListBlock(false, List.of(text(1), text(1))).richTextFields())
                .extracting(RichTextField::field)
                .containsExactly("items[0]", "items[1]");
        assertThat(new ParagraphBlock("가").richTextFields())
                .extracting(RichTextField::field)
                .containsExactly("content");
        assertThat(new HeadingBlock("가").richTextFields()).isEmpty();
    }

    private static DiagramBlock diagram(List<String> categories, List<Series> series) {
        return new DiagramBlock(DiagramKind.LINE, "", "", categories, series, "");
    }
}
