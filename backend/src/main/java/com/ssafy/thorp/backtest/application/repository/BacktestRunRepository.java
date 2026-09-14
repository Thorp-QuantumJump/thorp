package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 백테스트 Job 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. */
public interface BacktestRunRepository {

    /**
     * 신규 Job 생성 전용. 저장하면서 외부 식별자 {@code backtestId}를 부여하고, id와 backtestId가 채워진 Job을 돌려준다.
     *
     * <p>id가 이미 있는 Job이면 {@link IllegalArgumentException}. 상태 변경은 {@link #update(BacktestRun)}.
     */
    BacktestRun save(BacktestRun job);

    /**
     * 저장된 Job의 상태 전이를 반영한다. 도메인에서 바뀔 수 있는 값은 실행 상태({@code RunState})뿐이므로 그것만 갱신하고, 갱신된 Job을 돌려준다.
     *
     * <p>id가 없는 Job이거나 저장소에 없는 id면 {@link IllegalArgumentException}.
     */
    BacktestRun update(BacktestRun job);

    Optional<BacktestRun> findById(Long id);

    Optional<BacktestRun> findByBacktestId(String backtestId);

    /**
     * 같은 동일 버전 키로 아직 살아 있는 실행(QUEUED·RUNNING·COMPLETED). 부분 유니크 인덱스와 같은 상태 집합이다.
     *
     * <p>실패·취소한 실행은 걸리지 않는다 — 같은 조건을 다시 요청하면 새로 계산해야 하기 때문이다(R-02).
     */
    Optional<BacktestRun> findActiveByDedupKey(String dedupKey);

    /** 커밋됐지만 발행 확인({@code published_at})이 없는 대기 실행. 재발행 대상이다(R-18). */
    List<BacktestRun> findUnpublishedQueued(Instant queuedBefore, int limit);

    /**
     * 발행 시도 횟수를 하나 올린다(R-18). 상태·진행 컬럼은 건드리지 않는다.
     *
     * <p>도메인 객체를 거치지 않는 이유: 읽어서 +1 해 쓰면 첫 발행과 재발행이 겹쳤을 때 증가 하나를 잃고, 그러면 시도 횟수가 실제 메시지 수보다 작아져 DLQ
     * 종결 판정이 살아 있는 실행을 끊는다.
     */
    void recordPublishAttempt(Long runId);

    /** 발행 confirm 시각을 남긴다. 이 컬럼만 바꾼다. */
    void recordPublished(Long runId, Instant at);
}
