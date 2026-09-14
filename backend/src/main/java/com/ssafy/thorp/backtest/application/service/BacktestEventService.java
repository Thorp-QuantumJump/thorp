package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.command.SaveBacktestResultCommand;
import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>envelope의 {@code runId}는 <b>실행</b>의 외부 식별자다. 사용자가 보는 요청 식별자와 다르다(R-02). 취소 결과({@code
 * CANCELLED})는 부분 결과와 함께 저장하고 실행을 {@code CANCELLED·partial}로 끝낸다(R-04, T011).
 *
 * <p><b>중복 실행 종결 규칙(R-18)</b>: 재발행이나 confirm 유실로 같은 실행의 메시지가 둘 생길 수 있고, 그러면 워커 둘이 같은 입력을 동시에 돈다. ①
 * {@code COMPLETED}는 어느 {@code executionId}든 적용한다 — 입력이 같으므로 결과가 같다. ② {@code FAILED}는 마지막 STARTED의
 * {@code executionId}일 때만 적용한다. 먼저 도착한 다른 실행의 실패가 뒤에 올 정상 결과를 덮으면, 성공한 계산이 있는데도 사용자에게 실패를 보여 주게 된다.
 *
 * <p>같은 Job의 이벤트끼리는 큐 순서대로 오고 리스너가 prefetch 1·단일 Consumer이므로 한 인스턴스 안에서는 직렬이다. 하지만 <b>취소는 다른
 * 스레드(HTTP)</b>라 그 직렬성 밖에 있다 — 그래서 조회를 실행 행 잠금({@code findByBacktestIdForUpdate})으로 한다. 잠그지 않으면
 * STARTED 가 QUEUED 를 읽은 직후 커밋된 취소를 늦은 UPDATE 가 RUNNING 으로 덮는다. 잠금 순서는 취소(요청 → 실행)와 어긋나지 않는다 — 여기서는
 * 실행 행 하나만 잠근다.
 */
@Service
public class BacktestEventService {

    private static final Logger log = LoggerFactory.getLogger(BacktestEventService.class);

