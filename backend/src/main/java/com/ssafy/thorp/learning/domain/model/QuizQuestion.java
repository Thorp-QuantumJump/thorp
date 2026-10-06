package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 확인 문제 하나(data-model §6). 초안과 판이 같은 모양이다. 판에서는 식별자가 모두 채워져 있고 정답 보기가 정확히 하나다.
 *
 * <p>여기서 거절하는 것은 저장할 수 없는 모양(길이·보기 수·문제 안 보기 식별자 중복)이다. 질문·풀이가 비었는지, 보기가 2~5개이고 정답이 하나인지는 게시 전
 * 검사({@code QUIZ})가 본다.
 *
 * @param questionId 저장 전 새 문제면 null
 * @param prompt 0~200자
 * @param choices 0~5개
 * @param explanation 0~300자
 */
public record QuizQuestion(
        QuestionId questionId, String prompt, List<QuizChoice> choices, String explanation) {

    public static final int PROMPT_MAX_LENGTH = 200;
    public static final int MAX_CHOICES = 5;
    public static final int EXPLANATION_MAX_LENGTH = 300;

    /** 한 글에 저장할 수 있는 문제 수의 상한. 게시 조건(2~3개)은 규칙 파일이 정한다. */
    public static final int MAX_QUESTIONS = 3;

    public QuizQuestion {
        Objects.requireNonNull(prompt, "question prompt must not be null");
        Objects.requireNonNull(explanation, "question explanation must not be null");
        if (prompt.length() > PROMPT_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "question prompt must be at most " + PROMPT_MAX_LENGTH + " characters");
        }
        if (explanation.length() > EXPLANATION_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "question explanation must be at most "
                            + EXPLANATION_MAX_LENGTH
                            + " characters");
        }
        choices = List.copyOf(choices);
        if (choices.size() > MAX_CHOICES) {
            throw new IllegalArgumentException(
                    "a question must have at most " + MAX_CHOICES + " choices: " + choices.size());
        }
        Set<ChoiceId> seen = new HashSet<>();
        for (QuizChoice choice : choices) {
            if (choice.choiceId() != null && !seen.add(choice.choiceId())) {
                throw new IllegalArgumentException(
                        "duplicate choiceId in question: " + choice.choiceId());
            }
        }
    }

    /** 정답 보기의 식별자. 게시된 문제면 하나다. */
    public Set<ChoiceId> correctChoiceIds() {
        Set<ChoiceId> correct = new LinkedHashSet<>();
        for (QuizChoice choice : choices) {
            if (choice.correct()) {
                correct.add(choice.choiceId());
            }
        }
        return correct;
    }

    /** 보기 식별자 집합. */
    public Set<ChoiceId> choiceIds() {
        Set<ChoiceId> ids = new LinkedHashSet<>();
        choices.forEach(choice -> ids.add(choice.choiceId()));
        return ids;
    }

    QuizQuestion withIds(QuestionId id, List<QuizChoice> identifiedChoices) {
        return new QuizQuestion(id, prompt, identifiedChoices, explanation);
    }
}
