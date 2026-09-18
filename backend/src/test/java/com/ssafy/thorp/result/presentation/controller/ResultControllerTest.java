package com.ssafy.thorp.result.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.result.BacktestExceptionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import com.ssafy.thorp.result.application.result.PublicResultDetail;
import com.ssafy.thorp.result.application.result.PublicResultIndexComparison;
import com.ssafy.thorp.result.application.result.PublicResultRetry;
import com.ssafy.thorp.result.application.result.ValidationTier;
import com.ssafy.thorp.result.application.service.PublicResultService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 경계만 본다(spec 005 T024) — 일곱 경로의 상태 코드, 거래 내역 상한, 익명 통과. 토큰 판정과 필드 고르기는 {@code
 * PublicResultServiceTest}가 맡는다.
 */
@WebMvcTest(ResultController.class)
@Import({SecurityConfig.class, CurrentRequester.class})
@TestPropertySource(properties = "thorp.result.trades-page-size=" + ResultControllerTest.PAGE_CAP)
class ResultControllerTest {

    static final int PAGE_CAP = 50;
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PublicResultService service;

    private static PublicResultDetail detail() {
        return new PublicResultDetail(
                TOKEN,
                RunStatus.COMPLETED,
                ResultSource.NEW,
                false,
                false,
                Instant.parse("2026-09-23T04:11:00Z"),
                null,
                Instant.parse("2026-09-16T04:11:00Z"),
                null,
                null,
                null,
                Map.of("strategyId", "EQUAL_WEIGHT"),
                Map.of("rules", "1"),
                null,
                null,
                null,
                ValidationTier.UNVALIDATED,
                "똑같이 나누기",
                WeightsSource.USER,
                List.of());
    }

