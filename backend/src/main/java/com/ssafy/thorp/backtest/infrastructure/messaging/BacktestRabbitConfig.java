package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
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

    /** exchange 2, queue 4, binding 4. DLQ로 간 메시지는 자동 재투입하지 않는다. */
    @Bean
    public Declarables backtestTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dlx = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);

        Queue jobs =
                QueueBuilder.durable(JOBS_QUEUE)
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
     * 발행용 컨버터. 수신(Task 5)은 raw {@code Message}를 받아 본문 {@code type}을 확인한 뒤 {@link
     * #messageObjectMapper()}로 직접 변환하므로 이 컨버터와 {@code __TypeId__} 헤더에 의존하지 않는다.
     */
    @Bean
    public MessageConverter backtestMessageConverter() {
        return new Jackson2JsonMessageConverter(messageObjectMapper());
    }

    /**
     * 메시지 전용 ObjectMapper(README §5). 웹 MVC ObjectMapper와 분리한다. 같은 패키지의 테스트와 결과 Consumer가 공유한다.
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
    static ObjectMapper messageObjectMapper() {
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

    /** {@code toPlainString}: 소수 7자리 이상에서 지수 표기("1E-7")가 나오는 것을 막는다. */
    private static final class PlainBigDecimalSerializer extends StdSerializer<BigDecimal> {

        PlainBigDecimalSerializer() {
            super(BigDecimal.class);
        }

        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeString(value.toPlainString());
        }
    }
}
