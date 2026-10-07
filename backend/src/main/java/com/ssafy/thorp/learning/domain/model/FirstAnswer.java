package com.ssafy.thorp.learning.domain.model;

import com.ssafy.thorp.learning.domain.vo.ArticleId;
import com.ssafy.thorp.learning.domain.vo.ChoiceId;
import com.ssafy.thorp.learning.domain.vo.QuestionId;
import java.time.Instant;
import java.util.Objects;

/**
 * 확인 문제 하나의 첫 답({@code learning_answer} 한 행, data-model §11-2). 추가만 하고 고른 보기를 바꾸는 경로가 없다(FR-022).
 *
 * @param correct 서버가 답할 때의 공개 판으로 판정한 결과. 뒤에 문제가 바뀌어도 다시 판정하지 않는다
 */
public record FirstAnswer(
        ArticleId articleId,
        QuestionId questionId,
        ChoiceId choiceId,
        boolean correct,
        Instant answeredAt) {

    public FirstAnswer {
        Objects.requireNonNull(articleId, "articleId must not be null");
        Objects.requireNonNull(questionId, "questionId must not be null");
        Objects.requireNonNull(choiceId, "choiceId must not be null");
        Objects.requireNonNull(answeredAt, "answeredAt must not be null");
    }
}
