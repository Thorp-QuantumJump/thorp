package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.ExecutionLimits;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 살아 있지 않은 실행을 거둔다(spec 004 FR-005·FR-006, research R-19).
 *
 * <p>브로커는 "일하는 중"과 "멈춘 것"을 구분하지 못한다 — 연결만 살아 있으면 정상으로 본다. 워커는 {@code prefetch=1} 이라 Job 하나를 잡고 멈추면
 * ACK 를 하지 않아 <b>그 워커가 영원히 묶인다</b>. replica 둘 중 하나가 그러면 처리량이 절반이 되는데 아무도 모른다. 그래서 백엔드가 이벤트가 끊긴 실행을
 * 찾아 멈춰 세운다.
 *
 * <p>세 가지를 한 주기에 본다. 순서가 있다:
 *
 * <ol>
 *   <li><b>실행 시간 초과</b> — {@code started_at} 이 {@code max-run-seconds} 보다 오래됐다. CANCEL 을 보낸다
 *   <li><b>정체</b> — 마지막 이벤트로부터 {@code stale-run-seconds} 넘게 조용하다. CANCEL 을 보낸다
 *   <li><b>취소 무응답</b> — 어떤 이유로든 CANCEL 을 보낸 지 {@code cancel-grace-seconds} 가 지나도록 종결되지 않았다. 실행 주체가
 *       사라진 것으로 보고 {@code FAILED} 로 강제 종결한다
 * </ol>
 *
 * <p>①·②는 <b>협력 취소</b>다 — 워커가 살아 있으면 다음 거래일에 멈추고 부분 결과와 함께 {@code CANCELLED(partial=true)} 로 끝난다.
 * ③은 그 협력이 오지 않았을 때의 마지막 수단이라 {@code FAILED} 이고 결과 행을 만들지 않는다. <b>부분 결과가 없는데 부분 결과를 준다고 하지 않기
 * 위해</b> CANCELLED 가 아니라 FAILED 다. 실패 사유는 CANCEL 을 보낸 이유에서 온다({@link CancelReason#failureCode()}) —
 * 실행 시간 초과만 {@code EXECUTION_LIMIT} 이고 나머지는 {@code SYSTEM_FAILURE} 다.
 *
 * <p><b>대상을 읽은 뒤 건별 트랜잭션 안에서 행을 다시 잠그고 조건을 재검증한다.</b> 읽기와 쓰기 사이에 워커의 FINISHED 가 커밋될 수 있는데, 낡은 객체를
 * 그대로 쓰면 방금 COMPLETED 된 실행에 취소 의도를 남기거나(그 실행은 동일 버전 접수를 영원히 {@code CANCEL_PENDING} 으로 막는다) 정상 결과를
 * {@code FAILED} 로 덮는다.
 *
 * <p><b>유예 전의 취소 대기 실행에는 CANCEL 을 다시 보낸다.</b> 발행은 커밋 뒤에 일어나고 실패해도 삼켜지므로(R-04), 다시 보내지 않으면 브로커가 잠깐
 * 죽은 것만으로 워커는 계속 계산하는데 백엔드만 기다리다 강제 종결한다. 유예가 지나기 전까지 주기마다 다시 보낸다.
 *
 * <p><b>강제 종결도 완료 이벤트를 발행한다.</b> 일반 FINISHED·DLQ 종결과 같다 — 자동 탐색(007)은 그 이벤트로 후보를 정산하므로, 빠뜨리면 후보가
 * {@code DISPATCHED} 로 남아 묶음이 끝나지 않는다.
 *
 * <p>PROGRESS 가 계속 오는 긴 실행은 정체가 아니다. ②는 마지막 이벤트만 보므로 오래 걸리는 계산을 끊지 않는다 — 그것을 끊는 것은 ①의 몫이고, 그쪽은
 * 사용자에게 완료 범위를 남긴다.
 */
@Component
@ConditionalOnProperty(
        name = "thorp.backtest.liveness.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RunLivenessSweeper {

    private static final Logger log = LoggerFactory.getLogger(RunLivenessSweeper.class);

    private final BacktestRunRepository runRepository;
    private final BacktestCancelDispatcher cancelDispatcher;
    private final ExecutionMetricRecorder metrics;
    private final ExecutionLimits limits;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final int batchSize;
    private final TransactionTemplate newTransaction;

    public RunLivenessSweeper(
            BacktestRunRepository runRepository,
            BacktestCancelDispatcher cancelDispatcher,
            ExecutionMetricRecorder metrics,
            ExecutionLimits limits,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${thorp.backtest.liveness.batch-size:50}") int batchSize) {
        this.runRepository = runRepository;
        this.cancelDispatcher = cancelDispatcher;
        this.metrics = metrics;
        this.limits = limits;
        this.events = events;
        this.clock = clock;
        this.batchSize = batchSize;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${thorp.backtest.liveness.interval:PT30S}")
    public void sweep() {
        Instant now = clock.instant();
        cancelOverrunning(now);
        cancelStale(now);
        settleCancelPending(now);
    }

    /** 실행 시간 상한 초과. 워커가 응답하면 완료 범위가 남는다(FR-005). */
    private void cancelOverrunning(Instant now) {
        for (BacktestRun candidate :
                runRepository.findOverrunning(now.minus(limits.maxRun()), batchSize)) {
            sendCancel(
                    candidate,
                    CancelReason.EXECUTION_LIMIT,
                    now,
                    run ->
                            run.getLifecycle().startedAt() != null
                                    && run.getLifecycle()
                                            .startedAt()
                                            .plus(limits.maxRun())
                                            .isBefore(now),
                    "실행 시간 상한을 넘겼습니다");
        }
    }

    /** 정체. 마지막 이벤트로부터 오래 조용한 실행이다. */
    private void cancelStale(Instant now) {
        for (BacktestRun candidate :
                runRepository.findStaleRunning(now.minus(limits.staleRun()), batchSize)) {
            sendCancel(
                    candidate,
                    CancelReason.STALE,
                    now,
                    run -> run.getLifecycle().staleSince(now, limits.staleRun()),
                    "마지막 이벤트로부터 조용합니다");
        }
    }

    /**
     * 취소를 보낸 실행을 정산한다. 유예 전이면 CANCEL 을 다시 보내고, 지났으면 강제 종결한다.
     *
     * <p>강제 종결은 결과 행을 만들지 않는다 — 워커가 계산을 마치지 못했으므로 저장할 산출물이 없다. 상세 조회는 실행 행의 실패 사유를 그대로 보여 준다(T010
     * 에서 붙여 둔 경로다). 늦게 도착한 FINISHED 는 종결 상태 규칙이 무시한다.
     */
    private void settleCancelPending(Instant now) {
        for (BacktestRun candidate : runRepository.findCancelPending(batchSize)) {
            String runId = candidate.getBacktestId().value();
            try {
                newTransaction.executeWithoutResult(
                        status -> settleOne(candidate.getId(), runId, now));
            } catch (RuntimeException exc) {
                // 한 건이 터져도 나머지는 거둔다. 다음 주기에 다시 대상이 된다.
                log.error("취소 정산 중 예외가 났습니다. 다음 주기로 넘어갑니다: runId={}", runId, exc);
            }
        }
    }

    private void settleOne(Long id, String runId, Instant now) {
        BacktestRun run = runRepository.findByIdForUpdate(id).orElse(null);
        if (run == null
                || run.getStatus().isFinal()
                || run.getLifecycle().cancelRequestedAt() == null) {
            // 그 사이 워커가 응답했거나 취소가 사라졌다. 낡은 판단으로 정상 종결을 덮지 않는다.
            return;
        }
        if (!run.getLifecycle().cancelUnanswered(now, limits.cancelGrace())) {
            // 아직 기다릴 시간이 있다. 발행이 실패했을 수 있으니 다시 보낸다(R-04 는 실패를 삼킨다).
            cancelDispatcher.publishAfterCommit(runId);
            log.info("cancel re-sent while waiting for the worker: runId={}", runId);
            return;
        }
        CancelReason reason =
                run.getLifecycle().cancelReason() == null
                        ? CancelReason.USER
                        : run.getLifecycle().cancelReason();
        BacktestRun failed =
                run.markFailed(reason.failureCode(), "취소에 응답하지 않아 강제 종결했습니다: reason=" + reason)
                        .withLifecycle(run.getLifecycle().finished(now));
        runRepository.update(failed);
        // 종결 한 번에 측정 한 행(R-10). 워커 응답이 없으니 자원 사용은 null 이다.
        metrics.record(failed, null);
        // 일반 FINISHED·DLQ 종결과 같이 완료를 알린다 — 자동 탐색은 이 이벤트로 후보를 정산한다.
        events.publishEvent(
                new BacktestFinished(id, runId, RunStatus.FAILED, reason.failureCode()));
        log.warn(
                "run force-finished, cancel unanswered: runId={} reason={} grace={}s",
                runId,
                reason,
                limits.cancelGrace().toSeconds());
    }

    /**
     * 취소 의도를 먼저 커밋하고 CANCEL 은 커밋 뒤에 나간다 — 사용자 취소(R-04)와 같은 경로다.
     *
     * @param stillApplies 잠근 뒤 다시 보았을 때도 여전히 멈출 대상인가. 읽기와 쓰기 사이에 워커가 끝냈을 수 있다
     */
    private void sendCancel(
            BacktestRun candidate,
            CancelReason reason,
            Instant now,
            Predicate<BacktestRun> stillApplies,
            String why) {
        String runId = candidate.getBacktestId().value();
        try {
            newTransaction.executeWithoutResult(
                    status -> {
                        BacktestRun run =
                                runRepository.findByIdForUpdate(candidate.getId()).orElse(null);
                        if (run == null
                                || run.getStatus() != RunStatus.RUNNING
                                || run.getLifecycle().cancelRequestedAt() != null
                                || !stillApplies.test(run)) {
                            // 그 사이 끝났거나 이미 취소가 나갔다. 종결된 실행에 취소 의도를 남기면
                            // 동일 버전 접수가 영원히 CANCEL_PENDING 으로 막힌다.
                            return;
                        }
                        runRepository.recordCancelRequested(run.getId(), now, reason);
                        if (reason == CancelReason.EXECUTION_LIMIT) {
                            // 한도 도달(FR-006). 종결은 워커 응답 뒤라 측정 행이 아니라 별표에 남긴다(R-10 ②).
                            metrics.limitStopped(run);
                        }
                        cancelDispatcher.publishAfterCommit(runId);
                        log.warn(
                                "cancel sent by sweeper: runId={} reason={} ({})",
                                runId,
                                reason,
                                why);
                    });
        } catch (RuntimeException exc) {
            log.error("정지 요청 중 예외가 났습니다. 다음 주기로 넘어갑니다: runId={}", runId, exc);
        }
    }
}
