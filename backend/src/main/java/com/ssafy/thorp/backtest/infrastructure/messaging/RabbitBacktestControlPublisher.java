package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.ssafy.thorp.backtest.application.exception.BacktestPublishFailedException;
import com.ssafy.thorp.backtest.application.port.BacktestControlPublisher;
import com.ssafy.thorp.backtest.application.port.message.ControlMessage;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * {@code CANCEL{runId}}를 fanout {@code backtest.control}로 발행한다(계약 §4.4, 픽스처 {@code
 * control-cancel.json}).
 *
 * <p>fanout 이라 routing key 는 비어 있다. 제어 메시지는 살아 있는 워커에게만 뜻이 있어 비영속으로 보낸다 — 브로커가 재시작하면 워커의 exclusive
 * 큐도 함께 사라지므로 남겨 둘 곳이 없다. 발행 실패는 {@link BacktestPublishFailedException}(503)으로 올려 취소 트랜잭션을 되돌린다:
 * 요청만 취소로 남고 워커는 계속 돌면 유예 시간 뒤 강제 종결(T014)까지 아무도 모른다.
 */
@Component
public class RabbitBacktestControlPublisher implements BacktestControlPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitBacktestControlPublisher.class);

    static final String SCHEMA_VERSION_HEADER = RabbitBacktestJobPublisher.SCHEMA_VERSION_HEADER;

    private final RabbitTemplate rabbitTemplate;
    private final Clock clock;

    public RabbitBacktestControlPublisher(RabbitTemplate rabbitTemplate, Clock clock) {
        this.rabbitTemplate = rabbitTemplate;
        this.clock = clock;
    }

    @Override
    public void cancel(String runId) {
        ControlMessage message =
                new ControlMessage(
                        BacktestRabbitConfig.SCHEMA_VERSION,
                        ControlMessage.ControlType.CANCEL,
                        runId,
                        clock.instant());
        try {
            rabbitTemplate.convertAndSend(
                    BacktestRabbitConfig.CONTROL_EXCHANGE,
                    "",
                    message,
                    amqp -> {
                        amqp.getMessageProperties().setCorrelationId(runId);
                        amqp.getMessageProperties()
                                .setHeader(
                                        SCHEMA_VERSION_HEADER, BacktestRabbitConfig.SCHEMA_VERSION);
                        amqp.getMessageProperties()
                                .setDeliveryMode(MessageDeliveryMode.NON_PERSISTENT);
                        return amqp;
                    });
        } catch (AmqpException e) {
            log.warn("Failed to publish CANCEL: runId={}", runId, e);
            throw new BacktestPublishFailedException(runId, e);
        }
    }
}
