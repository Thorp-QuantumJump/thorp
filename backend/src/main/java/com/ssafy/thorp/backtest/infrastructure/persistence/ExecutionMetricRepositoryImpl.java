package com.ssafy.thorp.backtest.infrastructure.persistence;

import com.ssafy.thorp.backtest.application.command.ExecutionMetricRecord;
import com.ssafy.thorp.backtest.application.command.LimitHitRecord;
import com.ssafy.thorp.backtest.application.repository.ExecutionMetricRepository;
import com.ssafy.thorp.backtest.application.result.ExecutionMetricSummary;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 측정 기록은 엔티티 없이 native 로 쓴다(R-10). 관측값이라 도메인 불변식이 없고, 쓰기 한 번·집계 읽기뿐이라 JPA 가 해 줄 일이 없다.
 *
 * <p><b>집계 SQL 은 한 문장이다.</b> 뷰 {@code v_run_metric_summary}(마이그레이션)와 {@link #SUMMARY_BODY} 가 같은
 * SELECT 여야 S10 의 "API 와 뷰의 값이 같다"가 성립한다 — 여기서는 {@code recorded_at} 범위 조건만 더 붙는다. 뷰를 고치면 여기도 함께 고친다
 * ({@code ExecutionMetricsApiIT} 가 둘을 대조한다).
 */
@Repository
public class ExecutionMetricRepositoryImpl implements ExecutionMetricRepository {

    private static final String SUMMARY_BODY =
            """
            SELECT m.execution_type,
                   count(*)                                                    AS count,
                   avg(CASE WHEN m.status = 'FAILED' THEN 1.0 ELSE 0.0 END)    AS fail_rate,
                   avg(CASE WHEN m.status = 'CANCELLED' THEN 1.0 ELSE 0.0 END) AS cancel_rate,
                   avg(CASE WHEN m.auto_retried THEN 1.0 ELSE 0.0 END)         AS auto_retry_rate,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY m.wait_ms)      AS wait_p50_ms,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY m.wait_ms)     AS wait_p95_ms,
                   percentile_cont(0.99) WITHIN GROUP (ORDER BY m.wait_ms)     AS wait_p99_ms,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY m.run_ms)       AS run_p50_ms,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY m.run_ms)      AS run_p95_ms,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY m.peak_rss_mb) AS peak_rss_p95_mb,
                   avg(CASE WHEN m.worker_role = 'flex' THEN 1.0 ELSE 0.0 END) AS flex_served_rate,
                   (SELECT count(*) FROM backtest_reuse_event e
                     WHERE e.request_execution_type = m.execution_type
                       AND (CAST(:from AS timestamptz) IS NULL OR e.at >= :from)
                       AND (CAST(:to AS timestamptz) IS NULL OR e.at < :to))   AS reuse_count,
                   (SELECT count(*) FROM backtest_limit_hit h
                     WHERE h.execution_type = m.execution_type
                       AND (CAST(:from AS timestamptz) IS NULL OR h.at >= :from)
                       AND (CAST(:to AS timestamptz) IS NULL OR h.at < :to))   AS limit_hit_count,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY m.data_rows)    AS data_rows_p50,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY m.data_bytes)   AS data_bytes_p50
              FROM backtest_run_metric m
             WHERE (CAST(:type AS varchar) IS NULL OR m.execution_type = :type)
               AND (CAST(:from AS timestamptz) IS NULL OR m.recorded_at >= :from)
               AND (CAST(:to AS timestamptz) IS NULL OR m.recorded_at < :to)
             GROUP BY m.execution_type
             ORDER BY m.execution_type
            """;

    private final EntityManager entityManager;

    public ExecutionMetricRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public boolean record(ExecutionMetricRecord m) {
        // run_id UNIQUE 에 걸리면 조용히 0 건 — 늦게 온 중복 종결은 기록하지 않는다.
        int inserted =
                entityManager
                        .createNativeQuery(
                                "INSERT INTO backtest_run_metric (id, run_id, execution_type,"
                                        + " status, failure_code, queued_at, started_at,"
                                        + " finished_at, wait_ms, run_ms, concurrent_running,"
                                        + " peak_rss_mb, cpu_seconds, auto_retried, result_bytes,"
                                        + " data_rows, data_bytes,"
                                        + " served_queue, worker_role, recorded_at)"
                                        + " VALUES (nextval('backtest_run_metric_seq'), :runId,"
                                        + " :type, :status, :failureCode, :queuedAt, :startedAt,"
                                        + " :finishedAt, :waitMs, :runMs, :concurrent,"
                                        + " :peakRssMb, :cpuSeconds, :autoRetried, :resultBytes,"
                                        + " :dataRows, :dataBytes,"
                                        + " :servedQueue, :workerRole, :recordedAt)"
                                        + " ON CONFLICT (run_id) DO NOTHING")
                        .setParameter("runId", m.runId())
                        .setParameter("type", m.executionType().name())
                        .setParameter("status", m.status().name())
                        .setParameter("failureCode", m.failureCode())
                        .setParameter("queuedAt", m.queuedAt())
                        .setParameter("startedAt", m.startedAt())
                        .setParameter("finishedAt", m.finishedAt())
                        .setParameter("waitMs", m.waitMs())
                        .setParameter("runMs", m.runMs())
                        .setParameter("concurrent", m.concurrentRunning())
                        .setParameter("peakRssMb", m.peakRssMb())
                        .setParameter("cpuSeconds", m.cpuSeconds())
                        .setParameter("autoRetried", m.autoRetried())
                        .setParameter("resultBytes", m.resultBytes())
                        .setParameter("dataRows", m.dataRows())
                        .setParameter("dataBytes", m.dataBytes())
                        .setParameter("servedQueue", m.servedQueue())
                        .setParameter("workerRole", m.workerRole())
                        .setParameter("recordedAt", m.recordedAt())
                        .executeUpdate();
        return inserted == 1;
    }

    @Override
    public void recordLimitHit(LimitHitRecord hit) {
        entityManager
                .createNativeQuery(
                        "INSERT INTO backtest_limit_hit (id, kind, execution_type, reason,"
                                + " requester_id, run_id, at)"
                                + " VALUES (nextval('backtest_limit_hit_seq'), :kind, :type,"
                                + " :reason, :requesterId, :runId, :at)")
                .setParameter("kind", hit.kind().name())
                .setParameter("type", hit.executionType().name())
                .setParameter("reason", hit.reason())
                .setParameter("requesterId", hit.requesterId())
                .setParameter("runId", hit.runId())
                .setParameter("at", hit.at())
                .executeUpdate();
    }

    @Override
    public int countRunningExcept(long runId) {
        Number count =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT count(*) FROM backtest_run"
                                                + " WHERE status = 'RUNNING' AND id <> :id")
                                .setParameter("id", runId)
                                .getSingleResult();
        return count.intValue();
    }

    @Override
    public long resultBytes(long runId) {
        // pg_column_size(row) 는 TOAST 압축 뒤의 실제 저장 크기다 — JSONB 컬럼을 하나씩 더하는 것보다 원가에 가깝다.
        Number bytes =
                (Number)
                        entityManager
                                .createNativeQuery(
                                        "SELECT coalesce(sum(pg_column_size(r.*)), 0)"
                                                + " FROM backtest_result r WHERE r.run_id = :id")
                                .setParameter("id", runId)
                                .getSingleResult();
        return bytes.longValue();
    }

    @Override
    public WorkerInfo findWorkerInfo(long runId) {
        List<?> rows =
                entityManager
                        .createNativeQuery(
                                "SELECT served_queue, worker_role FROM backtest_run WHERE id = :id")
                        .setParameter("id", runId)
                        .getResultList();
        if (rows.isEmpty()) {
            return WorkerInfo.UNKNOWN;
        }
        Object[] row = (Object[]) rows.get(0);
        return new WorkerInfo((String) row[0], (String) row[1]);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ExecutionMetricSummary> summarize(
            ExecutionType executionType, Instant from, Instant to) {
        Query query =
                entityManager
                        .createNativeQuery(SUMMARY_BODY)
                        .setParameter("type", executionType == null ? null : executionType.name())
                        .setParameter("from", from)
                        .setParameter("to", to);
        List<Object[]> rows = query.getResultList();
        return rows.stream().map(ExecutionMetricRepositoryImpl::toSummary).toList();
    }

    private static ExecutionMetricSummary toSummary(Object[] r) {
        return new ExecutionMetricSummary(
                ExecutionType.valueOf((String) r[0]),
                ((Number) r[1]).longValue(),
                decimal(r[2]),
                decimal(r[3]),
                decimal(r[4]),
                decimal(r[5]),
                decimal(r[6]),
                decimal(r[7]),
                decimal(r[8]),
                decimal(r[9]),
                decimal(r[10]),
                decimal(r[11]),
                ((Number) r[12]).longValue(),
                ((Number) r[13]).longValue(),
                decimal(r[14]),
                decimal(r[15]));
    }

    /** percentile_cont 는 double, avg 는 numeric 으로 온다. 표본이 없으면 null 그대로 — 0 으로 채우지 않는다(원칙 VI). */
    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return BigDecimal.valueOf(((Number) value).doubleValue());
    }
}
