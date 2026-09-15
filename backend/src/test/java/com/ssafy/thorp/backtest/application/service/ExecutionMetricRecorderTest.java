package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.command.ExecutionMetricRecord;
import com.ssafy.thorp.backtest.application.command.LimitHitRecord;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.repository.ExecutionMetricRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.model.RunPlan;
import com.ssafy.thorp.backtest.domain.model.RunState;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 무엇을 어떻게 세는지(R-10). 저장은 저장소 몫이라 여기서는 넘기는 값만 본다. */
class ExecutionMetricRecorderTest {

    private static final Instant QUEUED = Instant.parse("2026-09-15T01:00:00Z");
    private static final Instant STARTED = QUEUED.plusSeconds(7);
    private static final Instant FINISHED = STARTED.plusSeconds(90);
    private static final Instant NOW = FINISHED.plusSeconds(1);

    private final ExecutionMetricRepository repository = mock(ExecutionMetricRepository.class);
    private final ExecutionMetricRecorder recorder =
            new ExecutionMetricRecorder(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void repositoryDefaults() {
        when(repository.record(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(repository.findWorkerInfo(anyLong()))
                .thenReturn(new ExecutionMetricRepository.WorkerInfo("backtest.jobs", "flex"));
        when(repository.countRunningExcept(anyLong())).thenReturn(3);
        when(repository.resultBytes(anyLong())).thenReturn(4096L);
    }

    private static BacktestRun run(
            RunStatus status, int attempt, RunLifecycle lifecycle, ExecutionType type) {
        BacktestRun base =
                BacktestRun.restore(
                        11L,
                        BacktestId.from("BT-20260915-000011"),
                        "FIXED_WEIGHT",
                        "1",
                        Map.of(),
                        List.of(new InstrumentId("091160.XKRX")),
                        new BacktestPeriod(LocalDate.of(2024, 1, 2), LocalDate.of(2024, 12, 31)),
                        new BigDecimal("10000000.00"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        null,
                        42,
                        new RunState(
                                status,
                                attempt,
                                "exec-1",
                                false,
                                null,
                                status == RunStatus.FAILED ? "SYSTEM_FAILURE" : null,
                                null));
        return base.withLifecycle(lifecycle)
                .withPlan(new RunPlan(null, null, null, null, null, null, null, null, null, type));
    }

    private static RunLifecycle fullLifecycle(int publishCount) {
        return new RunLifecycle(
                QUEUED, STARTED, FINISHED, QUEUED, publishCount, 0, null, null, STARTED);
    }

    private ExecutionMetricRecord recorded() {
        ArgumentCaptor<ExecutionMetricRecord> captor =
                ArgumentCaptor.forClass(ExecutionMetricRecord.class);
        verify(repository).record(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("대기·소요는 시각 차로, 자원 사용은 FINISHED resource 에서, 큐·역할은 실행 행에서 온다")
    void recordsAFinishedRun() {
        recorder.record(
                run(RunStatus.COMPLETED, 1, fullLifecycle(1), ExecutionType.SEARCH_BUNDLE),
                new FinishedPayload.ResourceUsage(
                        new BigDecimal("512.5"), new BigDecimal("3.210"), new BigDecimal("4.0")));

        ExecutionMetricRecord m = recorded();
        assertThat(m.runId()).isEqualTo(11L);
        assertThat(m.executionType()).isEqualTo(ExecutionType.SEARCH_BUNDLE);
        assertThat(m.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(m.waitMs()).isEqualTo(7_000L);
        assertThat(m.runMs()).isEqualTo(90_000L);
        assertThat(m.concurrentRunning()).isEqualTo(3);
        assertThat(m.peakRssMb()).isEqualByComparingTo("512.5");
        assertThat(m.cpuSeconds()).isEqualByComparingTo("3.210");
        assertThat(m.resultBytes()).isEqualTo(4096L);
        assertThat(m.servedQueue()).isEqualTo("backtest.jobs");
        assertThat(m.workerRole()).isEqualTo("flex");
        assertThat(m.recordedAt()).isEqualTo(NOW);
        assertThat(m.autoRetried()).isFalse();
    }

    @Test
    @DisplayName("재시도는 시도 수가 발행 수를 넘은 것만이다 — 재발행 중복은 재시도가 아니다(R-18)")
    void autoRetriedOnlyWhenAttemptsExceedPublishes() {
        recorder.record(run(RunStatus.COMPLETED, 2, fullLifecycle(1), ExecutionType.SINGLE), null);
        assertThat(recorded().autoRetried()).isTrue();
    }

    @Test
    @DisplayName("재발행으로 실행이 둘이었으면 시도 2·발행 2 는 재시도가 아니다")
    void republishedDuplicateIsNotARetry() {
        recorder.record(run(RunStatus.COMPLETED, 2, fullLifecycle(2), ExecutionType.SINGLE), null);
        assertThat(recorded().autoRetried()).isFalse();
    }

    @Test
    @DisplayName("STARTED 를 못 받은 종결(DLQ)은 대기·소요·자원이 null 이다 — 0 으로 채우지 않는다")
    void deadLetteredRunHasNoDurations() {
        RunLifecycle neverStarted =
                new RunLifecycle(QUEUED, null, FINISHED, QUEUED, 1, 1, null, null, null);
        recorder.record(run(RunStatus.FAILED, 0, neverStarted, ExecutionType.SINGLE), null);

        ExecutionMetricRecord m = recorded();
        assertThat(m.waitMs()).isNull();
        assertThat(m.runMs()).isNull();
        assertThat(m.peakRssMb()).isNull();
        assertThat(m.cpuSeconds()).isNull();
        assertThat(m.failureCode()).isEqualTo("SYSTEM_FAILURE");
    }

    @Test
    @DisplayName("실행 유형이 없는 실행(접수 경로 이전)은 단건으로 센다")
    void executionTypeDefaultsToSingle() {
        recorder.record(run(RunStatus.COMPLETED, 1, fullLifecycle(1), null), null);
        assertThat(recorded().executionType()).isEqualTo(ExecutionType.SINGLE);
    }

    @Test
    @DisplayName("이미 기록된 실행이면 조용히 넘어간다 — 늦은 중복 종결이 예외로 종결을 되돌리면 안 된다")
    void duplicateRecordIsIgnored() {
        when(repository.record(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        recorder.record(run(RunStatus.COMPLETED, 1, fullLifecycle(1), ExecutionType.SINGLE), null);
        verify(repository).record(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("접수 거절은 요청자·사유가 있고 실행이 없다")
    void limitRejectedHasRequesterAndNoRun() {
        recorder.limitRejected(7L, ExecutionType.SINGLE, "TOTAL");

        ArgumentCaptor<LimitHitRecord> captor = ArgumentCaptor.forClass(LimitHitRecord.class);
        verify(repository).recordLimitHit(captor.capture());
        LimitHitRecord hit = captor.getValue();
        assertThat(hit.kind()).isEqualTo(LimitHitRecord.Kind.ADMISSION_REJECTED);
        assertThat(hit.reason()).isEqualTo("TOTAL");
        assertThat(hit.requesterId()).isEqualTo(7L);
        assertThat(hit.runId()).isNull();
        assertThat(hit.at()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("실행 시간 상한 정지는 실행이 있고 요청자가 없다")
    void limitStoppedHasRunAndNoRequester() {
        recorder.limitStopped(
                run(RunStatus.RUNNING, 1, fullLifecycle(1), ExecutionType.SEARCH_BUNDLE));

        ArgumentCaptor<LimitHitRecord> captor = ArgumentCaptor.forClass(LimitHitRecord.class);
        verify(repository).recordLimitHit(captor.capture());
        LimitHitRecord hit = captor.getValue();
        assertThat(hit.kind()).isEqualTo(LimitHitRecord.Kind.RUN_STOPPED);
        assertThat(hit.reason()).isEqualTo("EXECUTION_LIMIT");
        assertThat(hit.executionType()).isEqualTo(ExecutionType.SEARCH_BUNDLE);
        assertThat(hit.runId()).isEqualTo(11L);
        assertThat(hit.requesterId()).isNull();
    }
}
