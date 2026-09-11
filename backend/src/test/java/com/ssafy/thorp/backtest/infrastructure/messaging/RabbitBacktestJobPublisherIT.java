package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.port.BacktestJobPublisher;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.service.EngineInputAssembler;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 브로커에 발행해 계약 §3의 메시지 속성과 본문을 확인한다.
 *
 * <p>컨텍스트는 메시징에 필요한 빈만 올린다. DB·웹 계층은 이 테스트의 관심사가 아니다. Docker가 없으면 건너뛴다.
 */
@SpringBootTest(classes = {BacktestRabbitConfig.class, RabbitBacktestJobPublisher.class})
@ImportAutoConfiguration(RabbitAutoConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class RabbitBacktestJobPublisherIT {

    @Container @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private final ObjectMapper mapper = BacktestRabbitConfig.messageObjectMapper();
    private final EngineInputAssembler assembler = new EngineInputAssembler();

    @Autowired private BacktestJobPublisher publisher;
    @Autowired private RabbitTemplate rabbitTemplate;

    private EngineInput sampleInput() {
        return assembler.assemble(
                BacktestRun.restore(
                        1L,
                        BacktestId.from("BT-20260907-000001"),
                        "MA_CROSSOVER",
                        "1.0.0",
                        Map.of("shortWindow", 20, "threshold", 0.05),
                        List.of(new InstrumentId("005930.XKRX")),
                        new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                        new BigDecimal("10000000"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        new BigDecimal("0.50"),
                        new InstrumentId("069500.XKRX"),
                        42,
                        RunStatus.QUEUED));
    }

    @Test
    @DisplayName("발행한 메시지가 backtest.jobs 큐에 계약 속성과 함께 도착한다")
    void publishesToJobsQueue() throws IOException {
        EngineInput input = sampleInput();

        publisher.publish(input);

        Message received = rabbitTemplate.receive(BacktestRabbitConfig.JOBS_QUEUE, 5000);
        assertThat(received).isNotNull();

        var properties = received.getMessageProperties();
        assertThat(properties.getMessageId()).isEqualTo("JOB-BT-20260907-000001");
        assertThat(properties.getCorrelationId()).isEqualTo("BT-20260907-000001");
        Integer schemaVersion =
                properties.getHeader(RabbitBacktestJobPublisher.SCHEMA_VERSION_HEADER);
        assertThat(schemaVersion).isEqualTo(RabbitBacktestJobPublisher.SCHEMA_VERSION);
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(properties.getContentType()).isEqualTo("application/json");

        EngineInput body = mapper.readValue(received.getBody(), EngineInput.class);
        assertThat(body).isEqualTo(input);
    }
}
