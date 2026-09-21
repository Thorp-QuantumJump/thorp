package com.ssafy.thorp.regimelink.infrastructure.messaging;

import com.ssafy.thorp.regimelink.application.service.RegimeLinkEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code regime-link.jobs.dlq}의 Consumer. 작업이 재전달 상한을 다 쓰고 죽었다는 신호다 (계약 "토폴로지").
 *
 * <p><b>본문을 읽지 않는다.</b> 발행기가 {@code correlation_id}에 linkId 를 넣어 두고 DLQ 로 옮겨져도 그 속성은 보존되므로, 소화되지 못한
 * 본문을 다시 역직렬화할 이유가 없다.
 *
 * <p><b>다시 큐에 넣지 않는다.</b> 자동 재투입하면 같은 실패를 무한히 돈다 — 재시도는 사용자가 {@code POST
 * /regime-links/{linkId}/retry}로 정한다.
 */
@Component
public class RegimeLinkDeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(RegimeLinkDeadLetterListener.class);

    private final RegimeLinkEventHandler handler;

    public RegimeLinkDeadLetterListener(RegimeLinkEventHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = RegimeLinkRabbitConfig.JOBS_DEAD_LETTER_QUEUE)
    public void onDeadLetteredJob(Message message) {
        String linkId = message.getMessageProperties().getCorrelationId();
        if (linkId == null || linkId.isBlank()) {
            log.error(
                    "dead-lettered regime link job without a correlationId; dropping: messageId={}",
                    message.getMessageProperties().getMessageId());
            return;
        }
        handler.handleDeadLetter(linkId);
    }
}