    @Test
    @DisplayName("로그인하지 않아도 일곱 GET 경로가 200 이고 backtestId 키가 없다")
    void anonymousGetsPass() throws Exception {
        when(service.getDetail(TOKEN)).thenReturn(detail());
        when(service.getTimeseries(TOKEN))
                .thenReturn(new BacktestTimeseriesResult("BT-1", false, List.of()));
        when(service.getTrades(eq(TOKEN), any()))
                .thenReturn(
                        new BacktestTradePageResult("BT-1", false, 0, PAGE_CAP, 0, 0, List.of()));
        when(service.getPositions(TOKEN, LocalDate.of(2025, 1, 22)))
                .thenReturn(
                        new BacktestPositionsResult(
                                "BT-1",
                                false,
                                LocalDate.of(2025, 1, 22),
                                LocalDate.of(2025, 1, 21),
                                List.of()));
        when(service.getWeights(TOKEN))
                .thenReturn(
                        new BacktestWeightsResult("BT-1", false, WeightsSource.USER, List.of()));
        when(service.getExceptions(TOKEN))
                .thenReturn(new BacktestExceptionsResult("BT-1", false, List.of()));
        when(service.getIndexComparison(TOKEN))
                .thenReturn(PublicResultIndexComparison.unavailable("INDEX_SERIES_UNAVAILABLE"));

        mockMvc.perform(get("/results/" + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publicToken").value(TOKEN))
                .andExpect(jsonPath("$.data.validationTier").value("UNVALIDATED"))
                .andExpect(jsonPath("$.data.weightsSource").value("USER"))
                .andExpect(jsonPath("$.data.backtestId").doesNotExist());
        for (String path :
                List.of(
                        "/timeseries",
                        "/trades",
                        "/positions?date=2025-01-22",
                        "/weights",
                        "/exceptions")) {
            mockMvc.perform(get("/results/" + TOKEN + path))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.backtestId").doesNotExist())
                    .andExpect(jsonPath("$.data.partial").value(false));
        }
        mockMvc.perform(get("/results/" + TOKEN + "/index-comparison"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.backtestId").doesNotExist())
                .andExpect(jsonPath("$.data.available").value(false))
                .andExpect(jsonPath("$.data.reason").value("INDEX_SERIES_UNAVAILABLE"))
                .andExpect(jsonPath("$.data.series").isEmpty());
    }

    @Test
    @DisplayName("서비스의 400·404·410·409 가 그대로 상태 코드가 된다")
    void serviceErrorsMapToStatus() throws Exception {
        when(service.getDetail("bad")).thenThrow(new BusinessException(ErrorCode.INVALID_INPUT));
        when(service.getDetail(TOKEN)).thenThrow(new BusinessException(ErrorCode.RESULT_EXPIRED));
        when(service.getWeights(TOKEN))
                .thenThrow(new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        when(service.getTimeseries(TOKEN))
                .thenThrow(new BusinessException(ErrorCode.RESULT_NOT_READY));

        mockMvc.perform(get("/results/bad")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/results/" + TOKEN))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("RESULT_EXPIRED"));
        mockMvc.perform(get("/results/" + TOKEN + "/weights")).andExpect(status().isNotFound());
        mockMvc.perform(get("/results/" + TOKEN + "/timeseries")).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("거래 내역 size 는 생략하면 상한, 넘으면(004 최댓값 1000 초과 포함) 상한으로 잘리고 작으면 그대로다")
    void tradesSizeIsCappedAtConfiguredLimit() throws Exception {
        when(service.getTrades(eq(TOKEN), any()))
                .thenReturn(
                        new BacktestTradePageResult("BT-1", false, 0, PAGE_CAP, 0, 0, List.of()));
        ArgumentCaptor<FindBacktestTradesQuery> captor =
                ArgumentCaptor.forClass(FindBacktestTradesQuery.class);

        mockMvc.perform(get("/results/" + TOKEN + "/trades")).andExpect(status().isOk());
        // 004 DTO 의 최댓값(1000)도 넘는 값이다. 400 이 아니라 상한으로 잘려야 한다.
        mockMvc.perform(get("/results/" + TOKEN + "/trades?size=5000")).andExpect(status().isOk());
        mockMvc.perform(
                        get(
                                "/results/"
                                        + TOKEN
                                        + "/trades?size=10&page=2&from=2025-01-01&to=2025-02-01"))
                .andExpect(status().isOk());

        verify(service, org.mockito.Mockito.times(3)).getTrades(eq(TOKEN), captor.capture());
        assertThat(captor.getAllValues())
                .extracting(FindBacktestTradesQuery::size)
                .containsExactly(PAGE_CAP, PAGE_CAP, 10);
        FindBacktestTradesQuery last = captor.getAllValues().get(2);
        assertThat(last.page()).isEqualTo(2);
        assertThat(last.from()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(last.to()).isEqualTo(LocalDate.of(2025, 2, 1));
    }

    @Test
    @DisplayName("거래 내역의 뒤집힌 기간과 잘못된 종목 형식은 400 이고 서비스까지 가지 않는다")
    void invalidTradeQueryIsRejected() throws Exception {
        mockMvc.perform(get("/results/" + TOKEN + "/trades?from=2025-02-01&to=2025-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));
        mockMvc.perform(get("/results/" + TOKEN + "/trades?instrumentId=abc"))
                .andExpect(status().isBadRequest());

        verify(service, never()).getTrades(any(), any());
    }

    @Test
    @DisplayName("보유 조회에 date 가 없으면 400 이다")
    void positionsRequireDate() throws Exception {
        mockMvc.perform(get("/results/" + TOKEN + "/positions")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비로그인 재시도는 202 이고 요청자 없이 서비스에 넘긴다. CSRF 없이는 막힌다")
    void anonymousRetry() throws Exception {
        when(service.retry(TOKEN, null))
                .thenReturn(
                        new PublicResultRetry(
                                "fedcba9876543210fedcba9876543210",
                                RunStatus.QUEUED,
                                ResultSource.JOINED,
                                TOKEN));

        mockMvc.perform(post("/results/" + TOKEN + "/retry").with(csrf()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.publicToken").value("fedcba9876543210fedcba9876543210"))
                .andExpect(jsonPath("$.data.retryOfToken").value(TOKEN))
                .andExpect(jsonPath("$.data.resultSource").value("JOINED"))
                .andExpect(jsonPath("$.data.backtestId").doesNotExist());
        mockMvc.perform(post("/results/" + TOKEN + "/retry")).andExpect(status().isForbidden());

        verify(service, org.mockito.Mockito.times(1)).retry(TOKEN, null);
    }
}
