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
        service.handleDeadLetter(runId);
    }
}
