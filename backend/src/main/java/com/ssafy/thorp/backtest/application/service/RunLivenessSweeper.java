package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.ExecutionLimits;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.CancelReason;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
    private final ExecutionLimits limits;
    private final Clock clock;
    private final int batchSize;
    private final TransactionTemplate newTransaction;

    public RunLivenessSweeper(
            BacktestRunRepository runRepository,
            BacktestCancelDispatcher cancelDispatcher,
            ExecutionLimits limits,
            PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${thorp.backtest.liveness.batch-size:50}") int batchSize) {
        this.runRepository = runRepository;
        this.cancelDispatcher = cancelDispatcher;
        this.limits = limits;
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
        finishUnanswered(now);
    }

    /** 실행 시간 상한 초과. 워커가 응답하면 완료 범위가 남는다(FR-005). */
    private void cancelOverrunning(Instant now) {
        List<BacktestRun> runs =
                runRepository.findOverrunning(now.minus(limits.maxRun()), batchSize);
        for (BacktestRun run : runs) {
            sendCancel(run, CancelReason.EXECUTION_LIMIT, now, "실행 시간 상한을 넘겼습니다");
        }
    }

    /** 정체. 마지막 이벤트로부터 오래 조용한 실행이다. */
    private void cancelStale(Instant now) {
        List<BacktestRun> runs =
                runRepository.findStaleRunning(now.minus(limits.staleRun()), batchSize);
        for (BacktestRun run : runs) {
            sendCancel(run, CancelReason.STALE, now, "마지막 이벤트로부터 조용합니다");
        }
    }

    /**
     * CANCEL 이 응답을 받지 못했다. 실행 주체가 없다고 보고 끊는다.
     *
     * <p>결과 행을 만들지 않는다 — 워커가 계산을 마치지 못했으므로 저장할 산출물이 없다. 상세 조회는 실행 행의 실패 사유를 그대로 보여 준다(T010 에서 붙여 둔
     * 경로다). 강제 종결 뒤 늦게 도착한 FINISHED 는 종결 상태 규칙이 무시한다.
     */
    private void finishUnanswered(Instant now) {
        List<BacktestRun> runs =
                runRepository.findCancelUnanswered(now.minus(limits.cancelGrace()), batchSize);
        for (BacktestRun run : runs) {
            CancelReason reason =
                    run.getLifecycle().cancelReason() == null
                            ? CancelReason.USER
                            : run.getLifecycle().cancelReason();
            try {
                newTransaction.executeWithoutResult(
                        status -> {
                            BacktestRun failed =
                                    run.markFailed(
                                            reason.failureCode(),
                                            "취소에 응답하지 않아 강제 종결했습니다: reason=" + reason);
                            runRepository.update(
                                    failed.withLifecycle(failed.getLifecycle().finished(now)));
                        });
                log.warn(
                        "run force-finished, cancel unanswered: runId={} reason={} grace={}s",
                        run.getBacktestId().value(),
                        reason,
                        limits.cancelGrace().toSeconds());
            } catch (RuntimeException exc) {
                // 한 건이 터져도 나머지는 거둔다. 그 사이 종결됐다면 다음 주기에 대상에서 빠진다.
                log.error(
                        "강제 종결 중 예외가 났습니다. 다음 주기로 넘어갑니다: runId={}",
                        run.getBacktestId().value(),
                        exc);
            }
        }
    }

    /**
     * 취소 의도를 먼저 커밋하고 CANCEL 은 커밋 뒤에 나간다 — 사용자 취소(R-04)와 같은 경로다. 발행이 실패해도 {@code
     * cancel_requested_at} 이 남아 다음 주기의 유예 감시가 이 실행을 거둔다.
     */
    private void sendCancel(BacktestRun run, CancelReason reason, Instant now, String why) {
        try {
            // 건마다 트랜잭션을 연다. 하나가 터져도 나머지를 거두고, 무엇보다 CANCEL 발행이
            // AFTER_COMMIT 이라 트랜잭션이 없으면 이벤트가 조용히 버려진다.
            newTransaction.executeWithoutResult(
                    status -> {
                        runRepository.recordCancelRequested(run.getId(), now, reason);
                        cancelDispatcher.publishAfterCommit(run.getBacktestId().value());
                    });
            log.warn(
                    "cancel sent by sweeper: runId={} reason={} ({})",
                    run.getBacktestId().value(),
                    reason,
                    why);
        } catch (RuntimeException exc) {
            log.error("정지 요청 중 예외가 났습니다. 다음 주기로 넘어갑니다: runId={}", run.getBacktestId().value(), exc);
        }
    }
}
