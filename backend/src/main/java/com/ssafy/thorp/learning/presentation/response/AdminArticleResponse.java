package com.ssafy.thorp.learning.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.learning.application.result.AdminArticleResult;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.InstitutionNoteBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ListBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.MisconceptionBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.RichText;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.model.SourceRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * 관리 초안 하나의 {@code data}(learning-admin-api §4, 예시 {@code admin-article.json}·{@code
 * admin-article-v2.json}). 만들기(201)·저장(200) 응답도 같다.
 *
 * <p><b>형식마다 모양이 다르다.</b> V1 초안은 9월 판 모양(블록 {@code text}/{@code termId}, 용어 {@code sentence})에 2판
 * 필드를 더하고, V2 초안은 글자 조각과 세 문장 용어를 싣는다. 저장 요청과 같은 모양이라 화면은 받은 것을 고쳐 그대로 보낸다.
 *
 * <p>초안의 {@code term} 블록은 {@code termId}만 담는다. 문장은 {@code terms}에 있다. 초안의 언급 조각에는 {@code
 * owned}·{@code aliases}가 없다. 그것은 공개 응답과 미리 보기가 채운다. {@code example}·{@code
 * publishedRevisionNo}·{@code publishedEdition}은 없으면 명시적 null이다.
 */
public record AdminArticleResponse(
        String articleId,
        String status,
        int draftVersion,
        Draft draft,
        Integer publishedRevisionNo,
        String publishedEdition) {

    public static AdminArticleResponse from(AdminArticleResult result) {
        LearningArticle article = result.article();
        boolean v1 = article.format() == ArticleFormat.V1;
        return new AdminArticleResponse(
                article.articleId().value(),
                article.status().name(),
                article.draftVersion(),
                new Draft(
                        article.title(),
                        article.question(),
                        article.readingOrder(),
                        article.blocks().stream().map(block -> block(block, v1)).toList(),
                        article.example() == null
                                ? null
                                : new Example(
                                        article.example().sectors().stream()
                                                .map(
                                                        sector ->
                                                                new Sector(
                                                                        sector.code(),
                                                                        sector.weightTenths()))
                                                .toList()),
                        article.sources().stream()
                                .map(source -> new Source(source.label(), source.url()))
                                .toList(),
                        article.terms().stream().map(term -> term(term, v1)).toList(),
                        article.format().name(),
                        article.placement().stageKey().value(),
                        article.placement().position(),
                        article.quiz().stream().map(AdminArticleResponse::question).toList()),
                result.publishedRevisionNo(),
                result.publishedEdition() == null ? null : result.publishedEdition().name());
    }

    private static DraftBlock block(ArticleBlock block, boolean v1) {
        return switch (block) {
            case HeadingBlock heading -> new TextBlock("heading", heading.text());
            case ParagraphBlock paragraph ->
                    v1
                            ? new TextBlock("paragraph", paragraph.content().plainText())
                            : new ContentBlock("paragraph", runs(paragraph.content()));
            case TermBlock term -> new TermRefBlock(term.termId().value());
            case HypotheticalBlock hypothetical ->
                    v1
                            ? new TextBlock("hypothetical", hypothetical.content().plainText())
                            : new ContentBlock("hypothetical", runs(hypothetical.content()));
            case KeySummaryBlock summary ->
                    new KeySummary(
                            summary.lines().stream().map(AdminArticleResponse::runs).toList());
            case MisconceptionBlock misconception ->
                    new Misconception(
                            runs(misconception.wrong()), runs(misconception.correction()));
            case ListBlock list ->
                    new ListItems(
                            list.ordered(),
                            list.items().stream().map(AdminArticleResponse::runs).toList());
            case TableBlock table ->
                    new Table(
                            table.caption(),
                            table.columns(),
                            table.rows().stream()
                                    .map(
                                            row ->
                                                    row.stream()
                                                            .map(AdminArticleResponse::runs)
                                                            .toList())
                                    .toList());
            case DiagramBlock diagram ->
                    new Diagram(
                            diagram.kind().name().toLowerCase(Locale.ROOT),
                            diagram.title(),
                            diagram.unit(),
                            diagram.categories(),
                            diagram.series().stream()
                                    .map(series -> new Series(series.name(), series.values()))
                                    .toList(),
                            diagram.description());
            case InstitutionNoteBlock note ->
                    new InstitutionNote(
                            runs(note.content()),
                            note.asOf(),
                            note.source() == null ? null : sourceRef(note.source()));
        };
    }

    private static DraftTerm term(LearningTerm term, boolean v1) {
        return v1
                ? new V1Term(term.termId().value(), term.label(), term.sentence())
                : new V2Term(
                        term.termId().value(),
                        term.label(),
                        term.aliases(),
                        term.meaning(),
                        term.reading(),
                        term.caveat(),
                        term.transferFrom() == null ? null : term.transferFrom().value());
    }

    private static Question question(QuizQuestion question) {
        return new Question(
                question.questionId().value(),
                question.prompt(),
                question.choices().stream()
                        .map(
                                choice ->
                                        new Choice(
                                                choice.choiceId().value(),
                                                choice.text(),
                                                choice.correct()))
                        .toList(),
                question.explanation());
    }

    private static List<Run> runs(RichText text) {
        return text.runs().stream()
                .map(
                        run ->
                                switch (run) {
                                    case TextRun textRun -> new Run(textRun.text(), null, null);
                                    case TermMentionRun mention ->
                                            new Run(mention.text(), mention.termId().value(), null);
                                    case SourceRun source ->
                                            new Run(null, null, sourceRef(source.source()));
                                })
                .toList();
    }

    private static SourceRefBody sourceRef(SourceRef source) {
        return new SourceRefBody(source.label(), source.url());
    }

    /**
     * @param format {@code V1}·{@code V2}
     * @param stageKey 초안 배치의 단계
     * @param stagePosition 초안 배치의 단계 안 순서
     * @param quiz 확인 문제 초안. 식별자가 모두 채워져 있다. V1 초안은 비어 있다
     */
    public record Draft(
            String title,
            String question,
            int readingOrder,
            List<DraftBlock> blocks,
            Example example,
            List<Source> sources,
            List<DraftTerm> terms,
            String format,
            String stageKey,
            int stagePosition,
            List<Question> quiz) {}

    /** 초안 블록. 종류마다 다른 레코드이고 각각 {@code type}을 싣는다. */
    public sealed interface DraftBlock
            permits TextBlock,
                    TermRefBlock,
                    ContentBlock,
                    KeySummary,
                    Misconception,
                    ListItems,
                    Table,
                    Diagram,
                    InstitutionNote {}

    /** 소제목과 V1 문단·가상의 계산 예. 저장 요청의 블록과 같은 모양이다. */
    public record TextBlock(String type, String text) implements DraftBlock {}

    public record TermRefBlock(String type, String termId) implements DraftBlock {
        TermRefBlock(String termId) {
            this("term", termId);
        }
    }

    /** V2 문단·가상의 계산 예. */
    public record ContentBlock(String type, List<Run> content) implements DraftBlock {}

    public record KeySummary(String type, List<List<Run>> lines) implements DraftBlock {
        KeySummary(List<List<Run>> lines) {
            this("keySummary", lines);
        }
    }

    public record Misconception(String type, List<Run> wrong, List<Run> correction)
            implements DraftBlock {
        Misconception(List<Run> wrong, List<Run> correction) {
            this("misconception", wrong, correction);
        }
    }

    public record ListItems(String type, boolean ordered, List<List<Run>> items)
            implements DraftBlock {
        ListItems(boolean ordered, List<List<Run>> items) {
            this("list", ordered, items);
        }
    }

    public record Table(
            String type, String caption, List<String> columns, List<List<List<Run>>> rows)
            implements DraftBlock {
        Table(String caption, List<String> columns, List<List<List<Run>>> rows) {
            this("table", caption, columns, rows);
        }
    }

    public record Diagram(
            String type,
            String kind,
            String title,
            String unit,
            List<String> categories,
            List<Series> series,
            String description)
            implements DraftBlock {
        Diagram(
                String kind,
                String title,
                String unit,
                List<String> categories,
                List<Series> series,
                String description) {
            this("diagram", kind, title, unit, categories, series, description);
        }
    }

    public record Series(String name, List<BigDecimal> values) {}

    /** {@code asOf}·{@code source}는 초안에서 비어 있으면 명시적 null이다. */
    public record InstitutionNote(
            String type, List<Run> content, LocalDate asOf, SourceRefBody source)
            implements DraftBlock {
        InstitutionNote(List<Run> content, LocalDate asOf, SourceRefBody source) {
            this("institutionNote", content, asOf, source);
        }
    }

    /** 글자 조각. 종류에 해당하지 않는 키는 싣지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Run(String text, String term, SourceRefBody source) {}

    public record SourceRefBody(String label, String url) {}

    public record Example(List<Sector> sectors) {}

    public record Sector(String code, int weightTenths) {}

    /** {@code url}이 없으면 키를 싣지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String label, String url) {}

    /** 초안 용어. 형식마다 다른 레코드다. */
    public sealed interface DraftTerm permits V1Term, V2Term {}

    public record V1Term(String termId, String label, String sentence) implements DraftTerm {}

    /**
     * @param transferFrom 넘겨받는 중이면 지금 소유 글(R24). 아니면 명시적 null이다
     */
    public record V2Term(
            String termId,
            String label,
            List<String> aliases,
            String meaning,
            String reading,
            String caveat,
            String transferFrom)
            implements DraftTerm {}

    public record Question(
            String questionId, String prompt, List<Choice> choices, String explanation) {}

    public record Choice(String choiceId, String text, boolean correct) {}
}
