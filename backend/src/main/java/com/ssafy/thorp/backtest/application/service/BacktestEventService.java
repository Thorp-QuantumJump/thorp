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
 * CANCELLED})는 워커가 취소를 구현하는 T011에서 받는다.
 *
 * <p><b>중복 실행 종결 규칙(R-18)</b>: 재발행이나 confirm 유실로 같은 실행의 메시지가 둘 생길 수 있고, 그러면 워커 둘이 같은 입력을 동시에 돈다. ①
 * {@code COMPLETED}는 어느 {@code executionId}든 적용한다 — 입력이 같으므로 결과가 같다. ② {@code FAILED}는 마지막 STARTED의
 * {@code executionId}일 때만 적용한다. 먼저 도착한 다른 실행의 실패가 뒤에 올 정상 결과를 덮으면, 성공한 계산이 있는데도 사용자에게 실패를 보여 주게 된다.
 *
 * <p>조회 → 판단 → 갱신 사이의 경쟁은 다루지 않는다. 같은 Job의 이벤트는 큐 순서대로 오고 리스너가 prefetch 1·단일 Consumer이므로 한 인스턴스
 * 안에서는 직렬이다. 백엔드를 다중 인스턴스로 띄우기 전에 낙관적 락 또는 상태 조건부 UPDATE를 넣어야 한다.
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
                    case CANCELLED ->
                            throw new UnsupportedOperationException(
                                    "CANCELLED results need the cancel flow (spec 004 T011);"
                                            + " runId="
                                            + event.runId());
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
        events.publishEvent(
                new BacktestFinished(
                        job.getId(),
                        event.runId(),
                        finished.getStatus(),
                        finished.getStatus() == RunStatus.FAILED ? failureCode(output) : null));
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
        jobRepository.update(
                job.recordProgress(
                        new RunProgress(
                                ProgressStage.COMPUTING,
                                payload.lastTradingDate(),
                                payload.completedTradingDays(),
                                payload.totalTradingDays())));
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

    /** 계약의 {@code run.error.code}. 검증기가 FAILED에 error를 요구하므로 여기서 비는 일은 없다. */
    private static String failureCode(EngineOutput output) {
        return output.run().error() == null ? "ENGINE_ERROR" : output.run().error().code();
    }

    private static String failureMessage(EngineOutput output) {
        return output.run().error() == null ? null : output.run().error().message();
    }
}
