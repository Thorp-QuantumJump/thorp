package com.ssafy.thorp.learning.presentation.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ssafy.thorp.learning.application.result.LearningArticleResult;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.DiagramView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.HypotheticalView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.InstitutionNoteView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.KeySummaryView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.LegacyTermBlock;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ListView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.MentionRunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.MisconceptionView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.ParagraphView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.RunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.SourceRunView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TableView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TermView;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TextBlock;
import com.ssafy.thorp.learning.application.result.LearningArticleResult.TextRunView;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /learning/articles/{articleId}} 응답의 {@code data}(learning-public-api §3). 관리 미리
 * 보기(learning-admin-api §8)도 같은 모양에 {@code preview: true}를 더한다.
 *
 * <p>키 생략은 필드마다 정한다. 전역 {@code NON_NULL}을 쓰지 않는다(backend/CLAUDE.md). 9월 판 필드의 모양은 바꾸지 않는다(배포 순서
 * 호환): 9월 판 블록은 종류에 해당하지 않는 키를, 출처는 없는 {@code url}을 생략하고, {@code example.unavailableReason}과 {@code
 * sectors[].name}은 명시적 null로 싣는다. 2판 필드({@code number}·{@code minDwellSeconds}·{@code next}·{@code
 * stageArticleCount}·{@code example})는 값이 없으면 명시적 null이다. {@code preview}는 미리 보기에서만 싣는다.
 */
