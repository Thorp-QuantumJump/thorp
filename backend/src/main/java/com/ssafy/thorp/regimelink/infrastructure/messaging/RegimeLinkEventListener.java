package com.ssafy.thorp.regimelink.infrastructure.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkEvent;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkEventType;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkFinishedPayload;
import com.ssafy.thorp.regimelink.application.port.message.RegimeLinkStartedPayload;
import com.ssafy.thorp.regimelink.application.service.RegimeLinkEventHandler;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * {@code regime-link.events} 큐의 Consumer (T032).
 *
 * <p>raw {@link Message}를 받는다 — 워커는 {@code __TypeId__} 헤더를 보내지 않으므로 컨버터에 맡기면 payload 타입을 정할 수 없다.
 * 본문 {@code type}을 먼저 읽고 그 타입으로 다시 읽는다(백테스트 결과 Consumer 와 같은 방식).
 *
 * <p>역직렬화 실패는 {@link AmqpRejectAndDontRequeueException}(재시도 뒤 버림)이고, 저장 실패 같은 일시 오류는 그대로 전파해 재전달
 * 경로를 탄다. 모르는 링크·늦은 이벤트는 서비스가 로그만 남기고 정상 반환하므로 ACK 된다.
 */
@Component
public class RegimeLinkEventListener {

    private static final Logger log = LoggerFactory.getLogger(RegimeLinkEventListener.class);

    private static final TypeReference<RegimeLinkEvent<RegimeLinkStartedPayload>> STARTED =
            new TypeReference<>() {};
    private static final TypeReference<RegimeLinkEvent<RegimeLinkFinishedPayload>> FINISHED =
            new TypeReference<>() {};

    private final ObjectMapper mapper = RegimeLinkMessageJson.objectMapper();
    private final RegimeLinkEventHandler handler;

    public RegimeLinkEventListener(RegimeLinkEventHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = RegimeLinkRabbitConfig.EVENTS_QUEUE)
    public void onEvent(Message message) {
        byte[] body = message.getBody();
        switch (readType(body)) {
            case STARTED -> handler.handleStarted(parse(body, STARTED));
            case FINISHED -> handler.handleFinished(parse(body, FINISHED));
            // enum 에 값이 늘고 여기에 case 가 빠지면 메시지가 조용히 ACK 되어 사라진다. 그 경로를 막는다.
            default -> throw reject("type", "no handler");
        }
    }

    private RegimeLinkEventType readType(byte[] body) {
        try {
            JsonNode type = mapper.readTree(body).path("type");
            if (!type.isTextual()) {
                throw reject("type", "missing or not a string");
            }
            return RegimeLinkEventType.valueOf(type.asText());
        } catch (IOException | IllegalArgumentException e) {
            throw reject("type", e.getMessage());
        }
    }

    private <P> RegimeLinkEvent<P> parse(byte[] body, TypeReference<RegimeLinkEvent<P>> type) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw reject("body", e.getMessage());
        }
    }

    private static AmqpRejectAndDontRequeueException reject(String where, String reason) {
        String message = "regime link event rejected (" + where + "): " + reason;
        log.warn(message);
        return new AmqpRejectAndDontRequeueException(message);
    }
}
