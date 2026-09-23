package com.ssafy.thorp.paramsearch.infrastructure.listener;

import com.ssafy.thorp.backtest.application.event.BacktestFinished;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.paramsearch.application.service.ParamSearchProgressService;
import java.util.ArrayDeque;
import java.util.Deque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 004의 실행 완료를 받아 묶음 진행을 전진시킨다(contracts/param-search-internal.md §3, research R6).
 *
 * <p><b>{@code backtest.events} 큐를 다시 소비하지 않는다.</b> 같은 큐에 컨슈머를 하나 더 붙이면 competing consumers라 메시지가
 * 분배되어 두 리스너가 각자 일부만 받는다. 그래서 인메모리 도메인 이벤트를 쓴다.
 *
 * <p>{@code AFTER_COMMIT}인 이유: 커밋 전에 받으면 아직 저장되지 않은 결과를 읽는다. 후보가 "완료"인데 성과가 비어 있는 상태가 되고, 원인이 007
 * 코드에 없어서 찾기 어렵다.
 *
 * <p>묶음에 속하지 않은 Job이 대부분이다(단일 실행). 못 찾으면 <b>경고 없이</b> 무시한다 — 경고를 남기면 로그가 그것으로 덮인다.
 */
@Component
@ConditionalOnProperty(prefix = "thorp.param-search", name = "enabled", havingValue = "true")
public class BacktestFinishedListener {

    private static final Logger log = LoggerFactory.getLogger(BacktestFinishedListener.class);

    /**
     * 처리 중에 새로 도착한 완료 이벤트를 모아 두는 자리. <b>이 스레드가 이미 이벤트를 처리하고 있는가</b>의 표시도 겸한다(비어 있지 않음이 아니라 존재 자체가
     * 표시다).
     *
     * <p>스레드마다 따로 두는 이유: 이벤트는 요청 스레드(재사용 접수)와 RabbitMQ 컨슈머 스레드(워커 결과) 양쪽에서 오고, 서로의 처리 순서를 알 필요가 없다.
     */
    private static final ThreadLocal<Deque<BacktestFinished>> PENDING = new ThreadLocal<>();

    private final ParamSearchProgressService progressService;
    private final TaskExecutor progressExecutor;

    public BacktestFinishedListener(
            ParamSearchProgressService progressService,
            // 이름으로 고른다 — 스프링 부트가 기본으로 두는 applicationTaskExecutor 와 섞이지 않게 한다.
            @Qualifier("paramSearchProgressExecutor") TaskExecutor progressExecutor) {
        this.progressService = progressService;
        this.progressExecutor = progressExecutor;
    }

