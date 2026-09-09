package com.ssafy.thorp.backtest.infrastructure.messaging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.RunStatus;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import com.ssafy.thorp.backtest.application.service.BacktestEventService;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;

/**
 * 본문 {@code type} 분기와 오류 분기(계약 오류 → reject, 일시 오류 → 그대로 전파)를 고정한다. 브로커 없이 {@link Message}를 직접 만든다.
 */
@ExtendWith(MockitoExtension.class)
class BacktestEventListenerTest {

    @Mock private BacktestEventService service;
    @InjectMocks private BacktestEventListener listener;

    private static Message message(String body) {
        return new Message(body.getBytes(UTF_8));
    }

    private static Message fixture(String file) {
        return message(MessageFixtures.raw(file));
    }

    private static Message mutated(String file, Consumer<ObjectNode> mutation) {
        try {
            ObjectNode node =
                    (ObjectNode) MessageFixtures.mapper().readTree(MessageFixtures.raw(file));
            mutation.accept(node);
            return message(MessageFixtures.mapper().writeValueAsString(node));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("STARTED 본문은 StartedPayload로 읽어 handleStarted에 넘긴다")
    void dispatchesStarted() {
        listener.onEvent(fixture(MessageFixtures.STARTED));

        ArgumentCaptor<BacktestEvent<StartedPayload>> captor = ArgumentCaptor.captor();
        verify(service).handleStarted(captor.capture());
        assertThat(captor.getValue().eventId()).isEqualTo("EVT-000001");
        assertThat(captor.getValue().payload().startedAt()).isNotNull();
        verifyNoMoreInteractions(service);
    }

    @Test
    @DisplayName("PROGRESS 본문은 ProgressPayload로 읽어 handleProgress에 넘긴다")
    void dispatchesProgress() {
        listener.onEvent(fixture(MessageFixtures.PROGRESS));

        ArgumentCaptor<BacktestEvent<ProgressPayload>> captor = ArgumentCaptor.captor();
        verify(service).handleProgress(captor.capture());
        assertThat(captor.getValue().payload().completedTradingDays()).isEqualTo(20);
        assertThat(captor.getValue().payload().portfolioSeries()).hasSize(20);
        verifyNoMoreInteractions(service);
    }

    @Test
    @DisplayName("FINISHED 본문(COMPLETED·FAILED)은 FinishedPayload로 읽어 handleFinished에 넘긴다")
    void dispatchesFinished() {
        listener.onEvent(fixture(MessageFixtures.FINISHED_COMPLETED));
        listener.onEvent(fixture(MessageFixtures.FINISHED_FAILED));

        ArgumentCaptor<BacktestEvent<FinishedPayload>> captor = ArgumentCaptor.captor();
        verify(service, org.mockito.Mockito.times(2)).handleFinished(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(e -> e.payload().engineOutput().run().status())
                .containsExactly(RunStatus.COMPLETED, RunStatus.FAILED);
        assertThat(captor.getAllValues().get(0).payload().engineOutput().trades()).hasSize(3);
        verifyNoMoreInteractions(service);
    }

    @Test
    @DisplayName("JSON이 아닌 본문은 재큐 없이 reject한다")
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> listener.onEvent(message("{ this is not json")))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("type이 없거나 모르는 값이면 reject한다")
    void rejectsMissingOrUnknownType() {
        assertThatThrownBy(
                        () ->
                                listener.onEvent(
                                        mutated(MessageFixtures.STARTED, n -> n.remove("type"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(
                        () ->
                                listener.onEvent(
                                        mutated(
                                                MessageFixtures.STARTED,
                                                n -> n.put("type", "CANCELLED"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("역직렬화는 되지만 계약을 어긴 본문(schemaVersion=2)은 경로를 담아 reject한다")
    void rejectsContractViolation() {
        assertThatThrownBy(
                        () ->
                                listener.onEvent(
                                        mutated(
                                                MessageFixtures.FINISHED_COMPLETED,
                                                n -> n.put("schemaVersion", 2))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("schemaVersion");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("type과 payload 모양이 어긋나면(FINISHED인데 STARTED payload) reject한다")
    void rejectsPayloadShapeMismatch() {
        assertThatThrownBy(
                        () ->
                                listener.onEvent(
                                        mutated(
                                                MessageFixtures.STARTED,
                                                n -> n.put("type", "FINISHED").put("sequence", 1))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("payload.engineOutput");
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("필수 정수(sequence)가 없으면 역직렬화 단계에서 reject한다")
    void rejectsMissingPrimitive() {
        assertThatThrownBy(
                        () ->
                                listener.onEvent(
                                        mutated(
                                                MessageFixtures.STARTED,
                                                n -> n.remove("sequence"))))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("서비스의 예외(일시 오류)는 감싸지 않고 그대로 전파해 재시도 대상이 된다")
    void propagatesServiceFailure() {
        doThrow(new IllegalStateException("db down")).when(service).handleStarted(any());

        assertThatThrownBy(() -> listener.onEvent(fixture(MessageFixtures.STARTED)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
    }
}
