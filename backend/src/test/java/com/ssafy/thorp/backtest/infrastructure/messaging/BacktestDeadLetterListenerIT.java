package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.test.annotation.DirtiesContext;
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
// 클래스가 끝나면 @Container 브로커가 내려가는데, 캐시에 남은 컨텍스트의 리스너는 테스트가 모두 끝날 때까지 5초마다 재접속을 시도한다.
// 이 설정을 쓰는 테스트는 이 클래스뿐이라 닫아도 컨텍스트를 다시 만드는 일은 없다.
@DirtiesContext
class BacktestDeadLetterListenerIT {

    private static final String RUN_ID = "BT-20260914-000077";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(100);

    @Container @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired private RabbitTemplate rabbitTemplate;
    @MockitoBean private BacktestEventService eventService;

    /** 지금 작업 큐에 있는 메시지 하나를 받아 requeue 로 거절한다. 없으면 false. */
    private boolean nackNow() {
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

    /**
     * 워커가 집었다가 죽은 것과 같은 모양. <b>도착을 기다렸다가</b> 거절한다.
     *
     * <p>발행은 비동기라 {@code basicGet} 을 바로 부르면 아직 큐에 없을 수 있다. 되돌아오는 재전달도 마찬가지다 — 기다리지 않으면 전체 스위트에서 부하에
     * 따라 통과했다 실패했다 한다(실제로 그랬다).
     */
    private void nackWhenDelivered() {
        await().atMost(TIMEOUT).pollInterval(POLL).until(this::nackNow);
    }

    @Test
    @DisplayName("재전달 상한을 넘긴 작업 메시지는 DLQ 로 가고 리스너가 runId·발행 시도 번호로 처리한다")
    void deadLetteredJobReachesTheListenerWithItsRunId() {
        byte[] body = ("{\"run\":{\"runId\":\"" + RUN_ID + "\"}}").getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId(RUN_ID);
        properties.setMessageId("JOB-" + RUN_ID);
        properties.setHeader(RabbitBacktestJobPublisher.PUBLISH_ATTEMPT_HEADER, 2);
        rabbitTemplate.send(
                BacktestRabbitConfig.EXCHANGE,
                BacktestRabbitConfig.JOBS_ROUTING_KEY,
                new Message(body, properties));

        // 첫 전달은 상한 안이라 되돌아온다. 두 번째 거절에서 브로커가 DLX 로 옮긴다.
        nackWhenDelivered();
        nackWhenDelivered();

        verify(eventService, timeout(TIMEOUT.toMillis())).handleDeadLetter(RUN_ID, 2);
        // 자동 재투입은 없다 — 위 verify 가 돌아온 시점에 메시지는 이미 DLQ 로 갔다.
        assertThat(nackNow()).isFalse();
    }
}
