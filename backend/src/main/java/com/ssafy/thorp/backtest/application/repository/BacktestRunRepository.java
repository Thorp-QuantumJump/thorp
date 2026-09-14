package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.application.result.RunView;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 백테스트 Job 저장소 포트. 구현은 {@code infrastructure.persistence}에 둔다. */
public interface BacktestRunRepository {

    /**
     * 목록용 실행 뷰 여러 건. 상태 + 요청 식별자 + 성과 숫자 셋만 읽는다.
     *
     * <p>실행마다 {@link #findById}를 돌리지 않는 이유는 왕복 수와 읽는 양이다 — 자동 탐색은 묶음 하나가 후보 수백 건이다.
     *
     * @return 존재하는 실행만 담는다
     */
    Map<Long, RunView> findRunViews(Collection<Long> runIds);

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

    /**
     * 행 잠금을 잡고 읽는다(SELECT … FOR UPDATE). 같은 실행의 마지막 활성 요청 판정을 직렬화한다(R-04) — 두 요청이 동시에 취소하면 둘 다 "다른
     * 활성 요청이 남았다"고 읽어 아무도 실행을 멈추지 않는다.
     */
    Optional<BacktestRun> findByIdForUpdate(Long id);

    /**
     * 외부 식별자로 잠그고 읽는다. 워커 이벤트의 상태 전이를 취소와 같은 행 잠금으로 직렬화한다(R-04) — 잠그지 않으면 STARTED 가 QUEUED 를 읽은 직후
     * 커밋된 취소를 늦은 UPDATE 가 RUNNING 으로 덮는다.
     */
    Optional<BacktestRun> findByBacktestIdForUpdate(String backtestId);

    /** 마지막 활성 요청의 취소로 CANCEL 을 보내기로 했다. 이 컬럼만 쓴다(상태 전이·발행 경로와 부딪히지 않는다). */
    void recordCancelRequested(Long runId, Instant at);

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
