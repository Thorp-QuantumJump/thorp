package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.ResultPerformance;
import com.ssafy.thorp.backtest.application.result.RunView;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunState;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

/**
 * 저장 시 외부 식별자를 부여한다.
 *
 * <p>{@code save()}는 신규 엔티티에 대해 {@code persist()}를 호출하고, SEQUENCE라 그 시점에 PK가 나온다. 그 PK로 {@code
 * backtest_id}를 INSERT 전에 채우므로 INSERT는 한 번이고 컬럼을 NOT NULL로 둘 수 있다. 날짜만 여기서 정하고 PK와의 결합은 엔티티의
 * {@code @PrePersist}가 한다.
 */
@Repository
public class BacktestRunRepositoryImpl implements BacktestRunRepository {

    /** 취소 유예 감시가 보는 "아직 살아 있는" 상태. 종결된 실행은 무응답이 아니다. */
    private static final List<RunStatus> ALIVE = List.of(RunStatus.QUEUED, RunStatus.RUNNING);

    /** 부분 유니크 인덱스 {@code uk_backtest_run_dedup_active} 와 같은 상태 집합이어야 한다. */
    private static final List<RunStatus> ACTIVE_FOR_REUSE =
            List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.COMPLETED);

    private final BacktestRunJpaRepository jpaRepository;
    private final EntityManager entityManager;
    private final Clock clock;

    public BacktestRunRepositoryImpl(
            BacktestRunJpaRepository jpaRepository, EntityManager entityManager, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Override
    public BacktestRun save(BacktestRun run) {
        if (run.getId() != null) {
            throw new IllegalArgumentException("save is for new runs only: id=" + run.getId());
        }
        BacktestRunJpaEntity entity = BacktestRunMapper.toEntity(run);
        entity.assignBacktestIdOn(LocalDate.now(clock));
        // queued_at 은 NOT NULL 이다. 접수가 시각을 주지 않은 실행(테스트 픽스처 등)은 저장 시각으로 둔다 —
        // 대기 시간의 기준은 "큐에 들어간 때"이고 그게 곧 이 INSERT 시점이다.
        if (run.getLifecycle().queuedAt() == null) {
            entity.applyLifecycle(
                    clock.instant(),
                    run.getLifecycle().startedAt(),
                    run.getLifecycle().finishedAt(),
                    run.getLifecycle().publishedAt(),
                    run.getLifecycle().publishCount(),
                    run.getLifecycle().dlqCount(),
                    run.getLifecycle().cancelRequestedAt(),
                    run.getLifecycle().cancelReason(),
                    run.getLifecycle().lastEventAt());
        }
        return BacktestRunMapper.toDomain(jpaRepository.save(entity));
    }

    /**
     * 관리 엔티티를 읽어 <b>상태 컬럼만</b> 바꾼다. 도메인 객체를 새 엔티티로 merge하면 {@code createdAt}(updatable=false)이
     * null인 사본이 관리 상태를 덮어 쓰므로 그 방식은 피한다.
     *
     * <p>계획(접수 확정값)과 발행 기록은 여기서 쓰지 않는다. 계획은 저장 시점에 한 번 정해지고 바뀌지 않으며, 발행 기록은 {@link
     * #recordPublishAttempt}·{@link #recordPublished}가 전용 UPDATE로 쓴다 — 이벤트 처리와 발행 경로가 동시에 돌기 때문에
     * 서로의 컬럼을 건드리면 늦게 저장한 쪽이 상대의 갱신을 되돌린다.
     */
    @Override
    public BacktestRun update(BacktestRun run) {
        if (run.getId() == null) {
            throw new IllegalArgumentException("update is for persisted runs only: id is null");
        }
        BacktestRunJpaEntity entity =
                jpaRepository
                        .findById(run.getId())
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "run not found: id=" + run.getId()));
        RunState state = run.getState();
        RunProgress progress = state.progress();
        entity.applyState(
                state.status(),
                state.attempt(),
                state.currentExecutionId(),
                state.partial(),
                progress == null ? null : progress.stage(),
                progress == null ? null : progress.lastTradingDate(),
                progress == null ? null : progress.completedTradingDays(),
                progress == null ? null : progress.totalTradingDays(),
                state.failureCode(),
                state.failureMessage(),
                run.getLifecycle().startedAt(),
                run.getLifecycle().finishedAt());
        return BacktestRunMapper.toDomain(jpaRepository.save(entity));
    }

    /**
     * 잠그고 <b>그 시점의 행을 다시 읽는다</b>.
     *
     * <p>잠금만으로는 최신 값을 보지 못한다. 이 트랜잭션이 그 행을 이미 읽었다면(접수는 동일 버전 조회로 먼저 읽는다) 영속성 컨텍스트의 1차 캐시가 이겨서 잠금
     * 쿼리가 가져온 행 상태는 버려진다 — 그러면 그 사이 커밋된 {@code cancel_requested_at} 을 놓치고 취소 대기 실행에 새 요청이 붙는다.
     * {@code BacktestCancelFlowIT} 가 그 순서를 실제 DB 로 고정한다.
     */
    @Override
    public Optional<BacktestRun> findByIdForUpdate(Long id) {
        return jpaRepository
                .lockById(id)
                .map(
                        entity -> {
                            entityManager.refresh(entity);
                            return BacktestRunMapper.toDomain(entity);
                        });
    }

    /** {@link #findByIdForUpdate}와 같다 — 이벤트 처리는 실행을 외부 식별자로 찾는다. */
    @Override
    public Optional<BacktestRun> findByBacktestIdForUpdate(String backtestId) {
        return jpaRepository
                .lockByBacktestId(backtestId)
                .map(
                        entity -> {
                            entityManager.refresh(entity);
                            return BacktestRunMapper.toDomain(entity);
                        });
    }

    @Override
    public void recordCancelRequested(Long runId, Instant at, CancelReason reason) {
        jpaRepository.markCancelRequested(runId, at, reason);
    }

    @Override
    public void recordEventReceived(Long runId, Instant at) {
        jpaRepository.markEventReceived(runId, at);
    }

    @Override
    public List<BacktestRun> findStaleRunning(Instant since, int limit) {
        return toDomain(jpaRepository.findStale(RunStatus.RUNNING, since, Limit.of(limit)));
    }

    @Override
    public List<BacktestRun> findOverrunning(Instant startedBefore, int limit) {
        return toDomain(
                jpaRepository.findOverrunning(RunStatus.RUNNING, startedBefore, Limit.of(limit)));
    }

    @Override
    public List<BacktestRun> findCancelPending(int limit) {
        return toDomain(jpaRepository.findCancelPending(ALIVE, Limit.of(limit)));
    }

    @Override
    public long countQueuedSingle() {
        return jpaRepository.countByStatusAndExecutionType(RunStatus.QUEUED, ExecutionType.SINGLE);
    }

    /**
     * 요청 행과 조인해 이 사용자의 <b>단건</b> 대기 수를 센다. 취소한 요청은 빠진다 — 취소해 두고 다시 넣지 못하면 한도가 벌이 된다.
     *
     * <p>native 로 쓰는 이유: 요청은 다른 애그리게이트라 JPA 연관을 걸지 않았고(그 관계는 스칼라 id 뿐이다), 이 셈은 조회 한 번이면 끝난다.
     */
    @Override
    public long countQueuedSingleByRequester(long requesterId) {
        Number count =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(DISTINCT r.id) FROM backtest_run r"
                                                + " JOIN backtest_request q ON q.run_id = r.id"
                                                + " WHERE r.status = 'QUEUED'"
                                                + " AND q.requester_id = ?1"
                                                + " AND q.execution_type = 'SINGLE'"
                                                + " AND q.cancelled_at IS NULL")
                                .setParameter(1, requesterId)
                                .getSingleResult();
        return count.longValue();
    }

    /**
     * {@code pg_advisory_xact_lock(bigint)}. 잠금 식별자는 키 문자열의 SHA-256 앞 64비트다 — {@link #lockDedupKey}
     * 는 키가 이미 해시라 그대로 읽지만, 여기 키는 {@code "user:7"} 같은 평문이라 한 번 해싱해야 고르게 퍼진다.
     */
    @Override
    public void lockAdmission(String key) {
        entityManager
                .createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, lockIdOf(sha256Hex(key)))
                .getSingleResult();
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exc) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exc);
        }
    }

    private static List<BacktestRun> toDomain(List<BacktestRunJpaEntity> entities) {
        return entities.stream().map(BacktestRunMapper::toDomain).toList();
    }

    @Override
    public void recordPublishAttempt(Long runId) {
        jpaRepository.incrementPublishCount(runId);
    }

    /**
     * 시도 기록을 먼저 넣고, <b>그 INSERT 가 실제로 들어갔을 때만</b> 카운터를 올린다. PK {@code (run_id, attempt)} 가 중복을 막으므로
     * {@code ON CONFLICT DO NOTHING} 의 반환값이 곧 "처음 세는가"다.
     *
     * <p>엔티티를 두지 않고 native 로 쓴다 — 이 표는 객체로 읽는 곳이 없고 {@code ON CONFLICT} 는 JPQL 로 표현할 수 없다.
     */
    @Override
    public boolean recordDeadLetter(Long runId, int attempt, Instant at) {
        int inserted =
                entityManager
                        .createNativeQuery(
                                "INSERT INTO backtest_run_dlq_attempt (run_id, attempt, at)"
                                        + " VALUES (?1, ?2, ?3) ON CONFLICT DO NOTHING")
                        .setParameter(1, runId)
                        .setParameter(2, attempt)
                        .setParameter(3, at)
                        .executeUpdate();
        if (inserted == 0) {
            return false;
        }
        jpaRepository.incrementDlqCount(runId);
        return true;
    }

    @Override
    public void recordPublished(Long runId, Instant at) {
        jpaRepository.markPublished(runId, at);
    }

    @Override
    public Optional<BacktestRun> findById(Long id) {
        return jpaRepository.findById(id).map(BacktestRunMapper::toDomain);
    }

    @Override
    public Map<Long, RunView> findRunViews(Collection<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            // 빈 IN 절은 PostgreSQL 문법 오류다. 물어볼 것이 없으면 왕복도 하지 않는다.
            return Map.of();
        }
        Map<Long, RunView> views = new LinkedHashMap<>();
        for (BacktestRunViewRow row : jpaRepository.findRunViewRows(runIds)) {
            views.put(
                    row.getRunId(),
                    new RunView(
                            row.getBacktestId(),
                            RunStatus.valueOf(row.getStatus()),
                            performanceOf(row)));
        }
        return views;
    }

    /** 결과 행이 없으면 null이다 — 실행 중이거나 실패한 실행이며 0으로 채우지 않는다(FR-011). */
    private static ResultPerformance performanceOf(BacktestRunViewRow row) {
        if (row.getNetReturn() == null
                && row.getMaxDrawdown() == null
                && row.getTradingCost() == null) {
            return null;
        }
        return new ResultPerformance(
                decimalOrNull(row.getNetReturn()),
                decimalOrNull(row.getMaxDrawdown()),
                decimalOrNull(row.getTradingCost()));
    }

    private static BigDecimal decimalOrNull(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    @Override
    public Optional<BacktestRun> findByBacktestId(String backtestId) {
        return jpaRepository.findByBacktestId(backtestId).map(BacktestRunMapper::toDomain);
    }

    /**
     * {@code pg_advisory_xact_lock(bigint)}. 잠금 식별자는 동일 버전 키(SHA-256 hex 64자)의 앞 16자를 그대로 읽은 값이다 —
     * 키 자체가 고르게 퍼진 해시라 다시 해싱할 이유가 없고, 앞 64비트만 봐도 충돌은 실질적으로 없다. 설령 충돌해도 관계없는 두 조건의 접수가 잠깐 줄을 설 뿐이라
     * 답이 달라지지 않는다.
     *
     * <p>{@code hashtext()} 를 쓰지 않는다 — 문서화되지 않은 내부 함수라 판 올림에 값이 바뀔 수 있다.
     */
    @Override
    public void lockDedupKey(String dedupKey) {
        entityManager
                .createNativeQuery("SELECT pg_advisory_xact_lock(?1)")
                .setParameter(1, lockIdOf(dedupKey))
                .getSingleResult();
    }

    private static long lockIdOf(String dedupKey) {
        return Long.parseUnsignedLong(dedupKey.substring(0, 16), 16);
    }

    @Override
    public Optional<BacktestRun> findActiveByDedupKey(String dedupKey) {
        if (dedupKey == null) {
            return Optional.empty();
        }
        return jpaRepository
                .findByDedupKeyAndStatusIn(dedupKey, ACTIVE_FOR_REUSE)
                .map(BacktestRunMapper::toDomain);
    }

    @Override
    public List<BacktestRun> findUnpublishedQueued(Instant queuedBefore, int limit) {
        return jpaRepository
                .findByStatusAndPublishedAtIsNullAndQueuedAtLessThanOrderByQueuedAtAsc(
                        RunStatus.QUEUED, queuedBefore, Limit.of(limit))
                .stream()
                .map(BacktestRunMapper::toDomain)
                .toList();
    }
}
