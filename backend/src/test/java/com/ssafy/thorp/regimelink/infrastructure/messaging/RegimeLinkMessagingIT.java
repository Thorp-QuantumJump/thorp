package com.ssafy.thorp.regimelink.infrastructure.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.regimelink.application.port.RegimeLinkJobPublisher;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkJob;
import com.ssafy.thorp.regimelink.application.repository.RegimeLinkRepository;
import com.ssafy.thorp.regimelink.domain.model.Availability;
import com.ssafy.thorp.regimelink.domain.model.LinkFailure;
import com.ssafy.thorp.regimelink.domain.model.PostWindow;
import com.ssafy.thorp.regimelink.domain.model.RegimeLink;
import com.ssafy.thorp.regimelink.domain.vo.CaseRef;
import com.ssafy.thorp.regimelink.domain.vo.LinkStatus;
import com.ssafy.thorp.regimelink.domain.vo.Scenario;
import com.ssafy.thorp.support.PostgresTestSupport;
import com.ssafy.thorp.support.RabbitTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * 사례 연결 메시징을 실제 브로커·DB로 끝에서 끝까지 확인한다 (spec 011 T036).
 *
 * <p>계약 픽스처({@code docs/specs/regime-link-messaging/examples})로 작업을 발행해 큐 도착·속성을 보고, 이벤트 픽스처를 주입해
 * 링크 상태가 {@code QUEUED → RUNNING → COMPLETED | FAILED}로 바뀌는지, 끝난 링크의 늦은 이벤트를 무시하는지, DLQ 로 간 작업이
 * {@code FAILED(SYSTEM_FAILURE)}가 되는지 본다. 워커는 띄우지 않는다 — 워커 쪽은 워커 테스트가 같은 픽스처로 본다. Docker 가 없으면
 * 건너뛴다.
 */
@SpringBootTest(
        properties = {
            // 전체 컨텍스트라 메일 발송 빈이 만들어진다. 운영에서만 주입되는 값이므로 더미를 넣는다.
            "spring.mail.username=test@example.com",
            "spring.mail.password=test"
        })
@DirtiesContext
@DisplayName("사례 연결 메시징 통합 (T036)")
class RegimeLinkMessagingIT extends PostgresTestSupport {

    @ServiceConnection static final RabbitMQContainer RABBITMQ = RabbitTestSupport.RABBITMQ;

    @BeforeAll
    static void startRabbitOnce() {
        RabbitTestSupport.startContainerOnce();
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Path FIXTURES =
            Path.of("..", "docs", "specs", "regime-link-messaging", "examples");
    private static final long RUN_ID = 910_001L;
    private static final String SNAPSHOT_KEY =
            "FIELD:LABOR:2026-07-01:FIXTURE-20260915:RL-REG-1:RL-MODEL-1";

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private AmqpAdmin amqpAdmin;
    @Autowired private RegimeLinkRepository links;
    @Autowired private RegimeLinkJobPublisher publisher;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        amqpAdmin.initialize();
        amqpAdmin.purgeQueue(RegimeLinkRabbitConfig.JOBS_QUEUE, false);
        amqpAdmin.purgeQueue(RegimeLinkRabbitConfig.EVENTS_QUEUE, false);
        amqpAdmin.purgeQueue(RegimeLinkRabbitConfig.JOBS_DEAD_LETTER_QUEUE, false);
        clean();
        jdbc.update(
                "INSERT INTO backtest_run (id, backtest_id, strategy_id, strategy_version,"
                        + " parameters, instrument_ids, start_date, end_date, initial_cash,"
                        + " position_sizing_type, random_seed, status, request_spec,"
                        + " effective_start, data_version, cost_profile_version, created_at,"
                        + " updated_at)"
                        + " VALUES (?, 'BT-20260101-910001', 'FIXED_WEIGHT', '1.0.0', '{}'::jsonb,"
                        + " '[\"244580.XKRX\"]'::jsonb, DATE '2015-01-02', DATE '2026-12-31',"
                        + " 10000000.00, 'STRATEGY_DEFINED', 42, 'COMPLETED', '{}'::jsonb,"
                        + " DATE '2015-04-01', 'FIXTURE-20260915', 'PROVISIONAL-DEMO-1', NOW(),"
                        + " NOW())",
                RUN_ID);
    }

    @AfterEach
    void clean() {
        jdbc.update(
                "DELETE FROM regime_link_result WHERE link_id IN"
                        + " (SELECT id FROM regime_link WHERE run_id = ?)",
                RUN_ID);
        jdbc.update("DELETE FROM regime_link WHERE run_id = ?", RUN_ID);
        jdbc.update("DELETE FROM backtest_run WHERE id = ?", RUN_ID);
    }

