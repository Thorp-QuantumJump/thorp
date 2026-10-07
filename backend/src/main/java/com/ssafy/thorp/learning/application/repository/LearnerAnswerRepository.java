package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.FirstAnswer;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.util.List;

/**
 * 첫 답({@code learning_answer}, data-model §11-2). 추가만 한다. 고른 보기를 바꾸거나 답 하나를 지우는 메서드가 없다(FR-022).
 */
public interface LearnerAnswerRepository {

    /** 그 사용자의 모든 첫 답. 지운 문제와 게시 취소된 글의 답도 담는다. */
    List<FirstAnswer> findAll(long userId);

    /** 그 사용자가 이 글에 남긴 첫 답. */
    List<FirstAnswer> findByArticle(long userId, ArticleId articleId);

    /**
     * 같은 (글, 문제)의 답이 없을 때만 넣고({@code ON CONFLICT DO NOTHING}) 저장된 행을 읽는다. 여러 탭이 동시에 답해도 첫 답은
     * 하나다(SC-009).
     */
    Stored insertIfAbsent(long userId, FirstAnswer answer);

    /** 그 사용자의 행을 모두 지운다('학습 초기화'). */
    void deleteAllForUser(long userId);

    /**
     * @param answer 저장된 첫 답. 이번에 넣지 않았으면 먼저 있던 답이다
     * @param inserted 이번 요청이 넣었다
     */
    record Stored(FirstAnswer answer, boolean inserted) {}
}
