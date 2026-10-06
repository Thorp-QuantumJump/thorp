package com.ssafy.thorp.learning.infrastructure.persistence;

import com.ssafy.thorp.learning.domain.model.ArticleBlock;
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
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TableBlock;
import com.ssafy.thorp.learning.domain.model.ArticleBlock.TermBlock;
import com.ssafy.thorp.learning.domain.model.ArticleFormat;
import com.ssafy.thorp.learning.domain.model.ArticleSnapshot;
import com.ssafy.thorp.learning.domain.model.ArticleSource;
import com.ssafy.thorp.learning.domain.model.LearningTerm;
import com.ssafy.thorp.learning.domain.model.QuizChoice;
import com.ssafy.thorp.learning.domain.model.QuizQuestion;
import com.ssafy.thorp.learning.domain.model.ReadingEstimate;
import com.ssafy.thorp.learning.domain.model.RichText;
import com.ssafy.thorp.learning.domain.model.RichText.Run;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.model.SourceRef;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * JSONB 컬럼({@code body_blocks}·{@code example}·{@code sources}·{@code quiz}·{@code snapshot}·용어의
 * {@code aliases})에 저장하는 모양과 도메인 사이 변환.
 *
 * <p>도메인 모델은 Jackson에 의존하지 않으므로(ADR-0001) 저장 모양을 여기 따로 둔다. {@code sealed} 블록을 한 레코드({@link
 * BlockJson})의 {@code type}으로 펼치는 이유도 같다. 저장 키 이름은 data-model §4·§5·§6과 1:1이다.
 *
 * <p><b>형식마다 모양이 다르다.</b> V1 글의 문단과 가상의 계산 예는 9월 판처럼 {@code text}로, V2 글은 글자 조각 배열 {@code content}로
 * 쓴다. V1 판은 지금 모양 그대로 읽고 쓴다. {@code format} 키가 없는 판은 V1 판이다. 2판 전에 쓰인 판에는 이 키가 없으므로 이것은 판 형식의 판별
 * 규칙이며 빠진 필수 값을 채우는 것이 아니다(data-model §4-1). 초안은 글 행의 {@code format} 컬럼이 형식을 정한다.
 *
 * <p><b>이 JSON은 메시지 ObjectMapper로 직렬화된다</b>({@code MessageJsonPersistenceConfig}). null 필드는 키가
 * 생략되므로 읽는 쪽은 <b>키 없음을 null로</b> 받는다. 도식 값({@code BigDecimal})은 문자열로 저장된다.
 *
 * <p><b>선택 값과 필수 값을 가른다.</b> 선택 값(판의 {@code example}, 출처의 {@code url}, 제도 안내의 {@code asOf}·{@code
 * source})은 null 그대로 둔다. 필수 값(순서·비중·초안 판 번호·본문·출처·용어 목록, 블록 종류와 형식에 따른 필드, V2 판의 확인 문제와 읽기 값)이 없으면
 * 0이나 빈 목록으로 채우지 않고 {@link IllegalStateException}으로 실패시킨다. 저장 경로는 필수 값을 늘 쓰므로 빠졌다면 저장 데이터가 깨진 것이고,
 * 채워 넣으면 순서 0인 글이나 본문 없는 글을 공개하게 된다(원칙 VI). 숫자를 원시 타입이 아니라 래퍼로 받는 이유도 같다.
 *
 * <p><b>키는 있는데 값이 도메인 규칙을 어기면</b>(형식이 틀린 식별자, 한도를 넘는 길이·비중) 도메인 생성자의 {@link
 * IllegalArgumentException}을 키 이름을 담은 {@link IllegalStateException}으로 감싼다({@link #storedValue}). 요청
 * 입력이 아니라 저장 데이터의 문제라는 것을 예외 종류로 드러내려는 것이다.
 */
public final class LearningContentJson {

    static final String KEY_SUMMARY = "keySummary";
    static final String HEADING = "heading";
    static final String PARAGRAPH = "paragraph";
    static final String TERM = "term";
    static final String HYPOTHETICAL = "hypothetical";
    static final String MISCONCEPTION = "misconception";
    static final String LIST = "list";
    static final String TABLE = "table";
    static final String DIAGRAM = "diagram";
    static final String INSTITUTION_NOTE = "institutionNote";

    private LearningContentJson() {}

    /** 글자 조각. 종류마다 키가 하나({@code text}만, {@code term}+{@code text}, {@code source})다. */
    public record RunJson(String text, String term, SourceJson source) {}

    /**
     * 본문 블록. 종류와 형식에 해당하는 키만 있다. V1의 {@code paragraph}·{@code hypothetical}은 {@code text}, V2는
     * {@code content}다.
     */
    public record BlockJson(
            String type,
            String text,
            String termId,
            List<RunJson> content,
            List<List<RunJson>> lines,
            List<RunJson> wrong,
            List<RunJson> correction,
            Boolean ordered,
            List<List<RunJson>> items,
            String caption,
            List<String> columns,
            List<List<List<RunJson>>> rows,
            String kind,
            String title,
            String unit,
            List<String> categories,
            List<SeriesJson> series,
            String description,
            String asOf,
            SourceJson source) {}

    /** 도식 계열 하나. */
    public record SeriesJson(String name, List<BigDecimal> values) {}

    public record SectorWeightJson(String code, Integer weightTenths) {}

    public record ExampleJson(List<SectorWeightJson> sectors) {}

    public record SourceJson(String label, String url) {}

    /**
     * 용어. V1 판은 {@code sentence}, V2 판은 {@code aliases}·{@code meaning}·{@code reading}·{@code
     * caveat}을 쓴다.
     */
    public record TermJson(
            String termId,
            String label,
            String sentence,
            List<String> aliases,
            String meaning,
            String reading,
            String caveat) {}

    /**
     * 확인 문제. 초안({@code learning_article.quiz})은 보기마다 {@code correct}를 싣고, 판은 {@code
     * correctChoiceId}를 싣는다 (data-model §4-2·§6-1).
     */
    public record QuizJson(
            String questionId,
            String prompt,
            List<ChoiceJson> choices,
            String correctChoiceId,
            String explanation) {}

    public record ChoiceJson(String choiceId, String text, Boolean correct) {}

    /** V2 판의 읽기 값(R20). */
    public record ReadingJson(Integer bodyChars, Integer readingMinutes, Integer minDwellSeconds) {}

    /** 판 내용(data-model §4). V1 판에는 {@code format}·{@code quiz}·{@code reading}이 없다. */
    public record SnapshotJson(
            String format,
            String articleId,
            String title,
            String question,
            Integer readingOrder,
            List<BlockJson> bodyBlocks,
            ExampleJson example,
            List<SourceJson> sources,
            List<TermJson> terms,
            Integer draftVersion,
            List<QuizJson> quiz,
            ReadingJson reading) {}

    /**
     * 저장된 JSON에서 필수 키의 값을 꺼낸다.
     *
     * @throws IllegalStateException 키가 없거나 null일 때. 메시지에 키 이름을 담는다
     */
    static <T> T required(T value, String key) {
        if (value == null) {
            throw new IllegalStateException("stored learning JSON is missing required key: " + key);
        }
        return value;
    }

    /**
     * 저장된 값으로 도메인 객체를 만든다. 도메인 규칙 위반({@link IllegalArgumentException})은 키 이름을 담은 {@link
     * IllegalStateException}으로 바꾸고 원인을 남긴다. 키 누락({@link #required})의 {@code IllegalStateException}은
     * 그대로 통과한다.
     */
    static <T> T storedValue(String key, Supplier<T> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "stored learning JSON has an invalid value at key: " + key, invalid);
        }
    }

    // --- 블록 ---

    static List<BlockJson> blocksToJson(List<ArticleBlock> blocks, ArticleFormat format) {
        return blocks.stream().map(block -> blockToJson(block, format)).toList();
    }

    static List<ArticleBlock> blocksFromJson(List<BlockJson> blocks, ArticleFormat format) {
        return required(blocks, "bodyBlocks").stream()
                .map(block -> storedValue("bodyBlocks", () -> blockFromJson(block, format)))
                .toList();
    }

    private static BlockJson blockToJson(ArticleBlock block, ArticleFormat format) {
        BlockJsonBuilder json = new BlockJsonBuilder();
        switch (block) {
            case KeySummaryBlock summary -> {
                json.type = KEY_SUMMARY;
                json.lines = summary.lines().stream().map(LearningContentJson::runsToJson).toList();
            }
            case HeadingBlock heading -> {
                json.type = HEADING;
                json.text = heading.text();
            }
            case ParagraphBlock paragraph -> {
                json.type = PARAGRAPH;
                textOrContent(json, paragraph.content(), format);
            }
            case TermBlock term -> {
                json.type = TERM;
                json.termId = term.termId().value();
            }
            case HypotheticalBlock hypothetical -> {
                json.type = HYPOTHETICAL;
                textOrContent(json, hypothetical.content(), format);
            }
            case MisconceptionBlock misconception -> {
                json.type = MISCONCEPTION;
                json.wrong = runsToJson(misconception.wrong());
                json.correction = runsToJson(misconception.correction());
            }
            case ListBlock list -> {
                json.type = LIST;
                json.ordered = list.ordered();
                json.items = list.items().stream().map(LearningContentJson::runsToJson).toList();
            }
            case TableBlock table -> {
                json.type = TABLE;
                json.caption = table.caption();
                json.columns = table.columns();
                json.rows =
                        table.rows().stream()
                                .map(
                                        row ->
                                                row.stream()
                                                        .map(LearningContentJson::runsToJson)
                                                        .toList())
                                .toList();
            }
            case DiagramBlock diagram -> {
                json.type = DIAGRAM;
                json.kind = diagram.kind().name().toLowerCase(Locale.ROOT);
                json.title = diagram.title();
                json.unit = diagram.unit();
                json.categories = diagram.categories();
                json.series =
                        diagram.series().stream()
                                .map(series -> new SeriesJson(series.name(), series.values()))
                                .toList();
                json.description = diagram.description();
            }
            case InstitutionNoteBlock note -> {
                json.type = INSTITUTION_NOTE;
                json.content = runsToJson(note.content());
                json.asOf = note.asOf() == null ? null : note.asOf().toString();
                json.source = note.source() == null ? null : sourceToJson(note.source());
            }
        }
        return json.build();
    }

    /** V1은 9월 판 모양({@code text})을 그대로 쓴다. V1 초안은 글자 조각 하나만 담는다({@code LearningArticle}). */
    private static void textOrContent(BlockJsonBuilder json, RichText text, ArticleFormat format) {
        if (format == ArticleFormat.V1) {
            json.text = text.plainText();
        } else {
            json.content = runsToJson(text);
        }
    }

    /** 저장된 블록을 읽는다. 알 수 없는 종류와 형식에 맞지 않는 종류는 건너뛰지 않고 실패시킨다. 조용히 버리면 공개 글에서 문단이 사라진 채 나간다(원칙 VI). */
    private static ArticleBlock blockFromJson(BlockJson block, ArticleFormat format) {
        String type = required(block.type(), "bodyBlocks[].type");
        boolean v1 = format == ArticleFormat.V1;
        return switch (type) {
            case HEADING -> new HeadingBlock(required(block.text(), "bodyBlocks[].text"));
            case PARAGRAPH ->
                    new ParagraphBlock(
                            v1
                                    ? RichText.text(required(block.text(), "bodyBlocks[].text"))
                                    : runsFromJson(block.content(), "bodyBlocks[].content"));
            case TERM -> new TermBlock(new TermId(required(block.termId(), "bodyBlocks[].termId")));
            case HYPOTHETICAL ->
                    new HypotheticalBlock(
                            v1
                                    ? RichText.text(required(block.text(), "bodyBlocks[].text"))
                                    : runsFromJson(block.content(), "bodyBlocks[].content"));
            default -> {
                if (v1) {
                    throw new IllegalStateException("V2 block type in a V1 article: " + type);
                }
                yield v2BlockFromJson(type, block);
            }
        };
    }

    private static ArticleBlock v2BlockFromJson(String type, BlockJson block) {
        return switch (type) {
            case KEY_SUMMARY -> new KeySummaryBlock(runLists(block.lines(), "bodyBlocks[].lines"));
            case MISCONCEPTION ->
                    new MisconceptionBlock(
                            runsFromJson(block.wrong(), "bodyBlocks[].wrong"),
                            runsFromJson(block.correction(), "bodyBlocks[].correction"));
            case LIST ->
                    new ListBlock(
                            required(block.ordered(), "bodyBlocks[].ordered"),
                            runLists(block.items(), "bodyBlocks[].items"));
            case TABLE ->
                    new TableBlock(
                            required(block.caption(), "bodyBlocks[].caption"),
                            required(block.columns(), "bodyBlocks[].columns"),
                            required(block.rows(), "bodyBlocks[].rows").stream()
                                    .map(row -> runLists(row, "bodyBlocks[].rows[]"))
                                    .toList());
            case DIAGRAM ->
                    new DiagramBlock(
                            diagramKind(required(block.kind(), "bodyBlocks[].kind")),
                            required(block.title(), "bodyBlocks[].title"),
                            required(block.unit(), "bodyBlocks[].unit"),
                            required(block.categories(), "bodyBlocks[].categories"),
                            required(block.series(), "bodyBlocks[].series").stream()
                                    .map(
                                            series ->
                                                    new Series(
                                                            required(
                                                                    series.name(),
                                                                    "bodyBlocks[].series[].name"),
                                                            required(
                                                                    series.values(),
                                                                    "bodyBlocks[].series[].values")))
                                    .toList(),
                            required(block.description(), "bodyBlocks[].description"));
            case INSTITUTION_NOTE ->
                    new InstitutionNoteBlock(
                            runsFromJson(block.content(), "bodyBlocks[].content"),
                            block.asOf() == null ? null : localDate(block.asOf()),
                            block.source() == null ? null : sourceFromJson(block.source()));
            default -> throw new IllegalStateException("unknown stored block type: " + type);
        };
    }

    private static DiagramKind diagramKind(String kind) {
        return switch (kind) {
            case "bar" -> DiagramKind.BAR;
            case "line" -> DiagramKind.LINE;
            default -> throw new IllegalStateException("unknown stored diagram kind: " + kind);
        };
    }

    private static LocalDate localDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException invalid) {
            throw new IllegalStateException("stored asOf is not a date: " + value, invalid);
        }
    }

    // --- 글자 조각 ---

    static List<RunJson> runsToJson(RichText text) {
        return text.runs().stream().map(LearningContentJson::runToJson).toList();
    }

    private static RunJson runToJson(Run run) {
        return switch (run) {
            case TextRun textRun -> new RunJson(textRun.text(), null, null);
            case TermMentionRun mention ->
                    new RunJson(mention.text(), mention.termId().value(), null);
            case SourceRun source -> new RunJson(null, null, sourceToJson(source.source()));
        };
    }

    static RichText runsFromJson(List<RunJson> runs, String key) {
        return new RichText(
                required(runs, key).stream().map(run -> runFromJson(run, key)).toList());
    }

    private static List<RichText> runLists(List<List<RunJson>> lists, String key) {
        return required(lists, key).stream().map(runs -> runsFromJson(runs, key)).toList();
    }

    /** 조각 하나에는 키가 하나뿐이다. 둘 이상이거나 없으면 저장 데이터가 깨진 것이다. */
    private static Run runFromJson(RunJson run, String key) {
        if (run.source() != null) {
            if (run.text() != null || run.term() != null) {
                throw new IllegalStateException("stored source run has extra keys at " + key);
            }
            return new SourceRun(sourceFromJson(run.source()));
        }
        String text = required(run.text(), key + "[].text");
        return run.term() == null
                ? new TextRun(text)
                : new TermMentionRun(new TermId(run.term()), text);
    }

    private static SourceJson sourceToJson(SourceRef source) {
        return new SourceJson(source.label(), source.url());
    }

    private static SourceRef sourceFromJson(SourceJson source) {
        return new SourceRef(
                required(source.label(), "source.label"), required(source.url(), "source.url"));
    }

    // --- 예시·출처 ---

    static ExampleJson exampleToJson(ExampleAllocation example) {
        if (example == null) {
            return null;
        }
        return new ExampleJson(
                example.sectors().stream()
                        .map(sector -> new SectorWeightJson(sector.code(), sector.weightTenths()))
                        .toList());
    }

    /** 예시 자체는 선택 값이다. 있으면 섹터 목록과 각 섹터의 코드·비중은 필수다. */
    static ExampleAllocation exampleFromJson(ExampleJson example) {
        if (example == null) {
            return null;
        }
        return storedValue(
                "example",
                () ->
                        new ExampleAllocation(
                                required(example.sectors(), "example.sectors").stream()
                                        .map(
                                                sector ->
                                                        new ExampleAllocation.SectorWeight(
                                                                required(
                                                                        sector.code(),
                                                                        "example.sectors[].code"),
                                                                required(
                                                                        sector.weightTenths(),
                                                                        "example.sectors[].weightTenths")))
                                        .toList()));
    }

    static List<SourceJson> sourcesToJson(List<ArticleSource> sources) {
        return sources.stream()
                .map(source -> new SourceJson(source.label(), source.url()))
                .toList();
    }

    static List<ArticleSource> sourcesFromJson(List<SourceJson> sources) {
        return required(sources, "sources").stream()
                .map(
                        source ->
                                new ArticleSource(
                                        required(source.label(), "sources[].label"), source.url()))
                .toList();
    }

    // --- 확인 문제 ---

    /** 초안의 문제. 보기마다 {@code correct}를 싣는다. */
    static List<QuizJson> draftQuizToJson(List<QuizQuestion> quiz) {
        return quiz.stream()
                .map(
                        question ->
                                new QuizJson(
                                        question.questionId().value(),
                                        question.prompt(),
                                        question.choices().stream()
                                                .map(
                                                        choice ->
                                                                new ChoiceJson(
                                                                        choice.choiceId().value(),
                                                                        choice.text(),
                                                                        choice.correct()))
                                                .toList(),
                                        null,
                                        question.explanation()))
                .toList();
    }

    static List<QuizQuestion> draftQuizFromJson(List<QuizJson> quiz) {
        return required(quiz, "quiz").stream()
                .map(
                        question ->
                                storedValue(
                                        "quiz",
                                        () ->
                                                quizQuestion(
                                                        question,
                                                        choice ->
                                                                required(
                                                                        choice.correct(),
                                                                        "quiz[].choices[].correct"))))
                .toList();
    }

    /** 판의 문제. 정답은 {@code correctChoiceId} 하나다(data-model §4-2). */
    private static List<QuizJson> snapshotQuizToJson(List<QuizQuestion> quiz) {
        return quiz.stream()
                .map(
                        question ->
                                new QuizJson(
                                        question.questionId().value(),
                                        question.prompt(),
                                        question.choices().stream()
                                                .map(
                                                        choice ->
                                                                new ChoiceJson(
                                                                        choice.choiceId().value(),
                                                                        choice.text(),
                                                                        null))
                                                .toList(),
                                        onlyCorrect(question).value(),
                                        question.explanation()))
                .toList();
    }

    private static List<QuizQuestion> snapshotQuizFromJson(List<QuizJson> quiz) {
        return required(quiz, "quiz").stream()
                .map(
                        question -> {
                            String correct =
                                    required(question.correctChoiceId(), "quiz[].correctChoiceId");
                            return storedValue(
                                    "quiz",
                                    () ->
                                            quizQuestion(
                                                    question,
                                                    choice -> correct.equals(choice.choiceId())));
                        })
                .toList();
    }

    private static QuizQuestion quizQuestion(QuizJson question, Predicate<ChoiceJson> correct) {
        List<QuizChoice> choices = new ArrayList<>();
        for (ChoiceJson choice : required(question.choices(), "quiz[].choices")) {
            choices.add(
                    new QuizChoice(
                            new ChoiceId(required(choice.choiceId(), "quiz[].choices[].choiceId")),
                            required(choice.text(), "quiz[].choices[].text"),
                            correct.test(choice)));
        }
        return new QuizQuestion(
                new QuestionId(required(question.questionId(), "quiz[].questionId")),
                required(question.prompt(), "quiz[].prompt"),
                choices,
                required(question.explanation(), "quiz[].explanation"));
    }

    /** 판에 넣는 문제는 정답 보기가 정확히 하나다. 게시 전 검사({@code QUIZ})가 막으므로 아니면 호출자의 오류다. */
    private static ChoiceId onlyCorrect(QuizQuestion question) {
        List<ChoiceId> correct =
                question.choices().stream()
                        .filter(QuizChoice::correct)
                        .map(QuizChoice::choiceId)
                        .toList();
        if (correct.size() != 1) {
            throw new IllegalStateException(
                    "a published question must have exactly one correct choice: "
                            + question.questionId());
        }
        return correct.getFirst();
    }

    // --- 판 ---

    static SnapshotJson snapshotToJson(ArticleSnapshot snapshot) {
        boolean v2 = snapshot.format() == ArticleFormat.V2;
        ReadingEstimate reading = snapshot.reading();
        return new SnapshotJson(
                v2 ? ArticleFormat.V2.name() : null,
                snapshot.articleId().value(),
                snapshot.title(),
                snapshot.question(),
                snapshot.readingOrder(),
                blocksToJson(snapshot.bodyBlocks(), snapshot.format()),
                exampleToJson(snapshot.example()),
                sourcesToJson(snapshot.sources()),
                snapshot.terms().stream().map(term -> termToJson(term, snapshot.format())).toList(),
                snapshot.draftVersion(),
                v2 ? snapshotQuizToJson(snapshot.quiz()) : null,
                v2
                        ? new ReadingJson(
                                reading.bodyChars(),
                                reading.readingMinutes(),
                                reading.minDwellSeconds())
                        : null);
    }

    static ArticleSnapshot snapshotFromJson(SnapshotJson snapshot) {
        ArticleFormat format = snapshotFormat(snapshot.format());
        boolean v2 = format == ArticleFormat.V2;
        return new ArticleSnapshot(
                format,
                storedValue(
                        "articleId",
                        () -> new ArticleId(required(snapshot.articleId(), "articleId"))),
                required(snapshot.title(), "title"),
                required(snapshot.question(), "question"),
                required(snapshot.readingOrder(), "readingOrder"),
                blocksFromJson(snapshot.bodyBlocks(), format),
                exampleFromJson(snapshot.example()),
                sourcesFromJson(snapshot.sources()),
                required(snapshot.terms(), "terms").stream()
                        .map(term -> termFromJson(term, format))
                        .toList(),
                required(snapshot.draftVersion(), "draftVersion"),
                v2 ? snapshotQuizFromJson(snapshot.quiz()) : List.of(),
                v2 ? readingFromJson(required(snapshot.reading(), "reading")) : null);
    }

    /** {@code format} 키가 없으면 V1 판이다(2판 전 판). 있으면 {@code V2}뿐이다. */
    private static ArticleFormat snapshotFormat(String format) {
        if (format == null) {
            return ArticleFormat.V1;
        }
        if (ArticleFormat.V2.name().equals(format)) {
            return ArticleFormat.V2;
        }
        throw new IllegalStateException("unknown stored snapshot format: " + format);
    }

    private static ReadingEstimate readingFromJson(ReadingJson reading) {
        return storedValue(
                "reading",
                () ->
                        new ReadingEstimate(
                                required(reading.bodyChars(), "reading.bodyChars"),
                                required(reading.readingMinutes(), "reading.readingMinutes"),
                                required(reading.minDwellSeconds(), "reading.minDwellSeconds")));
    }

    // --- 용어 ---

    private static TermJson termToJson(LearningTerm term, ArticleFormat format) {
        if (format == ArticleFormat.V1) {
            return new TermJson(
                    term.termId().value(), term.label(), term.sentence(), null, null, null, null);
        }
        return new TermJson(
                term.termId().value(),
                term.label(),
                null,
                term.aliases(),
                term.meaning(),
                term.reading(),
                term.caveat());
    }

    private static LearningTerm termFromJson(TermJson term, ArticleFormat format) {
        TermId termId =
                storedValue(
                        "terms[].termId",
                        () -> new TermId(required(term.termId(), "terms[].termId")));
        String label = required(term.label(), "terms[].label");
        if (format == ArticleFormat.V1) {
            return new LearningTerm(termId, label, required(term.sentence(), "terms[].sentence"));
        }
        return storedValue(
                "terms[]",
                () ->
                        LearningTerm.v2(
                                termId,
                                label,
                                required(term.aliases(), "terms[].aliases"),
                                required(term.meaning(), "terms[].meaning"),
                                required(term.reading(), "terms[].reading"),
                                required(term.caveat(), "terms[].caveat")));
    }

    /** 도메인 블록을 {@link BlockJson}으로 옮길 때 종류마다 다른 키만 채운다. 나머지는 null이라 저장 JSON에서 생략된다. */
    private static final class BlockJsonBuilder {
        String type;
        String text;
        String termId;
        List<RunJson> content;
        List<List<RunJson>> lines;
        List<RunJson> wrong;
        List<RunJson> correction;
        Boolean ordered;
        List<List<RunJson>> items;
        String caption;
        List<String> columns;
        List<List<List<RunJson>>> rows;
        String kind;
        String title;
        String unit;
        List<String> categories;
        List<SeriesJson> series;
        String description;
        String asOf;
        SourceJson source;

        BlockJson build() {
            return new BlockJson(
                    type,
                    text,
                    termId,
                    content,
                    lines,
                    wrong,
                    correction,
                    ordered,
                    items,
                    caption,
                    columns,
                    rows,
                    kind,
                    title,
                    unit,
                    categories,
                    series,
                    description,
                    asOf,
                    source);
        }
    }
}
