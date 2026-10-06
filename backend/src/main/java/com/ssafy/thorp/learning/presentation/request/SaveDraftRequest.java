package com.ssafy.thorp.learning.presentation.request;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.ssafy.thorp.learning.domain.model.ArticleBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.InstitutionNoteBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.KeySummaryBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.ListBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.MisconceptionBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.LearningArticle;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.Placement;
import com.ssafy.thorp.learning.domain.model.QuizChoice;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.RichText.RunKind;
import com.ssafy.thorp.learning.domain.model.SourceRef;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code PUT /admin/learning/articles/{articleId}} 요청(learning-admin-api §5). 초안을 통째로 바꾼다.
 *
 * <p>여기서 거절하는 것은 <b>저장할 수 없는 모양</b>이다(길이·개수·형식·중복, data-model §5~§7). 게시해도 되는 글인지(합계 100%, 용어 블록 짝,
 * 금지 표현, 최소 구성)는 검사하지 않는다. 초안은 쓰는 도중의 값을 담을 수 있어야 하고, 그 판정은 게시 전 검사가 한다.
 *
 * <p><b>V1·V2 모양을 함께 받는다.</b> 9월 판 화면의 요청(문단 {@code text}, 용어 {@code sentence}, {@code format} 없음)도
 * 그대로 받는다. 형식 하나에만 있는 요소를 어느 형식의 초안에 넣으려 했는지는 형식이 정해진 뒤 서비스가 가른다({@code SaveDraftCommand}). {@code
 * format}·{@code stageKey}·{@code stagePosition}·{@code quiz}는 없으면 지금 초안의 값을 그대로 둔다.
 *
 * <p>위반은 400 {@code INVALID_INPUT}이고 {@code error.details.fields}에 위치가 실린다. 알 수 없는 블록 {@code type}과
 * 글자 조각의 모르는 키처럼 본문을 읽지 못한 경우도 같다({@code GlobalExceptionHandler}).
 */
