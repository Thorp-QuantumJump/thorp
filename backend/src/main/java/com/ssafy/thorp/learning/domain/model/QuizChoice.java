package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import java.util.Objects;

/**
 * 확인 문제의 보기 하나(data-model §6-1).
 *
 * @param choiceId 저장 전 새 보기면 null. 저장이 {@code quiz_seq}로 채운다
 * @param text 0~100자. 비었는지는 게시 전 검사({@code QUIZ})가 본다
 * @param correct 정답 보기인가. 정답이 정확히 하나인지는 게시 전 검사가 본다
 */
public record QuizChoice(ChoiceId choiceId, String text, boolean correct) {

    public static final int TEXT_MAX_LENGTH = 100;

    public QuizChoice {
        Objects.requireNonNull(text, "choice text must not be null");
        if (text.length() > TEXT_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "choice text must be at most " + TEXT_MAX_LENGTH + " characters");
        }
    }

    QuizChoice withId(ChoiceId id) {
        return new QuizChoice(id, text, correct);
    }
}
