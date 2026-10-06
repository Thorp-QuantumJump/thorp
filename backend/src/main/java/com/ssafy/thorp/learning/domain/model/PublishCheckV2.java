package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.model.ArticleBlock.DiagramBlock;
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
import com.ssafy.thorp.learning.domain.model.PublishCheck.LocatedText;
import com.ssafy.thorp.learning.domain.model.PublishCheckFailure.Check;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2.QuizRules;
import com.ssafy.thorp.learning.domain.model.PublishCheckRulesV2.ReadingRules;
import com.ssafy.thorp.learning.domain.model.RichText.SourceRun;
import com.ssafy.thorp.learning.domain.model.RichText.TermMentionRun;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 게시 전 검사 v2(data-model §8-2, research R19). V2 초안을 받아 결과를 돌려주는 순수 함수다. 대응표·용어 색인을 조회하지 않고 아무것도 바꾸지
 * 않는다. 필요한 사실(열린 대응표의 섹터, 알려진 용어, 오늘 날짜)은 호출자가 인자로 넘긴다.
 *
 * <p><b>열일곱 검사를 모두 실행하고 걸린 항목을 전부 돌려준다.</b> 결과는 §8-2 표의 순서이고, 같은 검사 안에서는 제목·질문·예시·블록·용어·확인 문제 순서다.
 * 위치는 §8-4다.
 *
 * <p><b>예외 자리는 요소 종류와 {@code (예:} 괄호로만 정한다</b>(R19). 금지 표현은 오해의 틀린 생각 칸·틀린 보기·출처 이름을 보지 않는다. 화면이 그
 * 자리에 '오해'·'틀린 보기' 표시를 붙인다(FR-017). 결과성 숫자는 가상의 계산 예·도식·오해·비교 표·확인 문제·제도 안내를 보지 않고, 문단과 목록에서는
 * {@code (예:} 괄호 안에서 시작하는 숫자를 걸지 않는다(FR-005, FR-026).
 *
 * <p><b>한계</b>: 금지 표현과 결과성 숫자는 패턴 검사다. 통과했다는 사실을 SC-002 달성의 근거로 삼지 않는다.
 */
public final class PublishCheckV2 {

    private PublishCheckV2() {}

    /**
     * @param openSectorCodes 지금 열린 대응표의 섹터 코드. 예시가 있을 때만 쓴다. 대응표를 읽지 못했으면 비어 있고, 그때 예시가 있는 글은
     *     {@code EXAMPLE_SECTOR_IN_MAP}으로 거절한다(원칙 VI)
     * @param knownTermIds 어느 글의 초안 용어 전체와 게시 용어 색인의 합집합. 언급 조각이 가리킬 수 있는 용어다
     * @param today 검사 기준 날짜(Asia/Seoul). 제도 안내의 기준 시점이 미래인지 가른다
     * @throws IllegalStateException V2 초안이 아니다
     */
    public static PublishCheckResult run(
            LearningArticle draft,
            PublishCheckRulesV2 rules,
            Optional<Set<String>> openSectorCodes,
            Set<TermId> knownTermIds,
            LocalDate today) {
        if (draft.format() != ArticleFormat.V2) {
            throw new IllegalStateException(
                    "v2 publish check received a "
                            + draft.format()
                            + " draft: "
                            + draft.articleId());
        }
        List<PublishCheckFailure> failures = new ArrayList<>();
        requiredFields(draft, failures);
        keySummary(draft.blocks(), failures);
        headingCount(draft.blocks(), failures);
        present(
                draft.blocks(),
                HypotheticalBlock.class,
                Check.HYPOTHETICAL_PRESENT,
                "가상의 계산 예가 없습니다.",
                failures);
        present(
                draft.blocks(),
                MisconceptionBlock.class,
                Check.MISCONCEPTION_PRESENT,
                "'자주 하는 오해'가 없습니다.",
                failures);
        readingTime(draft, rules.reading(), failures);
        if (draft.example() == null) {
            if (rules.exampleRequired()) {
                failures.add(PublishCheck.examplePresent());
            }
        } else {
            PublishCheck.exampleShape(draft.example(), openSectorCodes, failures);
        }
        PublishCheck.termBlocks(draft.blocks(), draft.terms(), failures);
        termSentences(draft.terms(), failures);
        termMentions(draft.blocks(), knownTermIds, failures);
        quiz(draft.quiz(), rules.quiz(), failures);
        elementFormat(draft.blocks(), today, failures);
        PublishCheck.forbiddenExpressions(
                forbiddenTargets(draft), rules.common().forbiddenGroups(), failures);
        PublishCheck.resultNumbers(
                numberTargets(draft, rules.exampleMarker()),
                rules.common(),
                "실제 결과로 읽히는 숫자는 가상의 예 요소나 '(예: …)' 안에만 쓸 수 있습니다.",
                failures);
        return new PublishCheckResult(failures);
    }

    /** 이 초안의 본문 글자 수로 계산한 읽기 값. 게시가 판에 넣는 값과 {@code READING_TIME} 판정이 같은 식을 쓴다. */
    public static ReadingEstimate reading(LearningArticle draft, ReadingRules rules) {
        return rules.estimate(ReadingEstimate.bodyChars(draft.blocks(), draft.terms()));
    }

    private static void requiredFields(LearningArticle draft, List<PublishCheckFailure> failures) {
        if (draft.title().isBlank()) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.title(), "제목이 비어 있습니다."));
        }
        if (draft.question().isBlank()) {
            failures.add(fail(Check.REQUIRED_FIELDS, CheckLocation.question(), "질문이 비어 있습니다."));
        }
    }

    /** 첫 블록이 세 줄 핵심 요약이고 빈 줄이 없는가(FR-002). */
    private static void keySummary(List<ArticleBlock> blocks, List<PublishCheckFailure> failures) {
        if (blocks.isEmpty() || !(blocks.getFirst() instanceof KeySummaryBlock summary)) {
            failures.add(fail(Check.KEY_SUMMARY, CheckLocation.body(), "글 맨 위에 핵심 요약이 없습니다."));
            return;
        }
        if (summary.lines().size() != KeySummaryBlock.MAX_LINES) {
            failures.add(
                    fail(
                            Check.KEY_SUMMARY,
                            CheckLocation.block(0),
                            "핵심 요약은 세 줄이어야 합니다. 지금 " + summary.lines().size() + "줄입니다."));
        }
        for (int line = 0; line < summary.lines().size(); line++) {
            if (summary.lines().get(line).plainText().isBlank()) {
                failures.add(
                        fail(
                                Check.KEY_SUMMARY,
                                CheckLocation.block(0, "lines[" + line + "]"),
                                "핵심 요약 " + (line + 1) + "번째 줄이 비어 있습니다."));
            }
        }
    }

    private static void headingCount(
            List<ArticleBlock> blocks, List<PublishCheckFailure> failures) {
        long headings = blocks.stream().filter(HeadingBlock.class::isInstance).count();
        if (headings < 3) {
            failures.add(
                    fail(
                            Check.HEADING_COUNT,
                            CheckLocation.body(),
                            "소제목이 세 개 이상이어야 합니다. 지금 " + headings + "개입니다."));
        }
    }

    private static void present(
            List<ArticleBlock> blocks,
            Class<? extends ArticleBlock> type,
            Check check,
            String message,
            List<PublishCheckFailure> failures) {
        if (blocks.stream().noneMatch(type::isInstance)) {
            failures.add(fail(check, CheckLocation.body(), message));
        }
    }

    /** 보이는 예상 읽기 분이 규칙 범위 안인가(R20). 문장에 계산한 분과 글자 수를 적어 운영자가 얼마나 늘리거나 줄일지 알게 한다. */
    private static void readingTime(
            LearningArticle draft, ReadingRules rules, List<PublishCheckFailure> failures) {
        ReadingEstimate estimate = reading(draft, rules);
        int minutes = estimate.readingMinutes();
        if (minutes < rules.minMinutes() || minutes > rules.maxMinutes()) {
            failures.add(
                    fail(
                            Check.READING_TIME,
                            CheckLocation.body(),
                            "예상 읽기 시간이 "
                                    + minutes
                                    + "분입니다(본문 "
                                    + NumberFormat.getIntegerInstance(Locale.KOREA)
                                            .format(estimate.bodyChars())
                                    + "자). "
                                    + rules.minMinutes()
                                    + "~"
                                    + rules.maxMinutes()
                                    + "분이어야 합니다."));
        }
    }

    /** 용어의 이름과 뜻·읽는 법·한계가 모두 있는가(FR-008). */
    private static void termSentences(
            List<LearningTerm> terms, List<PublishCheckFailure> failures) {
        for (LearningTerm term : terms) {
            TermId id = term.termId();
            if (term.label().isBlank()) {
                failures.add(
                        fail(
                                Check.TERM_SENTENCES,
                                CheckLocation.term(id, "label"),
                                "용어 이름이 비어 있습니다."));
            }
            if (term.meaning().isBlank()) {
                failures.add(
                        fail(
                                Check.TERM_SENTENCES,
                                CheckLocation.term(id, "meaning"),
                                "뜻 문장이 비어 있습니다."));
            }
            if (term.reading().isBlank()) {
                failures.add(
                        fail(
                                Check.TERM_SENTENCES,
                                CheckLocation.term(id, "reading"),
                                "읽는 법 문장이 비어 있습니다."));
            }
            if (term.caveat().isBlank()) {
                failures.add(
                        fail(
                                Check.TERM_SENTENCES,
                                CheckLocation.term(id, "caveat"),
                                "한계 문장이 비어 있습니다."));
            }
        }
    }

    /** 언급 조각이 어느 글의 용어로든 있는가(FR-009). 같은 자리의 같은 용어는 한 번만 보고한다. */
    private static void termMentions(
            List<ArticleBlock> blocks,
            Set<TermId> knownTermIds,
            List<PublishCheckFailure> failures) {
        for (int index = 0; index < blocks.size(); index++) {
            for (RichTextField field : blocks.get(index).richTextFields()) {
                Set<TermId> reported = new LinkedHashSet<>();
                for (TermMentionRun mention : field.text().mentions()) {
                    if (!knownTermIds.contains(mention.termId())
                            && reported.add(mention.termId())) {
                        failures.add(
                                fail(
                                        Check.TERM_MENTION_KNOWN,
                                        CheckLocation.block(index, field.field()),
                                        "어느 글에도 없는 용어를 가리킵니다: " + mention.termId() + "."));
                    }
                }
            }
        }
    }

    /** 확인 문제의 게시 조건(data-model §6-1, FR-022). */
    private static void quiz(
            List<QuizQuestion> questions, QuizRules rules, List<PublishCheckFailure> failures) {
        if (questions.size() < rules.minQuestions() || questions.size() > rules.maxQuestions()) {
            failures.add(
                    fail(
                            Check.QUIZ,
                            CheckLocation.quiz(),
                            "확인 문제는 "
                                    + rules.minQuestions()
                                    + "~"
                                    + rules.maxQuestions()
                                    + "개여야 합니다. 지금 "
                                    + questions.size()
                                    + "개입니다."));
        }
        for (int index = 0; index < questions.size(); index++) {
            QuizQuestion question = questions.get(index);
            CheckLocation location = CheckLocation.quizQuestion(question.questionId(), index);
            if (question.prompt().isBlank()) {
                failures.add(fail(Check.QUIZ, location, "질문이 비어 있습니다."));
            }
            int choices = question.choices().size();
            if (choices < rules.minChoices() || choices > rules.maxChoices()) {
                failures.add(
                        fail(
                                Check.QUIZ,
                                location,
                                "보기는 "
                                        + rules.minChoices()
                                        + "~"
                                        + rules.maxChoices()
                                        + "개여야 합니다. 지금 "
                                        + choices
                                        + "개입니다."));
            }
            for (QuizChoice choice : question.choices()) {
                if (choice.text().isBlank()) {
                    failures.add(
                            fail(
                                    Check.QUIZ,
                                    CheckLocation.quizChoice(
                                            question.questionId(), index, choice.choiceId()),
                                    "보기 글자가 비어 있습니다."));
                }
            }
            if (question.correctChoiceIds().size() != 1
                    || question.choices().stream().filter(QuizChoice::correct).count() != 1) {
                failures.add(fail(Check.QUIZ, location, "정답 보기가 하나가 아닙니다."));
            }
            if (question.explanation().isBlank()) {
                failures.add(fail(Check.QUIZ, location, "풀이가 비어 있습니다."));
            }
        }
    }

    /** 도식 설명, 제도 안내의 기준 시점·출처, 출처 표시의 이름(FR-026, FR-007). */
    private static void elementFormat(
            List<ArticleBlock> blocks, LocalDate today, List<PublishCheckFailure> failures) {
        for (int index = 0; index < blocks.size(); index++) {
            ArticleBlock block = blocks.get(index);
            if (block instanceof DiagramBlock diagram && diagram.description().isBlank()) {
                failures.add(
                        fail(
                                Check.ELEMENT_FORMAT,
                                CheckLocation.block(index, "description"),
                                "도식 설명 문장이 비어 있습니다. 그림을 그리지 못하는 화면은 이 문장만 보입니다."));
            }
            if (block instanceof InstitutionNoteBlock note) {
                if (note.asOf() == null) {
                    failures.add(
                            fail(
                                    Check.ELEMENT_FORMAT,
                                    CheckLocation.block(index, "asOf"),
                                    "제도 안내의 기준 시점이 없습니다."));
                } else if (note.asOf().isAfter(today)) {
                    failures.add(
                            fail(
                                    Check.ELEMENT_FORMAT,
                                    CheckLocation.block(index, "asOf"),
                                    "제도 안내의 기준 시점(" + note.asOf() + ")이 오늘보다 뒤입니다."));
                }
                if (note.source() == null) {
                    failures.add(
                            fail(
                                    Check.ELEMENT_FORMAT,
                                    CheckLocation.block(index, "source"),
                                    "제도 안내의 출처가 없습니다."));
                } else if (note.source().label().isBlank()) {
                    failures.add(
                            fail(
                                    Check.ELEMENT_FORMAT,
                                    CheckLocation.block(index, "source"),
                                    "제도 안내의 출처 이름이 비어 있습니다."));
                }
            }
            for (RichTextField field : block.richTextFields()) {
                for (SourceRun source : field.text().sources()) {
                    if (source.source().label().isBlank()) {
                        failures.add(
                                fail(
                                        Check.ELEMENT_FORMAT,
                                        CheckLocation.block(index, field.field()),
                                        "출처 표시의 이름이 비어 있습니다."));
                        break;
                    }
                }
            }
        }
    }

    /**
     * 금지 표현을 찾을 텍스트(data-model §8-2 '금지 표현 대상'). 위치 순서는 제목·질문·블록·용어·확인 문제다.
     *
     * <p>보지 않는 것: 오해의 틀린 생각 칸, 틀린 보기, 출처 표시와 제도 안내 출처의 이름.
     */
    private static List<LocatedText> forbiddenTargets(LearningArticle draft) {
        List<LocatedText> texts = new ArrayList<>();
        texts.add(new LocatedText(CheckLocation.title(), draft.title()));
        texts.add(new LocatedText(CheckLocation.question(), draft.question()));
        List<ArticleBlock> blocks = draft.blocks();
        for (int index = 0; index < blocks.size(); index++) {
            ArticleBlock block = blocks.get(index);
            switch (block) {
                case HeadingBlock heading ->
                        texts.add(
                                new LocatedText(
                                        CheckLocation.block(index, "text"), heading.text()));
                case MisconceptionBlock misconception ->
                        texts.add(
                                new LocatedText(
                                        CheckLocation.block(index, "correction"),
                                        misconception.correction().plainText()));
                case TableBlock table -> {
                    texts.add(
                            new LocatedText(
                                    CheckLocation.block(index, "caption"), table.caption()));
                    for (int column = 0; column < table.columns().size(); column++) {
                        texts.add(
                                new LocatedText(
                                        CheckLocation.block(index, "columns[" + column + "]"),
                                        table.columns().get(column)));
                    }
                    addRichTextFields(texts, index, block);
                }
                case DiagramBlock diagram -> {
                    texts.add(
                            new LocatedText(CheckLocation.block(index, "title"), diagram.title()));
                    for (int series = 0; series < diagram.series().size(); series++) {
                        texts.add(
                                new LocatedText(
                                        CheckLocation.block(index, "series[" + series + "].name"),
                                        diagram.series().get(series).name()));
                    }
                    texts.add(
                            new LocatedText(
                                    CheckLocation.block(index, "description"),
                                    diagram.description()));
                }
                case TermBlock ignored -> {
                    // 문장은 용어 설명에 있다. 아래에서 용어 위치로 본다.
                }
                default -> addRichTextFields(texts, index, block);
            }
        }
        for (LearningTerm term : draft.terms()) {
            TermId id = term.termId();
            texts.add(new LocatedText(CheckLocation.term(id, "label"), term.label()));
            for (int alias = 0; alias < term.aliases().size(); alias++) {
                texts.add(
                        new LocatedText(
                                CheckLocation.term(id, "aliases[" + alias + "]"),
                                term.aliases().get(alias)));
            }
            texts.add(new LocatedText(CheckLocation.term(id, "meaning"), term.meaning()));
            texts.add(new LocatedText(CheckLocation.term(id, "reading"), term.reading()));
            texts.add(new LocatedText(CheckLocation.term(id, "caveat"), term.caveat()));
        }
        List<QuizQuestion> questions = draft.quiz();
        for (int index = 0; index < questions.size(); index++) {
            QuizQuestion question = questions.get(index);
            CheckLocation location = CheckLocation.quizQuestion(question.questionId(), index);
            texts.add(new LocatedText(location, question.prompt()));
            for (QuizChoice choice : question.choices()) {
                if (choice.correct()) {
                    texts.add(
                            new LocatedText(
                                    CheckLocation.quizChoice(
                                            question.questionId(), index, choice.choiceId()),
                                    choice.text()));
                }
            }
            texts.add(new LocatedText(location, question.explanation()));
        }
        return texts;
    }

    /**
     * 결과성 숫자를 찾을 텍스트(data-model §8-2 '숫자 검사 대상'): 제목, 질문, 소제목, 핵심 요약, 문단, 목록, 용어의 이름·다른 이름·세 문장.
     * 문단과 목록은 {@code exampleMarker} 괄호 안을 허용 구간으로 둔다.
     */
    private static List<LocatedText> numberTargets(LearningArticle draft, String exampleMarker) {
        List<LocatedText> texts = new ArrayList<>();
        texts.add(new LocatedText(CheckLocation.title(), draft.title()));
        texts.add(new LocatedText(CheckLocation.question(), draft.question()));
        List<ArticleBlock> blocks = draft.blocks();
        for (int index = 0; index < blocks.size(); index++) {
            ArticleBlock block = blocks.get(index);
            switch (block) {
                case HeadingBlock heading ->
                        texts.add(
                                new LocatedText(
                                        CheckLocation.block(index, "text"), heading.text()));
                case KeySummaryBlock ignored -> addRichTextFields(texts, index, block);
                case ParagraphBlock ignored ->
                        addRichTextFields(texts, index, block, exampleMarker);
                case ListBlock ignored -> addRichTextFields(texts, index, block, exampleMarker);
                default -> {
                    // 가상의 예 요소·오해·비교 표·제도 안내·용어 블록은 숫자를 보지 않는다(FR-005, FR-026).
                }
            }
        }
        for (LearningTerm term : draft.terms()) {
            TermId id = term.termId();
            texts.add(new LocatedText(CheckLocation.term(id, "label"), term.label()));
            for (int alias = 0; alias < term.aliases().size(); alias++) {
                texts.add(
                        new LocatedText(
                                CheckLocation.term(id, "aliases[" + alias + "]"),
                                term.aliases().get(alias)));
            }
            texts.add(new LocatedText(CheckLocation.term(id, "meaning"), term.meaning()));
            texts.add(new LocatedText(CheckLocation.term(id, "reading"), term.reading()));
            texts.add(new LocatedText(CheckLocation.term(id, "caveat"), term.caveat()));
        }
        return texts;
    }

    private static void addRichTextFields(List<LocatedText> texts, int index, ArticleBlock block) {
        addRichTextFields(texts, index, block, null);
    }

    private static void addRichTextFields(
            List<LocatedText> texts, int index, ArticleBlock block, String exemptMarker) {
        for (RichTextField field : block.richTextFields()) {
            texts.add(
                    new LocatedText(
                            CheckLocation.block(index, field.field()),
                            field.text().plainText(),
                            exemptMarker));
        }
    }

    private static PublishCheckFailure fail(Check check, CheckLocation location, String message) {
        return PublishCheck.fail(check, location, message);
    }
}