    private final BacktestRunRepository jobRepository;
    private final BacktestResultRepository resultRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public BacktestEventService(
            BacktestRunRepository jobRepository,
            BacktestResultRepository resultRepository,
            ApplicationEventPublisher events,
            Clock clock) {
        this.jobRepository = jobRepository;
        this.resultRepository = resultRepository;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 실행이 시작됐다. QUEUED면 RUNNING으로 가고 단계는 PREPARING_DATA다.
     *
     * <p>이미 RUNNING인데 <b>다른</b> executionId가 오면 상태는 그대로 두고 시도 횟수만 올린다 — 워커가 죽어 브로커가 재전달했거나(R-06)
     * 재발행으로 중복 실행이 생긴 경우다. 같은 executionId의 중복 STARTED는 아무것도 바꾸지 않는다(도메인이 판단).
     */
    @Transactional
    public void handleStarted(BacktestEvent<StartedPayload> event) {
        BacktestRun job = findJob(event);
        if (job == null) {
            return;
        }
        if (job.getStatus() == RunStatus.CANCELLED) {
            // 대기 중 취소된 실행이 뒤늦게 시작했다(R-04). 워커는 이 CANCEL 을 받아 첫 거래일 뒤 멈추고
            // FINISHED 를 보내지만 그것은 종결 상태 규칙으로 무시된다.
            log.info(
                    "STARTED for a cancelled run; re-sending CANCEL: runId={} eventId={}",
                    event.runId(),
                    event.eventId());
            events.publishEvent(new BacktestCancelDispatcher.CancelRequested(event.runId()));
            return;
        }
        if (job.getStatus().isFinal()) {
            log.info(
                    "late STARTED ignored: runId={} status={} eventId={}",
                    event.runId(),
                    job.getStatus(),
                    event.eventId());
            return;
        }
        BacktestRun started = job.markRunning(event.executionId());
        jobRepository.update(
                started.withLifecycle(started.getLifecycle().started(clock.instant())));
        // 워커가 살아 있다는 신호. 전용 UPDATE 라 상태 전이와 컬럼이 겹치지 않는다(R-19).
        jobRepository.recordEventReceived(job.getId(), clock.instant());
        if (job.getLifecycle().cancelRequestedAt() != null) {
            // 취소를 보낸 실행이 다시 STARTED 를 보냈다 = 재전달이나 중복 실행이다(R-06). 워커는 실행이
            // 끝날 때 레지스트리의 취소 표시를 지우므로 앞서 보낸 CANCEL 은 이 실행에 닿지 않는다 —
            // 다시 보내지 않으면 취소한 실행이 끝까지 계산돼 COMPLETED 가 된다.
            log.info(
                    "STARTED for a cancel-pending run; re-sending CANCEL: runId={} eventId={}",
                    event.runId(),
                    event.eventId());
            events.publishEvent(new BacktestCancelDispatcher.CancelRequested(event.runId()));
        }
    }

    /**
     * 결과 저장 + 상태 전이(COMPLETED | FAILED)를 한 트랜잭션으로. 둘 중 하나만 반영된 상태를 만들지 않는다.
     *
     * <p>QUEUED 상태의 FINISHED도 받는다. STARTED가 유실됐거나 DLQ에 가 있어도 결과는 확정된다.
     */
    @Transactional
    public void handleFinished(BacktestEvent<FinishedPayload> event) {
        BacktestRun job = findJob(event);
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
        BacktestRun finished =
                switch (event.payload().status()) {
                    case COMPLETED -> job.markCompleted();
                    case FAILED ->
                            isCurrentExecution(job, event)
                                    ? job.markFailed(failureCode(output), failureMessage(output))
                                    : null;
                    // 부분 결과(R-04). 워커가 실제로 멈춘 거래일이 완료 범위다. 먼저 도착한 것을 적용한다(R-18 ④).
                    // 실행 시간 상한으로 보낸 취소의 응답이면 그 사유를 남긴다 — 사용자가 스스로 멈춘 것과
                    // 서버가 끊은 것은 다르고, 계약이 후자에 failure.code=EXECUTION_LIMIT 를 정해 두었다(R-19).
                    case CANCELLED ->
                            job.markCancelled(
                                    event.payload().lastTradingDate(), cancelFailureCode(job));
                };
        if (finished == null) {
            return;
        }
        resultRepository.save(
                new SaveBacktestResultCommand(
                        job.getId(), event.executionId(), event.eventId(), output, null));
        jobRepository.update(
                finished.withLifecycle(finished.getLifecycle().finished(clock.instant())));

        // 트랜잭션 안에서 발행하고, 수신자가 AFTER_COMMIT으로 받는다. 커밋 전에 전달되면 수신자가
        // 아직 없는 결과를 읽는다(spec 007 research R6). 이 모듈은 수신자를 알지 못한다.
        //
        // 사유는 실행 행에서 그대로 읽는다. FAILED 만 싣던 때는 실행 시간 상한으로 끊긴 CANCELLED 의
        // EXECUTION_LIMIT 이 버려졌고, 받는 쪽이 그 후보를 정상 완료로 집계해 선정 대상에 넣었다(R-19).
        events.publishEvent(
                new BacktestFinished(
                        job.getId(),
                        event.runId(),
                        finished.getStatus(),
                        finished.getFailureCode()));
    }

    /**
     * 진행을 실행 행에 저장한다(R-05). 조회는 이 값을 폴링으로 읽는다.
     *
     * <p>20거래일 배치라 실행당 수백 번 UPDATE 이하이고, 저장해 두어야 사용자가 다른 화면에 갔다 와도 진행이 보인다. SSE는 이번 범위가 아니므로 {@code
     * LoggingBacktestProgressNotifier}는 지웠다 — 아무도 구독하지 않는 알림을 남겨 둘 이유가 없다.
     *
     * <p>RUNNING이 아닌 실행의 PROGRESS는 무시한다. QUEUED에 진행률만 먼저 들어오면 상태와 진행이 어긋나고, 끝난 실행에 들어오면 최종 범위가 뒤로
     * 밀린다.
     */
    @Transactional
    public void handleProgress(BacktestEvent<ProgressPayload> event) {
        BacktestRun job = findJob(event);
        if (job == null) {
            return;
        }
        if (job.getStatus() != RunStatus.RUNNING) {
            log.info(
                    "PROGRESS for non-running job ignored: runId={} status={} eventId={}",
                    event.runId(),
                    job.getStatus(),
                    event.eventId());
            return;
        }
        ProgressPayload payload = event.payload();
        jobRepository.recordEventReceived(job.getId(), clock.instant());
        jobRepository.update(
                job.recordProgress(
                        new RunProgress(
                                ProgressStage.COMPUTING,
                                payload.lastTradingDate(),
                                payload.completedTradingDays(),
                                payload.totalTradingDays())));
    }

    /**
     * 작업 메시지 하나가 DLQ 로 끝났다(R-18 ③, T010). 브로커가 재전달 상한({@code x-delivery-limit=1})을 다 쓴 뒤 보낸 것이다.
     *
     * <p><b>메시지 하나가 죽었다고 실행을 끝내지 않는다.</b> confirm 이 유실돼 같은 실행의 메시지가 둘 생길 수 있고(R-18), 그 중 하나만 DLQ 로
     * 가면 다른 하나는 아직 워커에서 돌고 있다. 발행한 메시지가 <b>전부</b> DLQ 로 끝났을 때만({@code dlqCount >= publishCount})
     * 실행을 {@code FAILED(SYSTEM_FAILURE)}로 종결한다. 그래서 {@code publishCount}가 실제 메시지 수의 상한이어야 한다 — 모자라면
     * 살아 있는 실행을 끊는다.
     *
     * <p>결과 행은 만들지 않는다. 워커가 계산을 마치지 못했으므로 저장할 산출물이 없다 — 상세 조회는 실행 행의 실패 사유를 그대로 보여 준다.
     *
     * <p><b>같은 시도를 두 번 세지 않는다.</b> DLQ 소비는 at-least-once 라 커밋 뒤 ACK 이 유실되면 같은 메시지가 다시 온다. 저장소가
     * {@code (runId, attempt)} 기록으로 걸러 주므로 재전달은 세지 않고, <b>커밋 전에 죽어 다시 온 첫 전달은 제대로 센다</b> — 그 경우 기록도
     * 함께 롤백됐기 때문이다.
     */
    @Transactional
    public void handleDeadLetter(String runId, int attempt) {
        BacktestRun job = jobRepository.findByBacktestIdForUpdate(runId).orElse(null);
        if (job == null) {
            log.warn("dead-lettered job for unknown runId dropped: runId={}", runId);
            return;
        }
        if (!jobRepository.recordDeadLetter(job.getId(), attempt, clock.instant())) {
            log.info(
                    "dead-lettered job already counted; redelivery ignored: runId={} attempt={}",
                    runId,
                    attempt);
            return;
        }
        // 방금 올린 값을 포함해 다시 읽는다. 셈은 DB 가 하므로 여기서 더하지 않는다.
        BacktestRun counted = jobRepository.findById(job.getId()).orElseThrow();
        if (counted.getStatus().isFinal()) {
            log.info(
                    "dead-lettered job for a finished run: runId={} status={}",
                    runId,
                    counted.getStatus());
            return;
        }
        if (!counted.getLifecycle().allMessagesDeadLettered()) {
            log.info(
                    "dead-lettered job counted, other messages may still run: runId={} dlq={}/{}",
                    runId,
                    counted.getLifecycle().dlqCount(),
                    counted.getLifecycle().publishCount());
            return;
        }
        BacktestRun failed = counted.markFailed("SYSTEM_FAILURE", "작업 메시지가 모두 처리되지 못했습니다(DLQ).");
        jobRepository.update(failed.withLifecycle(failed.getLifecycle().finished(clock.instant())));
        log.warn(
                "run failed: every published job message was dead-lettered: runId={} dlq={}/{}",
                runId,
                counted.getLifecycle().dlqCount(),
                counted.getLifecycle().publishCount());
        events.publishEvent(
                new BacktestFinished(job.getId(), runId, RunStatus.FAILED, "SYSTEM_FAILURE"));
    }

    /** 협력 취소의 사유. 사용자 취소·정체는 실패가 아니므로 null 이다. */
    private static String cancelFailureCode(BacktestRun job) {
        return job.getLifecycle().cancelReason() == CancelReason.EXECUTION_LIMIT
                ? "EXECUTION_LIMIT"
                : null;
    }

    /**
     * 이 이벤트가 마지막으로 시작한 실행의 것인가(R-18 2번).
     *
     * <p>아직 STARTED를 받지 못한 실행(QUEUED)의 FAILED는 받아들인다 — 비교할 current 가 없고, 그 실패를 버리면 실행이 영원히 QUEUED로
     * 남는다.
     */
    private boolean isCurrentExecution(BacktestRun job, BacktestEvent<?> event) {
        String current = job.getCurrentExecutionId();
        if (current == null || current.equals(event.executionId())) {
            return true;
        }
        log.info(
                "FAILED from a superseded execution ignored: runId={} eventExecutionId={}"
                        + " currentExecutionId={} eventId={}",
                event.runId(),
                event.executionId(),
                current,
                event.eventId());
        return false;
    }

    private BacktestRun findJob(BacktestEvent<?> event) {
        return jobRepository
                .findByBacktestIdForUpdate(event.runId())
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

    /** 계약의 {@code run.error.code}. 검증기가 FAILED에 error를 요구하므로 여기서 비는 일은 없다. */
    private static String failureCode(EngineOutput output) {
        return output.run().error() == null ? "ENGINE_ERROR" : output.run().error().code();
    }

    private static String failureMessage(EngineOutput output) {
        return output.run().error() == null ? null : output.run().error().message();
    }
}
