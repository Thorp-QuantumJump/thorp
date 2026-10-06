package com.ssafy.thorp.learning.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

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
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.model.RichText.TextRun;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation;
import com.ssafy.thorp.learning.domain.vo.ExampleAllocation.SectorWeight;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.StageKey;
import com.ssafy.thorp.learning.domain.vo.TermId;
import com.ssafy.thorp.learning.infrastructure.rules.YamlPublishCheckRulesLoader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 게시 전 검사 v2 열일곱 가지(data-model §8-2, quickstart 7-2·7-3). 규칙은 실제 파일({@code
 * learning/publish-checks-v2.yml})에서 읽는다. 목록을 바꾸면 이 테스트가 그 목록으로 돈다.
 *
 * <p>통과하는 초안 하나({@link Draft#passing()})를 두고 검사마다 한 군데만 바꿔 그 검사만 걸리는지 본다.
 */
class PublishCheckV2Test {

    private static final PublishCheckRulesV2 RULES = new YamlPublishCheckRulesLoader().v2();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final Optional<Set<String>> OPEN_MAP =
            Optional.of(Set.of("SEMICONDUCTOR", "BATTERY"));

    private static final TermId VOLATILITY = new TermId("volatility");
    private static final TermId MAX_DRAWDOWN = new TermId("max-drawdown");
    private static final Set<TermId> KNOWN = Set.of(VOLATILITY, MAX_DRAWDOWN);

    private static RichText text(String text) {
        return RichText.text(text);
    }

    /** 통과하는 초안의 부품. 테스트가 한 군데씩 바꾼다. */
    private static final class Draft {
        String title = "변동성은 손실일까?";
        String question = "값이 크게 오르내렸다는 것은 돈을 잃었다는 뜻일까?";
        List<ArticleBlock> blocks = new ArrayList<>();
        ExampleAllocation example;
        List<LearningTerm> terms = new ArrayList<>();
        List<QuizQuestion> quiz = new ArrayList<>();

        /** 본문 약 2,060자(4분). 숫자는 가상의 예 요소에만 있다. 블록 순서: 0 요약, 1 소제목, 2 문단(언급), 3 용어, … */
        static Draft passing() {
            Draft draft = new Draft();
            draft.blocks.add(
                    new KeySummaryBlock(
                            List.of(
                                    text("오르내림은 폭을 잰다."),
                                    text("끝 값이 같을 수 있다."),
                                    text("손실은 끝으로 본다."))));
            draft.blocks.add(new HeadingBlock("오르내림은 무엇을 재나"));
            draft.blocks.add(
                    new ParagraphBlock(
                            new RichText(
                                    List.of(
                                            new TextRun("오르내린 폭은 "),
                                            new TermMentionRun(MAX_DRAWDOWN, "가장 크게 줄었던 폭"),
                                            new TextRun("과 따로 본다."),
                                            new SourceRun(
                                                    new SourceRef(
                                                            "합성 출처", "https://example.com"))))));
            draft.blocks.add(new TermBlock(VOLATILITY));
            draft.blocks.add(new HeadingBlock("오르내림과 손실"));
            draft.blocks.add(new HypotheticalBlock("100만 원이 80만 원이 되면 20% 줄어든 것이다."));
            draft.blocks.add(new MisconceptionBlock(text("크게 오르내렸으니 잃었다."), text("폭과 손익은 따로 본다.")));
            draft.blocks.add(new HeadingBlock("읽을 때 볼 것"));
            draft.blocks.add(
                    new TableBlock(
                            "가상의 두 경우",
                            List.of("구분", "가"),
                            List.of(List.of(text("끝 값"), text("12%")))));
            draft.blocks.add(
                    new DiagramBlock(
                            DiagramKind.LINE,
                            "가상의 흐름",
                            "만 원",
                            List.of("1월", "2월"),
                            List.of(
                                    new Series(
                                            "가",
                                            List.of(
                                                    new BigDecimal("100"),
                                                    new BigDecimal("92.5")))),
                            "값이 내려갔다가 돌아온다."));
            draft.blocks.add(
                    new InstitutionNoteBlock(
                            text("거래세는 매도 금액의 0.15%다."),
                            TODAY,
                            new SourceRef("합성 제도 출처", "https://example.com/rule")));
            draft.blocks.add(new ListBlock(false, List.of(text("시작 값"), text("끝 값"))));
            draft.blocks.add(new ParagraphBlock("가".repeat(1950)));
            draft.terms.add(
                    LearningTerm.v2(
                            VOLATILITY,
                            "변동성",
                            List.of("volatility"),
                            "값이 오르내리는 정도다.",
                            "클수록 크게 흔들렸다는 뜻이다.",
                            "손실의 크기를 직접 말하지 않는다."));
            draft.quiz.add(
                    question(
                            "q1",
                            "질문 하나",
                            "풀이",
                            choice("c2", "그렇다", false),
                            choice("c3", "아니다", true)));
            draft.quiz.add(
                    question(
                            "q4",
                            "질문 둘",
                            "풀이 둘",
                            choice("c5", "시작과 끝", true),
                            choice("c6", "폭", false)));
            return draft;
        }

        LearningArticle build() {
            return new LearningArticle(
                    1L,
                    new ArticleId("volatility-is-not-loss"),
                    title,
                    question,
                    204,
                    ArticleFormat.V2,
                    new Placement(new StageKey("returns-and-risk"), 4),
                    null,
                    blocks,
                    example,
                    List.of(),
                    ArticleStatus.DRAFT,
                    null,
                    3,
                    terms,
                    quiz,
                    9);
        }
    }

    private static QuizChoice choice(String id, String text, boolean correct) {
        return new QuizChoice(new ChoiceId(id), text, correct);
    }

    private static QuizQuestion question(
            String id, String prompt, String explanation, QuizChoice... choices) {
        return new QuizQuestion(new QuestionId(id), prompt, List.of(choices), explanation);
    }

    private static List<PublishCheckFailure> run(Draft draft) {
        return PublishCheckV2.run(draft.build(), RULES, OPEN_MAP, KNOWN, TODAY).failures();
    }

    private static List<PublishCheckFailure> assertOnly(Draft draft, Check check) {
        List<PublishCheckFailure> failures = run(draft);
        assertThat(failures)
                .isNotEmpty()
                .extracting(PublishCheckFailure::check)
                .containsOnly(check);
        return failures;
    }

    @Test
    @DisplayName("모든 검사를 통과하는 초안은 결과가 비어 있다")
    void passingDraftPasses() {
        assertThat(run(Draft.passing())).isEmpty();
        assertThat(
                        PublishCheckV2.reading(Draft.passing().build(), RULES.reading())
                                .readingMinutes())
                .isBetween(4, 7);
    }

    @Test
    @DisplayName("REQUIRED_FIELDS: 제목·질문이 비었다")
    void requiredFields() {
        Draft draft = Draft.passing();
        draft.title = " ";
        draft.question = "";

        assertThat(assertOnly(draft, Check.REQUIRED_FIELDS))
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.title(), CheckLocation.question());
    }

    @Test
    @DisplayName("KEY_SUMMARY: 첫 블록이 핵심 요약이 아니면 본문 전체, 두 줄이면 블록 0, 빈 줄이면 그 줄을 가리킨다")
    void keySummary() {
        Draft missing = Draft.passing();
        missing.blocks.removeFirst();
        missing.blocks.add(new HeadingBlock("소제목 보충"));
        assertThat(run(missing))
                .filteredOn(failure -> failure.check() == Check.KEY_SUMMARY)
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.body());

        Draft twoLines = Draft.passing();
        twoLines.blocks.set(0, new KeySummaryBlock(List.of(text("하나"), text("둘"))));
        assertThat(assertOnly(twoLines, Check.KEY_SUMMARY))
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.block(0));

        Draft blankLine = Draft.passing();
        blankLine.blocks.set(0, new KeySummaryBlock(List.of(text("하나"), text(" "), text("셋"))));
        assertThat(assertOnly(blankLine, Check.KEY_SUMMARY))
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.block(0, "lines[1]"));
    }

    @Test
    @DisplayName("HEADING_COUNT: 소제목이 셋 미만이다")
    void headingCount() {
        Draft draft = Draft.passing();
        draft.blocks.remove(7);

        assertThat(assertOnly(draft, Check.HEADING_COUNT))
                .extracting(PublishCheckFailure::message)
                .containsExactly("소제목이 세 개 이상이어야 합니다. 지금 2개입니다.");
    }

    @Test
    @DisplayName("HYPOTHETICAL_PRESENT·MISCONCEPTION_PRESENT: 가상의 계산 예와 오해가 없다")
    void requiredElements() {
        Draft noHypothetical = Draft.passing();
        noHypothetical.blocks.removeIf(HypotheticalBlock.class::isInstance);
        assertOnly(noHypothetical, Check.HYPOTHETICAL_PRESENT);

        Draft noMisconception = Draft.passing();
        noMisconception.blocks.removeIf(MisconceptionBlock.class::isInstance);
        assertOnly(noMisconception, Check.MISCONCEPTION_PRESENT);
    }

    @Test
    @DisplayName("READING_TIME: 보이는 분이 4~7이 아니면 분과 글자 수를 적는다")
    void readingTime() {
        Draft draft = Draft.passing();
        draft.blocks.set(draft.blocks.size() - 1, new ParagraphBlock("가".repeat(500)));

        List<PublishCheckFailure> failures = assertOnly(draft, Check.READING_TIME);
        assertThat(failures.getFirst().location()).isEqualTo(CheckLocation.body());
        assertThat(failures.getFirst().message())
                .matches("예상 읽기 시간이 \\d분입니다\\(본문 [\\d,]+자\\)\\. 4~7분이어야 합니다\\.");
    }

    @Test
    @DisplayName("예시는 없어도 되고(EXAMPLE_PRESENT 없음), 있으면 v1과 같은 세 검사를 한다 (FR-003)")
    void exampleChecksOnlyWhenPresent() {
        assertThat(run(Draft.passing())).isEmpty();

        Draft draft = Draft.passing();
        draft.example =
                new ExampleAllocation(
                        List.of(
                                new SectorWeight("SEMICONDUCTOR", 500),
                                new SectorWeight("ENERGY", 450)));
        assertThat(run(draft))
                .extracting(PublishCheckFailure::check)
                .containsExactly(Check.EXAMPLE_SECTOR_IN_MAP, Check.EXAMPLE_WEIGHT_SUM);

        Draft single = Draft.passing();
        single.example = new ExampleAllocation(List.of(new SectorWeight("SEMICONDUCTOR", 1000)));
        assertOnly(single, Check.EXAMPLE_SECTOR_COUNT);

        Draft noMap = Draft.passing();
        noMap.example =
                new ExampleAllocation(
                        List.of(
                                new SectorWeight("SEMICONDUCTOR", 500),
                                new SectorWeight("BATTERY", 500)));
        assertThat(
                        PublishCheckV2.run(noMap.build(), RULES, Optional.empty(), KNOWN, TODAY)
                                .failures())
                .extracting(PublishCheckFailure::check)
                .containsExactly(Check.EXAMPLE_SECTOR_IN_MAP);
    }

    @Test
    @DisplayName("TERM_BLOCKS_MATCH: 용어가 용어 블록으로 정확히 한 번 나오지 않거나 없는 용어를 가리킨다")
    void termBlocksMatch() {
        Draft draft = Draft.passing();
        draft.blocks.remove(3);

        assertThat(assertOnly(draft, Check.TERM_BLOCKS_MATCH))
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.term(VOLATILITY));
    }

    @Test
    @DisplayName("TERM_SENTENCES: 이름이나 뜻·읽는 법·한계가 비면 그 필드를 가리킨다")
    void termSentences() {
        Draft draft = Draft.passing();
        draft.terms.set(0, LearningTerm.v2(VOLATILITY, "변동성", List.of(), "", "읽는 법", " "));

        assertThat(assertOnly(draft, Check.TERM_SENTENCES))
                .extracting(PublishCheckFailure::location)
                .containsExactly(
                        CheckLocation.term(VOLATILITY, "meaning"),
                        CheckLocation.term(VOLATILITY, "caveat"));
    }

    @Test
    @DisplayName("TERM_MENTION_KNOWN: 어느 글에도 없는 용어를 언급하면 그 블록과 필드를 가리킨다")
    void termMentionKnown() {
        Draft draft = Draft.passing();
        draft.blocks.set(
                2,
                new ParagraphBlock(
                        new RichText(
                                List.of(
                                        new TextRun("없는 "),
                                        new TermMentionRun(new TermId("no-such-term"), "용어"),
                                        new TermMentionRun(new TermId("no-such-term"), "용어")))));

        assertThat(assertOnly(draft, Check.TERM_MENTION_KNOWN))
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.block(2, "content"));
    }

    @Test
    @DisplayName("QUIZ: 문제 수, 질문·보기·풀이, 정답 하나를 보고 위치는 문제·보기 식별자다 (quickstart 7-2)")
    void quiz() {
        Draft oneQuestion = Draft.passing();
        oneQuestion.quiz.removeLast();
        assertThat(assertOnly(oneQuestion, Check.QUIZ))
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.quiz());

        Draft broken = Draft.passing();
        broken.quiz.set(
                1, question("q4", " ", "", choice("c5", "", true), choice("c6", "폭", true)));
        assertThat(assertOnly(broken, Check.QUIZ))
                .extracting(PublishCheckFailure::location, PublishCheckFailure::message)
                .containsExactly(
                        tuple(CheckLocation.quizQuestion(new QuestionId("q4"), 1), "질문이 비어 있습니다."),
                        tuple(
                                CheckLocation.quizChoice(
                                        new QuestionId("q4"), 1, new ChoiceId("c5")),
                                "보기 글자가 비어 있습니다."),
                        tuple(
                                CheckLocation.quizQuestion(new QuestionId("q4"), 1),
                                "정답 보기가 하나가 아닙니다."),
                        tuple(CheckLocation.quizQuestion(new QuestionId("q4"), 1), "풀이가 비어 있습니다."));

        Draft oneChoice = Draft.passing();
        oneChoice.quiz.set(0, question("q1", "질문", "풀이", choice("c2", "가", true)));
        assertOnly(oneChoice, Check.QUIZ);
    }

    @Test
    @DisplayName("ELEMENT_FORMAT: 도식 설명, 제도 안내의 기준 시점·출처, 출처 표시의 이름 (FR-026, FR-007)")
    void elementFormat() {
        Draft draft = Draft.passing();
        draft.blocks.set(
                9,
                new DiagramBlock(
                        DiagramKind.BAR,
                        "",
                        "",
                        List.of("가", "나"),
                        List.of(new Series("계열", List.of(BigDecimal.ONE, BigDecimal.TEN))),
                        " "));
        draft.blocks.set(10, new InstitutionNoteBlock(text("제도 안내"), null, null));
        draft.blocks.set(
                2,
                new ParagraphBlock(
                        new RichText(
                                List.of(
                                        new TextRun("인용한 글자"),
                                        new SourceRun(new SourceRef("", "https://example.com"))))));

        assertThat(assertOnly(draft, Check.ELEMENT_FORMAT))
                .extracting(PublishCheckFailure::location)
                .containsExactly(
                        CheckLocation.block(2, "content"),
                        CheckLocation.block(9, "description"),
                        CheckLocation.block(10, "asOf"),
                        CheckLocation.block(10, "source"));
    }

    @Test
    @DisplayName("제도 안내 기준 시점이 오늘이면 통과하고 다음 날이면 ELEMENT_FORMAT이다")
    void institutionNoteFutureDate() {
        Draft today = Draft.passing();
        assertThat(run(today)).isEmpty();

        Draft tomorrow = Draft.passing();
        tomorrow.blocks.set(
                10,
                new InstitutionNoteBlock(
                        text("제도 안내"),
                        TODAY.plusDays(1),
                        new SourceRef("출처", "https://example.com")));
        assertThat(assertOnly(tomorrow, Check.ELEMENT_FORMAT))
                .extracting(PublishCheckFailure::location)
                .containsExactly(CheckLocation.block(10, "asOf"));
    }

    @Test
    @DisplayName("오해의 틀린 생각 칸과 틀린 보기는 금지 표현을 보지 않는다 (quickstart 7-3, FR-017)")
    void wrongSidesAreExempt() {
        Draft draft = Draft.passing();
        draft.blocks.set(6, new MisconceptionBlock(text("오를 확률이 높다고 믿는다."), text("폭과 손익은 따로 본다.")));
        draft.quiz.set(
                0,
                question(
                        "q1",
                        "질문 하나",
                        "풀이",
                        choice("c2", "이 비중을 추천합니다", false),
                        choice("c3", "아니다", true)));

        assertThat(run(draft)).isEmpty();
    }

    @Test
    @DisplayName("같은 표현을 바로잡기 칸이나 정답 보기에 쓰면 FORBIDDEN_EXPRESSION이다")
    void rightSidesAreChecked() {
        Draft draft = Draft.passing();
        draft.blocks.set(6, new MisconceptionBlock(text("오해 문장"), text("오를 확률이 높다.")));
        draft.quiz.set(
                0,
                question(
                        "q1",
                        "질문 하나",
                        "풀이",
                        choice("c2", "그렇다", false),
                        choice("c3", "이 비중을 추천합니다", true)));

        assertThat(assertOnly(draft, Check.FORBIDDEN_EXPRESSION))
                .extracting(PublishCheckFailure::location, PublishCheckFailure::matched)
                .containsExactly(
                        tuple(CheckLocation.block(6, "correction"), "확률"),
                        tuple(
                                CheckLocation.quizChoice(
                                        new QuestionId("q1"), 0, new ChoiceId("c3")),
                                "추천합니다"));
    }

    @Test
    @DisplayName("용어의 다른 이름도 금지 표현과 결과성 숫자를 본다")
    void termAliasesAreChecked() {
        Draft draft = Draft.passing();
        draft.terms.set(
                0,
                LearningTerm.v2(
                        VOLATILITY,
                        "변동성",
                        List.of("성공률", "30%"),
                        "값이 오르내리는 정도다.",
                        "클수록 크게 흔들렸다는 뜻이다.",
                        "손실의 크기를 직접 말하지 않는다."));

        assertThat(run(draft))
                .extracting(PublishCheckFailure::check, PublishCheckFailure::location)
                .containsExactly(
                        tuple(
                                Check.FORBIDDEN_EXPRESSION,
                                CheckLocation.term(VOLATILITY, "aliases[0]")),
                        tuple(
                                Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL,
                                CheckLocation.term(VOLATILITY, "aliases[1]")));
    }

    @Test
    @DisplayName("문단의 '(예: …)' 안 숫자는 걸지 않고, '(예:' 없는 괄호 안 숫자는 건다 (quickstart 7-3)")
    void exampleParenthesesAreExempt() {
        Draft exempt = Draft.passing();
        exempt.blocks.set(2, new ParagraphBlock("비용이 쌓인다(예: 왕복 약 0.19%). 그래서 회전율을 본다."));
        assertThat(run(exempt)).isEmpty();

        Draft notExempt = Draft.passing();
        notExempt.blocks.set(2, new ParagraphBlock("비용이 쌓인다(왕복 약 0.19%)."));
        assertThat(assertOnly(notExempt, Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL))
                .extracting(PublishCheckFailure::location, PublishCheckFailure::matched)
                .containsExactly(tuple(CheckLocation.block(2, "content"), "0.19%"));

        Draft unclosed = Draft.passing();
        unclosed.blocks.set(2, new ParagraphBlock("비용이 쌓인다(예: 왕복 약 0.19%"));
        assertOnly(unclosed, Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL);

        Draft listExempt = Draft.passing();
        listExempt.blocks.set(11, new ListBlock(false, List.of(text("비용(예: 0.19%)"), text("끝 값"))));
        assertThat(run(listExempt)).isEmpty();
    }

    @Test
    @DisplayName("표·확인 문제·제도 안내·도식·가상의 예의 숫자는 걸지 않는다 (FR-005, FR-026)")
    void numberExemptElements() {
        Draft draft = Draft.passing();
        draft.quiz.set(
                0,
                question(
                        "q1",
                        "20% 오르면?",
                        "풀이",
                        choice("c2", "그렇다", false),
                        choice("c3", "아니다", true)));

        // 표 칸 12%, 제도 안내 0.15%, 가상의 계산 예 20%·100만 원은 통과하는 초안에 이미 있다.
        assertThat(run(draft)).isEmpty();
    }

    @Test
    @DisplayName("오해·비교 표·도식의 숫자는 숫자 검사 대상이 아니다 (data-model §8-2 '숫자 검사 대상')")
    void numbersOutsideNumberTargets() {
        Draft draft = Draft.passing();
        draft.blocks.set(
                6, new MisconceptionBlock(text("20% 올랐으니 잃지 않았다."), text("2배 뒤 50% 내리면 제자리다.")));
        draft.blocks.set(
                8,
                new TableBlock(
                        "가상의 12% 두 경우",
                        List.of("구분", "30%"), List.of(List.of(text("끝 값"), text("12%")))));
        draft.blocks.set(
                9,
                new DiagramBlock(
                        DiagramKind.LINE,
                        "가상의 30% 흐름",
                        "만 원",
                        List.of("1월", "2월"),
                        List.of(
                                new Series(
                                        "12% 계열",
                                        List.of(new BigDecimal("100"), new BigDecimal("92.5")))),
                        "값이 20% 내려갔다가 돌아온다."));

        assertThat(run(draft)).isEmpty();
    }

    @Test
    @DisplayName("금지 표현은 핵심 요약·가상의 예·비교 표·도식·제도 안내·목록에서도 그 필드 자리로 잡는다 (data-model §8-2)")
    void forbiddenExpressionsInEveryElement() {
        Draft draft = Draft.passing();
        draft.blocks.set(
                0, new KeySummaryBlock(List.of(text("첫 줄"), text("오를 확률이 높다."), text("셋째"))));
        draft.blocks.set(5, new HypotheticalBlock("100만 원을 추천합니다."));
        draft.blocks.set(
                8,
                new TableBlock(
                        "확률 비교",
                        List.of("구분", "추천합니다"),
                        List.of(List.of(text("끝 값"), text("확률")))));
        draft.blocks.set(
                9,
                new DiagramBlock(
                        DiagramKind.LINE,
                        "확률의 흐름",
                        "만 원",
                        List.of("1월", "2월"),
                        List.of(
                                new Series(
                                        "추천합니다",
                                        List.of(new BigDecimal("100"), new BigDecimal("92.5")))),
                        "확률이 내려갔다가 돌아온다."));
        draft.blocks.set(
                10,
                new InstitutionNoteBlock(
                        text("이 제도를 추천합니다."),
                        TODAY,
                        new SourceRef("합성 제도 출처", "https://example.com/rule")));
        draft.blocks.set(11, new ListBlock(false, List.of(text("시작 값"), text("오를 확률"))));

        assertThat(assertOnly(draft, Check.FORBIDDEN_EXPRESSION))
                .extracting(PublishCheckFailure::location)
                .containsExactly(
                        CheckLocation.block(0, "lines[1]"),
                        CheckLocation.block(5, "content"),
                        CheckLocation.block(8, "caption"),
                        CheckLocation.block(8, "columns[1]"),
                        CheckLocation.block(8, "rows[0][1]"),
                        CheckLocation.block(9, "title"),
                        CheckLocation.block(9, "series[0].name"),
                        CheckLocation.block(9, "description"),
                        CheckLocation.block(10, "content"),
                        CheckLocation.block(11, "items[1]"));
    }

    @Test
    @DisplayName("규칙이 예시를 요구하면(exampleRequired: true) 예시 없는 초안은 EXAMPLE_PRESENT다 (research R19)")
    void exampleRequiredRule() {
        PublishCheckRulesV2 required =
                new PublishCheckRulesV2(
                        RULES.common(), RULES.exampleMarker(), true, RULES.reading(), RULES.quiz());

        assertThat(
                        PublishCheckV2.run(
                                        Draft.passing().build(), required, OPEN_MAP, KNOWN, TODAY)
                                .failures())
                .extracting(PublishCheckFailure::check, PublishCheckFailure::location)
                .containsExactly(tuple(Check.EXAMPLE_PRESENT, CheckLocation.example()));
    }

    @Test
    @DisplayName("숫자 검사는 제목·핵심 요약·소제목·문단·목록에서 하고 전각 숫자도 잡는다")
    void resultNumbersInCheckedFields() {
        Draft draft = Draft.passing();
        draft.title = "２배 오른 이유";
        draft.blocks.set(
                0, new KeySummaryBlock(List.of(text("첫 줄"), text("12.5% 올랐다."), text("셋째"))));
        draft.blocks.set(1, new HeadingBlock("120만 원의 의미"));

        assertThat(assertOnly(draft, Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL))
                .extracting(PublishCheckFailure::location, PublishCheckFailure::matched)
                .containsExactly(
                        tuple(CheckLocation.title(), "2배"),
                        tuple(CheckLocation.block(0, "lines[1]"), "12.5%"),
                        tuple(CheckLocation.block(1, "text"), "120만 원"));
    }

    @Test
    @DisplayName("여러 검사가 한 번에 걸리면 모두 §8-2 순서로 돌려준다")
    void reportsAllInOrder() {
        Draft draft = Draft.passing();
        draft.title = "";
        draft.blocks.removeIf(MisconceptionBlock.class::isInstance);
        draft.quiz.removeLast();
        draft.blocks.set(1, new HeadingBlock("수익 보장 30%"));

        assertThat(run(draft))
                .extracting(PublishCheckFailure::check)
                .containsExactly(
                        Check.REQUIRED_FIELDS,
                        Check.MISCONCEPTION_PRESENT,
                        Check.QUIZ,
                        Check.FORBIDDEN_EXPRESSION,
                        Check.RESULT_NUMBER_OUTSIDE_HYPOTHETICAL);
    }

    @Test
    @DisplayName("v2 검사는 V1 초안을 받지 않는다")
    void rejectsV1Draft() {
        LearningArticle v1 =
                new LearningArticle(
                        1L,
                        new ArticleId("returns-and-losses"),
                        "t",
                        "q",
                        8,
                        ArticleFormat.V1,
                        new Placement(new StageKey("returns-and-risk"), 1),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        ArticleStatus.DRAFT,
                        null,
                        0,
                        List.of(),
                        List.of(),
                        0);

        assertThatThrownBy(() -> PublishCheckV2.run(v1, RULES, OPEN_MAP, KNOWN, TODAY))
                .isInstanceOf(IllegalStateException.class);
    }
}
