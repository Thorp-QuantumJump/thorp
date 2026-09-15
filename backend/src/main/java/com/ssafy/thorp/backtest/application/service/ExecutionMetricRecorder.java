package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.ExecutionMetricRecord;
import com.ssafy.thorp.backtest.application.command.LimitHitRecord;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.repository.ExecutionMetricRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 실행 종결마다 측정 한 행을 남긴다(spec 004 R-10, FR-006·US4). PD-06 한도 값과 R-20 워커 비율은 이 기록으로 정한다.
 *
 * <p><b>종결 세 지점이 직접 부른다</b> — {@code BacktestEventService.handleFinished}(FINISHED 셋)·{@code
 * handleDeadLetter}(DLQ 종결)·{@code RunLivenessSweeper.settleOne}(취소 무응답 강제 종결). {@code
 * BacktestFinished} 이벤트를 구독하지 않는 이유: 그 이벤트는 007 을 위한 모듈 경계 신호라 FINISHED 의 {@code resource} 가 실려 있지
 * 않고, 측정을 위해 필드를 늘리면 받는 쪽마다 모르는 값이 생긴다. 세 지점 모두 실행 행을 잠근 트랜잭션 안이라 종결 UPDATE 와 이 INSERT 는 함께 커밋되거나
 * 함께 사라진다.
 *
 * <p><b>호출자는 종결 시각까지 반영한 실행을 넘긴다</b>({@code lifecycle.finished(now)} 뒤). 여기서는 세기만 하고 실행을 바꾸지 않는다.
 *
 * <p>같은 실행을 두 번 세지 않는다 — 저장소가 {@code run_id} UNIQUE 로 막고 여기서는 로그만 남긴다. 기록 실패가 종결을 되돌리지는 않게 할 수도 있지만
 * 하지 않는다: 측정이 빠진 종결은 나중에 PD-06 을 정할 때 표본이 조용히 사라지는 것이고, 그 방향의 오류는 눈에 띄지 않는다.
 */
@Component
public class ExecutionMetricRecorder {

    private static final Logger log = LoggerFactory.getLogger(ExecutionMetricRecorder.class);

    private final ExecutionMetricRepository repository;
    private final Clock clock;

    public ExecutionMetricRecorder(ExecutionMetricRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @param run 종결 상태·시각이 반영된 실행
     * @param resource FINISHED 가 실어 온 자원 사용. DLQ·강제 종결처럼 워커 응답이 없는 종결은 null
     */
    public void record(BacktestRun run, FinishedPayload.ResourceUsage resource) {
        RunLifecycle lc = run.getLifecycle();
        ExecutionMetricRepository.WorkerInfo worker = repository.findWorkerInfo(run.getId());
        ExecutionMetricRecord metric =
                new ExecutionMetricRecord(
                        run.getId(),
                        run.getPlan().executionTypeOrSingle(),
                        run.getStatus(),
                        run.getFailureCode(),
                        lc.queuedAt(),
                        lc.startedAt(),
                        lc.finishedAt(),
                        millisBetween(lc.queuedAt(), lc.startedAt()),
                        millisBetween(lc.startedAt(), lc.finishedAt()),
                        repository.countRunningExcept(run.getId()),
                        resource == null ? null : resource.peakRssMb(),
                        resource == null ? null : resource.cpuSeconds(),
                        // 재발행으로 생긴 중복 실행은 재시도가 아니다 — 시도 수가 발행 수를 넘은 만큼만 재시도다(R-18).
                        run.getAttempt() > lc.publishCount(),
                        repository.resultBytes(run.getId()),
                        worker.queue(),
                        worker.role(),
                        clock.instant());
        if (!repository.record(metric)) {
            log.info(
                    "execution metric already recorded; duplicate finish ignored: runId={}",
                    run.getId());
        }
    }

    /** 접수가 429 로 거절했다(FR-006). 실행 행이 없으므로 요청자·유형·사유만 남는다. */
    public void limitRejected(long requesterId, ExecutionType executionType, String reason) {
        repository.recordLimitHit(
                new LimitHitRecord(
                        LimitHitRecord.Kind.ADMISSION_REJECTED,
                        executionType,
                        reason,
                        requesterId,
                        null,
                        clock.instant()));
    }

    /** 실행 시간 상한을 넘겨 CANCEL 을 보냈다(R-19). 종결은 아직이라 측정 행이 아니라 여기 남는다. */
    public void limitStopped(BacktestRun run) {
        repository.recordLimitHit(
                new LimitHitRecord(
                        LimitHitRecord.Kind.RUN_STOPPED,
                        run.getPlan().executionTypeOrSingle(),
                        "EXECUTION_LIMIT",
                        null,
                        run.getId(),
                        clock.instant()));
    }

    /** 둘 중 하나가 없으면 null — STARTED 를 못 받은 실행의 대기·소요는 분포에 넣지 않는다(0 으로 채우지 않는다). */
    private static Long millisBetween(Instant from, Instant to) {
        if (from == null || to == null) {
            return null;
        }
        return Duration.between(from, to).toMillis();
    }
}
