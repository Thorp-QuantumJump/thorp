package com.ssafy.thorp.regimelink.infrastructure.messaging;

import com.ssafy.thorp.regimelink.application.exception.RegimeLinkPublishFailedException;
import com.ssafy.thorp.regimelink.application.port.RegimeLinkJobPublisher;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.stereotype.Component;

/**
 * 작업 메시지를 {@code regime-link.jobs}로 발행한다 (계약 "메시지 속성과 토폴로지", T030).
 *
 * <p>{@code message_id = "RLJ-" + linkId + "-" + attempt}, {@code correlation_id = linkId}, header
 * {@code schemaVersion}. 재시도(T069)가 {@code attempt}를 올리므로 같은 링크의 두 발행은 다른 {@code message_id}를 갖는다 —
 * DLQ 소비가 어느 시도의 실패인지 가릴 근거다.
 */
@Component
public class RabbitRegimeLinkJobPublisher implements RegimeLinkJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitRegimeLinkJobPublisher.class);

    static final String MESSAGE_ID_PREFIX = "RLJ-";
    static final String SCHEMA_VERSION_HEADER = "schemaVersion";

    private final RabbitTemplate rabbitTemplate;

    /**
     * 템플릿을 <b>빈으로 두지 않고</b> 여기서 만든다.
     *
     * <p>Boot 자동설정의 {@code RabbitTemplate}은 {@code @ConditionalOnMissingBean(RabbitOperations)}이라,
     * 빈을 하나 더 선언하면 자동설정 쪽이 사라지고 백테스트 발행기가 그 템플릿을 잃는다. 사례 연결은 표기(ObjectMapper)가 달라 공용 템플릿을 쓸 수 없으므로
     * 자기 템플릿을 자기 안에서 만든다.
     */
    public RabbitRegimeLinkJobPublisher(ConnectionFactory connectionFactory) {
        this.rabbitTemplate = new RabbitTemplate(connectionFactory);
        this.rabbitTemplate.setMessageConverter(
                new Jackson2JsonMessageConverter(RegimeLinkMessageJson.objectMapper()));
    }

    /** 이 메시지의 {@code message_id}. 발행 전에 링크 행에 적어 두므로 계산 규칙을 한 곳에 둔다. */
    public static String messageId(String linkId, int attempt) {
        return MESSAGE_ID_PREFIX + linkId + "-" + attempt;
    }

    @Override
    public void publish(RegimeLinkJob job) {
        String linkId = job.job().linkId();
        int attempt = job.job().attempt();
        try {
            rabbitTemplate.convertAndSend(
                    RegimeLinkRabbitConfig.EXCHANGE,
                    RegimeLinkRabbitConfig.JOBS_ROUTING_KEY,
                    job,
                    message -> {
                        message.getMessageProperties().setMessageId(messageId(linkId, attempt));
                        message.getMessageProperties().setCorrelationId(linkId);
                        message.getMessageProperties()
                                .setHeader(
                                        SCHEMA_VERSION_HEADER,
                                        RegimeLinkRabbitConfig.SCHEMA_VERSION);
                        message.getMessageProperties()
                                .setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return message;
                    });
        } catch (AmqpException e) {
            log.warn("Failed to publish regime link job: linkId={} attempt={}", linkId, attempt, e);
            throw new RegimeLinkPublishFailedException(linkId, e);
        }
    }
}
