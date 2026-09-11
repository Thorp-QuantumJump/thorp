package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 작업 메시지를 <b>커밋한 뒤에</b> 브로커로 보낸다(spec 004 research R-18).
 *
 * <p>예전에는 접수 트랜잭션 안에서 발행했다. 그러면 워커가 너무 빨라 STARTED가 커밋보다 먼저 도착하고, 결과 Consumer가 모르는 runId라며 버렸다. 반대로
 * 발행 뒤 커밋이 실패하면 아무도 모르는 고아 메시지가 남았다. 커밋 후 발행이 앞의 문제를, {@code published_at} 재발행(JobRepublisher)이 뒤의
 * 문제를 닫는다. run 행이 발행 대상과 1:1이라 별도 outbox 테이블 없이 같은 보장을 얻는다.
 *
 * <p><b>{@code publishCount}는 접수 트랜잭션에서 이미 1로 올라와 있다</b>(발행 시도 예고). 여기서 다시 올리지 않는다 — 시도 횟수는 실제 메시지
 * 수의 상한이면 되고, 예고했다가 못 보낸 경우(1 ≥ 0)도 상한을 지킨다. 재발행할 때만 한 번 더 올린다.
 */
@Component
public class BacktestJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BacktestJobDispatcher.class);

    private final BacktestRunRepository repository;
    private final BacktestJobPublisher publisher;
    private final EngineInputAssembler assembler;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public BacktestJobDispatcher(
            BacktestRunRepository repository,
            BacktestJobPublisher publisher,
            EngineInputAssembler assembler,
            TransactionTemplate transactionTemplate,
            Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.assembler = assembler;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 접수가 커밋됐다는 신호. 이 이벤트가 없으면(롤백) 발행도 없다. */
    public record RunAccepted(long runId) {}

    @TransactionalEventListener
    public void onRunAccepted(RunAccepted event) {
        publishAlreadyCounted(event.runId());
    }

    /**
     * 접수가 시도 횟수를 이미 올려 둔 실행을 발행한다.
     *
     * <p>실패해도 예외를 밖으로 던지지 않는다 — 커밋은 이미 끝났고 사용자에게는 202를 돌려준 뒤다. 발행이 안 된 실행은 {@code published_at}이
     * NULL로 남아 {@link JobRepublisher}가 집어 간다.
     */
    void publishAlreadyCounted(long runId) {
        Optional<BacktestRun> found =
                transactionTemplate.execute(status -> repository.findById(runId));
        if (found.isEmpty()) {
            log.warn("발행할 실행을 찾지 못했습니다: runId={}", runId);
            return;
        }
        BacktestRun run = found.get();
        try {
            publisher.publish(assembler.assemble(run));
        } catch (RuntimeException exc) {
            log.warn(
                    "작업 발행에 실패했습니다. 재발행기가 다시 시도합니다: runId={}, backtestId={}, cause={}",
                    runId,
                    run.getBacktestId().value(),
                    exc.toString());
            return;
        }
        markPublished(run);
    }

    /**
     * 재발행. 시도 횟수를 먼저 올리고 커밋한 뒤 보낸다.
     *
     * <p>순서가 중요하다. 올리기 전에 보내면 그 사이 죽었을 때 브로커에는 메시지가 있는데 시도 횟수가 모자라고, 그러면 DLQ 종결 판정 ({@code dlqCount
     * >= publishCount})이 살아 있는 실행을 실패로 끊는다.
     */
    void republish(long runId) {
        BacktestRun counted =
                transactionTemplate.execute(
                        status ->
                                repository
                                        .findById(runId)
                                        .map(
                                                run ->
                                                        repository.update(
                                                                run.withLifecycle(
                                                                        run.getLifecycle()
                                                                                .publishAttempted())))
                                        .orElse(null));
        if (counted == null) {
            log.warn("재발행할 실행을 찾지 못했습니다: runId={}", runId);
            return;
        }
        try {
            publisher.publish(assembler.assemble(counted));
        } catch (RuntimeException exc) {
            log.warn("재발행에 실패했습니다: runId={}, cause={}", runId, exc.toString());
            return;
        }
        markPublished(counted);
    }

    private void markPublished(BacktestRun run) {
        transactionTemplate.executeWithoutResult(
                status ->
                        repository
                                .findById(run.getId())
                                .ifPresent(
                                        fresh ->
                                                repository.update(
                                                        fresh.withLifecycle(
                                                                fresh.getLifecycle()
                                                                        .published(
                                                                                clock
                                                                                        .instant())))));
    }
}