    /**
     * <b>COMPLETED 가 아닌 종결은 모두 실패로 넘긴다.</b> FAILED 만 실패로 보던 때는, 실행 시간 상한으로 중간까지만 계산하고 끊긴
     * 후보(CANCELLED + 부분 결과)가 정상 완료로 집계되어 <b>선정 대상</b>에 들어갔다. 끝까지 계산되지 않은 성과를 다른 후보와 나란히 비교하면 선정 자체가
     * 거짓이 된다(R-19).
     *
     * <p>사유가 없는 CANCELLED 는 사용자가 스스로 멈춘 것이라 엔진 오류가 아니다 — {@code ENGINE_ERROR} 로 뭉개지 않도록 여기서 번역한다.
     * 모듈 경계를 넘는 번역은 이 리스너의 몫이고, {@code paramsearch} 는 {@code RunStatus} 를 알 필요가 없다.
     *
     * <p><b>이어지는 완료는 겹쳐 처리하지 않고 줄을 세운다.</b> 후보를 하나 전진시키면 다음 후보가 발행되는데, 그 조합이 이미 계산돼 있으면 004가 <b>그
     * 자리에서</b> 완료를 다시 알린다(재사용, spec 004 R-02). 그 알림을 곧바로 처리하면 후보 하나당 트랜잭션이 하나씩 <b>겹겹이</b> 열린다 — 바깥
     * 트랜잭션은 커밋됐어도 이 콜백이 끝날 때까지 커넥션을 쥐고 있으므로, 묶음 하나가 커넥션 풀을 통째로 말린다. 풀이 마르는 순간 마지막 후보는 결과를 반영받지 못한 채
     * {@code DISPATCHED} 로 남고, 그 후보를 깨울 이벤트는 다시 오지 않아 <b>묶음이 영영 끝나지 않는다</b>. 같은 설정 조합을 두 번째로
     * 실행하면(전부 재사용) 바로 걸린다.
     *
     * <p><b>사용자가 기다리는 스레드에서는 묶음을 돌리지 않는다.</b> 재사용 연쇄는 접수 요청 스레드에서 시작되므로 그대로 두면 묶음 전체가 {@code POST
     * /param-searches} 응답 안에서 끝난다 — 계약은 접수가 202로 바로 끝나고 진행은 화면의 3초 폴링으로 보이는 것이다(FR-008). 요청 스레드면
     * 일감을 진행 실행기에 넘기고 즉시 돌아온다.
     *
     * <p><b>워커 결과(RabbitMQ 컨슈머 스레드)는 그 자리에서 처리한다.</b> 기다리는 사람이 없고, 무엇보다 처리가 끝나야 메시지가 ACK 된다 — 넘기면
     * ACK 가 먼저 나가서 그 사이에 죽으면 재전달로도 복구되지 않는다(004 는 이미 끝난 실행의 FINISHED 를 다시 처리하지 않는다).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBacktestFinished(BacktestFinished event) {
        Deque<BacktestFinished> queue = PENDING.get();
        if (queue != null) {
            // 이 스레드가 이미 처리 중이다 — 바깥 고리가 자기 트랜잭션을 정리한 뒤 이어서 집어 간다.
            queue.addLast(event);
            return;
        }
        if (someoneIsWaiting()) {
            progressExecutor.execute(() -> drain(event));
            return;
        }
        drain(event);
    }

    /**
     * 응답을 기다리는 요청 스레드인가.
     *
     * <p>요청 속성은 기본적으로 자식 스레드에 물려주지 않으므로({@code threadContextInheritable} 기본값 false) 진행 실행기 스레드와
     * RabbitMQ 컨슈머 스레드에서는 비어 있다 — 그래서 이 판정이 곧 "누가 응답을 기다리는가"다.
     */
    private static boolean someoneIsWaiting() {
        return RequestContextHolder.getRequestAttributes() != null;
    }

    /** 이 완료와, 처리하는 동안 이어서 도착한 완료를 차례로 반영한다. */
    private void drain(BacktestFinished first) {
        PENDING.set(new ArrayDeque<>());
        try {
            BacktestFinished next = first;
            do {
                apply(next);
                next = PENDING.get().pollFirst();
            } while (next != null);
        } catch (RuntimeException e) {
            // 실행기에 넘긴 일감이면 아무도 이 예외를 보지 않는다. 남기지 않으면 묶음이 멈춘 이유가 어디에도 남지 않는다.
            log.error("param search progress failed: backtestJobId={}", first.backtestJobId(), e);
            throw e;
        } finally {
            // 중간에 실패해 남은 것이 있어도 다음 이벤트가 그것을 물려받지 않게 비우고 나간다.
            PENDING.remove();
        }
    }

    private void apply(BacktestFinished event) {
        boolean belongsToSearch =
                progressService.candidateFinished(
                        event.backtestJobId(),
                        event.status() != RunStatus.COMPLETED,
                        errorCodeOf(event));
        if (belongsToSearch) {
            log.debug(
                    "param search candidate finished: backtestJobId={} status={}",
                    event.backtestJobId(),
                    event.status());
        }
    }

    private static String errorCodeOf(BacktestFinished event) {
        if (event.errorCode() != null) {
            return event.errorCode();
        }
        return event.status() == RunStatus.CANCELLED ? "CANCELLED" : null;
    }
}
