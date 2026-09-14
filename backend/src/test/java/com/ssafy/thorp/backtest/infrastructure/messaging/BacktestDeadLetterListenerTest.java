package com.ssafy.thorp.backtest.infrastructure.messaging;

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

    private static Message message(String correlationId, boolean redelivered) {
        MessageProperties properties = new MessageProperties();
        properties.setCorrelationId(correlationId);
        properties.setRedelivered(redelivered);
        return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
    }

    @Test
    @DisplayName("correlationId 의 runId 로 처리한다")
    void handlesTheRunIdFromCorrelationId() {
        listener.onDeadLetteredJob(message(RUN_ID, false));

        verify(service).handleDeadLetter(RUN_ID);
    }

    @Test
    @DisplayName("재전달된 delivery 는 세지 않는다 — 커밋 뒤 ACK 이 유실되면 같은 메시지가 다시 온다")
    void doesNotCountARedeliveredMessage() {
        // 두 번 세면 살아 있는 작업이 남았는데도 dlqCount >= publishCount 가 서서 실행을 잘못 끊는다.
        listener.onDeadLetteredJob(message(RUN_ID, true));

        verify(service, never()).handleDeadLetter(RUN_ID);
    }

    @Test
    @DisplayName("correlationId 가 없으면 버린다 — 되돌려 봐야 같은 자리로 다시 온다")
    void dropsAMessageWithoutACorrelationId() {
        listener.onDeadLetteredJob(message(null, false));

        verify(service, never()).handleDeadLetter(org.mockito.ArgumentMatchers.anyString());
    }
}
