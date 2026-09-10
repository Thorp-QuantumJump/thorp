package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.port.BacktestProgressNotifier;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워커 이벤트(STARTED / PROGRESS / FINISHED)로 Job 상태를 움직이고 결과를 저장한다.
 *
 * <p>계약 검증은 수신 측(리스너)이 끝낸 뒤 호출한다. 여기서는 Job 상태에 따라 적용·무시만 결정한다.
 *
 * <p>멱등성은 상태 전이가 보장한다. STARTED는 QUEUED에서만, FINISHED는 비최종 상태에서만 적용되므로 같은 이벤트가 두 번 와도 두 번째는 걸러진다. 별도
 * 처리 이력 테이블을 두지 않는다. 재실행(다른 {@code executionId})의 결과도 첫 최종 상태를 채택하고 이후는 무시한다.
 *
 * <p>모르는 {@code runId}는 경고 로그 후 버린다. 발행 성공 뒤 Job 생성 트랜잭션이 실패해 생긴 고아 메시지가 여기로 온다. 계약을 지킨 정상 메시지이므로
 * DLQ로 보내지 않는다.
 *
 * <p>요청/실행 분리(spec 004 T003) 전이므로 envelope의 {@code runId} 값은 backtestId와 같고 Job을 그것으로 찾는다. 취소 결과
 * ({@code CANCELLED})는 실행 모델과 '취소' 상태가 생기기 전에는 저장할 곳이 없어 받지 않는다 — 워커가 취소를 구현하는 T011보다 T003·T005가 먼저
 * 병합되므로 실제로는 도달하지 않는다.
 *
 * <p>조회 → 판단 → 갱신 사이의 경쟁은 다루지 않는다. 같은 Job의 이벤트는 큐 순서대로 오고 리스너가 prefetch 1·단일 Consumer이므로 한 인스턴스
 * 안에서는 직렬이다. 백엔드를 다중 인스턴스로 띄우기 전에 낙관적 락 또는 상태 조건부 UPDATE를 넣어야 한다.
 */
@Service
public class BacktestEventService {

    private static final Logger log = LoggerFactory.getLogger(BacktestEventService.class);

    private final BacktestJobRepository jobRepository;
    private final BacktestResultRepository resultRepository;
    private final BacktestProgressNotifier progressNotifier;

    public BacktestEventService(
            BacktestJobRepository jobRepository,
            BacktestResultRepository resultRepository,
            BacktestProgressNotifier progressNotifier) {
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
        this.progressNotifier = progressNotifier;
    }

    /** QUEUED → RUNNING. 이미 RUNNING이거나 끝난 Job이면 무시한다. */
    @Transactional
    public void handleStarted(BacktestEvent<StartedPayload> event) {
        BacktestJob job = findJob(event);
        if (job == null) {
            return;
        }
        if (job.getStatus() != BacktestStatus.QUEUED) {
            log.info(
                    "late STARTED ignored: runId={} status={} eventId={}",
                    event.runId(),
                    job.getStatus(),
                    event.eventId());
            return;
        }
        jobRepository.update(job.markRunning());
    }

    /**
     * 결과 저장 + 상태 전이(COMPLETED | FAILED)를 한 트랜잭션으로. 둘 중 하나만 반영된 상태를 만들지 않는다.
     *
     * <p>QUEUED 상태의 FINISHED도 받는다. STARTED가 유실됐거나 DLQ에 가 있어도 결과는 확정된다.
     */
    @Transactional
    public void handleFinished(BacktestEvent<FinishedPayload> event) {
        BacktestJob job = findJob(event);
        if (job == null) {
            return;
        }
        if (job.getStatus().isFinal()) {
            log.info(
                    "late FINISHED ignored: runId={} status={} eventId={} executionId={}",
                    event.runId(),
                    job.getStatus(),
                    event.eventId(),
                    event.executionId());
            return;
        }

        EngineOutput output = event.payload().engineOutput();
        BacktestJob finished =
                switch (event.payload().status()) {
                    case COMPLETED -> job.markCompleted();
                    case FAILED -> job.markFailed();
                    case CANCELLED ->
                            throw new UnsupportedOperationException(
                                    "CANCELLED results need the run model (spec 004 T003);"
                                            + " runId="
                                            + event.runId());
                };
        resultRepository.save(
                new SaveBacktestResultCommand(
                        job.getId(), event.executionId(), event.eventId(), output, null));
        jobRepository.update(finished);
    }

    /**
     * RUNNING 상태인 Job의 진행만 전달한다. DB에는 아무것도 남기지 않는다.
     *
     * <p>Job을 조회하는 이유: 모르는 {@code runId}와 끝난 Job의 늦은 PROGRESS가 그대로 구독자에게 흘러가면 클라이언트 차트가 뒤로 간다.
     * QUEUED의 PROGRESS도 무시한다 — STARTED가 아직 안 온 상태에서 진행률만 먼저 보내면 상태가 어긋난다.
     *
     * <p>트랜잭션을 열지 않는다. 조회 한 번뿐이고, notifier가 SSE로 바뀌면 클라이언트 네트워크 I/O를 하는 동안 DB 커넥션을 쥐고 있지 않기 위해서다.
     */
    public void handleProgress(BacktestEvent<ProgressPayload> event) {
        BacktestJob job = findJob(event);
        if (job == null) {
            return;
        }
        if (job.getStatus() != BacktestStatus.RUNNING) {
            log.info(
                    "PROGRESS for non-running job ignored: runId={} status={} eventId={}",
                    event.runId(),
                    job.getStatus(),
                    event.eventId());
            return;
        }
        progressNotifier.notifyProgress(event);
    }

    private BacktestJob findJob(BacktestEvent<?> event) {
        return jobRepository
                .findByBacktestId(event.runId())
                .orElseGet(
                        () -> {
                            log.warn(
                                    "event for unknown runId dropped: runId={} type={} eventId={}",
                                    event.runId(),
                                    event.type(),
                                    event.eventId());
                            return null;
                        });
    }
}
