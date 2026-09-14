package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 작업 메시지를 <b>커밋한 뒤에</b> 브로커로 보낸다(spec 004 research R-18).
 *
 * <p>예전에는 접수 트랜잭션 안에서 발행했다. 그러면 워커가 너무 빨라 STARTED가 커밋보다 먼저 도착하고, 결과 Consumer가 모르는 runId라며 버렸다. 반대로
 * 발행 뒤 커밋이 실패하면 아무도 모르는 고아 메시지가 남았다. 커밋 후 발행이 앞의 문제를, {@code published_at} 재발행(JobRepublisher)이 뒤의
 * 문제를 닫는다. run 행이 발행 대상과 1:1이라 별도 outbox 테이블 없이 같은 보장을 얻는다.
 *
 * <p><b>여기의 DB 작업은 모두 새 트랜잭션이어야 한다.</b> {@code AFTER_COMMIT} 콜백은 원래 트랜잭션이 커밋된 뒤, 그러나 자원이 아직 묶여 있는
 * 상태에서 실행된다. 기본 전파(REQUIRED)로 열면 이미 끝난 그 트랜잭션에 참여해 변경이 다시 커밋되지 않는다 — 발행에 성공해도 {@code published_at}이
 * NULL로 남아 재발행기가 멀쩡한 작업을 계속 다시 보낸다.
 *
 * <p><b>상태 컬럼은 건드리지 않는다.</b> 발행 경로는 {@code publish_count}·{@code published_at}만 쓰고, 이벤트 처리는
 * 상태·진행·시각만 쓴다. 둘은 서로 다른 스레드(스케줄러와 리스너)에서 동시에 도는데, 한쪽이 실행 전체를 읽어 통째로 저장하면 늦게 저장한 쪽이 상대의 갱신을 되돌린다.
 * 예를 들어 재발행기가 QUEUED를 읽은 뒤 리스너가 RUNNING으로 바꿨는데 재발행기가 나중에 저장하면 실행이 QUEUED로 되돌아간다.
 */
@Component
public class BacktestJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BacktestJobDispatcher.class);

    private final BacktestRunRepository repository;
    private final BacktestJobPublisher publisher;
    private final EngineInputAssembler assembler;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    public BacktestJobDispatcher(
            BacktestRunRepository repository,
            BacktestJobPublisher publisher,
            EngineInputAssembler assembler,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.assembler = assembler;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** 접수가 커밋됐다는 신호. 이 이벤트가 없으면(롤백) 발행도 없다. */
    public record RunAccepted(long runId) {}

    /**
     * <b>여기서 나가는 예외는 접수를 실패로 만든다.</b> {@code AFTER_COMMIT} 콜백의 예외는 삼켜지지 않고 커밋을 호출한 쪽으로 전파된다 — 접수는
     * 이미 커밋됐고 작업은 브로커에 들어갔는데 사용자만 500을 받는다. 그러면 사용자가 다시 눌러 같은 요청이 하나 더 생긴다.
     *
     * <p>그래서 발행 실패뿐 아니라 <b>발행 뒤 기록 실패까지</b> 전부 여기서 막는다. 기록에 실패한 실행은 {@code published_at}이 NULL로 남아
     * 재발행기가 집어 가므로 잃는 것이 없다.
     */
    @TransactionalEventListener
    public void onRunAccepted(RunAccepted event) {
        try {
            publishAlreadyCounted(event.runId());
        } catch (RuntimeException exc) {
            log.error("커밋 후 발행 처리에서 예외가 났습니다. 접수는 그대로 두고 재발행기에 맡깁니다: runId={}", event.runId(), exc);
        }
    }

    /**
     * 접수가 시도 횟수를 이미 올려 둔 실행을 발행한다.
     *
     * <p>실패해도 예외를 밖으로 던지지 않는다 — 커밋은 이미 끝났고 사용자에게는 202를 돌려준 뒤다. 발행이 안 된 실행은 {@code published_at}이
     * NULL로 남아 {@link JobRepublisher}가 집어 간다.
     */
    void publishAlreadyCounted(long runId) {
        Optional<BacktestRun> found = newTransaction.execute(status -> repository.findById(runId));
        if (found == null || found.isEmpty()) {
            log.warn("발행할 실행을 찾지 못했습니다: runId={}", runId);
            return;
        }
        send(found.get());
    }

    /**
     * 재발행. 시도 횟수를 먼저 올리고 커밋한 뒤 보낸다.
     *
     * <p>순서가 중요하다. 올리기 전에 보내면 그 사이 죽었을 때 브로커에는 메시지가 있는데 시도 횟수가 모자라고, 그러면 DLQ 종결 판정 ({@code dlqCount
     * >= publishCount})이 살아 있는 실행을 실패로 끊는다.
     */
    void republish(long runId) {
        Optional<BacktestRun> found =
                newTransaction.execute(
                        status -> {
                            repository.recordPublishAttempt(runId);
                            return repository.findById(runId);
                        });
        if (found == null || found.isEmpty()) {
            log.warn("재발행할 실행을 찾지 못했습니다: runId={}", runId);
            return;
        }
        send(found.get());
    }

    private void send(BacktestRun run) {
        try {
            // 시도 번호는 방금 올린 publish_count 다 — 두 경로 모두 올린 뒤 실행을 다시 읽고 여기로 온다.
            publisher.publish(assembler.assemble(run), run.getLifecycle().publishCount());
        } catch (RuntimeException exc) {
            log.warn(
                    "작업 발행에 실패했습니다. 재발행기가 다시 시도합니다: runId={}, backtestId={}, cause={}",
                    run.getId(),
                    run.getBacktestId().value(),
                    exc.toString());
            return;
        }
        newTransaction.executeWithoutResult(
                status -> repository.recordPublished(run.getId(), clock.instant()));
    }
}
