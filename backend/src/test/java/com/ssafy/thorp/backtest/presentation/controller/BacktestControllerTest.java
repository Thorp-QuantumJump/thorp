package com.ssafy.thorp.backtest.presentation.controller;

import static com.ssafy.thorp.support.Requesters.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.result.BacktestCancelResult;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestExceptionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.application.service.BacktestCancelService;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestRetryService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.application.service.CostProfileService;
import com.ssafy.thorp.backtest.application.service.StrategyRulesService;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import com.ssafy.thorp.common.config.SecurityConfig;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 경계만 본다 — 본문 검증, 명령 변환, 상태 코드. 도메인 판단은 서비스 테스트가 맡는다.
 *
 * <p>요청 본문의 기준은 계약 예시 파일이다. 예시가 바뀌면 이 테스트가 먼저 깨져서 문서와 코드가 갈라지지 않는다.
 */
@WebMvcTest(BacktestController.class)
@Import({SecurityConfig.class, CurrentRequester.class})
class BacktestControllerTest {

    private static final String BACKTEST_ID = "BT-20260911-000001";
    private static final long USER = 7;
    private static final String PUBLIC_TOKEN = "0123456789abcdef0123456789abcdef";

    /** gradle test의 작업 디렉터리는 backend/다. */
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
    @MockitoBean private BacktestCancelService cancelService;
    @MockitoBean private BacktestRetryService retryService;
    @MockitoBean private StrategyRulesService strategyRulesService;
    @MockitoBean private CostProfileService costProfileService;

    private static BacktestJobResult accepted() {
        return new BacktestJobResult(
                BACKTEST_ID,
                RunStatus.QUEUED,
                ResultSource.NEW,
                LocalDate.of(2023, 7, 3),
                LocalDate.of(2024, 12, 31),
                Map.of("rules", "1"),
                null,
                PUBLIC_TOKEN);
    }

