package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** DLQ 메시지에서 무엇을 읽고 무엇을 버리는가. 브로커 없이 볼 수 있는 판단만 여기서 고정한다. */
class BacktestDeadLetterListenerTest {

    private static final String RUN_ID = "BT-20260914-000077";

    private final BacktestEventService service = mock(BacktestEventService.class);
    private final BacktestDeadLetterListener listener = new BacktestDeadLetterListener(service);

    private static Message message(String correlationId, Object attempt, boolean redelivered) {
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId(correlationId);
        properties.setRedelivered(redelivered);
        if (attempt != null) {
            properties.setHeader(RabbitBacktestJobPublisher.PUBLISH_ATTEMPT_HEADER, attempt);
        }
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    @Test
    @DisplayName("correlationId 의 runId 와 publishAttempt 헤더를 그대로 넘긴다")
    void passesTheRunIdAndPublishAttempt() {
        listener.onDeadLetteredJob(message(RUN_ID, 2, false));

        verify(service).handleDeadLetter(RUN_ID, 2);
    }

    @Test
    @DisplayName("재전달된 delivery 도 그대로 넘긴다 — 멱등 판정은 시도 번호로 저장소가 한다")
    void stillPassesARedeliveredMessage() {
        // redelivered 는 "이미 세었다"가 아니라 "이미 보낸 적 있다"라서, 여기서 버리면 커밋 전에 죽어
        // 다시 온 첫 전달까지 버리게 되고 죽은 실행이 영영 종결되지 않는다.
        listener.onDeadLetteredJob(message(RUN_ID, 1, true));

        verify(service).handleDeadLetter(RUN_ID, 1);
    }

    @Test
    @DisplayName("헤더 정수형이 Long 으로 와도 받는다 — 브로커를 거치며 폭이 바뀐다")
    void acceptsALongHeader() {
        listener.onDeadLetteredJob(message(RUN_ID, 3L, false));

        verify(service).handleDeadLetter(RUN_ID, 3);
    }

    @Test
    @DisplayName("시도 번호가 없으면 세지 않는다 — 재전달을 구분할 수 없어 살아 있는 실행을 끊을 수 있다")
    void doesNotCountWithoutAPublishAttempt() {
        listener.onDeadLetteredJob(message(RUN_ID, null, false));

        verify(service, never()).handleDeadLetter(anyString(), anyInt());
    }

    @Test
    @DisplayName("correlationId 가 없으면 버린다 — 되돌려 봐야 같은 자리로 다시 온다")
    void dropsAMessageWithoutACorrelationId() {
        listener.onDeadLetteredJob(message(null, 1, false));

        verify(service, never()).handleDeadLetter(anyString(), anyInt());
    }
}
