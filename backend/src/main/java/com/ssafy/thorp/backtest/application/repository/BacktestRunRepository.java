package com.ssafy.thorp.backtest.application.repository;

import com.ssafy.thorp.backtest.application.result.RunView;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
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

    /** CANCEL 을 보내기로 했다. 시각과 이유 두 컬럼만 쓴다(상태 전이·발행 경로와 부딪히지 않는다). */
    void recordCancelRequested(Long runId, Instant at, CancelReason reason);

    /**
     * 워커가 살아 있다는 신호를 받았다(STARTED·PROGRESS). 이 컬럼만 쓴다 — 발행 기록이 함께 올라가면 멈춘 실행이 살아 있는 것처럼 보여 정체를
     * 놓친다(R-19).
     */
    void recordEventReceived(Long runId, Instant at);

    /**
     * 이 실행을 어느 큐에서 어느 역할의 워커가 집었는지(STARTED {@code resource.queue}·{@code role}, R-20·T012). 두 컬럼만
     * 쓴다 — 측정 기록이 종결 때 복사한다. 공용(flex) 워커가 단건을 도운 비율은 이 값 없이 셀 수 없다.
     */
    void recordWorkerInfo(Long runId, String queue, String role);

    /** RUNNING 인데 마지막 신호가 {@code since} 보다 오래된 실행. 아직 CANCEL 을 보내지 않은 것만(R-19 정체 감지). */
    List<BacktestRun> findStaleRunning(Instant since, int limit);

    /** RUNNING 이고 {@code startedBefore} 이전에 시작한 실행. 아직 CANCEL 을 보내지 않은 것만(실행 시간 상한). */
    List<BacktestRun> findOverrunning(Instant startedBefore, int limit);

    /**
     * CANCEL 을 보냈는데 아직 종결되지 않은 실행. 유예 경과 여부는 호출자가 건별로 본다 — 유예 전이면 CANCEL 을 다시 보내야 하고(발행이 실패했을 수 있다)
     * 지났으면 강제 종결한다.
     */
    List<BacktestRun> findCancelPending(int limit);

    /**
     * 대기 중인 <b>단건</b> 실행 수(사용자 무관). 접수 한도 판정에 쓴다. 탐색·경제환경 파생 실행은 다른 큐로 가므로 세지 않는다(R-20) — 한 큐에서
     * 배치까지 세면 배치가 큐를 채우는 동안 단건만 429 를 받았다.
     */
    long countQueuedSingle();

    /**
     * 이 사용자가 대기시켜 둔 <b>단건</b> 실행 수. 탐색 묶음({@code SEARCH_BUNDLE})은 007 의 발행 창이 이미 통제하므로 세지 않는다 — 두
     * 곳에서 막으면 창을 키운 날 탐색이 조용히 멈춘다(R-19, 2026-09-14 결정).
     */
    long countQueuedSingleByRequester(long requesterId);

    /**
     * <b>비로그인 요청이 새로 만든</b> 단건 대기 실행 수(spec 005 B01). 실행을 만든 요청({@code result_source = 'NEW'})의
     * 요청자가 없는 것만 센다 — 비로그인 요청이 남의 실행에 합류해도 큐는 늘지 않으므로 세지 않는다. 취소한 요청은 빠진다.
     */
    long countQueuedSingleAnonymous();

    /** 같은 키의 접수를 직렬화한다. 잠금 이름이 다르면 서로 기다리지 않는다. */
    void lockAdmission(String key);

    Optional<BacktestRun> findByBacktestId(String backtestId);

    /**
     * 이 사용자의 요청이 가리키는 실행을 요청 최신순으로. 같은 실행을 여러 요청이 가리키면 여러 번 나온다 — 부른 쪽이 조합 단위로 접는다(spec 005 비교 후보,
     * {@code GET /backtests/used-strategies}).
     *
     * @param executionType 이 유형의 요청만. 탐색 묶음이 만든 실행은 사용자가 고른 설정이 아니라서 후보에서 뺀다
     */
    List<BacktestRun> findRecentRequestedBy(
            long requesterId, ExecutionType executionType, int limit);

    /**
     * 같은 동일 버전 키의 접수를 직렬화한다(R-02, T010). 트랜잭션이 끝나면 저절로 풀린다.
     *
     * <p>동일 버전 조회와 실행 INSERT 사이에 다른 접수가 같은 키로 먼저 INSERT 하면 부분 유니크 인덱스 {@code
     * uk_backtest_run_dedup_active} 에 걸려 정상적인 사용자 행동이 500 이 된다. 잡아서 재조회하는 방법은 PostgreSQL 에서 쓸 수 없다
     * — 제약 위반이 나면 그 트랜잭션이 abort 돼 같은 트랜잭션에서 더 질의할 수 없다. 세이브포인트로 감쌀 수도 있지만, 애초에 부딪히지 않게 하는 쪽이 예외 경로가
     * 없어 단순하다.
     *
     * <p>키마다 다른 잠금이라 서로 다른 조건의 접수는 기다리지 않는다. 같은 키의 접수만 줄을 서고, 앞엣것이 커밋하면 뒤엣것의 조회가 그 실행을 보고 합류한다.
     */
    void lockDedupKey(String dedupKey);

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

    /**
     * DLQ 로 끝난 발행 시도를 기록하고 {@code dlq_count} 를 하나 올린다(R-18 ③). 상태·진행 컬럼은 건드리지 않는다.
     *
     * <p><b>같은 시도를 두 번 세지 않는다.</b> DLQ 소비는 at-least-once 라 커밋 뒤 ACK 이 유실되면 같은 메시지가 다시 온다. 시도 번호로
     * 기록을 남겨 두 번째부터는 세지 않는다 — 두 번 세면 살아 있는 작업이 남았는데도 종결 판정이 서서 실행을 잘못 끊는다.
     *
     * @return 이번에 처음 세었으면 true. false = 이미 센 시도(재전달)
     */
    boolean recordDeadLetter(Long runId, int attempt, Instant at);

    /** 발행 confirm 시각을 남긴다. 이 컬럼만 바꾼다. */
    void recordPublished(Long runId, Instant at);
}