    private org.springframework.test.web.servlet.ResultActions postBody(String body)
            throws Exception {
        return mockMvc.perform(
                post("/backtests")
                        .with(csrf())
                        .with(as(USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    @Test
    @DisplayName("계약 예시를 보내면 202와 식별자·결과 출처·실효 기간이 돌아온다")
    void acceptsContractExample() throws Exception {
        when(backtestService.create(any())).thenReturn(accepted());

        postBody(VALID_BODY)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.backtestId").value(BACKTEST_ID))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.resultSource").value("NEW"))
                .andExpect(jsonPath("$.data.effectiveStart").value("2023-07-03"))
                .andExpect(jsonPath("$.data.versions.rules").value("1"))
                .andExpect(jsonPath("$.data.publicToken").value(PUBLIC_TOKEN));
    }

    @Test
    @DisplayName("중첩 본문을 유스케이스 입력으로 평평하게 옮긴다")
    void mapsRequestToCommand() throws Exception {
        when(backtestService.create(any())).thenReturn(accepted());

        postBody(VALID_BODY).andExpect(status().isAccepted());

        ArgumentCaptor<CreateBacktestCommand> captor =
                ArgumentCaptor.forClass(CreateBacktestCommand.class);
        verify(backtestService).create(captor.capture());
        CreateBacktestCommand command = captor.getValue();

        assertThat(command.requesterId()).isEqualTo(7L);
        assertThat(command.executionType()).isEqualTo(ExecutionType.SINGLE);
        assertThat(command.mappingVersion()).isEqualTo("SECTOR-ETF-2026-09");
        assertThat(command.instrumentIds())
                .containsExactly("091160.XKRX", "305720.XKRX", "244580.XKRX");
        assertThat(command.items().get(0).targetWeight()).isEqualByComparingTo("0.4000");
        assertThat(command.strategyId()).isEqualTo("FIXED_WEIGHT");
        assertThat(command.rulesVersion()).isEqualTo("1");
        assertThat(command.start()).isEqualTo(LocalDate.of(2023, 7, 3));
        assertThat(command.initialCash()).isEqualByComparingTo(new BigDecimal("10000000.00"));
        assertThat(command.costProfileVersion()).as("null = 서버 기본값").isNull();
        assertThat(command.warmupTradingDays()).isNull();
        assertThat(command.scenario()).isEqualTo(Scenario.FULL_PERIOD);
    }

    @Test
    @DisplayName("scenario 를 생략하면 주 분석으로 본다")
    void missingScenarioDefaultsToFullPeriod() throws Exception {
        when(backtestService.create(any())).thenReturn(accepted());

        postBody(VALID_BODY.replace("\"scenario\": \"FULL_PERIOD\",", ""))
                .andExpect(status().isAccepted());

        ArgumentCaptor<CreateBacktestCommand> captor =
                ArgumentCaptor.forClass(CreateBacktestCommand.class);
        verify(backtestService).create(captor.capture());
        assertThat(captor.getValue().scenario()).isEqualTo(Scenario.FULL_PERIOD);
    }

    @Test
    @DisplayName("모르는 scenario 이름은 400이다 — 500으로 흘리지 않는다")
    void unknownScenarioIsBadRequest() throws Exception {
        postBody(VALID_BODY.replace("FULL_PERIOD", "SOMETHING_ELSE"))
                .andExpect(status().isBadRequest());

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("로그인하지 않아도 접수된다 — 요청자 없이 넘기고 결과는 공개 토큰으로 연다(spec 005 B01)")
    void anonymousRequestIsAcceptedWithoutRequester() throws Exception {
        when(backtestService.create(any())).thenReturn(accepted());

        mockMvc.perform(
                        post("/backtests")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.publicToken").value(PUBLIC_TOKEN));

        ArgumentCaptor<CreateBacktestCommand> captor =
                ArgumentCaptor.forClass(CreateBacktestCommand.class);
        verify(backtestService).create(captor.capture());
        assertThat(captor.getValue().requesterId()).isNull();
    }

    @Test
    @DisplayName("비로그인 접수도 CSRF 토큰이 없으면 403 이다 — 공개로 연 것은 인증이지 쿠키 규약이 아니다")
    void anonymousRequestStillNeedsCsrf() throws Exception {
        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                .andExpect(status().isForbidden());

        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("필수 블록이 빠지면 400이다")
    void missingBlocksAreRejected() throws Exception {
        postBody("{}").andExpect(status().isBadRequest());
        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("종목 형식이 어긋나면 400이다")
    void malformedInstrumentIsRejected() throws Exception {
        postBody(VALID_BODY.replace("091160.XKRX", "091160.KS")).andExpect(status().isBadRequest());
        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("시작 금액이 0 이하면 400이다")
    void nonPositiveCashIsRejected() throws Exception {
        postBody(VALID_BODY.replace("\"10000000.00\"", "\"0\"")).andExpect(status().isBadRequest());
        verify(backtestService, never()).create(any());
    }

    @Test
    @DisplayName("워밍업 거래일 수가 음수면 400이다")
    void negativeWarmupIsRejected() throws Exception {
        postBody(VALID_BODY.replace("\"warmupTradingDays\": null", "\"warmupTradingDays\": -1"))
                .andExpect(status().isBadRequest());
        verify(backtestService, never()).create(any());
    }

    // --- 조회 ---------------------------------------------------------------

    private static BacktestDetailResult detail(RunStatus status) {
        return new BacktestDetailResult(
                BACKTEST_ID,
                status,
                ResultSource.NEW,
                false,
                false,
                new BacktestDetailResult.Progress(
                        ProgressStage.COMPUTING, LocalDate.of(2024, 6, 28), 120, 360),
                Instant.parse("2026-09-11T04:00:00Z"),
                Instant.parse("2026-09-11T04:00:04Z"),
                null,
                4L,
                Map.of("strategyId", "FIXED_WEIGHT"),
                Map.of("rules", "1"),
                null,
                null,
                null);
    }

    @Test
    @DisplayName("상태 조회는 진행·대기 시간·버전을 담아 200을 돌려준다")
    void getDetail() throws Exception {
        when(queryService.getDetail(eq(BACKTEST_ID), anyLong()))
                .thenReturn(detail(RunStatus.RUNNING));

        mockMvc.perform(get("/backtests/{id}", BACKTEST_ID).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.partial").value(false))
                .andExpect(jsonPath("$.data.progress.stage").value("COMPUTING"))
                .andExpect(jsonPath("$.data.progress.lastTradingDate").value("2024-06-28"))
                .andExpect(jsonPath("$.data.queueWaitSeconds").value(4))
                .andExpect(jsonPath("$.data.versions.rules").value("1"));
    }

    @Test
    @DisplayName("남의 요청과 없는 식별자는 똑같이 404다")
    void unknownOrForeignBacktestIsNotFound() throws Exception {
        when(queryService.getDetail(eq(BACKTEST_ID), anyLong()))
                .thenThrow(new BacktestNotFoundException(BACKTEST_ID));

        mockMvc.perform(get("/backtests/{id}", BACKTEST_ID).with(as(USER)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("하위 리소스도 로그인하지 않으면 조회되지 않는다 — 상세만 막으면 우회 경로가 남는다")
    void subResourcesRequireRequester() throws Exception {
        for (String path :
                List.of(
                        "/timeseries",
                        "/trades",
                        "/positions?date=2024-06-28",
                        "/weights",
                        "/exceptions")) {
            mockMvc.perform(get("/backtests/" + BACKTEST_ID + path))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("비중 기록은 리밸런싱 포인트·비중 출처·partial 을 내려준다 — 없는 선택 항목은 키를 생략한다")
    void weightsEndpoint() throws Exception {
        when(queryService.getWeights(eq(BACKTEST_ID), anyLong()))
                .thenReturn(
                        new BacktestWeightsResult(
                                BACKTEST_ID,
                                false,
                                WeightsSource.USER,
                                List.of(
                                        new EngineOutput.WeightsPoint(
                                                LocalDate.of(2024, 1, 3),
                                                Map.of("091160.XKRX", new BigDecimal("0.4000")),
                                                Map.of("091160.XKRX", new BigDecimal("0.3998")),
                                                new BigDecimal("0.0002"),
                                                null,
                                                null))));

        mockMvc.perform(get("/backtests/{id}/weights", BACKTEST_ID).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weightsSource").value("USER"))
                .andExpect(jsonPath("$.data.partial").value(false))
                .andExpect(jsonPath("$.data.points[0].target['091160.XKRX']").value("0.4000"))
                .andExpect(jsonPath("$.data.points[0].weightError").value("0.0002"))
                .andExpect(jsonPath("$.data.points[0].riskScale").doesNotExist());
    }

    @Test
    @DisplayName("예외 기록은 항목 그대로 내려준다")
    void exceptionsEndpoint() throws Exception {
        when(queryService.getExceptions(eq(BACKTEST_ID), anyLong()))
                .thenReturn(
                        new BacktestExceptionsResult(
                                BACKTEST_ID,
                                true,
                                List.of(
                                        new EngineOutput.ExceptionRecord(
                                                LocalDate.of(2024, 6, 11),
                                                "091160.XKRX",
                                                "HALT_DEFERRED",
                                                "DEFER_TO_NEXT_TRADABLE",
                                                "planned 2024-06-11 -> next tradable day"))));

        mockMvc.perform(get("/backtests/{id}/exceptions", BACKTEST_ID).with(as(USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.partial").value(true))
                .andExpect(jsonPath("$.data.items[0].kind").value("HALT_DEFERRED"))
                .andExpect(jsonPath("$.data.items[0].instrumentId").value("091160.XKRX"));
    }

    @Test
    @DisplayName("취소는 202 와 {backtestId, status, cancelRequested} 를 돌려준다")
    void cancelAccepted() throws Exception {
        when(cancelService.cancel(eq(BACKTEST_ID), anyLong()))
                .thenReturn(new BacktestCancelResult(BACKTEST_ID, RunStatus.CANCELLED, true));

        mockMvc.perform(post("/backtests/{id}/cancel", BACKTEST_ID).with(csrf()).with(as(USER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.backtestId").value(BACKTEST_ID))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.cancelRequested").value(true));
    }

    @Test
    @DisplayName("끝난 분석의 취소는 409 NOT_CANCELLABLE 이고 로그인하지 않으면 401 이다")
    void cancelRejections() throws Exception {
        when(cancelService.cancel(eq(BACKTEST_ID), anyLong()))
                .thenThrow(new BusinessException(ErrorCode.NOT_CANCELLABLE));

        mockMvc.perform(post("/backtests/{id}/cancel", BACKTEST_ID).with(csrf()).with(as(USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_CANCELLABLE"));
        mockMvc.perform(post("/backtests/{id}/cancel", BACKTEST_ID).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("결과가 준비되지 않은 하위 리소스는 409다")
    void subResourceNotReady() throws Exception {
        when(queryService.getTimeseries(eq(BACKTEST_ID), anyLong()))
                .thenThrow(new BacktestResultNotReadyException(BACKTEST_ID, RunStatus.RUNNING));

        mockMvc.perform(get("/backtests/{id}/timeseries", BACKTEST_ID).with(as(USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
    }

    @Test
    @DisplayName("실패한 백테스트의 하위 리소스는 409 BACKTEST_FAILED다")
    void subResourceFailed() throws Exception {
        when(queryService.getTrades(eq(BACKTEST_ID), anyLong(), any()))
                .thenThrow(new BacktestFailedException(BACKTEST_ID));

        mockMvc.perform(get("/backtests/{id}/trades", BACKTEST_ID).with(as(USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BACKTEST_FAILED"));
    }

    @Test
    @DisplayName("POST /backtests/{id}/retry 는 202 와 retryOf 를 돌려준다")
    void retryReturnsAccepted() throws Exception {
        when(retryService.retry(BACKTEST_ID, USER)).thenReturn(accepted().withRetryOf(BACKTEST_ID));

        mockMvc.perform(post("/backtests/{id}/retry", BACKTEST_ID).with(csrf()).with(as(USER)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.backtestId").value(BACKTEST_ID))
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.resultSource").value("NEW"))
                .andExpect(jsonPath("$.data.retryOf").value(BACKTEST_ID))
                .andExpect(jsonPath("$.data.publicToken").value(PUBLIC_TOKEN));
    }

    @Test
    @DisplayName("재시도는 여전히 소유자 경로다 — 로그인하지 않으면 401")
    void anonymousRetryIsRejected() throws Exception {
        mockMvc.perform(post("/backtests/{id}/retry", BACKTEST_ID).with(csrf()))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(retryService);
    }

    @Test
    @DisplayName("거래 내역의 from·to 를 조회 조건으로 옮긴다(spec 005 T015)")
    void tradesPeriodIsMappedToQuery() throws Exception {
        when(queryService.getTrades(eq(BACKTEST_ID), anyLong(), any()))
                .thenThrow(new BacktestFailedException(BACKTEST_ID));

        mockMvc.perform(
                get("/backtests/{id}/trades", BACKTEST_ID)
                        .param("from", "2024-03-01")
                        .param("to", "2024-03-31")
                        .with(as(USER)));

        ArgumentCaptor<FindBacktestTradesQuery> captor =
                ArgumentCaptor.forClass(FindBacktestTradesQuery.class);
        verify(queryService).getTrades(eq(BACKTEST_ID), eq(USER), captor.capture());
        assertThat(captor.getValue().from()).isEqualTo(LocalDate.of(2024, 3, 1));
        assertThat(captor.getValue().to()).isEqualTo(LocalDate.of(2024, 3, 31));
    }

    @Test
    @DisplayName("from 이 to 보다 늦으면 400 이다 — 빈 페이지로 돌려주면 그 기간에 거래가 없다는 거짓이 된다")
    void reversedTradesPeriodIsBadRequest() throws Exception {
        mockMvc.perform(
                        get("/backtests/{id}/trades", BACKTEST_ID)
                                .param("from", "2024-04-01")
                                .param("to", "2024-03-31")
                                .with(as(USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT"));

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("from 날짜 형식이 틀리면 400 이다")
    void malformedTradesPeriodIsBadRequest() throws Exception {
        mockMvc.perform(
                        get("/backtests/{id}/trades", BACKTEST_ID)
                                .param("from", "2024/04/01")
                                .with(as(USER)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("재시도할 수 없는 상태면 409 NOT_RETRYABLE 을 그대로 전한다")
    void retryConflictIsPropagated() throws Exception {
        when(retryService.retry(BACKTEST_ID, USER))
                .thenThrow(new BusinessException(ErrorCode.NOT_RETRYABLE, "아직 돌고 있습니다"));

        mockMvc.perform(post("/backtests/{id}/retry", BACKTEST_ID).with(csrf()).with(as(USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NOT_RETRYABLE"));
    }
}