public record LearningArticleResponse(
        String articleId,
        String edition,
        String title,
        String question,
        int readingOrder,
        Stage stage,
        String number,
        int readingMinutes,
        Integer minDwellSeconds,
        List<Block> blocks,
        List<Question> quiz,
        Example example,
        List<Source> sources,
        Next next,
        Integer stageArticleCount,
        boolean hasUnreleasedStages,
        Instant publishedAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean preview) {

    public static LearningArticleResponse from(LearningArticleResult result) {
        LearningArticleResult.Example example = result.example();
        return new LearningArticleResponse(
                result.articleId(),
                result.edition(),
                result.title(),
                result.question(),
                result.readingOrder(),
                new Stage(
                        result.stage().stageKey(), result.stage().number(), result.stage().name()),
                result.number(),
                result.readingMinutes(),
                result.minDwellSeconds(),
                result.blocks().stream().map(LearningArticleResponse::block).toList(),
                result.quiz().stream()
                        .map(
                                question ->
                                        new Question(
                                                question.questionId(),
                                                question.prompt(),
                                                question.choices().stream()
                                                        .map(
                                                                choice ->
                                                                        new Choice(
                                                                                choice.choiceId(),
                                                                                choice.text()))
                                                        .toList(),
                                                question.correctChoiceId(),
                                                question.explanation()))
                        .toList(),
                example == null
                        ? null
                        : new Example(
                                example.available(),
                                example.sectors().stream()
                                        .map(
                                                sector ->
                                                        new ExampleSector(
                                                                sector.code(),
                                                                sector.name(),
                                                                sector.weightTenths()))
                                        .toList(),
                                example.missingSectorCodes(),
                                example.unavailableReason() == null
                                        ? null
                                        : example.unavailableReason().name()),
                result.sources().stream().map(LearningArticleResponse::source).toList(),
                result.next() == null
                        ? null
                        : new Next(
                                result.next().articleId(),
                                result.next().number(),
                                result.next().title()),
                result.stageArticleCount(),
                result.hasUnreleasedStages(),
                result.publishedAt(),
                result.preview() ? Boolean.TRUE : null);
    }

    private static Block block(LearningArticleResult.Block block) {
        return switch (block) {
            case TextBlock text -> new LegacyBlock(text.type(), text.text(), null, null, null);
            case LegacyTermBlock term ->
                    new LegacyBlock("term", null, term.termId(), term.label(), term.sentence());
            case KeySummaryView summary ->
                    new KeySummary(
                            "keySummary",
                            summary.lines().stream().map(LearningArticleResponse::runs).toList());
            case ParagraphView paragraph -> new Content("paragraph", runs(paragraph.content()));
            case TermView term ->
                    new Term(
                            "term",
                            term.termId(),
                            term.label(),
                            term.aliases(),
                            term.meaning(),
                            term.reading(),
                            term.caveat());
            case HypotheticalView hypothetical ->
                    new Content("hypothetical", runs(hypothetical.content()));
            case MisconceptionView misconception ->
                    new Misconception(
                            "misconception",
                            runs(misconception.wrong()),
                            runs(misconception.correction()));
            case ListView list ->
                    new ListItems(
                            "list",
                            list.ordered(),
                            list.items().stream().map(LearningArticleResponse::runs).toList());
            case TableView table ->
                    new Table(
                            "table",
                            table.caption(),
                            table.columns(),
                            table.rows().stream()
                                    .map(
                                            row ->
                                                    row.stream()
                                                            .map(LearningArticleResponse::runs)
                                                            .toList())
                                    .toList());
            case DiagramView diagram ->
                    new Diagram(
                            "diagram",
                            diagram.kind(),
                            diagram.title(),
                            diagram.unit(),
                            diagram.categories(),
                            diagram.series().stream()
                                    .map(series -> new Series(series.name(), series.values()))
                                    .toList(),
                            diagram.description());
            case InstitutionNoteView note ->
                    new InstitutionNote(
                            "institutionNote",
                            runs(note.content()),
                            note.asOf(),
                            note.source() == null
                                    ? null
                                    : new SourceRef(note.source().label(), note.source().url()));
        };
    }

    private static List<Run> runs(List<RunView> runs) {
        return runs.stream()
                .map(
                        run ->
                                switch (run) {
                                    case TextRunView text -> (Run) new TextRun(text.text());
                                    case MentionRunView mention ->
                                            new MentionRun(
                                                    mention.term(),
                                                    mention.text(),
                                                    mention.owned(),
                                                    mention.aliases());
                                    case SourceRunView source ->
                                            new SourceRun(
                                                    new SourceRef(
                                                            source.source().label(),
                                                            source.source().url()));
                                })
                .toList();
    }

    private static Source source(LearningArticleResult.Source source) {
        return new Source(source.label(), source.url());
    }

    /** 공개 배치의 단계(미리 보기는 초안 배치의 단계). */
    public record Stage(String stageKey, int number, String name) {}

    /** 읽는 순서상 다음 공개된 글. */
    public record Next(String articleId, String number, String title) {}

    // --- 블록 ---

    /** 본문 블록. 종류마다 다른 레코드이고 각각 {@code type}을 싣는다. */
    public sealed interface Block
            permits LegacyBlock,
                    KeySummary,
                    Content,
                    Term,
                    Misconception,
                    ListItems,
                    Table,
                    Diagram,
                    InstitutionNote {}

    /**
     * 9월 판 모양의 블록과 소제목. 종류에 해당하지 않는 키는 싣지 않는다. {@code heading}·{@code paragraph}·{@code
     * hypothetical}은 {@code text}만, 9월 판 {@code term}은 {@code termId}·{@code label}·{@code
     * sentence}만 싣는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LegacyBlock(
            String type, String text, String termId, String label, String sentence)
            implements Block {}

    public record KeySummary(String type, List<List<Run>> lines) implements Block {}

    /** V2 문단과 가상의 계산 예. */
    public record Content(String type, List<Run> content) implements Block {}

    /** V2 용어 블록. {@code meaning}은 도움말의 {@code sentence}와 같은 판의 같은 값이다(SC-003). */
    public record Term(
            String type,
            String termId,
            String label,
            List<String> aliases,
            String meaning,
            String reading,
            String caveat)
            implements Block {}

    public record Misconception(String type, List<Run> wrong, List<Run> correction)
            implements Block {}

    public record ListItems(String type, boolean ordered, List<List<Run>> items) implements Block {}

    public record Table(
            String type, String caption, List<String> columns, List<List<List<Run>>> rows)
            implements Block {}

    public record Diagram(
            String type,
            String kind,
            String title,
            String unit,
            List<String> categories,
            List<Series> series,
            String description)
            implements Block {}

    public record Series(String name, List<BigDecimal> values) {}

    /** 제도 안내. 공개 판에서는 {@code asOf}·{@code source}가 늘 있다. 미리 보기는 초안 값이라 명시적 null일 수 있다. */
    public record InstitutionNote(String type, List<Run> content, LocalDate asOf, SourceRef source)
            implements Block {}

    // --- 글자 조각 ---

    /** 글자 조각. 종류마다 키가 다르다. */
    public sealed interface Run permits TextRun, MentionRun, SourceRun {}

    public record TextRun(String text) implements Run {}

    /** 용어 언급. 서버가 {@code owned}·{@code aliases}를 채운다(R18). */
    public record MentionRun(String term, String text, boolean owned, List<String> aliases)
            implements Run {}

    public record SourceRun(SourceRef source) implements Run {}

    public record SourceRef(String label, String url) {}

    // --- 확인 문제 ---

    /** 정답과 풀이를 싣는다. 화면은 답한 뒤(로그인은 기록을 저장한 뒤)에만 보인다(R22). */
    public record Question(
            String questionId,
            String prompt,
            List<Choice> choices,
            String correctChoiceId,
            String explanation) {}

    public record Choice(String choiceId, String text) {}

    // --- 예시·출처 ---

    /** {@code unavailableReason}은 {@code available=true}여도 키를 싣고 값을 null로 둔다. */
    public record Example(
            boolean available,
            List<ExampleSector> sectors,
            List<String> missingSectorCodes,
            String unavailableReason) {}

    /** {@code name}은 대응표에 없는 코드면 null로 싣는다. */
    public record ExampleSector(String code, String name, int weightTenths) {}

    /** {@code url}이 없으면 키를 싣지 않는다. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Source(String label, String url) {}
}
