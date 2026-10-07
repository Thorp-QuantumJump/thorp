package com.ssafy.thorp.learning.application.result;

import com.ssafy.thorp.learning.application.result.LearnerParts.Gained;
import com.ssafy.thorp.learning.application.result.LearnerParts.QuizSummary;

/**
 * 첫 답(learning-me-api §5)과 다시 풀기(§6)의 결과. 서버가 공개 판으로 판정한 뒤 돌려준다. 화면은 이 응답을 받은 뒤에만 정답과 풀이를 보인다
 * (FR-022).
 *
 * @param choiceId 첫 답이면 저장된 보기다. 다른 탭이 먼저 답했으면 요청과 다를 수 있다
 * @param alreadyAnswered 첫 답에서 이미 답한 문제였다. 다시 풀기에는 없어 null이다
 */
public record AnswerResult(
        String questionId,
        String choiceId,
        boolean correct,
        String correctChoiceId,
        String explanation,
        Boolean alreadyAnswered,
        QuizSummary quiz,
        Gained gained) {}
