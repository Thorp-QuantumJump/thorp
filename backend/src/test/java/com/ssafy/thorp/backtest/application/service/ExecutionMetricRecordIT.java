package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.repository.ExecutionMetricRepository;
import com.ssafy.thorp.backtest.application.result.ExecutionMetricSummary;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.backtest.infrastructure.messaging.MessageFixtures;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 종결 경로가 실제 DB 에 측정 한 행을 남기는지(R-10, T012). 브로커는 띄우지 않고 계약 픽스처를 이벤트 서비스에 직접 넣는다.
 *
 * <p>스위트가 컨테이너를 공유하므로 집계는 절대값이 아니라 "이 실행이 들어간 뒤 count 가 하나 늘었다"와 "API 질의 = 뷰"로 본다.
 */
@SpringBootTest(
        properties = {
            "spring.rabbitmq.host=localhost",
            "spring.rabbitmq.port=1",
            "spring.rabbitmq.connection-timeout=1s",
            "spring.rabbitmq.listener.simple.auto-startup=false",
            "thorp.backtest.republish.enabled=false",
            "thorp.backtest.liveness.interval=PT1H",
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class ExecutionMetricRecordIT extends PostgresTestSupport {

    @Autowired private BacktestEventService events;
    @Autowired private BacktestRunRepository runRepository;
    @Autowired private ExecutionMetricRepository metricRepository;
    @Autowired private ExecutionMetricRecorder recorder;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private Clock clock;

    private BacktestRun newRun(int publishCount) {
        RunLifecycle lifecycle = RunLifecycle.queuedAt(clock.instant());
        for (int i = 0; i < publishCount; i++) {
            lifecycle = lifecycle.publishAttempted();
        }
        RunLifecycle queued = lifecycle;
        return transactionTemplate.execute(
                status ->
                        runRepository.save(
                                BacktestRun.create(
                                                "MA_CROSSOVER",
                                                "1.0.0",
                                                Map.of("shortWindow", 20),
                                                List.of(
                                                        new InstrumentId("005930.XKRX"),
                                                        new InstrumentId("000660.XKRX")),
                                                new BacktestPeriod(
                                                        LocalDate.of(2025, 1, 1),
                                                        LocalDate.of(2025, 6, 30)),
                                                new BigDecimal("10000000"),
                                                PositionSizingMode.STRATEGY_DEFINED,
                                                new BigDecimal("0.50"),
                                                new InstrumentId("069500.XKRX"),
                                                42)
                                        .withLifecycle(queued)));
    }

    private void started(BacktestRun run) {
        events.handleStarted(
                MessageFixtures.parse(
                        MessageFixtures.withRunId(
                                MessageFixtures.STARTED, run.getBacktestId().value()),
                        MessageFixtures.STARTED_EVENT));
    }

    private void finished(BacktestRun run, String file) {
        events.handleFinished(
                MessageFixtures.parse(
                        MessageFixtures.withRunId(file, run.getBacktestId().value()),
                        MessageFixtures.FINISHED_EVENT));
    }

    private Map<String, Object> metricOf(BacktestRun run) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM backtest_run_metric WHERE run_id = ?", run.getId());
    }

    private Integer metricCount(BacktestRun run) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM backtest_run_metric WHERE run_id = ?",
                Integer.class,
                run.getId());
    }

    @Test
    @DisplayName("FINISHED(COMPLETED): 대기·소요·자원·결과 크기·집은 큐와 워커 역할이 한 행에 남는다")
    void completedRunIsRecorded() {
        BacktestRun run = newRun(1);
        started(run);
        finished(run, MessageFixtures.FINISHED_COMPLETED);

        Map<String, Object> m = metricOf(run);
        assertThat(m.get("execution_type")).isEqualTo("SINGLE");
        assertThat(m.get("status")).isEqualTo("COMPLETED");
        assertThat(m.get("failure_code")).isNull();
        assertThat(((Number) m.get("wait_ms")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(((Number) m.get("run_ms")).longValue()).isGreaterThanOrEqualTo(0L);
        assertThat(new BigDecimal(m.get("peak_rss_mb").toString())).isEqualByComparingTo("512.5");
        assertThat(new BigDecimal(m.get("cpu_seconds").toString())).isEqualByComparingTo("3.210");
        // 결과 저장 뒤에 기록하므로 결과 행의 크기가 보인다.
        assertThat(((Number) m.get("result_bytes")).longValue()).isPositive();
        assertThat(m.get("auto_retried")).isEqualTo(false);
        // STARTED resource 의 queue·role 이 실행 행을 거쳐 여기까지 온다(R-20).
        assertThat(m.get("served_queue")).isEqualTo("backtest.jobs");
        assertThat(m.get("worker_role")).isEqualTo("single");
    }

    @Test
    @DisplayName("같은 실행을 두 번 기록하지 않는다 — 두 번째 record 는 false 고 행은 하나다")
    void duplicateFinishIsRecordedOnce() {
        BacktestRun run = newRun(1);
        started(run);
        finished(run, MessageFixtures.FINISHED_FAILED);
        BacktestRun settled = runRepository.findById(run.getId()).orElseThrow();

        Boolean again =
                transactionTemplate.execute(
                        status -> {
                            recorder.record(settled, null);
                            return true;
                        });

        assertThat(again).isTrue();
        assertThat(metricCount(run)).isEqualTo(1);
        assertThat(metricOf(run).get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("DLQ 종결: STARTED 없이 끝나 대기·소요·자원은 null, 결과 크기는 0, 사유는 SYSTEM_FAILURE")
    void deadLetteredRunIsRecordedWithoutDurations() {
        BacktestRun run = newRun(1);

        transactionTemplate.executeWithoutResult(
                status -> events.handleDeadLetter(run.getBacktestId().value(), 1));

        Map<String, Object> m = metricOf(run);
        assertThat(m.get("status")).isEqualTo("FAILED");
        assertThat(m.get("failure_code")).isEqualTo("SYSTEM_FAILURE");
        assertThat(m.get("wait_ms")).isNull();
        assertThat(m.get("run_ms")).isNull();
        assertThat(m.get("peak_rss_mb")).isNull();
        assertThat(((Number) m.get("result_bytes")).longValue()).isZero();
        assertThat(m.get("served_queue")).isNull();
    }

    @Test
    @DisplayName("한도 도달 두 종류가 한 표에 남고, 범위 없는 집계는 뷰와 값이 같다")
    void limitHitsAndSummaryMatchTheView() {
        BacktestRun run = newRun(1);
        transactionTemplate.executeWithoutResult(
                status -> {
                    recorder.limitRejected(7L, ExecutionType.SINGLE, "TOTAL");
                    recorder.limitStopped(run);
                });

        List<Map<String, Object>> hits =
                jdbcTemplate.queryForList(
                        "SELECT kind, reason, requester_id, run_id FROM backtest_limit_hit"
                                + " WHERE run_id = ? OR (requester_id = 7 AND reason = 'TOTAL')"
                                + " ORDER BY id",
                        run.getId());
        assertThat(hits)
                .extracting(h -> h.get("kind"))
                .contains("ADMISSION_REJECTED", "RUN_STOPPED");

        // 집계(API 가 쓰는 질의)와 뷰가 같은 숫자를 낸다 — SQL 이 한 문장에서 나온다(R-10 ⑥).
        started(run);
        finished(run, MessageFixtures.FINISHED_COMPLETED);
        ExecutionMetricSummary api =
                transactionTemplate.execute(
                        status ->
                                metricRepository
                                        .summarize(ExecutionType.SINGLE, null, null)
                                        .get(0));
        Map<String, Object> view =
                jdbcTemplate.queryForMap(
                        "SELECT * FROM v_run_metric_summary WHERE execution_type = 'SINGLE'");
        assertThat(api.count()).isEqualTo(((Number) view.get("count")).longValue());
        assertThat(api.limitHitCount())
                .isEqualTo(((Number) view.get("limit_hit_count")).longValue())
                .isGreaterThanOrEqualTo(2L);
        assertThat(api.reuseCount()).isEqualTo(((Number) view.get("reuse_count")).longValue());
        assertThat(api.waitP50Ms()).isNotNull();
        assertThat(api.waitP99Ms()).isGreaterThanOrEqualTo(api.waitP95Ms());
        assertThat(api.waitP95Ms()).isGreaterThanOrEqualTo(api.waitP50Ms());
    }
}
