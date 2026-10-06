package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import com.ssafy.thorp.learning.domain.vo.TermId;
import java.util.Objects;

/**
 * 게시 전 검사에 걸린 위치(data-model §8-4, learning-admin-api §6).
 *
 * <p>v1 검사는 {@code index}·{@code termId}만 쓴다. 그래서 v1 거절 응답의 모양은 9월 판 그대로다. v2 검사는 블록·용어·문제 안의
 * 필드({@code field})와 확인 문제 위치를 더한다.
 *
 * @param index {@code BLOCK}이면 {@code blocks} 순번(0부터), {@code QUIZ}면 식별자 없는 새 문제의 순번. 본문 전체나 문제 전체를
 *     가리키면 null이다
 * @param termId {@code TERM}일 때만 있다
 * @param field 블록·용어·문제 안의 필드. 예: {@code lines[1]}, {@code wrong}, {@code rows[0][1]}, {@code
 *     meaning}, 문제의 {@code prompt}·{@code explanation}
 * @param questionId {@code QUIZ}에서 문제를 가리킬 때
 * @param choiceId {@code QUIZ}에서 보기를 가리킬 때
 */
public record CheckLocation(
        Part part,
        Integer index,
        TermId termId,
        String field,
        QuestionId questionId,
        ChoiceId choiceId) {

    /** 위치의 종류. 응답에는 소문자 이름으로 나간다. */
    public enum Part {
        /** 글 전체(형식·배치). 단계 공개의 글별 항목만 쓴다. */
        ARTICLE,
        TITLE,
        QUESTION,
        EXAMPLE,
        BLOCK,
        TERM,
        QUIZ
    }

    public CheckLocation {
        Objects.requireNonNull(part, "part must not be null");
        if (index != null && part != Part.BLOCK && part != Part.QUIZ) {
            throw new IllegalArgumentException(
                    "index is only for block and quiz locations: " + part);
        }
        if ((termId != null) != (part == Part.TERM)) {
            throw new IllegalArgumentException("termId must be present exactly for term locations");
        }
        if (field != null && part != Part.BLOCK && part != Part.TERM && part != Part.QUIZ) {
            throw new IllegalArgumentException(
                    "field is only for block, term and quiz locations: " + part);
        }
        if ((questionId != null || choiceId != null) && part != Part.QUIZ) {
            throw new IllegalArgumentException(
                    "question and choice ids are only for quiz locations");
        }
    }

    /** 글 전체. 단계 공개에서 초안 형식({@code FORMAT})과 초안 배치({@code PLACEMENT})를 가리킨다. */
    public static CheckLocation article() {
        return new CheckLocation(Part.ARTICLE, null, null, null, null, null);
    }

    public static CheckLocation title() {
        return new CheckLocation(Part.TITLE, null, null, null, null, null);
    }

    public static CheckLocation question() {
        return new CheckLocation(Part.QUESTION, null, null, null, null, null);
    }

    public static CheckLocation example() {
        return new CheckLocation(Part.EXAMPLE, null, null, null, null, null);
    }

    public static CheckLocation block(int index) {
        return new CheckLocation(Part.BLOCK, index, null, null, null, null);
    }

    /** 블록 안의 필드. */
    public static CheckLocation block(int index, String field) {
        return new CheckLocation(Part.BLOCK, index, null, field, null, null);
    }

    /** 특정 블록이 아니라 본문 전체. */
    public static CheckLocation body() {
        return new CheckLocation(Part.BLOCK, null, null, null, null, null);
    }

    public static CheckLocation term(TermId termId) {
        return new CheckLocation(Part.TERM, null, termId, null, null, null);
    }

    /** 용어 안의 필드. */
    public static CheckLocation term(TermId termId, String field) {
        return new CheckLocation(Part.TERM, null, termId, field, null, null);
    }

    /** 확인 문제 전체(문제 수 등). */
    public static CheckLocation quiz() {
        return new CheckLocation(Part.QUIZ, null, null, null, null, null);
    }

    /** 문제 하나. 식별자가 없는 새 문제면 순번으로 가리킨다. */
    public static CheckLocation quizQuestion(QuestionId questionId, int index) {
        return questionId == null
                ? new CheckLocation(Part.QUIZ, index, null, null, null, null)
                : new CheckLocation(Part.QUIZ, null, null, null, questionId, null);
    }

    /**
     * 문제 안의 글자 칸 하나({@code prompt}·{@code explanation}). 같은 문제의 두 칸에 같은 표현이 있으면 둘을 따로 보고해야 운영자가 한
     * 번에 고친다.
     */
    public static CheckLocation quizQuestion(QuestionId questionId, int index, String field) {
        return questionId == null
                ? new CheckLocation(Part.QUIZ, index, null, field, null, null)
                : new CheckLocation(Part.QUIZ, null, null, field, questionId, null);
    }

    /** 문제의 보기 하나. */
    public static CheckLocation quizChoice(QuestionId questionId, int index, ChoiceId choiceId) {
        return questionId == null
                ? new CheckLocation(Part.QUIZ, index, null, null, null, choiceId)
                : new CheckLocation(Part.QUIZ, null, null, null, questionId, choiceId);
    }
}
