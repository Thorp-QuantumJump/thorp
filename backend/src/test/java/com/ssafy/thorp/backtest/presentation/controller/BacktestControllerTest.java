package com.ssafy.thorp.backtest.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
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

    private static final String VALID_BODY =
            """
            {
              "run": { "start": "2025-01-01", "end": "2025-06-30" },
              "universe": { "instrumentIds": ["005930.XKRX", "000660.XKRX"] },
              "strategy": {
                "strategyId": "MA_CROSSOVER",
                "parameters": { "shortWindow": 20, "longWindow": 60, "threshold": 0.05 }
              },
              "portfolio": {
                "initialCash": "10000000",
                "positionSizing": { "type": "STRATEGY_DEFINED", "maxPositionPct": "0.50" }
              },
              "benchmark": { "instrumentId": "069500.XKRX" }
            }
            """;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BacktestService backtestService;

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
    @DisplayName("GET은 문자열 backtestId로 현재 상태를 돌려준다")
    void getReturnsStatus() throws Exception {
        when(backtestService.get(BACKTEST_ID))
                .thenReturn(new BacktestJobResult(BACKTEST_ID, BacktestStatus.RUNNING));

        mockMvc.perform(get("/backtests/" + BACKTEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backtestId").value(BACKTEST_ID))
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }

    @Test
    @DisplayName("없는 backtestId는 404와 RESOURCE_NOT_FOUND")
    void getReturnsNotFound() throws Exception {
        String missing = "BT-20260907-999999";
        when(backtestService.get(missing)).thenThrow(new BacktestNotFoundException(missing));

        mockMvc.perform(get("/backtests/" + missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
