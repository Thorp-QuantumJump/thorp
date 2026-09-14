package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 작업 메시지가 재전달 상한을 다 쓰면 DLQ 로 가고, 그것을 이 리스너가 runId 와 함께 집어 온다(spec 004 R-18, T010).
 *
 * <p>브로커 쪽 약속(quorum {@code x-delivery-limit=1} → 두 번째 nack 에서 DLX 로 이동, 그때도 {@code correlationId}
 * 가 보존됨)과 리스너의 runId 추출까지 실제 브로커로 확인한다. 종결 판정(모든 메시지가 DLQ 로 갔는가)은 DB 몫이라 {@code
 * BacktestEventServiceTest} 가 본다 — 여기서는 서비스를 목으로 받아 "무엇을 넘겼는가"만 본다.
 *
 * <p>컨텍스트는 메시징 빈만 올린다. Docker 가 없으면 건너뛴다.
 */
@SpringBootTest(classes = {BacktestRabbitConfig.class, BacktestDeadLetterListener.class})
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class BacktestDeadLetterListenerIT {

    private static final String RUN_ID = "BT-20260914-000077";

    @Container @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired private RabbitTemplate rabbitTemplate;
    @MockitoBean private BacktestEventService eventService;

    /** 워커가 집었다가 죽은 것과 같은 모양 — 받아서 requeue 로 거절한다. */
    private boolean nackOnce() {
        return Boolean.TRUE.equals(
                rabbitTemplate.execute(
                        channel -> {
                            var response = channel.basicGet(BacktestRabbitConfig.JOBS_QUEUE, false);
                            if (response == null) {
                                return false;
                            }
                            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, true);
                            return true;
                        }));
    }

    @Test
    @DisplayName("재전달 상한을 넘긴 작업 메시지는 DLQ 로 가고 리스너가 correlationId 의 runId 로 처리한다")
    void deadLetteredJobReachesTheListenerWithItsRunId() {
        byte[] body = ("{\"run\":{\"runId\":\"" + RUN_ID + "\"}}").getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId(RUN_ID);
        properties.setMessageId("JOB-" + RUN_ID);
        rabbitTemplate.send(
                BacktestRabbitConfig.EXCHANGE,
                BacktestRabbitConfig.JOBS_ROUTING_KEY,
                new Message(body, properties));

        // 첫 전달은 상한 안이라 되돌아온다. 두 번째 거절에서 브로커가 DLX 로 옮긴다.
        assertThat(nackOnce()).isTrue();
        assertThat(nackOnce()).isTrue();

        verify(eventService, timeout(10_000)).handleDeadLetter(RUN_ID);
        // 자동 재투입은 없다 — 작업 큐로 돌아오지 않는다.
        assertThat(nackOnce()).isFalse();
    }
}
