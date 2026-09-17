package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.port.BacktestControlPublisher;
import com.ssafy.thorp.backtest.application.port.message.ControlMessage;
import com.ssafy.thorp.support.RabbitTestSupport;
import java.io.IOException;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * CANCEL 이 fanout {@code backtest.control}로 나가는지 실제 브로커로 확인한다(계약 §4.4).
 *
 * <p>워커처럼 exclusive·auto-delete 큐를 fanout 에 묶어 받는다. 컨텍스트는 메시징 빈만 올린다(DB·웹은 관심사가 아니다).
 */
@SpringBootTest(
        classes = {
            BacktestRabbitConfig.class,
            RabbitBacktestControlPublisher.class,
            RabbitBacktestControlPublisherIT.ClockConfig.class
        })
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
class RabbitBacktestControlPublisherIT extends RabbitTestSupport {

    /** 발행기가 requestedAt 에 쓰는 시계. 메시징 슬라이스에는 앱의 Clock 빈이 없다. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private final ObjectMapper mapper = BacktestRabbitConfig.messageObjectMapper();

    @Autowired private BacktestControlPublisher publisher;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin admin;

    @Test
    @DisplayName("CANCEL 은 backtest.control fanout 으로 나가고 워커식 exclusive 큐가 계약 모양 그대로 받는다")
    void cancelReachesAFanoutSubscriber() throws IOException {
        Queue probe = new Queue("", false, true, true);
        String queue = admin.declareQueue(probe);
        Binding binding =
                BindingBuilder.bind(new Queue(queue))
                        .to(new FanoutExchange(BacktestRabbitConfig.CONTROL_EXCHANGE));
        admin.declareBinding(binding);

        publisher.cancel("BT-20260907-000001");

        Message received = rabbitTemplate.receive(queue, 5000);
        assertThat(received).isNotNull();
        ControlMessage body = mapper.readValue(received.getBody(), ControlMessage.class);
        assertThat(body.type()).isEqualTo(ControlMessage.ControlType.CANCEL);
        assertThat(body.runId()).isEqualTo("BT-20260907-000001");
        assertThat(body.schemaVersion()).isEqualTo(BacktestRabbitConfig.SCHEMA_VERSION);
        assertThat(body.requestedAt()).isNotNull();
        var properties = received.getMessageProperties();
        assertThat(properties.getCorrelationId()).isEqualTo("BT-20260907-000001");
        Integer schemaVersion =
                properties.getHeader(RabbitBacktestControlPublisher.SCHEMA_VERSION_HEADER);
        assertThat(schemaVersion).isEqualTo(BacktestRabbitConfig.SCHEMA_VERSION);
        assertThat(properties.getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.NON_PERSISTENT);
    }
}
