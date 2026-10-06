package com.ssafy.thorp.learning.presentation.controller;

import com.ssafy.thorp.learning.application.command.SaveDraftCommand;
import com.ssafy.thorp.learning.domain.exception.InvalidDraftException;
import com.ssafy.thorp.learning.domain.exception.InvalidDraftException.FieldViolation;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramKind;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HeadingBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.HypotheticalBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.InstitutionNoteBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ListBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.MisconceptionBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ParagraphBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.QuizChoice;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.RichText;
import com.ssafy.thorp.learning.domain.model.RichText.Run;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.model.SourceRef;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import com.ssafy.thorp.learning.presentation.request.SaveDraftRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * 초안 저장 요청 → {@link SaveDraftCommand}. 컨트롤러가 요청을 명령으로 바꾸는 자리다(backend/CLAUDE.md). 블록 종류가 열이라 따로 둔다.
 *
 * <p><b>형식 모양 표시</b>(research R16): 요청은 V1·V2 모양을 함께 받으므로, 형식 하나에만 있는 요소를 쓴 자리와 형식 하나에만 필요한 요소가 빠진
 * 자리를 형식별 위반 목록으로 넘긴다. 어느 목록을 쓸지는 형식을 정한 서비스가 고른다. 형식과 무관하게 틀린 모양(문단에 {@code text}와 {@code
 * content}가 함께 있음, 표의 칸 수 불일치 등)은 여기서 바로 거절한다.
 */
final class DraftCommands {

    private static final String V1_ONLY = "V2 초안에는 둘 수 없는 요소입니다";
    private static final String V2_ONLY = "V1 초안에는 둘 수 없는 요소입니다";

    private final List<FieldViolation> invalid = new ArrayList<>();
    private final List<FieldViolation> v1Violations = new ArrayList<>();
    private final List<FieldViolation> v2Violations = new ArrayList<>();

    private DraftCommands() {}

    /**
     * @throws InvalidDraftException 형식과 무관하게 저장할 수 없는 모양
     */
    static SaveDraftCommand toCommand(SaveDraftRequest request) {
        return new DraftCommands().convert(request);
    }

    private SaveDraftCommand convert(SaveDraftRequest request) {
        List<ArticleBlock> blocks = new ArrayList<>();
        for (int index = 0; index < request.blocks().size(); index++) {
            blocks.add(block(request.blocks().get(index), "blocks[" + index + "]"));
        }
        if (!request.sources().isEmpty()) {
            v1Only("sources");
        }
        List<LearningTerm> terms = new ArrayList<>();
        for (int index = 0; index < request.terms().size(); index++) {
            terms.add(term(request.terms().get(index), "terms[" + index + "]"));
        }
        List<QuizQuestion> quiz = request.quiz() == null ? null : quiz(request.quiz());
        if (quiz != null && !quiz.isEmpty()) {
            v2Only("quiz");
        }
        if (!invalid.isEmpty()) {
            throw new InvalidDraftException(invalid);
        }
        return new SaveDraftCommand(
                request.expectedDraftVersion(),
                request.title(),
                request.question(),
                request.readingOrder(),
                blocks,
                request.example() == null
                        ? null
                        : new ExampleAllocation(
                                request.example().sectors().stream()
                                        .map(
                                                sector ->
                                                        new ExampleAllocation.SectorWeight(
                                                                sector.code(),
                                                                sector.weightTenths()))
                                        .toList()),
                request.sources().stream()
                        .map(source -> new ArticleSource(source.label(), source.url()))
                        .toList(),
                terms,
                request.format(),
                request.stageKey() == null ? null : new StageKey(request.stageKey()),
                request.stagePosition(),
                quiz,
                v1Violations,
                v2Violations);
    }

    private ArticleBlock block(SaveDraftRequest.Block block, String field) {
        return switch (block) {
            case SaveDraftRequest.Heading heading -> new HeadingBlock(heading.text());
            case SaveDraftRequest.Paragraph paragraph ->
                    new ParagraphBlock(textOrContent(paragraph.text(), paragraph.content(), field));
            case SaveDraftRequest.TermBlock term -> new TermBlock(new TermId(term.termId()));
            case SaveDraftRequest.Hypothetical hypothetical ->
                    new HypotheticalBlock(
                            textOrContent(hypothetical.text(), hypothetical.content(), field));
            case SaveDraftRequest.KeySummary summary -> {
                v2Only(field);
                yield new KeySummaryBlock(
                        summary.lines().stream().map(DraftCommands::runs).toList());
            }
            case SaveDraftRequest.Misconception misconception -> {
                v2Only(field);
                yield new MisconceptionBlock(
                        runs(misconception.wrong()), runs(misconception.correction()));
            }
            case SaveDraftRequest.ListItems list -> {
                v2Only(field);
                yield new ListBlock(
                        list.ordered(), list.items().stream().map(DraftCommands::runs).toList());
            }
            case SaveDraftRequest.Table table -> {
                v2Only(field);
                yield table(table, field);
            }
            case SaveDraftRequest.Diagram diagram -> {
                v2Only(field);
                yield diagram(diagram, field);
            }
            case SaveDraftRequest.InstitutionNote note -> {
                v2Only(field);
                yield new InstitutionNoteBlock(
                        runs(note.content()),
                        note.asOf(),
                        note.source() == null
                                ? null
                                : new SourceRef(note.source().label(), note.source().url()));
            }
        };
    }