    @Test
    @DisplayName("작업은 계약 속성을 달고 regime-link.jobs 에 도착한다")
    void jobArrivesWithContractProperties() throws Exception {
        RegimeLinkJob job =
                RegimeLinkMessageJson.objectMapper()
                        .readValue(fixture("job-continuous.json"), RegimeLinkJob.class);

        publisher.publish(job);

        Message arrived = rabbitTemplate.receive(RegimeLinkRabbitConfig.JOBS_QUEUE, 10_000);
        assertThat(arrived).isNotNull();
        MessageProperties properties = arrived.getMessageProperties();
        String linkId = job.job().linkId();
        assertThat(properties.getMessageId())
                .isEqualTo("RLJ-" + linkId + "-" + job.job().attempt());
        assertThat(properties.getCorrelationId()).isEqualTo(linkId);
        assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(properties.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(((Number) properties.getHeader("schemaVersion")).intValue())
                .isEqualTo(RegimeLinkRabbitConfig.SCHEMA_VERSION);
        // 본문을 다시 읽으면 같은 작업이다(워커가 받는 것이 계약 픽스처와 같은 모양).
        RegimeLinkJob roundTrip =
                RegimeLinkMessageJson.objectMapper()
                        .readValue(new String(arrived.getBody(), UTF_8), RegimeLinkJob.class);
        assertThat(roundTrip).isEqualTo(job);
    }

    @Test
    @DisplayName(
            "STARTED → FINISHED(COMPLETED) 로 RUNNING → COMPLETED 가 되고 늦은 FINISHED(FAILED)는 무시된다")
    void eventsMoveTheLinkAndLateEventsAreIgnored() throws Exception {
        RegimeLink link = queuedLink();

        publishEvent("event-started.json", link);
        await().atMost(TIMEOUT).until(() -> statusOf(link) == LinkStatus.RUNNING);

        publishEvent("event-finished-completed.json", link);
        await().atMost(TIMEOUT).until(() -> statusOf(link) == LinkStatus.COMPLETED);
        assertThat(resultCount(link)).isEqualTo(1);

        publishEvent("event-finished-failed.json", link);
        await().atMost(TIMEOUT).until(() -> messageCount(RegimeLinkRabbitConfig.EVENTS_QUEUE) == 0);
        await().during(Duration.ofSeconds(2))
                .atMost(TIMEOUT)
                .until(() -> statusOf(link) == LinkStatus.COMPLETED);
        assertThat(resultCount(link)).isEqualTo(1);
    }

    @Test
    @DisplayName("FINISHED(FAILED)는 워커가 보고한 오류로 링크를 끝낸다")
    void failedEventFailsTheLink() throws Exception {
        RegimeLink link = queuedLink();

        publishEvent("event-finished-failed.json", link);

        await().atMost(TIMEOUT).until(() -> statusOf(link) == LinkStatus.FAILED);
        assertThat(failureOf(link).code()).isNotEqualTo(LinkFailure.SYSTEM_FAILURE);
        assertThat(resultCount(link)).isZero();
    }

    @Test
    @DisplayName("DLQ 로 간 작업은 링크를 FAILED(SYSTEM_FAILURE)로 끝낸다")
    void deadLetteredJobFailsTheLink() {
        RegimeLink link = queuedLink();

        // 워커가 reject 한 작업이 큐 속성(DLX)을 타고 가는 길을 그대로 밟는다 — 같은 exchange·routing key 다.
        rabbitTemplate.send(
                RegimeLinkRabbitConfig.DEAD_LETTER_EXCHANGE,
                RegimeLinkRabbitConfig.JOBS_DEAD_LETTER_ROUTING_KEY,
                MessageBuilder.withBody("{}".getBytes(UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .setCorrelationId(link.linkId().value())
                        .build());

        await().atMost(TIMEOUT).until(() -> statusOf(link) == LinkStatus.FAILED);
        assertThat(failureOf(link).code()).isEqualTo(LinkFailure.SYSTEM_FAILURE);
        assertThat(messageCount(RegimeLinkRabbitConfig.JOBS_DEAD_LETTER_QUEUE)).isZero();
    }

    // --- 도우미 -------------------------------------------------------------------

    private RegimeLink queuedLink() {
        RegimeLink created =
                links.create(
                        RegimeLink.pending(
                                RUN_ID,
                                new CaseRef(SNAPSHOT_KEY, YearMonth.of(2012, 3)),
                                Scenario.CONTINUOUS,
                                new PostWindow(
                                        LocalDate.of(2012, 4, 2),
                                        LocalDate.of(2012, 5, 2),
                                        LocalDate.of(2012, 7, 3),
                                        LocalDate.of(2012, 7, 3),
                                        63,
                                        true,
                                        true),
                                Availability.normal(63),
                                LinkStatus.QUEUED));
        links.recordPublished(
                created.id(),
                "RLJ-" + created.linkId().value() + "-1",
                1,
                Instant.parse("2026-09-18T01:59:00Z"));
        return created;
    }

    private void publishEvent(String name, RegimeLink link) throws Exception {
        ObjectNode event =
                (ObjectNode) RegimeLinkMessageJson.objectMapper().readTree(fixture(name));
        event.put("linkId", link.linkId().value());
        event.put("attempt", 1);
        rabbitTemplate.send(
                RegimeLinkRabbitConfig.EXCHANGE,
                RegimeLinkRabbitConfig.EVENTS_ROUTING_KEY,
                MessageBuilder.withBody(
                                RegimeLinkMessageJson.objectMapper()
                                        .writeValueAsString(event)
                                        .getBytes(UTF_8))
                        .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                        .build());
    }

    private LinkStatus statusOf(RegimeLink link) {
        return links.findById(link.id()).orElseThrow().status();
    }

    private LinkFailure failureOf(RegimeLink link) {
        return links.findById(link.id()).orElseThrow().failure();
    }

    private int resultCount(RegimeLink link) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM regime_link_result WHERE link_id = ?",
                        Integer.class,
                        link.id());
        return count == null ? 0 : count;
    }

    private int messageCount(String queue) {
        return amqpAdmin.getQueueInfo(queue).getMessageCount();
    }

    private static String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name), UTF_8);
    }
}
