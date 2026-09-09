package com.ssafy.thorp.backtest.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.exception.BacktestPublishFailedException;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BacktestController.class)
class BacktestControllerTest {

    private static final String BACKTEST_ID = "BT-20260907-000001";

    /** 계약 예시가 요청 본문의 기준이다. gradle test의 작업 디렉터리는 backend/다. */
    private static final Path REQUEST_FIXTURE =
            Path.of("..", "docs", "specs", "backtest-api", "examples", "backtest-request.json");

    private static final String VALID_BODY = readFixture();

    private static String readFixture() {
        try {
            return Files.readString(REQUEST_FIXTURE);
        } catch (IOException e) {
            throw new UncheckedIOException("계약 예시를 읽을 수 없습니다: " + REQUEST_FIXTURE, e);
        }
    }

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BacktestService backtestService;
    @MockitoBean private BacktestResultQueryService queryService;

    private void expectAccepted(String body) throws Exception {
        when(backtestService.create(any()))
                .thenReturn(new BacktestJobResult(BACKTEST_ID, BacktestStatus.QUEUED));
        mockMvc.perform(post("/backtests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
    }

    private void expectBadRequest(String body) throws Exception {
        mockMvc.perform(post("/backtests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    private CreateBacktestCommand capturedCommand() {
        ArgumentCaptor<CreateBacktestCommand> captor =
                ArgumentCaptor.forClass(CreateBacktestCommand.class);
        verify(backtestService).create(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("POST /backtests는 202와 backtestId·QUEUED를 돌려준다")
    void createReturnsAccepted() throws Exception {
        when(backtestService.create(any()))
                .thenReturn(new BacktestJobResult(BACKTEST_ID, BacktestStatus.QUEUED));

        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backtestId").value(BACKTEST_ID))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    @Test
    @DisplayName("중첩 요청을 평평한 커맨드로 옮긴다")
    void createFlattensRequestIntoCommand() throws Exception {
        expectAccepted(VALID_BODY);

        CreateBacktestCommand command = capturedCommand();
        assertThat(command.strategyId()).isEqualTo("MA_CROSSOVER");
        assertThat(command.instrumentIds()).containsExactly("005930.XKRX", "000660.XKRX");
        assertThat(command.parameters()).containsEntry("shortWindow", 20);
        assertThat(command.start()).isEqualTo("2025-01-01");
        assertThat(command.end()).isEqualTo("2025-06-30");
        assertThat(command.initialCash()).isEqualByComparingTo("10000000");
        assertThat(command.maxPositionPct()).isEqualByComparingTo("0.50");
        assertThat(command.benchmarkInstrumentId()).isEqualTo("069500.XKRX");
    }

    @Test
    @DisplayName("positionSizing을 생략하면 STRATEGY_DEFINED와 제한 없음으로 채운다")
    void createFillsPositionSizingDefault() throws Exception {
        expectAccepted(
                """
                {
                  "run": { "start": "2025-01-01", "end": "2025-06-30" },
                  "universe": { "instrumentIds": ["005930.XKRX"] },
                  "strategy": { "strategyId": "MA_CROSSOVER", "parameters": {} },
                  "portfolio": { "initialCash": "10000000" }
                }
                """);

        CreateBacktestCommand command = capturedCommand();
        assertThat(command.positionSizingType()).isEqualTo(PositionSizingMode.STRATEGY_DEFINED);
        assertThat(command.maxPositionPct()).isNull();
        assertThat(command.benchmarkInstrumentId()).isNull();
    }

    @Test
    @DisplayName("필수 블록이 빠지면 400이고 서비스를 부르지 않는다")
    void createRejectsMissingBlocks() throws Exception {
        expectBadRequest(
                """
                { "run": { "start": "2025-01-01", "end": "2025-06-30" } }
                """);

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("종목 형식 위반과 빈 종목 목록은 400")
    void createRejectsInvalidInstruments() throws Exception {
        expectBadRequest(VALID_BODY.replace("005930.XKRX", "005930"));
        expectBadRequest(
                """
                {
                  "run": { "start": "2025-01-01", "end": "2025-06-30" },
                  "universe": { "instrumentIds": [] },
                  "strategy": { "strategyId": "MA_CROSSOVER", "parameters": {} },
                  "portfolio": { "initialCash": "10000000" }
                }
                """);

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("maxPositionPct가 0이거나 1을 넘으면 400")
    void createRejectsMaxPositionPctOutOfRange() throws Exception {
        expectBadRequest(VALID_BODY.replace("0.50", "0"));
        expectBadRequest(VALID_BODY.replace("0.50", "1.01"));

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("저장 컬럼보다 세밀한 소수는 400")
    void createRejectsTooPreciseDecimals() throws Exception {
        expectBadRequest(VALID_BODY.replace("10000000", "0.001"));
        expectBadRequest(VALID_BODY.replace("0.50", "0.00001"));

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("strategyId가 64자를 넘으면 400, 64자는 통과")
    void createChecksStrategyIdLength() throws Exception {
        expectBadRequest(
                VALID_BODY.replace(
                        "MA_CROSSOVER", "A".repeat(BacktestJob.MAX_STRATEGY_ID_LENGTH + 1)));
        verify(backtestService, never()).create(any());

        expectAccepted(
                VALID_BODY.replace("MA_CROSSOVER", "A".repeat(BacktestJob.MAX_STRATEGY_ID_LENGTH)));
    }

    @Test
    @DisplayName("parameters 값이 null이면 500이 아니라 400")
    void createRejectsNullParameterValue() throws Exception {
        when(backtestService.create(any()))
                .thenThrow(new IllegalArgumentException("parameters must not contain null values"));

        expectBadRequest(VALID_BODY.replace("20, ", "null, "));
    }

    @Test
    @DisplayName("initialCash가 0 이하면 400")
    void createRejectsNonPositiveCash() throws Exception {
        expectBadRequest(VALID_BODY.replace("10000000", "0"));

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("start가 end보다 늦으면 도메인이 거부해 400")
    void createRejectsInvertedPeriod() throws Exception {
        when(backtestService.create(any()))
                .thenThrow(new IllegalArgumentException("startDate must not be after endDate"));

        expectBadRequest(VALID_BODY.replace("2025-01-01", "2025-12-31"));
    }

    @Test
    @DisplayName("발행 실패는 503 BROKER_UNAVAILABLE로 응답한다")
    void createReturnsServiceUnavailableWhenBrokerDown() throws Exception {
        when(backtestService.create(any()))
                .thenThrow(
                        new BacktestPublishFailedException(
                                BACKTEST_ID, new RuntimeException("broker down")));

        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("BROKER_UNAVAILABLE"));
    }

    // ---------- GET /backtests/{backtestId} ----------

    private static final BacktestDetailResult.Config CONFIG =
            new BacktestDetailResult.Config(
                    "MA_CROSSOVER",
                    "1.0.0",
                    Map.of("shortWindow", 20),
                    List.of("005930.XKRX"),
                    LocalDate.of(2025, 1, 1),
                    LocalDate.of(2025, 6, 30),
                    new BigDecimal("10000000.00"),
                    "KRW",
                    "069500.XKRX");

    private static BacktestDetailResult detail(
            BacktestStatus status,
            BacktestDetailResult.Summary summary,
            BacktestDetailResult.Error error) {
        return new BacktestDetailResult(
                BACKTEST_ID,
                status,
                CONFIG,
                summary,
                error,
                status.isFinal() ? Instant.parse("2026-09-07T01:00:00Z") : null,
                status.isFinal() ? Instant.parse("2026-09-07T01:00:12Z") : null);
    }

    @Test
    @DisplayName("QUEUED·RUNNING은 config만 있고 summary·error는 null이다")
    void getReturnsConfigOnlyBeforeResult() throws Exception {
        when(queryService.getDetail(BACKTEST_ID))
                .thenReturn(detail(BacktestStatus.RUNNING, null, null));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backtestId").value(BACKTEST_ID))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.config.strategyVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.summary").value(nullValue()))
                .andExpect(jsonPath("$.data.error").value(nullValue()))
                .andExpect(jsonPath("$.data.startedAt").value(nullValue()));
    }

    @Test
    @DisplayName("COMPLETED는 summary를 담고 decimal은 전부 문자열이다")
    void getReturnsSummaryWithStringDecimals() throws Exception {
        BacktestDetailResult.Summary summary =
                new BacktestDetailResult.Summary(
                        new BigDecimal("10000000.00"),
                        new BigDecimal("11666521.51"),
                        "KRW",
                        new BigDecimal("0.166652"),
                        new BigDecimal("0.366917"),
                        new BigDecimal("-0.041757"),
                        new BigDecimal("2.342630"),
                        new BigDecimal("0.132307"),
                        2,
                        new BigDecimal("0.500000"),
                        new BigDecimal("3941.19"),
                        new BigDecimal("13137.30"),
                        new BacktestDetailResult.Benchmark(
                                "069500.XKRX", new BigDecimal("0.053292")));
        when(queryService.getDetail(BACKTEST_ID))
                .thenReturn(detail(BacktestStatus.COMPLETED, summary, null));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                // 숫자로 나오면 계약 위반이다. value(String)은 타입까지 본다
                .andExpect(jsonPath("$.data.summary.totalReturn").value("0.166652"))
                .andExpect(jsonPath("$.data.summary.initialEquity").value("10000000.00"))
                .andExpect(jsonPath("$.data.config.initialCash").value("10000000.00"))
                .andExpect(jsonPath("$.data.summary.benchmark.totalReturn").value("0.053292"))
                // tradeCount는 계약상 정수다
                .andExpect(jsonPath("$.data.summary.tradeCount").value(2))
                .andExpect(jsonPath("$.data.error").value(nullValue()));
    }

    @Test
    @DisplayName("FAILED도 200이고 error를 담는다. summary는 null")
    void getReturnsOkWhenBacktestFailed() throws Exception {
        when(queryService.getDetail(BACKTEST_ID))
                .thenReturn(
                        detail(
                                BacktestStatus.FAILED,
                                null,
                                new BacktestDetailResult.Error("NO_MARKET_DATA", "no data")));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.error.code").value("NO_MARKET_DATA"))
                .andExpect(jsonPath("$.data.summary").value(nullValue()));
    }

    @Test
    @DisplayName("없는 backtestId는 404와 RESOURCE_NOT_FOUND")
    void getReturnsNotFound() throws Exception {
        String missing = "BT-20260907-999999";
        when(queryService.getDetail(missing)).thenThrow(new BacktestNotFoundException(missing));

        mockMvc.perform(get("/backtests/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ---------- GET /backtests/{backtestId}/timeseries ----------

    @Test
    @DisplayName("시계열은 points를 담고 benchmarkReturn이 없으면 키를 생략한다")
    void getTimeseriesOmitsBenchmarkReturnKey() throws Exception {
        when(queryService.getTimeseries(BACKTEST_ID))
                .thenReturn(
                        new BacktestTimeseriesResult(
                                BACKTEST_ID,
                                List.of(
                                        new BacktestTimeseriesResult.Point(
                                                LocalDate.of(2025, 1, 2),
                                                new BigDecimal("10000000.00"),
                                                new BigDecimal("0.000000"),
                                                new BigDecimal("0.000000"),
                                                new BigDecimal("0.000000")),
                                        new BacktestTimeseriesResult.Point(
                                                LocalDate.of(2025, 1, 3),
                                                new BigDecimal("10100000.00"),
                                                new BigDecimal("0.010000"),
                                                new BigDecimal("0.000000"),
                                                null))));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID + "/timeseries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.points.length()").value(2))
                .andExpect(jsonPath("$.data.points[0].equity").value("10000000.00"))
                .andExpect(jsonPath("$.data.points[0].benchmarkReturn").value("0.000000"))
                // 벤치마크가 없는 포인트는 키 자체가 없다 (null이 아니다)
                .andExpect(jsonPath("$.data.points[1].benchmarkReturn").doesNotExist());
    }

    @Test
    @DisplayName("결과 전이면 409 RESULT_NOT_READY (폴링 계속)")
    void getTimeseriesReturnsConflictWhenNotReady() throws Exception {
        when(queryService.getTimeseries(BACKTEST_ID))
                .thenThrow(
                        new BacktestResultNotReadyException(BACKTEST_ID, BacktestStatus.RUNNING));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID + "/timeseries"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
    }

    @Test
    @DisplayName("FAILED면 409 BACKTEST_FAILED (폴링 중단)")
    void getTimeseriesReturnsConflictWhenFailed() throws Exception {
        when(queryService.getTimeseries(BACKTEST_ID))
                .thenThrow(new BacktestFailedException(BACKTEST_ID));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID + "/timeseries"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BACKTEST_FAILED"));
    }

    @Test
    @DisplayName("최종 상태인데 결과 행이 없으면 500이다 — RESULT_NOT_READY로 감추지 않는다")
    void getReturnsServerErrorOnMissingResultRow() throws Exception {
        when(queryService.getDetail(BACKTEST_ID))
                .thenThrow(new IllegalStateException("결과 행이 없습니다"));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }
}
