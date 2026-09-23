package com.ssafy.thorp.backtest.infrastructure.messaging;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.thorp.backtest.application.port.message.BacktestEvent;
import com.ssafy.thorp.backtest.application.port.message.ControlMessage;
import com.ssafy.thorp.backtest.application.port.message.FinishedPayload;
import com.ssafy.thorp.backtest.application.port.message.ProgressPayload;
import com.ssafy.thorp.backtest.application.port.message.StartedPayload;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 계약 픽스처({@code docs/specs/backtest-messaging/examples})를 메시지 ObjectMapper로 읽는 테스트 헬퍼.
 *
 * <p>다른 패키지의 테스트(persistence·service)도 이 클래스를 통해 픽스처를 얻는다. gradle 테스트의 작업 디렉터리는 {@code backend/}이므로
 * 경로는 리포 루트 기준으로 한 단계 올라간다.
 */
public final class MessageFixtures {

    public static final Path DIR = Path.of("..", "docs", "specs", "backtest-messaging", "examples");

    public static final String ENGINE_INPUT = "engine-input.json";
    public static final String ENGINE_INPUT_TARGET_RISK = "engine-input-target-risk.json";
    public static final String STARTED = "event-started.json";
    public static final String PROGRESS = "event-progress.json";
    public static final String FINISHED_COMPLETED = "event-finished-completed.json";
    public static final String FINISHED_FAILED = "event-finished-failed.json";
    public static final String FINISHED_CANCELLED = "event-finished-cancelled.json";
    public static final String CONTROL_CANCEL = "control-cancel.json";

    public static final TypeReference<BacktestEvent<StartedPayload>> STARTED_EVENT =
            new TypeReference<>() {};
    public static final TypeReference<BacktestEvent<ProgressPayload>> PROGRESS_EVENT =
            new TypeReference<>() {};
    public static final TypeReference<BacktestEvent<FinishedPayload>> FINISHED_EVENT =
            new TypeReference<>() {};
    public static final TypeReference<ControlMessage> CONTROL = new TypeReference<>() {};

    private static final ObjectMapper MAPPER = BacktestRabbitConfig.messageObjectMapper();

    private MessageFixtures() {}

    /** 메시지 ObjectMapper. 테스트에서 픽스처를 변형하거나 깨진 본문을 만들 때 쓴다. */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String raw(String file) {
        try {
            return Files.readString(DIR.resolve(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String file, TypeReference<T> type) {
        return parse(raw(file), type);
    }

    public static <T> T parse(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 픽스처의 {@code runId}(envelope와 {@code engineOutput.run})를 바꾼 JSON. 통합 테스트가 실제로 만든 Job에 이벤트를 붙일
     * 때 쓴다(요청/실행 분리 전이라 runId 값 = backtestId). 픽스처의 나머지는 그대로다.
     */
    public static String withRunId(String file, String runId) {
        try {
            ObjectNode node = (ObjectNode) MAPPER.readTree(raw(file));
            node.put("runId", runId);
            JsonNode run = node.at("/payload/engineOutput/run");
            if (run.isObject()) {
                ((ObjectNode) run).put("runId", runId);
            }
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static BacktestEvent<StartedPayload> started() {
        return read(STARTED, STARTED_EVENT);
    }

    public static BacktestEvent<ProgressPayload> progress() {
        return read(PROGRESS, PROGRESS_EVENT);
    }

    public static BacktestEvent<FinishedPayload> finishedCompleted() {
        return read(FINISHED_COMPLETED, FINISHED_EVENT);
    }

    public static BacktestEvent<FinishedPayload> finishedFailed() {
        return read(FINISHED_FAILED, FINISHED_EVENT);
    }

    public static BacktestEvent<FinishedPayload> finishedCancelled() {
        return read(FINISHED_CANCELLED, FINISHED_EVENT);
    }

    public static ControlMessage controlCancel() {
        return read(CONTROL_CANCEL, CONTROL);
    }
}
