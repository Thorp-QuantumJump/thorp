package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.BacktestEventType;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code backtest.events} 큐의 Consumer. 본문 {@code type}으로 payload 타입을 정해 역직렬화·검증한 뒤 서비스에 넘긴다.
 *
 * <p>raw {@link Message}를 받는다. 계약대로 워커는 {@code __TypeId__} 헤더를 보내지 않으므로 컨버터에 맡기면 payload 타입을 정할 수
 * 없다. {@code type}만 먼저 읽고(1차 파싱) 그 타입으로 다시 읽는다. 두 번 파싱하지만 FINISHED 한 건에 한 번뿐이다.
 *
 * <p>오류 분기:
 *
 * <ul>
 *   <li>역직렬화 실패·계약 검증 실패 → {@link AmqpRejectAndDontRequeueException}. 브로커 재큐 없이 같은 delivery 안에서 리스너
 *       재시도 후 DLQ
 *   <li>DB 저장 실패 등 일시 오류 → 예외 그대로 전파. 재시도 후 DLQ
 *   <li>모르는 {@code backtestId}·늦은 이벤트 → 서비스가 로그만 남기고 정상 반환. ACK
 * </ul>
 *
 * <p>{@code @Transactional}은 서비스 메서드에 있다. ACK 모드가 AUTO이므로 서비스가 정상 반환(커밋)한 뒤에 ACK된다. 설정은 {@code
 * spring.rabbitmq.listener.simple.*}.
 */
@Component
public class BacktestEventListener {

    private static final Logger log = LoggerFactory.getLogger(BacktestEventListener.class);

    private static final TypeReference<BacktestEvent<StartedPayload>> STARTED_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<BacktestEvent<ProgressPayload>> PROGRESS_TYPE =
            new TypeReference<>() {};
    private static final TypeReference<BacktestEvent<FinishedPayload>> FINISHED_TYPE =
            new TypeReference<>() {};

    private final ObjectMapper mapper = BacktestRabbitConfig.messageObjectMapper();
    private final BacktestEventService service;

    public BacktestEventListener(BacktestEventService service) {
        this.service = service;
    }

    @RabbitListener(queues = BacktestRabbitConfig.EVENTS_QUEUE)
    public void onEvent(Message message) {
        byte[] body = message.getBody();
        BacktestEventType type = readType(body);
        switch (type) {
            case STARTED -> {
                BacktestEvent<StartedPayload> event =
                        parse(body, STARTED_TYPE, BacktestEventValidator::validateStarted);
                service.handleStarted(event);
            }
            case PROGRESS -> {
                BacktestEvent<ProgressPayload> event =
                        parse(body, PROGRESS_TYPE, BacktestEventValidator::validateProgress);
                service.handleProgress(event);
            }
            case FINISHED -> {
                BacktestEvent<FinishedPayload> event =
                        parse(body, FINISHED_TYPE, BacktestEventValidator::validateFinished);
                service.handleFinished(event);
            }
            // enum에 값이 추가되고 여기에 case가 빠지면 메시지가 조용히 ACK되어 사라진다. 그 경로를 막는다.
            default -> throw reject("type", "no handler for " + type);
        }
    }

    private BacktestEventType readType(byte[] body) {
        try {
            JsonNode type = mapper.readTree(body).path("type");
            if (!type.isTextual()) {
                throw reject("type", "missing or not a string");
            }
            return BacktestEventType.valueOf(type.asText());
        } catch (IOException | IllegalArgumentException e) {
            throw reject("type", e.getMessage());
        }
    }

    /** 역직렬화와 검증만 여기서 잡는다. 서비스의 예외(일시 오류)는 재시도 대상이므로 감싸지 않는다. */
    private <P> BacktestEvent<P> parse(
            byte[] body, TypeReference<BacktestEvent<P>> type, Validator<P> validator) {
        BacktestEvent<P> event;
        try {
            event = mapper.readValue(body, type);
        } catch (IOException e) {
            throw reject("body", e.getMessage());
        }
        try {
            validator.validate(event);
        } catch (IllegalArgumentException e) {
            throw reject(event.eventId(), e.getMessage());
        }
        return event;
    }

    private static AmqpRejectAndDontRequeueException reject(String where, String reason) {
        String message = "backtest event rejected (" + where + "): " + reason;
        log.warn(message);
        return new AmqpRejectAndDontRequeueException(message);
    }

    @FunctionalInterface
    private interface Validator<P> {
        void validate(BacktestEvent<P> event);
    }
}
