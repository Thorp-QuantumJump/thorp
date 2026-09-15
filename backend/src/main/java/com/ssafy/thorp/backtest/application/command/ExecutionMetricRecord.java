package com.ssafy.thorp.backtest.application.command;

import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * 실행 종결 한 번의 측정 기록({@code backtest_run_metric} 한 행, spec 004 R-10). 값의 뜻은 data-model.md
 * §backtest_run_metric.
 *
 * <p>값을 세는 쪽은 {@code ExecutionMetricRecorder}고 저장소는 그대로 넣는다 — 무엇을 어떻게 세는지는 한 곳에만 있어야 한다.
 */
public record ExecutionMetricRecord(
        long runId,
        ExecutionType executionType,
        RunStatus status,
        String failureCode,
        Instant queuedAt,
        Instant startedAt,
        Instant finishedAt,
        Long waitMs,
        Long runMs,
        int concurrentRunning,
        BigDecimal peakRssMb,
        BigDecimal cpuSeconds,
        boolean autoRetried,
        long resultBytes,
        String servedQueue,
        String workerRole,
        Instant recordedAt) {}
