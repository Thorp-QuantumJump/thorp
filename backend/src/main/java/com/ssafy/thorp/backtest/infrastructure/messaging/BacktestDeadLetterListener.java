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
 * <p><b>발행 시도 번호로 멱등하게 센다.</b> 이 전달은 at-least-once 라 DB 가 커밋된 뒤 ACK 이 유실되면 브로커가 같은 메시지를 다시 준다. 그것을
 * 또 세면 살아 있는 작업이 남았는데도 {@code dlqCount >= publishCount} 가 서서 실행이 잘못 {@code FAILED} 로 끝난다. 발행기가 실어
 * 보낸 {@code publishAttempt} 를 그대로 넘기면 저장소가 {@code (runId, attempt)} 기록으로 걸러 준다 — <b>AMQP 의 {@code
 * redelivered} 표시로 거르지 않는다.</b> 그 표시는 "이미 세었다"가 아니라 "이미 보낸 적 있다"라서, 커밋 전에 죽어 다시 온 첫 전달까지 버리게 되고 그러면
 * 죽은 실행이 영영 종결되지 않는다.
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
        Integer attempt = publishAttemptOf(message);
        if (attempt == null) {
            // 시도 번호가 없으면 재전달을 구분할 수 없다. 세면 살아 있는 실행을 끊을 수 있으므로 세지 않고
            // 드러나게 남긴다 — 이 헤더를 붙이기 전 버전이 발행한 메시지에서만 생길 수 있다.
            log.error(
                    "dead-lettered job without a {} header; not counted: runId={}",
                    RabbitBacktestJobPublisher.PUBLISH_ATTEMPT_HEADER,
                    runId);
            return;
        }
        service.handleDeadLetter(runId, attempt);
    }

    /** 헤더는 브로커를 거치며 정수형이 바뀔 수 있어 {@link Number} 로 받는다. */
    private static Integer publishAttemptOf(Message message) {
        Object raw =
                message.getMessageProperties()
                        .getHeader(RabbitBacktestJobPublisher.PUBLISH_ATTEMPT_HEADER);
        if (!(raw instanceof Number number) || number.intValue() < 1) {
            return null;
        }
        return number.intValue();
    }
}
