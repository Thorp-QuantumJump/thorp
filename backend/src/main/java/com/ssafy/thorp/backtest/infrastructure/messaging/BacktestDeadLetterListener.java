package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code backtest.jobs.dlq} 의 Consumer(spec 004 R-18, T010). 작업 메시지가 재전달 상한을 다 쓰고 죽었다는 신호다.
 *
 * <p><b>본문을 읽지 않는다.</b> 발행기가 {@code correlationId} 에 runId 를 넣어 두고 DLQ 로 옮겨져도 그 속성이 보존되므로, 계약 모델을
 * 다시 역직렬화할 이유가 없다 — 여기까지 온 메시지는 워커가 소화하지 못한 것이라 본문이 온전하다는 보장도 없다.
 *
 * <p><b>DLQ 로 온 메시지는 다시 큐에 넣지 않는다.</b> 자동 재투입하면 같은 실패를 무한히 돈다. 재시도는 사용자가 {@code POST
 * /backtests/{id}/retry} 로 정한다.
 *
 * <p><b>재전달된 delivery 는 세지 않는다.</b> 이 전달은 at-least-once 다 — DB 가 커밋된 뒤 ACK 이 유실되면 브로커가 같은 메시지를 다시
 * 준다. 그것을 또 세면 {@code dlq_count} 가 실제 죽은 메시지 수보다 커지고, 살아 있는 작업이 하나 남았는데도 {@code dlqCount >=
 * publishCount} 가 서서 실행이 잘못 {@code FAILED} 로 끝난다. 세지 않아 생기는 반대 오차(커밋 전에 죽어 재전달된 경우 한 번을 놓침)는 실행이
 * 종결되지 않고 남는 쪽이라 살아 있는 실행을 끊는 것보다 안전하다 — 그 실행은 유예 감시(T014 {@code RunLivenessSweeper})가 거둔다.
 *
 * <p>runId 를 알 수 없으면 로그만 남기고 ACK 한다. 되돌려 봐야 같은 자리로 다시 오고, 그 사이 DLQ 가 막힌다.
 */
@Component
public class BacktestDeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(BacktestDeadLetterListener.class);

    private final BacktestEventService service;

    public BacktestDeadLetterListener(BacktestEventService service) {
        this.service = service;
    }

    @RabbitListener(queues = BacktestRabbitConfig.JOBS_DEAD_LETTER_QUEUE)
    public void onDeadLetteredJob(Message message) {
        String runId = message.getMessageProperties().getCorrelationId();
        if (runId == null || runId.isBlank()) {
            log.error(
                    "dead-lettered job without a correlationId; dropping: messageId={}",
                    message.getMessageProperties().getMessageId());
            return;
        }
        if (Boolean.TRUE.equals(message.getMessageProperties().getRedelivered())) {
            // 이미 세었을 수 있다. 두 번 세면 살아 있는 실행을 끊으므로 세지 않는다(위 주석).
            log.warn("redelivered dead-letter not counted again: runId={}", runId);
            return;
        }
        service.handleDeadLetter(runId);
    }
}
