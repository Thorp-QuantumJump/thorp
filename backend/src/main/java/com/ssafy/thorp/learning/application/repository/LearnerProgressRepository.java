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

    /**
     * 그 사용자의 나의 학습 쓰기를 줄 세우는 트랜잭션 잠금. 트랜잭션이 끝나면 풀린다.
     *
     * <p>나의 학습을 쓰는 요청(정산하는 조회·위치·완료·첫 답·다시 풀기·학습 초기화)은 모두 첫 문장에서 이 잠금을 잡는다. 행 잠금만으로는 학습 초기화가 지우는 사이
     * 다른 요청이 넣은 답·장부·배지 행이 초기화 뒤에 남는다(SC-015). 같은 사용자의 정산 둘이 서로의 새 줄을 보지 못해 단계 완주나 {@code gained}를
     * 놓치는 일도 막는다.
     */
    void lockLearner(long userId);

    /** 그 사용자의 모든 글 기록. 게시 취소된 글의 기록도 담는다. */
    List<LearnerProgress> findAll(long userId);

    Optional<LearnerProgress> find(long userId, ArticleId articleId);

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
