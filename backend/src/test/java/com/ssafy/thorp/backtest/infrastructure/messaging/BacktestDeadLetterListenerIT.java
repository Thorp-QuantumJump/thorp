package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import com.ssafy.thorp.support.RabbitTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
class BacktestDeadLetterListenerIT extends RabbitTestSupport {

    private static final String RUN_ID = "BT-20260914-000077";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL = Duration.ofMillis(100);

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @MockitoBean private BacktestEventService eventService;

    /** 브로커를 다른 클래스와 함께 쓴다. 작업 큐에 남은 메시지가 있으면 아래 거절이 엉뚱한 메시지를 집는다. */
    @BeforeEach
    void purgeQueues() {
        amqpAdmin.initialize(); // 토폴로지가 아직 선언되지 않았어도 purge가 실패하지 않게 한 번 선언한다
        amqpAdmin.purgeQueue(BacktestRabbitConfig.JOBS_QUEUE, false);
        amqpAdmin.purgeQueue(BacktestRabbitConfig.JOBS_DEAD_LETTER_QUEUE, false);
    }

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
