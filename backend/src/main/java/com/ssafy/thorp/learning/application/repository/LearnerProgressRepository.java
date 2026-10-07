package com.ssafy.thorp.learning.application.repository;

import com.ssafy.thorp.learning.domain.model.LearnerProgress;
import com.ssafy.thorp.learning.domain.model.LearnerProgress.MasteredVia;
import com.ssafy.thorp.learning.domain.vo.ArticleId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 사용자 × 글 기록({@code learning_progress}, data-model §11-1). 모든 메서드는 그 사용자의 행만 읽고 쓴다.
 *
 * <p>완료 시각과 익힘은 한 번 쓴 뒤 비우지 않는다. 지우는 경로는 {@link #deleteAllForUser}('학습 초기화') 하나다(FR-025).
 */
public interface LearnerProgressRepository {

    /** 그 사용자의 모든 글 기록. 게시 취소된 글의 기록도 담는다. */
    List<LearnerProgress> findAll(long userId);

    Optional<LearnerProgress> find(long userId, ArticleId articleId);

    /** 행을 잠그고 읽는다. 같은 글의 답 처리가 겹치지 않게 한다(research R29). */
    Optional<LearnerProgress> findForUpdate(long userId, ArticleId articleId);

    /** 읽은 위치. 행이 없으면 만들고(글이 '읽는 중'이 된다), 있으면 더 큰 비율만 남기고 마지막으로 연 시각은 늘 바꾼다(learning-me-api §3). */
    void recordPosition(long userId, ArticleId articleId, int percent, Instant at);

    /**
     * 완료. 행이 없으면 만들고 비율을 100으로 둔다. 이미 완료한 글이면 완료 시각을 바꾸지 않는다. 마지막으로 연 시각은 늘 바꾼다.
     *
     * @return 저장된 완료 시각. 이번에 완료했으면 {@code at}과 같다
     */
    Instant recordCompletion(long userId, ArticleId articleId, Instant at);

    /** 처음 모든 문제에 답한 시각. 이미 있으면 바꾸지 않는다. 행이 있어야 한다. */
    void markAllAnswered(long userId, ArticleId articleId, Instant at);

    /** 익힘. 이미 익힘이면 바꾸지 않는다. 행이 있어야 한다. */
    void markMastered(long userId, ArticleId articleId, MasteredVia via, Instant at);

    /** 그 사용자의 행을 모두 지운다('학습 초기화'). */
    void deleteAllForUser(long userId);
}
