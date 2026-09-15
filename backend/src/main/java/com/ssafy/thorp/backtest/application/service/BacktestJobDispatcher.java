package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.common.exception.BusinessException;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p><b>상태 컬럼은 원칙적으로 건드리지 않는다.</b> 발행 경로는 {@code publish_count}·{@code published_at}만 쓰고, 이벤트 처리는
 * 상태·진행·시각만 쓴다. 둘은 서로 다른 스레드(스케줄러와 리스너)에서 동시에 도는데, 한쪽이 실행 전체를 읽어 통째로 저장하면 늦게 저장한 쪽이 상대의 갱신을 되돌린다.
 * 예를 들어 재발행기가 QUEUED를 읽은 뒤 리스너가 RUNNING으로 바꿨는데 재발행기가 나중에 저장하면 실행이 QUEUED로 되돌아간다.
 *
 * <p><b>예외는 하나뿐이다 — 첫 발행에서 입력 조립이 영구히 실패했을 때.</b> 그런 실행은 메시지가 브로커에 나간 적이 없어 {@code dlq_count}가 오르지
 * 않고, 그러면 DLQ 종결 판정({@code dlqCount >= publishCount})이 영영 서지 않아 실행이 {@code QUEUED}에 갇힌 채 시도 횟수만
 * 주기마다 오른다. 사용자는 202를 받고 화면은 끝없이 대기한다. 그래서 {@link #send}가 그 경우에만 실행을 {@code
 * FAILED(SYSTEM_FAILURE)}로 닫는다.
 *
 * <p><b>조립 실패가 전부 영구인 것은 아니다.</b> {@link EngineInputAssembler}는 전략 규칙·비용 등록표를 DB 에서 읽는다. 커넥션 풀 고갈이나
 * DB 재기동이면 거기서 {@code DataAccessException} 계열이 나오는데, 그것은 30초 뒤 재발행하면 그냥 성공한다. 그래서 {@link
 * #isPermanentAssemblyFailure}가 <b>영구라고 아는 예외만</b> 허용 목록으로 골라 낸다 — 모르는 예외는 일시로 보고 재발행기에 맡긴다. 잘못 닫으면
 * 멀쩡한 요청이 사용자 실패가 되고, 잘못 재시도하면 로그가 쌓일 뿐이다.
 *
 * <p><b>재발행 경로에서는 닫지 않는다.</b> 상태가 {@code QUEUED}라고 해서 워커가 없다는 뜻이 아니다 — STARTED 가 아직 처리되기 전이면 워커는 이미
 * 그 메시지를 들고 계산 중인데 상태는 {@code QUEUED}다. 재발행 대상이 되었다는 것 자체가 "이전에 메시지가 나갔을 수도 있다"는 뜻이므로(발행은 성공했는데
 * {@code published_at} 기록이 실패한 경우), 거기서 닫으면 뒤늦게 올 성공 결과까지 최종 상태 규칙에 걸려 버려진다. 메시지가 나간 적이 없음이 확실한 것은
 * <b>첫 발행({@link #publishAlreadyCounted})뿐</b>이다.
 *
 * <p>재발행에서 영구 실패가 반복돼 갇히는 일은 드물다. 영구 실패라면 첫 발행에서 이미 닫혀 재발행 대상이 되지 못하고, 남는 재발행 대상은 조립이 한 번 성공했던 실행이라
 * 다시 실패하는 이유도 대개 일시 장애다.
 *
 * <p><b>다만 갇히는 경우가 하나 남는다.</b> 첫 발행에서 조립이 일시 장애로 실패해 닫히지 않은 실행이 재발행 대상이 되고, 그 사이 등록표 행이 사라지는 식으로 영구
 * 실패로 바뀌면 경로 판정에 걸려 닫히지 않는다. 그 좁은 경우를 막자고 재발행에서도 닫으면 위 문단의 회귀가 되살아나므로, 남겨 두는 쪽을 골랐다.
 *
 * <p>닫을 때는 {@code status = QUEUED}인 행만 바꾼다. 행을 잠그고({@code findByIdForUpdate}) 다시 읽어 확인하므로, 읽고 판단하고
 * 쓰는 사이에 들어온 취소나 상태 전이를 덮어쓰지 않는다.
 */
@Component
public class BacktestJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BacktestJobDispatcher.class);

    /** DLQ 종결과 같은 코드를 쓴다. 사용자가 고칠 수 없는 내부 장애라는 뜻이 같다. */
    private static final String ASSEMBLY_FAILURE_CODE = "SYSTEM_FAILURE";

    private static final String ASSEMBLY_FAILURE_MESSAGE = "작업 입력을 만들지 못했습니다. 다시 시도해도 같은 결과입니다.";

    private final BacktestRunRepository repository;
    private final BacktestJobPublisher publisher;
    private final EngineInputAssembler assembler;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    public BacktestJobDispatcher(
            BacktestRunRepository repository,
            BacktestJobPublisher publisher,
            EngineInputAssembler assembler,
            ApplicationEventPublisher events,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.assembler = assembler;
        this.events = events;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /**
     * 접수가 커밋됐다는 신호. 이 이벤트가 없으면(롤백) 발행도 없다.
     *
     * @param publishAttempt 접수가 확보한 시도 번호. <b>여기에 실어 나른다</b> — 이 콜백이 늦어지는 사이 재발행기가 시도 번호를 올릴 수 있고,
     *     그때 DB 를 다시 읽으면 최초 발행과 재발행이 같은 번호를 쓴다. 그러면 서로 다른 두 메시지가 DLQ 에서 한 건으로 집계돼 종결 판정이 영영 서지
     *     않는다(R-18 ③)
     */
    public record RunAccepted(long runId, int publishAttempt) {}

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
            publishAlreadyCounted(event.runId(), event.publishAttempt());
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
    void publishAlreadyCounted(long runId, int publishAttempt) {
        Optional<BacktestRun> found = newTransaction.execute(status -> repository.findById(runId));
        if (found == null || found.isEmpty()) {
            log.warn("발행할 실행을 찾지 못했습니다: runId={}", runId);
            return;
        }
        // 시도 번호는 접수가 확보한 값을 쓰고 방금 읽은 실행에서 다시 읽지 않는다 — 이 콜백이 늦어지면
        // 그 사이 재발행기가 올린 번호를 보게 되고, 두 발행이 같은 번호를 달면 DLQ 집계가 한 건으로 접힌다.
        send(found.get(), publishAttempt, PublishPath.FIRST);
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
        // 올리기와 읽기가 한 트랜잭션이고 UPDATE 가 행을 잠그므로, 여기 값은 이 재발행이 확보한 번호다.
        send(found.get(), found.get().getLifecycle().publishCount(), PublishPath.REPUBLISH);
    }

    /**
     * 조립이 실패했을 때 실행을 닫아도 되는 경로인가.
     *
     * <p>{@link #FIRST}는 이 실행의 메시지가 브로커에 나간 적이 없음이 확실하다. {@link #REPUBLISH}는 그렇지 않다 — 재발행 대상이 되었다는
     * 것 자체가 "발행에는 성공했는데 {@code published_at} 기록만 실패했을 수도 있다"는 뜻이고, 그때 워커는 이미 계산 중인데 STARTED 가 아직
     * 처리되지 않아 상태는 {@code QUEUED}다. 거기서 닫으면 뒤늦게 올 성공 결과가 최종 상태 규칙에 걸려 버려진다.
     */
    private enum PublishPath {
        FIRST,
        REPUBLISH
    }

    /**
     * <b>조립과 발행을 따로 잡는다.</b> 한 {@code try}로 묶으면 성질이 다른 두 실패가 같은 길로 흐른다. 다만 갈림은 "조립이냐 발행이냐"만으로 서지
     * 않는다 — 조립 실패에도 일시(등록표 조회가 DB 장애로 실패)와 영구(등록표에 그 행이 없다)가 섞여 있다. 그래서 <b>영구라고 아는 예외</b>가 <b>첫
     * 발행</b>에서 났을 때만 닫고, 나머지는 전부 재발행기에 맡긴다.
     *
     * @param publishAttempt <b>호출자가 확보한</b> 시도 번호. 실행에서 다시 읽지 않는다 — 두 발행 경로가 같은 번호를 달면 DLQ 집계가 서로
     *     다른 두 메시지를 한 건으로 접어 종결 판정이 서지 않는다(R-18 ③)
     * @param path 조립이 영구히 실패했을 때 실행을 닫아도 되는 경로인지
     */
    private void send(BacktestRun run, int publishAttempt, PublishPath path) {
        EngineInput input;
        try {
            input = assembler.assemble(run);
        } catch (RuntimeException exc) {
            onAssemblyFailure(run, exc, path);
            return;
        }
        try {
            publisher.publish(input, publishAttempt);
        } catch (RuntimeException exc) {
            // 일시 장애다. 상태는 QUEUED 그대로 두고 재발행기에 맡긴다 — 여기서 함께 닫으면 브로커가
            // 잠깐 흔들린 것이 사용자 실패가 된다.
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

    /**
     * 조립 실패를 닫을지 재발행기에 맡길지 가른다.
     *
     * <p>닫는 것은 <b>영구 실패 + 첫 발행</b>일 때뿐이다. 둘 중 하나라도 아니면 아무것도 쓰지 않고 WARN 만 남긴다 — 실행은 {@code QUEUED}로
     * 남아 다음 재발행 주기에 다시 시도된다.
     */
    private void onAssemblyFailure(BacktestRun run, RuntimeException cause, PublishPath path) {
        if (!isPermanentAssemblyFailure(cause)) {
            // 등록표 조회가 DB 장애로 실패한 경우다. 30초 뒤 재발행하면 그냥 성공한다.
            log.warn(
                    "작업 입력 조립이 일시적으로 실패했습니다. 재발행기가 다시 시도합니다: runId={}, backtestId={}, cause={}",
                    run.getId(),
                    run.getBacktestId().value(),
                    cause.toString());
            return;
        }
        if (path != PublishPath.FIRST) {
            // 이 실행의 메시지가 이미 나갔을 수 있다. 워커가 계산 중인데 STARTED 가 아직 처리되지 않아
            // 상태만 QUEUED 일 수 있으므로, 여기서 닫으면 뒤늦게 올 성공 결과를 버리게 된다.
            log.warn(
                    "재발행에서 조립이 영구히 실패했지만 메시지가 이미 나갔을 수 있어 닫지 않습니다: runId={}, backtestId={}, cause={}",
                    run.getId(),
                    run.getBacktestId().value(),
                    cause.toString());
            return;
        }
        closeAsAssemblyFailure(run, cause);
    }

    /**
     * 다시 보내도 같은 결과인 실패인가. <b>허용 목록으로 고른다</b> — 모르는 예외는 일시로 본다.
     *
     * <p>{@link EngineInputAssembler}는 전략 규칙·비용 등록표를 DB 에서 읽으므로({@code StrategyRulesService}·{@code
     * CostProfileService} 모두 {@code @Transactional}) 커넥션 풀 고갈·질의 타임아웃·DB 재기동이면 {@code
     * DataAccessException}·{@code TransactionException} 계열이 그대로 올라온다. 그것을 영구로 판정하면 DB 가 2초 흔들리는 동안
     * 접수된 실행이 전부 사용자 실패가 된다. 그 계열은 허용 목록에 없으므로 자연히 일시로 남는다 — 몇 줄 위 {@code findById}가 같은 예외를 일시로 다루는
     * 것과 어긋나지 않는다.
     *
     * <p>목록에 담은 것은 조립이 <b>스스로 판단해 거부한</b> 실패뿐이다.
     *
     * <ul>
     *   <li>{@code BusinessException} — 등록표에 규칙이 없거나(422 {@code RULES_NOT_REGISTERED}) 비용 버전이
     *       틀렸거나(400 {@code INVALID_INPUT}) 서버 기본 비용 행이 없다(500). 저장된 실행의 값은 바뀌지 않으므로 다시 읽어도 같다
     *   <li>{@code IllegalArgumentException}·{@code ClassCastException} — 저장된 요청 사본의 모양이 계약과 다르다
     *       ({@code Scenario.valueOf}, {@code new BigDecimal}, {@code (String) spec.get(...)}). 같은
     *       행을 다시 읽으면 같은 값이 나온다
     * </ul>
     *
     * <p>{@code IllegalStateException}·{@code NullPointerException}은 일부러 뺐다. 조립도 던지지만 Spring 기반 코드가
     * 자원 상태 때문에 던지기도 해서, 닫아도 되는지 여기서 가릴 수 없다. 잘못 닫는 쪽이 잘못 재시도하는 쪽보다 훨씬 나쁘다.
     */
    private static boolean isPermanentAssemblyFailure(RuntimeException cause) {
        return cause instanceof BusinessException
                || cause instanceof IllegalArgumentException
                || cause instanceof ClassCastException;
    }

    /**
     * 조립이 영구히 실패한 실행을 {@code FAILED(SYSTEM_FAILURE)}로 닫는다.
     *
     * <p>{@code published_at}은 NULL로 남지만 상태가 {@code QUEUED}가 아니게 되므로 재발행 대상({@code status=QUEUED
     * AND published_at IS NULL})에서 빠진다.
     *
     * <p><b>상태를 덮어쓰지 않도록 잠그고 다시 읽는다.</b> 자세한 까닭은 클래스 주석에 적었다. 예외 메시지는 로그에만 남기고 사용자에게는 노출하지 않는다 — 내부
     * 구조가 그대로 드러난다.
     *
     * <p>여기서 나가는 예외도 삼킨다. 트랜잭션이 커밋되기 전에 터졌으면 실행은 {@code QUEUED}로 남아 다음 재발행 주기가 같은 판정을 다시 한다. 커밋된 뒤
     * 이벤트 수신자에서 터졌으면 실행은 이미 {@code FAILED}이고 수신자 쪽만 어긋난 것이다 — 그래서 로그가 둘을 단정하지 않는다.
     */
    private void closeAsAssemblyFailure(BacktestRun run, RuntimeException cause) {
        log.error(
                "작업 입력 조립이 영구히 실패했습니다. 실행을 실패로 닫습니다: runId={}, backtestId={}",
                run.getId(),
                run.getBacktestId().value(),
                cause);
        try {
            newTransaction.executeWithoutResult(status -> markAssemblyFailed(run.getId()));
        } catch (RuntimeException exc) {
            log.error("조립 실패를 닫는 도중 예외가 났습니다. 실행 상태를 다시 확인해야 합니다: runId={}", run.getId(), exc);
        }
    }

    /** 잠긴 행을 다시 읽어 아직 {@code QUEUED}일 때만 닫는다. 호출자가 새 트랜잭션을 연 뒤에 부른다. */
    private void markAssemblyFailed(Long runId) {
        BacktestRun locked = repository.findByIdForUpdate(runId).orElse(null);
        if (locked == null) {
            log.warn("실패로 닫을 실행을 찾지 못했습니다: runId={}", runId);
            return;
        }
        if (locked.getStatus() != RunStatus.QUEUED) {
            // 읽고 판단하고 쓰는 사이에 취소가 커밋됐거나 다른 전이가 들어온 경우다. 잠그고 다시 읽는
            // 것은 그것을 보기 위해서다. "워커가 없다"는 보장은 여기서 얻지 않는다 — 그것은 첫 발행
            // 경로에서만 성립하고, 경로 판정이 이미 걸러 냈다.
            log.warn(
                    "조립에 실패했지만 대기 상태가 아니어서 상태를 그대로 둡니다: runId={}, status={}",
                    runId,
                    locked.getStatus());
            return;
        }
        BacktestRun failed = locked.markFailed(ASSEMBLY_FAILURE_CODE, ASSEMBLY_FAILURE_MESSAGE);
        repository.update(failed.withLifecycle(failed.getLifecycle().finished(clock.instant())));
        // 이벤트를 빠뜨리면 DB 는 실패인데 묶음 진행은 그 후보를 끝내지 못해 화면이 계속 돈다.
        events.publishEvent(
                new BacktestFinished(
                        locked.getId(),
                        locked.getBacktestId().value(),
                        RunStatus.FAILED,
                        ASSEMBLY_FAILURE_CODE));
    }
}
