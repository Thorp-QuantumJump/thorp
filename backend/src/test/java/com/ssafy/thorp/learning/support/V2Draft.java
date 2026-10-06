package com.ssafy.thorp.learning.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 관리 API 저장 요청(learning-admin-api §5)으로 보낼 V2 초안. 기본값은 v2 검사 열일곱 가지를 모두 통과한다.
 *
 * <p>본문은 약 2,100자(4분)이고 숫자는 가상의 계산 예에만 있다. 언급 조각의 {@code max-drawdown}은 {@code returns-and-losses}의
 * 초안 용어라 알려진 용어다. 용어마다 용어 블록 하나를 둔다. 검사에 일부러 걸리게 하는 선택({@link #keySummaryLines}, {@link
 * #secondQuestionBothCorrect})이 있다.
 *
 * <p>관리 V2·단계 공개·넘겨받기 통합 테스트가 같은 초안을 쓴다. 문장은 합성 값이며 원고가 아니다.
 */
public final class V2Draft {

    private final ObjectMapper objectMapper;
    private final String title;
    private final String stageKey;
    private final int stagePosition;
    private final List<Term> terms = new ArrayList<>();
    private String question = "값이 오르내린 정도와 잃은 돈은 어떻게 다를까?";
    private int readingOrder = 100;
    private int keySummaryLines = 3;
    private int firstQuestionChoices = 2;
    private int secondQuestionCorrectChoice;
    private boolean secondQuestionBothCorrect;
    private List<String> questionIds;
    private List<String> choiceIds;

    private record Term(
            String termId,
            String label,
            String meaning,
            List<String> aliases,
            String transferFrom) {}

    private V2Draft(ObjectMapper objectMapper, String title, String stageKey, int stagePosition) {
        this.objectMapper = objectMapper;
        this.title = title;
        this.stageKey = stageKey;
        this.stagePosition = stagePosition;
    }

    public static V2Draft of(
            ObjectMapper objectMapper, String title, String stageKey, int stagePosition) {
        return new V2Draft(objectMapper, title, stageKey, stagePosition);
    }

    /** 이 글이 소유하는 용어. 뜻 문장은 {@code "{label}의 뜻 문장이다."}다. */
    public V2Draft term(String termId, String label) {
        return term(termId, label, label + "의 뜻 문장이다.");
    }

    /** 이 글이 소유하는 용어와 그 뜻 문장·다른 이름. */
    public V2Draft term(String termId, String label, String meaning, String... aliases) {
        terms.add(new Term(termId, label, meaning, List.of(aliases), null));
        return this;
    }

    /** 다른 글에서 넘겨받는 용어. */
    public V2Draft transferredTerm(String termId, String label, String transferFrom) {
        terms.add(new Term(termId, label, label + "의 뜻 문장이다.", List.of(), transferFrom));
        return this;
    }

    /** 글의 질문. */
    public V2Draft question(String question) {
        this.question = question;
        return this;
    }

    public V2Draft readingOrder(int readingOrder) {
        this.readingOrder = readingOrder;
        return this;
    }

    /** 핵심 요약 줄 수. 3이 아니면 {@code KEY_SUMMARY}에 걸린다. */
    public V2Draft keySummaryLines(int lines) {
        this.keySummaryLines = lines;
        return this;
    }

    /** 첫 문제의 보기 수(2~5). 식별자 번호를 밀 때 쓴다. 정답은 마지막 보기다. */
    public V2Draft firstQuestionChoices(int choices) {
        this.firstQuestionChoices = choices;
        return this;
    }

    /** 둘째 문제의 정답 보기(0 또는 1). 바꾸면 같은 문제 판정(data-model §6-3)에서 새 문제가 된다. */
    public V2Draft secondQuestionCorrectChoice(int choice) {
        this.secondQuestionCorrectChoice = choice;
        return this;
    }

    /**
     * 문제·보기 식별자. 저장 응답에서 받은 식별자를 다시 보낼 때 쓴다. 없으면 비워 두고 서버가 준다.
     *
     * @param questionIds 두 문제의 식별자, 문제 순서
     * @param choiceIds 모든 보기의 식별자, 첫 문제의 보기부터 순서대로
     */
    public V2Draft quizIds(List<String> questionIds, List<String> choiceIds) {
        this.questionIds = Objects.requireNonNull(questionIds);
        this.choiceIds = Objects.requireNonNull(choiceIds);
        return this;
    }

    /** 둘째 문제의 두 보기를 모두 정답으로 둔다. {@code QUIZ}("정답 보기가 하나가 아닙니다.")에 걸린다. */
    public V2Draft secondQuestionBothCorrect() {
        this.secondQuestionBothCorrect = true;
        return this;
    }

    public ObjectNode build(int expectedDraftVersion) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("expectedDraftVersion", expectedDraftVersion);
        body.put("title", title);
        body.put("question", question);
        body.put("readingOrder", readingOrder);
        body.put("format", "V2");
        body.put("stageKey", stageKey);
        body.put("stagePosition", stagePosition);
        body.putNull("example");
        body.putArray("sources");

        ArrayNode blocks = body.putArray("blocks");
        ArrayNode summary = blocks.addObject().put("type", "keySummary").putArray("lines");
        List<String> lines = List.of("오르내림은 폭을 잰다.", "끝 값이 같을 수 있다.", "손실은 시작과 끝으로 본다.");
        for (int line = 0; line < keySummaryLines; line++) {
            text(summary.addArray(), lines.get(line % lines.size()));
        }
        heading(blocks, "오르내림은 무엇을 재나");
        ArrayNode mention = paragraph(blocks);
        text(mention, "오르내린 폭은 ");
        mention.addObject().put("term", "max-drawdown").put("text", "가장 크게 줄었던 폭");
        text(mention, "과 따로 본다.");
        for (Term term : terms) {
            blocks.addObject().put("type", "term").put("termId", term.termId());
        }
        heading(blocks, "오르내림과 손실");
        text(
                blocks.addObject().put("type", "hypothetical").putArray("content"),
                "100만 원이 80만 원이 되었다가 100만 원으로 돌아오면 손실은 0%다.");
        ObjectNode misconception = blocks.addObject().put("type", "misconception");
        text(misconception.putArray("wrong"), "크게 오르내렸으니 그만큼 잃었다.");
        text(misconception.putArray("correction"), "오르내린 폭과 손익은 따로 본다.");
        heading(blocks, "읽을 때 볼 것");
        text(paragraph(blocks), "가".repeat(2000));

        ArrayNode termNodes = body.putArray("terms");
        for (Term term : terms) {
            ObjectNode node =
                    termNodes
                            .addObject()
                            .put("termId", term.termId())
                            .put("label", term.label())
                            .put("meaning", term.meaning())
                            .put("reading", "클수록 값이 크게 흔들렸다는 뜻이다.")
                            .put("caveat", "손실의 크기를 직접 말하지 않는다.");
            ArrayNode aliases = node.putArray("aliases");
            term.aliases().forEach(aliases::add);
            if (term.transferFrom() != null) {
                node.put("transferFrom", term.transferFrom());
            }
        }

        ArrayNode quiz = body.putArray("quiz");
        int choiceIndex = 0;
        ObjectNode first =
                question(quiz, id(questionIds, 0), "오르내림이 크면 반드시 잃었을까?", "끝 값을 함께 봐야 한다.");
        for (int choice = 0; choice < firstQuestionChoices; choice++) {
            choice(
                    first,
                    id(choiceIds, choiceIndex++),
                    "보기 " + (choice + 1),
                    choice == firstQuestionChoices - 1);
        }
        ObjectNode second = question(quiz, id(questionIds, 1), "손실은 무엇으로 보나?", "시작과 끝을 비교한다.");
        choice(
                second,
                id(choiceIds, choiceIndex++),
                "시작과 끝",
                secondQuestionBothCorrect || secondQuestionCorrectChoice == 0);
        choice(
                second,
                id(choiceIds, choiceIndex),
                "오르내린 폭",
                secondQuestionBothCorrect || secondQuestionCorrectChoice == 1);
        return body;
    }

    private static String id(List<String> ids, int index) {
        return ids == null ? null : ids.get(index);
    }

    private static void text(ArrayNode runs, String text) {
        runs.addObject().put("text", text);
    }

    private static void heading(ArrayNode blocks, String text) {
        blocks.addObject().put("type", "heading").put("text", text);
    }

    private static ArrayNode paragraph(ArrayNode blocks) {
        return blocks.addObject().put("type", "paragraph").putArray("content");
    }

    private static ObjectNode question(
            ArrayNode quiz, String id, String prompt, String explanation) {
        ObjectNode question = quiz.addObject();
        if (id == null) {
            question.putNull("questionId");
        } else {
            question.put("questionId", id);
        }
        question.put("prompt", prompt);
        question.putArray("choices");
        question.put("explanation", explanation);
        return question;
    }

    private static void choice(ObjectNode question, String id, String text, boolean correct) {
        ObjectNode choice = ((ArrayNode) question.get("choices")).addObject();
        if (id == null) {
            choice.putNull("choiceId");
        } else {
            choice.put("choiceId", id);
        }
        choice.put("text", text).put("correct", correct);
    }
}
