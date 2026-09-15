package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.ssafy.thorp.backtest.application.exception.BacktestPublishFailedException;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 작업 메시지를 실행 유형에 맞는 작업 큐로 발행한다 — 단건은 {@code backtest.jobs}, 탐색·경제환경 파생은 {@code
 * backtest.jobs.bulk}(R-20). 메시지 속성은 계약 {@code docs/specs/backtest-messaging/README.md} §3을 따르며 큐가
 * 달라도 본문·헤더는 같다.
 *
 * <p>{@code message_id}는 {@code JOB-{runId}}다. 재시도할 때 유지되는 발행 측 고유 ID여야 하므로 저장 구조가 아니라 runId에서
 * 결정적으로 만든다(실행 하나에 작업 메시지 하나).
 *
 * <p>본문 직렬화는 {@code backtestMessageConverter}가 담당하고 Boot 자동설정이 {@link RabbitTemplate}에 넣어준다.
 */
@Component
public class RabbitBacktestJobPublisher implements BacktestJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitBacktestJobPublisher.class);

    static final String MESSAGE_ID_PREFIX = "JOB-";
    static final String SCHEMA_VERSION_HEADER = "schemaVersion";

    /** 이 메시지가 몇 번째 발행 시도인가. DLQ 소비가 같은 메시지를 두 번 세지 않는 근거다(R-18 ③). */
    static final String PUBLISH_ATTEMPT_HEADER = "publishAttempt";

    static final int SCHEMA_VERSION = BacktestRabbitConfig.SCHEMA_VERSION;

    private final RabbitTemplate rabbitTemplate;

    public RabbitBacktestJobPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(EngineInput engineInput, int publishAttempt, ExecutionType executionType) {
        String runId = engineInput.run().runId();
        try {
            rabbitTemplate.convertAndSend(
                    BacktestRabbitConfig.EXCHANGE,
                    routingKey(executionType),
                    engineInput,
                    message -> {
                        message.getMessageProperties().setMessageId(MESSAGE_ID_PREFIX + runId);
                        message.getMessageProperties().setCorrelationId(runId);
                        message.getMessageProperties()
                                .setHeader(SCHEMA_VERSION_HEADER, SCHEMA_VERSION);
                        message.getMessageProperties()
                                .setHeader(PUBLISH_ATTEMPT_HEADER, publishAttempt);
                        message.getMessageProperties()
                                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    });
        } catch (AmqpException e) {
            log.warn("Failed to publish backtest job: runId={}", runId, e);
            throw new BacktestPublishFailedException(runId, e);
        }
    }

    /** 단건만 단건 큐다. 새 실행 유형이 생기면 기본은 벌크 — 사람이 기다리는 큐에 배치가 섞이는 쪽이 더 나쁜 실수다. */
    static String routingKey(ExecutionType executionType) {
        return executionType == ExecutionType.SINGLE
                ? BacktestRabbitConfig.JOBS_ROUTING_KEY
                : BacktestRabbitConfig.BULK_JOBS_ROUTING_KEY;
    }
}