public record SaveDraftRequest(
        @NotNull Integer expectedDraftVersion,
        @NotNull @Size(max = LearningArticle.TITLE_MAX_LENGTH) String title,
        @NotNull @Size(max = LearningArticle.QUESTION_MAX_LENGTH) String question,
        @NotNull @Min(LearningArticle.MIN_READING_ORDER) @Max(LearningArticle.MAX_READING_ORDER)
                Integer readingOrder,
        @NotNull @Size(max = ArticleBlock.MAX_BLOCKS_V2) List<@NotNull @Valid Block> blocks,
        @Valid Example example,
        @NotNull List<@NotNull @Valid Source> sources,
        @NotNull @UniqueElementKeys(key = "termId", message = "같은 termId가 두 번 있습니다")
                List<@NotNull @Valid Term> terms,
        ArticleFormat format,
        @Size(min = StageKey.MIN_LENGTH, max = StageKey.MAX_LENGTH)
                @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                String stageKey,
        @Min(Placement.MIN_POSITION) @Max(Placement.MAX_POSITION) Integer stagePosition,
        @Size(max = QuizQuestion.MAX_QUESTIONS) List<@NotNull @Valid Question> quiz) {

    /**
     * 본문 블록. {@code type}으로 열 종류를 가른다. <b>알 수 없는 {@code type}은 역직렬화에서 실패해 400이 된다.</b> 모르는 블록을 조용히
     * 버리지 않는다(data-model §5-3, 원칙 VI).
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = KeySummary.class, name = "keySummary"),
        @JsonSubTypes.Type(value = Heading.class, name = "heading"),
        @JsonSubTypes.Type(value = Paragraph.class, name = "paragraph"),
        @JsonSubTypes.Type(value = TermBlock.class, name = "term"),
        @JsonSubTypes.Type(value = Hypothetical.class, name = "hypothetical"),
        @JsonSubTypes.Type(value = Misconception.class, name = "misconception"),
        @JsonSubTypes.Type(value = ListItems.class, name = "list"),
        @JsonSubTypes.Type(value = Table.class, name = "table"),
        @JsonSubTypes.Type(value = Diagram.class, name = "diagram"),
        @JsonSubTypes.Type(value = InstitutionNote.class, name = "institutionNote")
    })
    public sealed interface Block
            permits KeySummary,
                    Heading,
                    Paragraph,
                    TermBlock,
                    Hypothetical,
                    Misconception,
                    ListItems,
                    Table,
                    Diagram,
                    InstitutionNote {}

    /** 핵심 요약(V2). 줄 0~3개, 줄마다 1~120자. */
    public record KeySummary(
            @NotNull @Size(max = KeySummaryBlock.MAX_LINES)
                    List<
                                    @NotNull @RichTextField(max = KeySummaryBlock.LINE_MAX_LENGTH)
                                            List<
                                            @NotNull @Valid Run>>
                            lines)
            implements Block {}

    public record Heading(
            @NotNull @Size(min = 1, max = ArticleBlock.HeadingBlock.MAX_LENGTH) String text)
            implements Block {}

    /** 문단. V1은 {@code text}(1~2,000자), V2는 {@code content}(글자 조각, 1~2,000자·조각 40개 이하) 가운데 하나다. */
    public record Paragraph(
            @Size(min = 1, max = ArticleBlock.ParagraphBlock.MAX_LENGTH) String text,
            @RichTextField(
                            max = ArticleBlock.ParagraphBlock.MAX_LENGTH,
                            maxRuns = ArticleBlock.ParagraphBlock.MAX_RUNS)
                    List<@NotNull @Valid Run> content)
            implements Block {}

    /** 이 글에 없는 용어를 가리켜도 저장은 받는다. 게시 전 검사가 막는다(data-model §5-3). */
    public record TermBlock(
            @NotNull
                    @Size(min = TermId.MIN_LENGTH, max = TermId.MAX_LENGTH)
                    @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                    String termId)
            implements Block {}

    /** 가상의 계산 예. V1은 {@code text}, V2는 글자와 용어 언급만 담는 {@code content}다(1~1,000자). */
    public record Hypothetical(
            @Size(min = 1, max = ArticleBlock.HypotheticalBlock.MAX_LENGTH) String text,
            @RichTextField(
                            max = ArticleBlock.HypotheticalBlock.MAX_LENGTH,
                            kinds = {RunKind.TEXT, RunKind.TERM})
                    List<@NotNull @Valid Run> content)
            implements Block {}

    /** '자주 하는 오해'(V2). */
    public record Misconception(
            @NotNull @RichTextField(max = MisconceptionBlock.WRONG_MAX_LENGTH)
                    List<@NotNull @Valid Run> wrong,
            @NotNull @RichTextField(max = MisconceptionBlock.CORRECTION_MAX_LENGTH)
                    List<@NotNull @Valid Run> correction)
            implements Block {}

    /** 목록(V2). 항목은 글자와 출처 표시만 담는다(FR-026). */
    public record ListItems(
            @NotNull Boolean ordered,
            @NotNull @Size(min = 1, max = ListBlock.MAX_ITEMS)
                    List<
                                    @NotNull
                                            @RichTextField(
                                                    max = ListBlock.ITEM_MAX_LENGTH,
                                                    kinds = {RunKind.TEXT, RunKind.SOURCE})
                                            List<
                                            @NotNull @Valid Run>>
                            items)
            implements Block {}

    /** 비교 표(V2). 행마다 칸 수가 열 수와 같은지는 변환 단계가 본다. */
    public record Table(
            @NotNull @Size(max = TableBlock.CAPTION_MAX_LENGTH) String caption,
            @NotNull @Size(min = TableBlock.MIN_COLUMNS, max = TableBlock.MAX_COLUMNS)
                    List<@NotNull @Size(min = 1, max = TableBlock.COLUMN_MAX_LENGTH) String>
                            columns,
            @NotNull @Size(min = 1, max = TableBlock.MAX_ROWS)
                    List<
                                    @NotNull List<
                                            @NotNull
                                                    @RichTextField(
                                                            min = 0,
                                                            max = TableBlock.CELL_MAX_LENGTH,
                                                            kinds = {RunKind.TEXT, RunKind.SOURCE})
                                                    List<
                                                    @NotNull @Valid Run>>>
                            rows)
            implements Block {}

    /** 가상 수치 도식(V2). 계열마다 값 수가 칸 수와 같은지는 변환 단계가 본다. */
    public record Diagram(
            @NotNull @Pattern(regexp = "bar|line", message = "bar 또는 line이어야 합니다") String kind,
            @NotNull @Size(max = DiagramBlock.TITLE_MAX_LENGTH) String title,
            @NotNull @Size(max = DiagramBlock.UNIT_MAX_LENGTH) String unit,
            @NotNull @Size(min = DiagramBlock.MIN_CATEGORIES, max = DiagramBlock.MAX_CATEGORIES)
                    List<@NotNull @Size(min = 1, max = DiagramBlock.CATEGORY_MAX_LENGTH) String>
                            categories,
            @NotNull @Size(min = 1, max = DiagramBlock.MAX_SERIES)
                    List<@NotNull @Valid Series> series,
            @NotNull @Size(max = DiagramBlock.DESCRIPTION_MAX_LENGTH) String description)
            implements Block {}

    public record Series(
            @NotNull @Size(min = 1, max = DiagramBlock.Series.NAME_MAX_LENGTH) String name,
            @NotNull
                    List<@NotNull @DecimalMin("-1000000000") @DecimalMax("1000000000") BigDecimal>
                            values) {}

    /** 제도 안내(V2). 기준 시점과 출처는 초안에서 비어 있어도 된다. 게시 전 검사가 본다. */
    public record InstitutionNote(
            @NotNull @RichTextField(max = InstitutionNoteBlock.MAX_LENGTH)
                    List<@NotNull @Valid Run> content,
            LocalDate asOf,
            @Valid SourceRefRequest source)
            implements Block {}

    /**
     * 글자 조각 하나. {@code {text}}·{@code {term, text}}·{@code {source}} 가운데 하나다. 키 구성은 {@link
     * RunDeserializer}가 본문을 읽을 때 거절하고, 값은 {@link RichTextField}가 본다.
     */
    @JsonDeserialize(using = RunDeserializer.class)
    public record Run(String text, String term, SourceRefRequest source) {

        /** 조각 종류. 키 구성은 역직렬화가 이미 보장한다. */
        public RunKind kind() {
            if (source != null) {
                return RunKind.SOURCE;
            }
            return term == null ? RunKind.TEXT : RunKind.TERM;
        }
    }

    /** 출처 표시와 제도 안내의 출처. 이름은 저장할 때 비어 있어도 된다(게시 때 1자 이상). */
    public record SourceRefRequest(
            @NotNull @Size(max = SourceRef.LABEL_MAX_LENGTH) String label,
            @NotNull
                    @Size(max = SourceRef.URL_MAX_LENGTH)
                    @Pattern(regexp = SourceRef.URL_REGEX, message = "http 또는 https 주소여야 합니다")
                    String url) {}

    /**
     * 글자 조각의 키 구성을 엄격하게 읽는다. 키가 하나뿐인 세 모양 밖(모르는 키, 키 둘, 빈 조각)은 본문을 읽지 못한 것으로 거절한다. 전역 설정은 모르는 키를
     * 무시하므로 이 자리에서 따로 막는다. 모르는 조각을 조용히 버리면 문장 일부가 사라진 채 저장된다(원칙 VI).
     */
    public static final class RunDeserializer extends JsonDeserializer<Run> {

        @Override
        public Run deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() != JsonToken.START_OBJECT) {
                return context.reportInputMismatch(Run.class, "a run must be an object");
            }
            String text = null;
            String term = null;
            SourceRefRequest source = null;
            for (JsonToken token = parser.nextToken();
                    token == JsonToken.FIELD_NAME;
                    token = parser.nextToken()) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (name) {
                    case "text" -> text = string(parser, context, value);
                    case "term" -> term = string(parser, context, value);
                    case "source" -> source = sourceRef(parser, context, value);
                    default -> context.reportInputMismatch(Run.class, "unknown run key: %s", name);
                }
            }
            boolean textRun = text != null && source == null;
            boolean sourceRun = source != null && text == null && term == null;
            if (!textRun && !sourceRun) {
                return context.reportInputMismatch(
                        Run.class, "a run must be {text}, {term, text} or {source}");
            }
            return new Run(text, term, source);
        }

        private static String string(
                JsonParser parser, DeserializationContext context, JsonToken value)
                throws IOException {
            if (value != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(Run.class, "run values must be strings");
            }
            return parser.getText();
        }

        private static SourceRefRequest sourceRef(
                JsonParser parser, DeserializationContext context, JsonToken value)
                throws IOException {
            if (value != JsonToken.START_OBJECT) {
                return context.reportInputMismatch(Run.class, "a source must be an object");
            }
            String label = null;
            String url = null;
            for (JsonToken token = parser.nextToken();
                    token == JsonToken.FIELD_NAME;
                    token = parser.nextToken()) {
                String name = parser.currentName();
                JsonToken field = parser.nextToken();
                switch (name) {
                    case "label" -> label = string(parser, context, field);
                    case "url" -> url = string(parser, context, field);
                    default ->
                            context.reportInputMismatch(Run.class, "unknown source key: %s", name);
                }
            }
            if (label == null || url == null) {
                return context.reportInputMismatch(Run.class, "a source needs label and url");
            }
            return new SourceRefRequest(label, url);
        }
    }

    /** 예시. 합계와 섹터 수는 게시 전 검사가 본다. V2 글에서는 비울 수 있다. */
    public record Example(
            @NotNull
                    @Size(max = ExampleAllocation.MAX_SECTORS)
                    @UniqueElementKeys(key = "code", message = "같은 섹터 코드가 두 번 있습니다")
                    List<@NotNull @Valid Sector> sectors) {}

    /**
     * @param weightTenths 0.1%p 단위 정수이고 {@code 1000}이 100%다(002 selection-handoff 계약)
     */
    public record Sector(
            @NotNull
                    @Pattern(
                            regexp = ExampleAllocation.SectorWeight.CODE_REGEX,
                            message = "섹터 코드 형식이 아닙니다")
                    String code,
            @NotNull @Min(0) @Max(ExampleAllocation.FULL_WEIGHT_TENTHS) Integer weightTenths) {}

    /**
     * V1 글의 출처 목록. 길이 상한은 data-model이 정하지 않아 여기서 둔 값이다. V2 글은 출처를 글자 조각으로 담고 이 목록을 비운다.
     *
     * @param url 없을 수 있다. 있으면 {@code http}·{@code https}만 받는다
     */
    public record Source(
            @NotBlank @Size(max = 200) String label,
            @Size(max = 2000)
                    @Pattern(regexp = "^https?://\\S+$", message = "http 또는 https 주소여야 합니다")
                    String url) {}

    /**
     * 용어 설명. V1 용어는 {@code sentence}를, V2 용어는 {@code aliases}·{@code meaning}·{@code
     * reading}·{@code caveat}을 쓴다(data-model §7-1). 빈 문장은 게시 전 검사가 막는다.
     *
     * @param transferFrom 넘겨받기(R24). V2 용어만 둘 수 있고 지금 소유 글의 {@code articleId}다
     */
    public record Term(
            @NotNull
                    @Size(min = TermId.MIN_LENGTH, max = TermId.MAX_LENGTH)
                    @Pattern(regexp = ArticleId.REGEX, message = "소문자·숫자를 하이픈으로 이은 식별자여야 합니다")
                    String termId,
            @NotNull @Size(max = LearningTerm.LABEL_MAX_LENGTH) String label,
            @Size(max = LearningTerm.SENTENCE_MAX_LENGTH) String sentence,
            @Size(max = LearningTerm.MAX_ALIASES)
                    @UniqueElementValues(message = "같은 다른 이름이 두 번 있습니다")
                    List<@NotNull @Size(min = 1, max = LearningTerm.ALIAS_MAX_LENGTH) String>
                            aliases,
            @Size(max = LearningTerm.V2_SENTENCE_MAX_LENGTH) String meaning,
            @Size(max = LearningTerm.V2_SENTENCE_MAX_LENGTH) String reading,
            @Size(max = LearningTerm.V2_SENTENCE_MAX_LENGTH) String caveat,
            String transferFrom) {}

    /**
     * 확인 문제(V2, data-model §6-1). 새 문제는 {@code questionId}를 null로 보낸다. 지금 초안에 없는 식별자는 서비스가 거절한다.
     */
    public record Question(
            @Pattern(regexp = QuestionId.REGEX, message = "q와 숫자로 된 문제 식별자여야 합니다")
                    String questionId,
            @NotNull @Size(max = QuizQuestion.PROMPT_MAX_LENGTH) String prompt,
            @NotNull
                    @Size(max = QuizQuestion.MAX_CHOICES)
                    @UniqueElementKeys(key = "choiceId", message = "같은 보기 식별자가 두 번 있습니다")
                    List<@NotNull @Valid Choice> choices,
            @NotNull @Size(max = QuizQuestion.EXPLANATION_MAX_LENGTH) String explanation) {}

    /** 보기. 새 보기는 {@code choiceId}를 null로 보낸다. */
    public record Choice(
            @Pattern(regexp = ChoiceId.REGEX, message = "c와 숫자로 된 보기 식별자여야 합니다") String choiceId,
            @NotNull @Size(max = QuizChoice.TEXT_MAX_LENGTH) String text,
            @NotNull Boolean correct) {}
}
