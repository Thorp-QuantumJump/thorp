package com.ssafy.thorp.backtest.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 계약 픽스처를 한 군데씩 깨뜨려 검증기가 그 경로를 짚어내는지 본다. 픽스처 원본과 계약이 허용하는 생략은 통과해야 한다.
 *
 * <p>위반 케이스는 (기대 경로, 픽스처 변형)의 쌍이다. 경로가 예외 메시지에 들어 있어야 DLQ에서 원인을 바로 찾을 수 있다.
 */
class BacktestEventValidatorTest {

    private static final ObjectMapper MAPPER = MessageFixtures.mapper();
    private static final String LONG_ID = "X".repeat(BacktestEventValidator.MAX_ID_LENGTH + 1);
    private static final String MAX_ID = "X".repeat(BacktestEventValidator.MAX_ID_LENGTH);

    private static ObjectNode fixture(String file) {
        try {
            return (ObjectNode) MAPPER.readTree(MessageFixtures.raw(file));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ObjectNode at(ObjectNode root, String pointer) {
        return (ObjectNode) root.at(pointer);
    }

    private static <T> T convert(ObjectNode node, TypeReference<T> type) {
        return MAPPER.convertValue(node, type);
    }

    private static Arguments violation(String expectedPath, Consumer<ObjectNode> mutation) {
        return Arguments.of(expectedPath, mutation);
    }

    // ---------- envelope · STARTED ----------

    static Stream<Arguments> startedViolations() {
        return Stream.of(
                violation("schemaVersion", n -> n.put("schemaVersion", 2)),
                violation("eventId", n -> n.put("eventId", " ")),
                violation("eventId", n -> n.put("eventId", LONG_ID)),
                violation("backtestId", n -> n.remove("backtestId")),
                violation("executionId", n -> n.remove("executionId")),
                violation("executionId", n -> n.put("executionId", LONG_ID)),
                violation("sequence", n -> n.put("sequence", -1)),
                violation("sequence", n -> n.put("sequence", 1)),
                violation("occurredAt", n -> n.remove("occurredAt")),
                violation("payload", n -> n.remove("payload")),
                violation("payload.startedAt", n -> at(n, "/payload").remove("startedAt")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startedViolations")
    @DisplayName("envelope·STARTED 위반은 경로를 담은 계약 오류다")
    void rejectsStartedViolation(String path, Consumer<ObjectNode> mutation) {
        ObjectNode node = fixture(MessageFixtures.STARTED);
        mutation.accept(node);

        assertThatThrownBy(
                        () ->
                                BacktestEventValidator.validateStarted(
                                        convert(node, MessageFixtures.STARTED_EVENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(path);
    }

    // ---------- PROGRESS ----------

    static Stream<Arguments> progressViolations() {
        return Stream.of(
                violation("sequence", n -> n.put("sequence", 0)),
                violation(
                        "payload.completedTradingDays",
                        n -> at(n, "/payload").put("completedTradingDays", -1)),
                violation(
                        "payload.totalTradingDays",
                        n -> at(n, "/payload").put("totalTradingDays", 0)),
                violation(
                        "payload.completedTradingDays",
                        n -> at(n, "/payload").put("completedTradingDays", 1_000)),
                violation(
                        "payload.portfolioSeries",
                        n -> at(n, "/payload").remove("portfolioSeries")),
                violation(
                        "payload.portfolioSeries[0].equity",
                        n -> at(n, "/payload/portfolioSeries/0").remove("equity")),
                violation(
                        "payload.portfolioSeries[1].drawdown",
                        n -> at(n, "/payload/portfolioSeries/1").remove("drawdown")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("progressViolations")
    @DisplayName("PROGRESS 위반은 경로를 담은 계약 오류다")
    void rejectsProgressViolation(String path, Consumer<ObjectNode> mutation) {
        ObjectNode node = fixture(MessageFixtures.PROGRESS);
        mutation.accept(node);

        assertThatThrownBy(
                        () ->
                                BacktestEventValidator.validateProgress(
                                        convert(node, MessageFixtures.PROGRESS_EVENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(path);
    }

    // ---------- FINISHED (COMPLETED) ----------

    private static final String EO = "/payload/engineOutput";

    static Stream<Arguments> completedViolations() {
        return Stream.of(
                violation("sequence", n -> n.put("sequence", 0)),
                violation("payload.engineOutput", n -> at(n, "/payload").remove("engineOutput")),
                violation("payload.engineOutput.run", n -> at(n, EO).remove("run")),
                violation(
                        "payload.engineOutput.run.status",
                        n -> at(n, EO + "/run").remove("status")),
                violation(
                        "payload.engineOutput.run.backtestId",
                        n -> at(n, EO + "/run").put("backtestId", "BT-20260907-000999")),
                violation(
                        "payload.engineOutput.run.finishedAt",
                        n -> at(n, EO + "/run").put("finishedAt", "2020-01-01T00:00:00Z")),
                violation(
                        "payload.engineOutput.portfolioSeries",
                        n -> at(n, EO).remove("portfolioSeries")),
                violation("payload.engineOutput.trades", n -> at(n, EO).remove("trades")),
                violation("payload.engineOutput.diagnostics", n -> at(n, EO).remove("diagnostics")),
                violation(
                        "payload.engineOutput.diagnostics.warnings",
                        n -> at(n, EO + "/diagnostics").remove("warnings")),
                violation(
                        "payload.engineOutput.portfolioSeries[0].cash",
                        n -> at(n, EO + "/portfolioSeries/0").remove("cash")),
                violation(
                        "payload.engineOutput.portfolioSeries[0].positions[0].quantity",
                        n ->
                                at(n, EO + "/portfolioSeries/0")
                                        .putArray("positions")
                                        .addObject()
                                        .put("instrumentId", "005930.XKRX")
                                        .put("marketValue", "1.00")),
                violation(
                        "payload.engineOutput.trades[0].entryPrice",
                        n -> at(n, EO + "/trades/0").remove("entryPrice")),
                violation(
                        "payload.engineOutput.trades[0].exitPrice",
                        n -> at(n, EO + "/trades/0").remove("exitPrice")),
                violation(
                        "payload.engineOutput.trades[0].tradeId",
                        n -> at(n, EO + "/trades/0").put("tradeId", LONG_ID)),
                violation(
                        "payload.engineOutput.diagnostics.warnings[0].code",
                        n -> at(n, EO + "/diagnostics/warnings/0").put("code", "")),
                violation(
                        "payload.engineOutput.run.error",
                        n ->
                                at(n, EO + "/run")
                                        .putObject("error")
                                        .put("code", "ENGINE_ERROR")
                                        .put("message", "boom")),
                violation("payload.engineOutput.performance", n -> at(n, EO).remove("performance")),
                violation(
                        "payload.engineOutput.performance.initialEquity",
                        n -> at(n, EO + "/performance").remove("initialEquity")),
                violation("payload.engineOutput.execution", n -> at(n, EO).remove("execution")),
                violation(
                        "payload.benchmark.instrumentId",
                        n -> at(n, "/payload/benchmark").remove("instrumentId")),
                violation(
                        "payload.benchmark.timeseries[0].equity",
                        n -> at(n, "/payload/benchmark/timeseries/0").remove("equity")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("completedViolations")
    @DisplayName("FINISHED(COMPLETED) 위반은 경로를 담은 계약 오류다")
    void rejectsCompletedViolation(String path, Consumer<ObjectNode> mutation) {
        ObjectNode node = fixture(MessageFixtures.FINISHED_COMPLETED);
        mutation.accept(node);

        assertThatThrownBy(
                        () ->
                                BacktestEventValidator.validateFinished(
                                        convert(node, MessageFixtures.FINISHED_EVENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(path);
    }

    // ---------- FINISHED (FAILED) ----------

    static Stream<Arguments> failedViolations() {
        ObjectNode completed = fixture(MessageFixtures.FINISHED_COMPLETED);
        return Stream.of(
                violation(
                        "payload.engineOutput.run.error", n -> at(n, EO + "/run").remove("error")),
                violation(
                        "payload.engineOutput.run.error.code",
                        n -> at(n, EO + "/run/error").put("code", " ")),
                violation(
                        "payload.engineOutput.run.error.code",
                        n -> at(n, EO + "/run/error").put("code", LONG_ID)),
                violation(
                        "payload.engineOutput.run.error.message",
                        n -> at(n, EO + "/run/error").remove("message")),
                violation(
                        "payload.engineOutput.performance",
                        n -> at(n, EO).set("performance", at(completed, EO + "/performance"))),
                violation(
                        "payload.engineOutput.execution",
                        n -> at(n, EO).set("execution", at(completed, EO + "/execution"))),
                violation(
                        "payload.engineOutput.trades",
                        n -> at(n, EO).set("trades", completed.at(EO + "/trades"))),
                violation(
                        "payload.engineOutput.portfolioSeries",
                        n ->
                                at(n, EO)
                                        .set(
                                                "portfolioSeries",
                                                completed.at(EO + "/portfolioSeries"))),
                violation(
                        "payload.benchmark",
                        n ->
                                at(n, "/payload")
                                        .set("benchmark", completed.at("/payload/benchmark"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("failedViolations")
    @DisplayName("FINISHED(FAILED) 위반은 경로를 담은 계약 오류다")
    void rejectsFailedViolation(String path, Consumer<ObjectNode> mutation) {
        ObjectNode node = fixture(MessageFixtures.FINISHED_FAILED);
        mutation.accept(node);

        assertThatThrownBy(
                        () ->
                                BacktestEventValidator.validateFinished(
                                        convert(node, MessageFixtures.FINISHED_EVENT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(path);
    }

    // ---------- 통과해야 하는 것 ----------

    @Test
    @DisplayName("계약 픽스처 4종은 그대로 통과한다")
    void acceptsFixtures() {
        assertThatCode(() -> BacktestEventValidator.validateStarted(MessageFixtures.started()))
                .doesNotThrowAnyException();
        assertThatCode(() -> BacktestEventValidator.validateProgress(MessageFixtures.progress()))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                BacktestEventValidator.validateFinished(
                                        MessageFixtures.finishedCompleted()))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                BacktestEventValidator.validateFinished(
                                        MessageFixtures.finishedFailed()))
                .doesNotThrowAnyException();
    }

    static Stream<Arguments> allowedOmissions() {
        return Stream.of(
                Arguments.of(
                        "성과 지표 개별 null(sharpeRatio·winRate)",
                        (Consumer<ObjectNode>)
                                n -> {
                                    at(n, EO + "/performance").remove("sharpeRatio");
                                    at(n, EO + "/performance").remove("winRate");
                                }),
                Arguments.of(
                        "benchmark 없음",
                        (Consumer<ObjectNode>) n -> at(n, "/payload").remove("benchmark")),
                Arguments.of(
                        "snapshot의 positions 없음",
                        (Consumer<ObjectNode>)
                                n -> at(n, EO + "/portfolioSeries/0").remove("positions")),
                Arguments.of(
                        "OPEN 거래의 청산 필드 없음",
                        (Consumer<ObjectNode>)
                                n -> {
                                    ObjectNode trade = at(n, EO + "/trades/0");
                                    trade.put("status", "OPEN");
                                    trade.remove("exitTime");
                                    trade.remove("exitPrice");
                                    trade.remove("grossPnl");
                                    trade.remove("realizedPnl");
                                    trade.remove("returnRate");
                                }),
                Arguments.of(
                        "ID가 정확히 255자",
                        (Consumer<ObjectNode>)
                                n -> {
                                    n.put("eventId", MAX_ID);
                                    n.put("executionId", MAX_ID);
                                    at(n, EO + "/trades/0").put("tradeId", MAX_ID);
                                }),
                Arguments.of(
                        "snapshot의 dailyReturn 없음(nullable)",
                        (Consumer<ObjectNode>)
                                n -> at(n, EO + "/portfolioSeries/0").remove("dailyReturn")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedOmissions")
    @DisplayName("계약이 nullable로 정한 생략은 통과한다")
    void acceptsAllowedOmissions(String name, Consumer<ObjectNode> mutation) {
        ObjectNode node = fixture(MessageFixtures.FINISHED_COMPLETED);
        mutation.accept(node);

        assertThatCode(
                        () ->
                                BacktestEventValidator.validateFinished(
                                        convert(node, MessageFixtures.FINISHED_EVENT)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FAILED의 error.code가 정확히 255자면 통과한다")
    void acceptsMaxLengthErrorCode() {
        ObjectNode node = fixture(MessageFixtures.FINISHED_FAILED);
        at(node, EO + "/run/error").put("code", MAX_ID);

        assertThatCode(
                        () ->
                                BacktestEventValidator.validateFinished(
                                        convert(node, MessageFixtures.FINISHED_EVENT)))
                .doesNotThrowAnyException();
    }
}
