package com.ssafy.thorp.backtest.presentation.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.BacktestResultFixtures;
import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.result.BacktestConfigResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestSummaryResult;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(BacktestController.class)
class BacktestControllerTest {

    private static final LocalDate START = LocalDate.of(2019, 1, 1);
    private static final LocalDate END = LocalDate.of(2023, 12, 31);
    private static final long DEFAULT_INITIAL_CASH = 10_000_000L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private BacktestService backtestService;

    @Test
    @DisplayName(
            "POST /backtests → 202 + { backtestId, status: QUEUED, config, summary: null }, initialCash 생략 시 기본값")
    void createReturnsAccepted() throws Exception {
        when(backtestService.create(
                        new CreateBacktestCommand("sma-cross", START, END, DEFAULT_INITIAL_CASH)))
                .thenReturn(jobResult(1L, BacktestStatus.QUEUED, DEFAULT_INITIAL_CASH, null));

        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"strategy":"sma-cross","startDate":"2019-01-01","endDate":"2023-12-31"}
                                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backtestId").value(1))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.config.strategy").value("sma-cross"))
                .andExpect(jsonPath("$.data.config.startDate").value("2019-01-01"))
                .andExpect(jsonPath("$.data.config.endDate").value("2023-12-31"))
                .andExpect(jsonPath("$.data.config.initialCash").value(10000000))
                .andExpect(jsonPath("$.data.summary").value(nullValue()))
                .andExpect(jsonPath("$.error").isEmpty());

        verify(backtestService)
                .create(new CreateBacktestCommand("sma-cross", START, END, DEFAULT_INITIAL_CASH));
    }

    @Test
    @DisplayName("POST /backtests initialCash 지정 시 그 값이 command와 응답 config에 실린다")
    void createPassesGivenInitialCash() throws Exception {
        when(backtestService.create(
                        new CreateBacktestCommand("sma-cross", START, END, 50_000_000L)))
                .thenReturn(jobResult(2L, BacktestStatus.QUEUED, 50_000_000L, null));

        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"strategy":"sma-cross","startDate":"2019-01-01","endDate":"2023-12-31","initialCash":50000000}
                                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.backtestId").value(2))
                .andExpect(jsonPath("$.data.config.initialCash").value(50000000));

        verify(backtestService)
                .create(new CreateBacktestCommand("sma-cross", START, END, 50_000_000L));
    }

    @Test
    @DisplayName("POST /backtests initialCash 0 → 400 INVALID_INPUT, 서비스 호출 없음")
    void createRejectsNonPositiveInitialCash() throws Exception {
        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"strategy":"sma-cross","startDate":"2019-01-01","endDate":"2023-12-31","initialCash":0}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("POST /backtests 필수 필드 누락 → 400, 서비스 호출 없음")
    void createRejectsInvalidBody() throws Exception {
        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"strategy\":\"\",\"startDate\":\"2019-01-01\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("POST /backtests startDate > endDate → 400")
    void createRejectsInvertedPeriod() throws Exception {
        when(backtestService.create(any()))
                .thenThrow(new IllegalArgumentException("startDate must not be after endDate"));

        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"strategy":"sma-cross","startDate":"2023-12-31","endDate":"2019-01-01"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("GET /backtests/{id} QUEUED → 200 + config, summary null")
    void getReturnsStatusWithoutSummary() throws Exception {
        when(backtestService.get(1L))
                .thenReturn(jobResult(1L, BacktestStatus.RUNNING, DEFAULT_INITIAL_CASH, null));

        mockMvc.perform(get("/backtests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backtestId").value(1))
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.config.strategy").value("sma-cross"))
                .andExpect(jsonPath("$.data.config.initialCash").value(10000000))
                .andExpect(jsonPath("$.data.summary").value(nullValue()));
    }

    @Test
    @DisplayName("GET /backtests/{id} COMPLETED → 200 + config 4필드 + summary 14필드")
    void getReturnsSummaryWhenCompleted() throws Exception {
        BacktestSummaryResult summary =
                BacktestSummaryResult.from(BacktestResultFixtures.sampleSummary());
        when(backtestService.get(1L))
                .thenReturn(jobResult(1L, BacktestStatus.COMPLETED, DEFAULT_INITIAL_CASH, summary));

        mockMvc.perform(get("/backtests/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.config.strategy").value("sma-cross"))
                .andExpect(jsonPath("$.data.config.startDate").value("2019-01-01"))
                .andExpect(jsonPath("$.data.config.endDate").value("2023-12-31"))
                .andExpect(jsonPath("$.data.config.initialCash").value(10000000))
                .andExpect(jsonPath("$.data.summary.initialEquity").value(10000000))
                .andExpect(jsonPath("$.data.summary.finalEquity").value(22480000))
                .andExpect(jsonPath("$.data.summary.totalReturn").value(1.248))
                .andExpect(jsonPath("$.data.summary.cagr").value(0.184))
                .andExpect(jsonPath("$.data.summary.maxDrawdown").value(-0.1261))
                .andExpect(jsonPath("$.data.summary.maxDrawdownPeakDate").value("2020-01-01"))
                .andExpect(jsonPath("$.data.summary.maxDrawdownTroughDate").value("2020-04-01"))
                .andExpect(jsonPath("$.data.summary.maxDrawdownRecoveryDate").value("2020-07-01"))
                .andExpect(jsonPath("$.data.summary.volatility").value(0.124))
                .andExpect(jsonPath("$.data.summary.sharpeRatio").value(1.42))
                .andExpect(jsonPath("$.data.summary.tradeCount").value(156))
                .andExpect(jsonPath("$.data.summary.winRate").value(nullValue()))
                .andExpect(jsonPath("$.data.summary.profitFactor").value(nullValue()))
                .andExpect(jsonPath("$.data.summary.metricDefinitionVersion").value("1.0"));
    }

    @Test
    @DisplayName("GET /backtests/{id} 없는 id → 404 + RESOURCE_NOT_FOUND")
    void getReturnsNotFound() throws Exception {
        when(backtestService.get(999L)).thenThrow(new BacktestNotFoundException(999L));

        mockMvc.perform(get("/backtests/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private static BacktestJobResult jobResult(
            Long id, BacktestStatus status, long initialCash, BacktestSummaryResult summary) {
        return new BacktestJobResult(
                id,
                status,
                new BacktestConfigResult("sma-cross", START, END, initialCash),
                summary);
    }
}
