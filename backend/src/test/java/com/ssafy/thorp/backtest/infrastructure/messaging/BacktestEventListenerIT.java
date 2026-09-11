package com.ssafy.thorp.backtest.infrastructure.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * 계약 픽스처를 실제 브로커의 {@code backtest.events}에 발행하고 리스너가 소비해 DB에 반영하는지, 계약 오류가 DLQ로 가는지 확인한다.
 *
 * <p>워커 실행 Consumer가 아직 없어도 픽스처 발행으로 검증한다. 리스너 처리가 비동기이므로 {@code Awaitility}로 기다린다. 계약 오류는 리스너
 * 재시도(1s·2s 백오프) 뒤에 DLQ로 가므로 몇 초 걸린다. PostgreSQL·RabbitMQ 컨테이너를 모두 띄운다. Docker가 없으면 건너뛴다.
 */
@SpringBootTest(
        properties = {
            // 전체 컨텍스트라 메일 발송 빈이 만들어진다. 운영에서만 주입되는 값이므로 더미를 넣는다.
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
class BacktestEventListenerIT extends PostgresTestSupport {

    @Container @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private BacktestRunRepository jobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void purgeQueues() {
        amqpAdmin.initialize(); // 토폴로지가 아직 선언되지 않았어도 purge가 실패하지 않게 한 번 선언한다
        amqpAdmin.purgeQueue(BacktestRabbitConfig.EVENTS_QUEUE, false);
        amqpAdmin.purgeQueue(BacktestRabbitConfig.EVENTS_DEAD_LETTER_QUEUE, false);
    }

    private BacktestRun newJob() {
        return jobRepository.save(
                BacktestRun.create(
                        "MA_CROSSOVER",
                        "1.0.0",
                        Map.of("shortWindow", 20),
                        List.of(new InstrumentId("005930.XKRX")),
                        new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                        new BigDecimal("10000000.00"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        new InstrumentId("069500.XKRX"),
                        42));
    }

    /** 워커처럼 raw JSON을 그대로 발행한다. 컨버터를 거치면 문자열이 JSON 문자열로 한 번 더 감싸진다. */
    private void publish(String json) {
        rabbitTemplate.send(
                BacktestRabbitConfig.EXCHANGE,
                BacktestRabbitConfig.EVENTS_ROUTING_KEY,
                MessageBuilder.withBody(json.getBytes(UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
    }

    private void publish(String fixture, BacktestRun job) {
        publish(MessageFixtures.withRunId(fixture, job.getBacktestId().value()));
    }

    private RunStatus statusOf(BacktestRun job) {
        return jobRepository
                .findByBacktestId(job.getBacktestId().value())
                .orElseThrow()
                .getStatus();
    }

    private int messageCount(String queue) {
        return amqpAdmin.getQueueInfo(queue).getMessageCount();
    }

    private Integer count(String table, Long jobId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE run_id = ?", Integer.class, jobId);
    }

    @Test
    @DisplayName("STARTED → FINISHED를 발행하면 리스너가 소비해 Job이 COMPLETED가 되고 결과가 저장된다")
    void consumesStartedAndFinished() {
        BacktestRun job = newJob();

        publish(MessageFixtures.STARTED, job);
        await().atMost(TIMEOUT).until(() -> statusOf(job) == RunStatus.RUNNING);

        publish(MessageFixtures.FINISHED_COMPLETED, job);
        await().atMost(TIMEOUT).until(() -> statusOf(job) == RunStatus.COMPLETED);

        assertThat(count("backtest_result", job.getId())).isEqualTo(1);
        assertThat(count("backtest_trade", job.getId())).isEqualTo(3);
        assertThat(messageCount(BacktestRabbitConfig.EVENTS_QUEUE)).isZero();
        assertThat(messageCount(BacktestRabbitConfig.EVENTS_DEAD_LETTER_QUEUE)).isZero();
    }

    @Test
    @DisplayName("계약을 어긴 메시지는 재시도 뒤 backtest.events.dlq로 가고 Job은 그대로다")
    void contractViolationGoesToDeadLetterQueue() throws Exception {
        BacktestRun job = newJob();
        ObjectNode broken =
                (ObjectNode)
                        MessageFixtures.mapper()
                                .readTree(
                                        MessageFixtures.withRunId(
                                                MessageFixtures.FINISHED_COMPLETED,
                                                job.getBacktestId().value()));
        broken.put("schemaVersion", 99);

        publish(MessageFixtures.mapper().writeValueAsString(broken));

        await().atMost(TIMEOUT)
                .until(() -> messageCount(BacktestRabbitConfig.EVENTS_DEAD_LETTER_QUEUE) == 1);
        Message dead = rabbitTemplate.receive(BacktestRabbitConfig.EVENTS_DEAD_LETTER_QUEUE, 5000);
        assertThat(dead).isNotNull();
        assertThat(dead.getMessageProperties().getXDeathHeader()).isNotEmpty();
        assertThat(new String(dead.getBody(), UTF_8)).contains("\"schemaVersion\":99");
        assertThat(messageCount(BacktestRabbitConfig.EVENTS_QUEUE)).isZero();
        assertThat(statusOf(job)).isEqualTo(RunStatus.QUEUED);
        assertThat(count("backtest_result", job.getId())).isZero();
    }

    @Test
    @DisplayName("모르는 backtestId의 정상 메시지는 ACK되어 버려지고 DLQ로 가지 않는다")
    void unknownBacktestIdIsAckedNotDeadLettered() {
        publish(MessageFixtures.withRunId(MessageFixtures.STARTED, "BT-20991231-999999"));

        await().atMost(TIMEOUT).until(() -> messageCount(BacktestRabbitConfig.EVENTS_QUEUE) == 0);
        // 계약 오류였다면 재시도(약 3초) 뒤 DLQ에 나타난다. 그 시간 동안 DLQ가 비어 있어야 한다.
        await().during(Duration.ofSeconds(4))
                .atMost(TIMEOUT)
                .until(() -> messageCount(BacktestRabbitConfig.EVENTS_DEAD_LETTER_QUEUE) == 0);
    }

    @Test
    @DisplayName("이미 끝난 Job의 늦은 FINISHED는 무시된다(결과를 덮어쓰지 않음)")
    void lateFinishedIsIgnored() {
        BacktestRun job = newJob();
        publish(MessageFixtures.FINISHED_COMPLETED, job);
        await().atMost(TIMEOUT).until(() -> statusOf(job) == RunStatus.COMPLETED);

        publish(MessageFixtures.FINISHED_FAILED, job);

        await().atMost(TIMEOUT).until(() -> messageCount(BacktestRabbitConfig.EVENTS_QUEUE) == 0);
        await().during(Duration.ofSeconds(2))
                .atMost(TIMEOUT)
                .until(() -> statusOf(job) == RunStatus.COMPLETED);
        assertThat(count("backtest_result", job.getId())).isEqualTo(1);
        assertThat(
                        jdbcTemplate.queryForObject(
                                "SELECT error_code FROM backtest_result WHERE run_id = ?",
                                String.class,
                                job.getId()))
                .isNull();
        assertThat(messageCount(BacktestRabbitConfig.EVENTS_DEAD_LETTER_QUEUE)).isZero();
    }
}
