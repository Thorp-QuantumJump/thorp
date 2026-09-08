package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.EngineInput;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.RunStatus;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 계약 픽스처({@code docs/specs/backtest-messaging/examples})를 DTO로 읽고 다시 써서 원본과 정확 동치인지 확인한다.
 *
 * <p>워커 테스트가 같은 파일을 읽는다. 픽스처에는 null 값이 없고, nullable 필드는 키가 생략되어 있으므로 정규화 없이 JSON 트리를 그대로 비교한다(키 순서만
 * 무시). gradle test의 작업 디렉터리는 {@code backend/}다.
 */
class BacktestMessageRoundTripTest {

    private static final Path FIXTURES =
            Path.of("..", "docs", "specs", "backtest-messaging", "examples");

    private final ObjectMapper mapper = BacktestRabbitConfig.messageObjectMapper();

    @Test
    void engine_input() throws IOException {
        EngineInput input = roundTrip("engine-input.json", new TypeReference<>() {});

        assertThat(input.run().backtestId()).isEqualTo("BT-20260907-000001");
        assertThat(input.portfolio().initialCash()).isEqualByComparingTo("10000000");
        // 자유 형식 parameters: 정수는 정수, 소수는 숫자로 통과 (문자열 변환 금지)
        assertThat(input.strategy().parameters().get("shortWindow")).isEqualTo(20);
        assertThat(input.strategy().parameters().get("threshold")).isEqualTo(0.05);
    }

    @Test
    void event_started() throws IOException {
        BacktestEvent<StartedPayload> event =
                roundTrip("event-started.json", new TypeReference<>() {});
        assertThat(event.sequence()).isZero();
        assertThat(event.payload().startedAt()).isEqualTo("2026-09-07T01:00:00Z");
    }

    @Test
    void event_progress() throws IOException {
        BacktestEvent<ProgressPayload> event =
                roundTrip("event-progress.json", new TypeReference<>() {});
        assertThat(event.payload().portfolioSeries())
                .hasSize(event.payload().completedTradingDays());
    }

    @Test
    void event_finished_completed() throws IOException {
        BacktestEvent<FinishedPayload> event =
                roundTrip("event-finished-completed.json", new TypeReference<>() {});
        FinishedPayload payload = event.payload();
        assertThat(payload.engineOutput().run().status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(payload.engineOutput().run().error()).isNull();
        assertThat(payload.engineOutput().portfolioSeries().get(0).positions()).isEmpty();
        assertThat(payload.engineOutput().portfolioSeries().get(1).positions()).isNull();
        assertThat(payload.benchmark()).isNotNull();
    }

    @Test
    void event_finished_failed() throws IOException {
        BacktestEvent<FinishedPayload> event =
                roundTrip("event-finished-failed.json", new TypeReference<>() {});
        var output = event.payload().engineOutput();
        assertThat(output.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(output.run().error().code()).isEqualTo("NO_MARKET_DATA");
        assertThat(output.performance()).isNull();
        assertThat(output.execution()).isNull();
        assertThat(event.payload().benchmark()).isNull();
    }

    @Test
    void 직렬화_표기_규약() throws IOException {
        JsonNode out =
                mapper.readTree(
                        mapper.writeValueAsString(
                                read(
                                        "event-finished-completed.json",
                                        new TypeReference<BacktestEvent<FinishedPayload>>() {})));
        JsonNode performance = out.at("/payload/engineOutput/performance");
        assertThat(performance.get("initialEquity").isTextual()).isTrue(); // BigDecimal → 문자열
        assertThat(performance.get("tradeCount").isInt()).isTrue();
        assertThat(out.get("occurredAt").asText()).endsWith("Z"); // Instant → UTC ISO
        assertThat(out.at("/payload/engineOutput/run").has("error")).isFalse(); // null 키 생략
    }

    @Test
    void 필수_정수_누락은_실패한다() {
        String noSequence =
                """
                {"schemaVersion":1,"eventId":"E","backtestId":"B","executionId":"X",
                 "type":"STARTED","occurredAt":"2026-09-07T01:00:00Z","payload":{}}
                """;
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        noSequence,
                                        new TypeReference<BacktestEvent<StartedPayload>>() {}))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void 미지의_enum_값은_실패한다() {
        String badType =
                """
                {"schemaVersion":1,"eventId":"E","backtestId":"B","executionId":"X",
                 "type":"PAUSED","sequence":1,"occurredAt":"2026-09-07T01:00:00Z","payload":{}}
                """;
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        badType,
                                        new TypeReference<BacktestEvent<StartedPayload>>() {}))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void enum_자리의_숫자는_실패한다() {
        String numericType =
                """
                {"schemaVersion":1,"eventId":"E","backtestId":"B","executionId":"X",
                 "type":0,"sequence":0,"occurredAt":"2026-09-07T01:00:00Z","payload":{}}
                """;
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        numericType,
                                        new TypeReference<BacktestEvent<StartedPayload>>() {}))
                .isInstanceOf(MismatchedInputException.class);
    }

    @Test
    void 정수_자리의_소수는_실패한다() {
        String floatDays =
                """
                {"schemaVersion":1,"eventId":"E","backtestId":"B","executionId":"X",
                 "type":"PROGRESS","sequence":1,"occurredAt":"2026-09-07T01:00:00Z",
                 "payload":{"completedTradingDays":1.9,"totalTradingDays":129,"portfolioSeries":[]}}
                """;
        assertThatThrownBy(
                        () ->
                                mapper.readValue(
                                        floatDays,
                                        new TypeReference<BacktestEvent<ProgressPayload>>() {}))
                .isInstanceOf(MismatchedInputException.class);
    }

    private <T> T roundTrip(String file, TypeReference<T> type) throws IOException {
        String original = Files.readString(FIXTURES.resolve(file));
        T value = mapper.readValue(original, type);
        JsonNode written = mapper.readTree(mapper.writeValueAsString(value));
        assertThat(written).as(file).isEqualTo(mapper.readTree(original));
        return value;
    }

    private <T> T read(String file, TypeReference<T> type) throws IOException {
        return mapper.readValue(Files.readString(FIXTURES.resolve(file)), type);
    }
}
