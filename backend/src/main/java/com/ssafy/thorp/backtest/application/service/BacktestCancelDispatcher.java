package com.ssafy.thorp.backtest.application.service;

import com.ssafy.thorp.backtest.application.port.BacktestControlPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * CANCEL 을 <b>취소 트랜잭션이 커밋된 뒤에</b> 보낸다(R-04). 작업 발행({@code BacktestJobDispatcher})과 같은 이유다.
 *
 * <p>트랜잭션 안에서 보내면 발행은 됐는데 커밋이 실패할 수 있다 — 취소 기록은 사라졌는데 워커는 이미 멈춰 부분 결과를 보내고, 아무도 취소하지 않은 실행이
 * CANCELLED 로 끝난다. 반대로 커밋 뒤 발행이 실패하면 취소 의도({@code run.cancel_requested_at})는 남아 있으므로 유예 감시(T014)가
 * 다시 보낼 수 있다. 그래서 여기서는 실패를 삼키고 로그만 남긴다 — 커밋은 끝났고 사용자는 202 를 받은 뒤다.
 */
@Component
public class BacktestCancelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(BacktestCancelDispatcher.class);

    private final BacktestControlPublisher publisher;

    public BacktestCancelDispatcher(
            BacktestControlPublisher publisher,
            org.springframework.context.ApplicationEventPublisher events) {
        this.publisher = publisher;
        this.events = events;
    }

    /** 취소 의도가 커밋됐다는 신호. 롤백되면 발행도 없다. */
    public record CancelRequested(String runId) {}

    private final org.springframework.context.ApplicationEventPublisher events;

    /**
     * 커밋 뒤에 CANCEL 을 보내 달라고 예약한다. 사용자 취소(R-04)와 유예 감시(R-19)가 같은 경로를 쓰도록 한 곳에 둔다 — 트랜잭션 안에서 보내면 발행은
     * 됐는데 커밋이 실패해 아무도 취소하지 않은 실행이 멈춘다.
     */
    public void publishAfterCommit(String runId) {
        events.publishEvent(new CancelRequested(runId));
    }

    @TransactionalEventListener
    public void onCancelRequested(CancelRequested event) {
        try {
            publisher.cancel(event.runId());
        } catch (RuntimeException exc) {
            log.error(
                    "커밋 후 CANCEL 발행에 실패했습니다. 취소 의도(cancel_requested_at)는 남아 있으니 유예 감시가 다시 보냅니다:"
                            + " runId={}",
                    event.runId(),
                    exc);
        }
    }
}
