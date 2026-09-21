package com.ssafy.thorp.regimelink.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 사례 연결 RabbitMQ 토폴로지 (계약 {@code docs/specs/regime-link-messaging/README.md} "메시지 속성과 토폴로지", T030).
 *
 * <p>백테스트 경로({@code BacktestRabbitConfig})와 <b>분리된</b> exchange·큐다(research R1). 두 계약은 스키마 버전을 따로
 * 세므로 상수를 빌려 쓰지 않는다.
 *
 * <p><b>{@code public static final String} 상수는 계약 이름 일곱뿐이다.</b> 워커 시험({@code
 * tests/regime_link/test_messaging.py})이 이 파일의 그 선언을 통째로 긁어 {@code worker.regime_link.messaging}의
 * 같은 이름과 대조하므로, 워커에 없는 이름을 이 형태로 더하면 워커 빌드가 깨진다. DLQ 라우팅 키처럼 계약에 이름이 없는 값은 private 으로 둔다.
 *
 * <p>선언은 Boot 자동설정 {@code RabbitAdmin}이 브로커 연결 시점에 수행하므로 브로커 없이도 기동된다.
 */
@Configuration
public class RegimeLinkRabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(RegimeLinkRabbitConfig.class);

    public static final String EXCHANGE = "regime-link";
    public static final String DEAD_LETTER_EXCHANGE = "regime-link.dlx";

    public static final String JOBS_QUEUE = "regime-link.jobs";
    public static final String JOBS_ROUTING_KEY = "regime-link.job.requested";

    /** 재전달 상한을 넘긴 작업이 가는 큐. <b>백엔드가 소비</b>해 링크를 {@code FAILED(SYSTEM_FAILURE)}로 끝낸다. */
    public static final String JOBS_DEAD_LETTER_QUEUE = "regime-link.jobs.dlq";

    public static final String EVENTS_QUEUE = "regime-link.events";
    public static final String EVENTS_ROUTING_KEY = "regime-link.event";

    /** 메시지 스키마 버전. 작업은 AMQP 헤더로, 이벤트는 본문 첫 필드로 싣는다. */
    public static final int SCHEMA_VERSION = 1;

    /** 작업 큐의 quorum 재전달 상한. 한 번만 다시 실행되고 두 번째도 끊기면 브로커가 DLQ 로 보낸다. */
    public static final int JOBS_DELIVERY_LIMIT = 1;

    /** 계약이 이름을 정하지 않은 내부 값이라 계약 상수로 두지 않는다(위 클래스 주석 참고). */
    private static final String JOBS_DEAD_LETTER_ROUTING_KEY = "regime-link.job.dead";

    /** exchange 2(direct), queue 3, binding 3. DLQ 로 간 메시지는 자동 재투입하지 않는다. */
    @Bean
    public Declarables regimeLinkTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);

        Queue jobs =
                QueueBuilder.durable(JOBS_QUEUE)
                        .quorum()
                        .deliveryLimit(JOBS_DELIVERY_LIMIT)
                        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                        .deadLetterRoutingKey(JOBS_DEAD_LETTER_ROUTING_KEY)
                        .build();
        Queue jobsDlq = QueueBuilder.durable(JOBS_DEAD_LETTER_QUEUE).build();
        Queue events = QueueBuilder.durable(EVENTS_QUEUE).build();

        return new Declarables(
                exchange,
                dlx,
                jobs,
                jobsDlq,
                events,
                BindingBuilder.bind(jobs).to(exchange).with(JOBS_ROUTING_KEY),
                BindingBuilder.bind(events).to(exchange).with(EVENTS_ROUTING_KEY),
                BindingBuilder.bind(jobsDlq).to(dlx).with(JOBS_DEAD_LETTER_ROUTING_KEY));
    }

    /** 기동 시 브로커 연결을 한 번 연다. 닿지 못하면 경고만 남기고 첫 연결에서 선언한다(백테스트와 같다). */
    @Bean
    public ApplicationRunner declareRegimeLinkTopology(ConnectionFactory connectionFactory) {
        return args -> {
            try {
                connectionFactory.createConnection();
                log.info(
                        "RabbitMQ connected; regime-link topology declared (exchange={})",
                        EXCHANGE);
            } catch (AmqpException e) {
                log.warn(
                        "RabbitMQ unreachable at startup; regime-link topology will be declared on"
                                + " first connection. cause={}",
                        e.getMessage());
            }
        };
    }
}
