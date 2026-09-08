package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.ssafy.thorp.backtest.application.exception.BacktestPublishFailedException;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 작업 메시지를 {@code backtest.jobs}로 발행한다. 메시지 속성은 계약 {@code docs/specs/backtest-messaging/README.md}
 * §3을 따른다.
 *
 * <p>{@code message_id}는 {@code JOB-{backtestId}}다. 재시도할 때 유지되는 발행 측 고유 ID여야 하므로 저장 구조가 아니라
 * backtestId에서 결정적으로 만든다(v1은 Job당 작업 메시지가 하나).
 *
 * <p>본문 직렬화는 {@code backtestMessageConverter}가 담당하고 Boot 자동설정이 {@link RabbitTemplate}에 넣어준다.
 */
@Component
public class RabbitBacktestJobPublisher implements BacktestJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitBacktestJobPublisher.class);

    static final String MESSAGE_ID_PREFIX = "JOB-";
    static final String SCHEMA_VERSION_HEADER = "schemaVersion";
    static final int SCHEMA_VERSION = 1;

    private final RabbitTemplate rabbitTemplate;

    public RabbitBacktestJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(EngineInput engineInput) {
        String backtestId = engineInput.run().backtestId();
        try {
            rabbitTemplate.convertAndSend(
                    BacktestRabbitConfig.EXCHANGE,
                    BacktestRabbitConfig.JOBS_ROUTING_KEY,
                    engineInput,
                    message -> {
                        message.getMessageProperties().setMessageId(MESSAGE_ID_PREFIX + backtestId);
                        message.getMessageProperties().setCorrelationId(backtestId);
                        message.getMessageProperties()
                                .setHeader(SCHEMA_VERSION_HEADER, SCHEMA_VERSION);
                        message.getMessageProperties()
                                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    });
        } catch (AmqpException e) {
            log.warn("Failed to publish backtest job: backtestId={}", backtestId, e);
            throw new BacktestPublishFailedException(backtestId, e);
        }
    }
}