    /** V1은 {@code text}, V2는 {@code content}다. 둘 다 있거나 둘 다 없으면 형식과 무관하게 거절한다. */
    private RichText textOrContent(String text, List<SaveDraftRequest.Run> content, String field) {
        if ((text == null) == (content == null)) {
            invalid.add(new FieldViolation(field, "text와 content 가운데 하나만 있어야 합니다"));
            // 위반을 모아 한 번에 거절한다. 아래 값은 저장되지 않는 자리표시다.
            return RichText.text(" ");
        }
        if (text != null) {
            v1Only(field + ".text");
            return RichText.text(text);
        }
        v2Only(field + ".content");
        return runs(content);
    }

    private ArticleBlock table(SaveDraftRequest.Table table, String field) {
        List<List<RichText>> rows = new ArrayList<>();
        for (int row = 0; row < table.rows().size(); row++) {
            List<List<SaveDraftRequest.Run>> cells = table.rows().get(row);
            if (cells.size() != table.columns().size()) {
                invalid.add(
                        new FieldViolation(
                                field + ".rows[" + row + "]",
                                "칸 수가 열 수(" + table.columns().size() + ")와 같아야 합니다"));
                return new HeadingBlock(" ");
            }
            rows.add(cells.stream().map(DraftCommands::runs).toList());
        }
        return new TableBlock(table.caption(), table.columns(), rows);
    }

    private ArticleBlock diagram(SaveDraftRequest.Diagram diagram, String field) {
        List<DiagramBlock.Series> series = new ArrayList<>();
        for (int index = 0; index < diagram.series().size(); index++) {
            SaveDraftRequest.Series one = diagram.series().get(index);
            if (one.values().size() != diagram.categories().size()) {
                invalid.add(
                        new FieldViolation(
                                field + ".series[" + index + "].values",
                                "값 수가 칸 수(" + diagram.categories().size() + ")와 같아야 합니다"));
                return new HeadingBlock(" ");
            }
            series.add(new DiagramBlock.Series(one.name(), one.values()));
        }
        return new DiagramBlock(
                "bar".equals(diagram.kind()) ? DiagramKind.BAR : DiagramKind.LINE,
                diagram.title(),
                diagram.unit(),
                diagram.categories(),
                series,
                diagram.description());
    }

    /**
     * V1 용어는 설명 문장을, V2 용어는 다른 이름과 세 문장을 쓴다. 쓰지 않는 쪽은 도메인에 빈 값으로 넘긴다. 형식에 맞지 않는 자리는 형식별 위반으로 남긴다.
     */
    private LearningTerm term(SaveDraftRequest.Term term, String field) {
        if (term.transferFrom() != null) {
            invalid.add(new FieldViolation(field + ".transferFrom", "넘겨받기는 아직 받지 않습니다"));
        }
        if (term.sentence() != null) {
            v1Only(field + ".sentence");
        } else {
            v1Violations.add(new FieldViolation(field + ".sentence", "V1 용어에는 sentence가 필요합니다"));
        }
        v2Field(term.aliases(), field + ".aliases");
        v2Field(term.meaning(), field + ".meaning");
        v2Field(term.reading(), field + ".reading");
        v2Field(term.caveat(), field + ".caveat");
        return new LearningTerm(
                new TermId(term.termId()),
                term.label(),
                term.sentence() == null ? "" : term.sentence(),
                term.aliases() == null ? List.of() : term.aliases(),
                term.meaning() == null ? "" : term.meaning(),
                term.reading() == null ? "" : term.reading(),
                term.caveat() == null ? "" : term.caveat());
    }

    /** V2 용어의 필드. 있으면 V1에서 거절하고, 없으면 V2에서 거절한다. */
    private void v2Field(Object value, String field) {
        if (value != null) {
            v2Only(field);
        } else {
            String name = field.substring(field.lastIndexOf('.') + 1);
            v2Violations.add(new FieldViolation(field, "V2 용어에는 " + name + "가 필요합니다"));
        }
    }

    private static List<QuizQuestion> quiz(List<SaveDraftRequest.Question> questions) {
        return questions.stream()
                .map(
                        question ->
                                new QuizQuestion(
                                        question.questionId() == null
                                                ? null
                                                : new QuestionId(question.questionId()),
                                        question.prompt(),
                                        question.choices().stream()
                                                .map(
                                                        choice ->
                                                                new QuizChoice(
                                                                        choice.choiceId() == null
                                                                                ? null
                                                                                : new ChoiceId(
                                                                                        choice
                                                                                                .choiceId()),
                                                                        choice.text(),
                                                                        choice.correct()))
                                                .toList(),
                                        question.explanation()))
                .toList();
    }

    /** 키 구성과 값은 요청 단계에서 이미 검사했다({@code RunDeserializer}·{@code @RichTextField}). */
    private static RichText runs(List<SaveDraftRequest.Run> runs) {
        return new RichText(runs.stream().map(DraftCommands::run).toList());
    }

    private static Run run(SaveDraftRequest.Run run) {
        return switch (run.kind()) {
            case TEXT -> new TextRun(run.text());
            case TERM -> new TermMentionRun(new TermId(run.term()), run.text());
            case SOURCE -> new SourceRun(new SourceRef(run.source().label(), run.source().url()));
        };
    }

    /** V1 초안에만 둘 수 있는 요소를 썼다. V2 초안이면 거절한다. */
    private void v1Only(String field) {
        v2Violations.add(new FieldViolation(field, V1_ONLY));
    }

    /** V2 초안에만 둘 수 있는 요소를 썼다. V1 초안이면 거절한다. */
    private void v2Only(String field) {
        v1Violations.add(new FieldViolation(field, V2_ONLY));
    }
}
