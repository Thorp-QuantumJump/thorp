package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ssafy.thorp.common.json.PlainBigDecimalSerializer;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 백테스트 RabbitMQ 토폴로지와 메시지 직렬화 설정. 계약 기준: {@code docs/specs/backtest-messaging/README.md} §2·§5.
 *
 * <p>큐·exchange·routing key 이름은 워커와 합의한 계약 상수라 프로퍼티로 빼지 않는다. 선언은 Boot 자동설정 {@code RabbitAdmin}이 브로커
 * 연결 시점에 수행하므로 브로커 없이도 기동된다. 인프라가 같은 속성으로 미리 만들어 두어도 충돌하지 않는다.
 */
@Configuration
public class BacktestRabbitConfig {

    private static final Logger log = LoggerFactory.getLogger(BacktestRabbitConfig.class);

    public static final String EXCHANGE = "backtest";
    public static final String DEAD_LETTER_EXCHANGE = "backtest.dlx";

    public static final String JOBS_QUEUE = "backtest.jobs";
    public static final String JOBS_ROUTING_KEY = "backtest.job.requested";
    public static final String JOBS_DEAD_LETTER_QUEUE = "backtest.jobs.dlq";
    public static final String JOBS_DEAD_LETTER_ROUTING_KEY = "backtest.job.dead";

    public static final String EVENTS_QUEUE = "backtest.events";
    public static final String EVENTS_ROUTING_KEY = "backtest.event";
    public static final String EVENTS_DEAD_LETTER_QUEUE = "backtest.events.dlq";
    public static final String EVENTS_DEAD_LETTER_ROUTING_KEY = "backtest.event.dead";

    /** 백엔드 → 워커 제어(취소) fanout. 워커마다 exclusive·auto-delete 큐를 바인딩한다(계약 §2·§4.4). */
    public static final String CONTROL_EXCHANGE = "backtest.control";

    /** 메시지 스키마 버전(계약 §3 header, §4.2 envelope). 워커 {@code messaging.SCHEMA_VERSION}과 같아야 한다. */
    public static final int SCHEMA_VERSION = 2;

    /**
     * 작업 큐의 quorum 재전달 상한. 워커 프로세스가 죽어 unacked 메시지가 재전달되면 한 번만 재실행되고, 두 번째도 끊기면 브로커가 DLQ로 보낸다(spec
     * 004 Clarify Q5, research R-06). classic → quorum 전환은 인자 변경이라 기존 큐를 삭제해야 한다.
     */
    public static final int JOBS_DELIVERY_LIMIT = 1;

    /** exchange 3(direct 2 + fanout 1), queue 4, binding 4. DLQ로 간 메시지는 자동 재투입하지 않는다. */
    @Bean
    public Declarables backtestTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
        FanoutExchange control = new FanoutExchange(CONTROL_EXCHANGE, true, false);

        Queue jobs =
                QueueBuilder.durable(JOBS_QUEUE)
                        .quorum()
                        .deliveryLimit(JOBS_DELIVERY_LIMIT)
                        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                        .deadLetterRoutingKey(JOBS_DEAD_LETTER_ROUTING_KEY)
                        .build();
        Queue events =
                QueueBuilder.durable(EVENTS_QUEUE)
                        .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                        .deadLetterRoutingKey(EVENTS_DEAD_LETTER_ROUTING_KEY)
                        .build();
        Queue jobsDlq = QueueBuilder.durable(JOBS_DEAD_LETTER_QUEUE).build();
        Queue eventsDlq = QueueBuilder.durable(EVENTS_DEAD_LETTER_QUEUE).build();

        return new Declarables(
                exchange,
                dlx,
                control,
                jobs,
                events,
                jobsDlq,
                eventsDlq,
                BindingBuilder.bind(jobs).to(exchange).with(JOBS_ROUTING_KEY),
                BindingBuilder.bind(events).to(exchange).with(EVENTS_ROUTING_KEY),
                BindingBuilder.bind(jobsDlq).to(dlx).with(JOBS_DEAD_LETTER_ROUTING_KEY),
                BindingBuilder.bind(eventsDlq).to(dlx).with(EVENTS_DEAD_LETTER_ROUTING_KEY));
    }

    /**
     * 기동 시 브로커 연결을 한 번 연다. {@code RabbitAdmin}은 첫 연결이 열릴 때 토폴로지를 선언하는데, 발행·리스너가 없는 동안은 연결을 열 계기가 없어
     * 관리 UI에 큐가 보이지 않는다. 브로커에 닿지 못하면 경고만 남기고 기동은 계속한다(첫 연결 성공 시 자동 선언).
     */
    @Bean
    public ApplicationRunner declareBacktestTopology(ConnectionFactory connectionFactory) {
        return args -> {
            try {
                connectionFactory.createConnection(); // CachingConnectionFactory: 공유 연결로 유지된다
                log.info("RabbitMQ connected; backtest topology declared (exchange={})", EXCHANGE);
            } catch (AmqpException e) {
                log.warn(
                        "RabbitMQ unreachable at startup; topology will be declared on first"
                                + " connection. cause={}",
                        e.getMessage());
            }
        };
    }

    /**
     * 발행용 컨버터. 수신(결과 Consumer)은 raw {@code Message}를 받아 본문 {@code type}을 확인한 뒤 {@link
     * #messageObjectMapper()}로 직접 변환하므로 이 컨버터와 {@code __TypeId__} 헤더에 의존하지 않는다.
     */
    @Bean
    public MessageConverter backtestMessageConverter() {
        return new Jackson2JsonMessageConverter(messageObjectMapper());
    }

    /**
     * 메시지 전용 ObjectMapper(README §5). 웹 MVC ObjectMapper와 분리한다.
     *
     * <p>브로커 경로와 결과 저장·조회가 같은 매퍼를 써야 한다 — {@code MessageJsonPersistenceConfig}가 이 매퍼를 Hibernate
     * JSON 포맷 매퍼로 연결해 decimal을 jsonb 문자열로 저장하므로, 그 컬럼을 직접 읽어 파싱하는 쪽도 이것으로 읽어야 값과 scale이 복원된다.
     *
     * <p>{@code public}이지만 **다른 계층에서 쓰지 않는다.** 그 경계는 Java 가시성이 아니라 {@code ArchitectureTest}의 {@code
     * whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer()}가 지킨다 — application·presentation은
     * 가시성과 무관하게 이 클래스를 참조할 수 없다. package-private는 그 위에 덧댄 이중 잠금이었고 정작 같은 계층의 {@code
     * infrastructure.persistence}가 쓰는 것을 막았다.
     *
     * <ul>
     *   <li>BigDecimal → plain 문자열, 읽기는 숫자·문자열 모두 수용
     *   <li>LocalDate·Instant → ISO 문자열
     *   <li>null 필드는 키 생략(NON_NULL)
     *   <li>필수 정수(primitive) 누락·null → 실패. enum 자리에 숫자, 정수 자리에 소수 → 실패(잘못된 입력을 정상값으로 바꾸지 않는다). 알 수
     *       없는 필드 → 무시
     *   <li>enum은 toString 기준(Interval "1D")
     *   <li>{@code USE_BIG_DECIMAL_FOR_FLOATS}는 켜지 않는다 — {@code strategy.parameters}의 소수가 문자열로 변질되는
     *       것을 막기 위함
     * </ul>
     */
    public static ObjectMapper messageObjectMapper() {
        SimpleModule decimals =
                new SimpleModule().addSerializer(BigDecimal.class, new PlainBigDecimalSerializer());
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(decimals)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.WRITE_ENUMS_USING_TO_STRING)
                .enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }
}
