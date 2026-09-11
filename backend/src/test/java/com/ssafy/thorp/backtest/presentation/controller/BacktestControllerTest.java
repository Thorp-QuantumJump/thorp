package com.ssafy.thorp.backtest.presentation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ssafy.thorp.backtest.application.command.CreateBacktestCommand;
import com.ssafy.thorp.backtest.application.port.message.Scenario;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestJobResult;
import com.ssafy.thorp.backtest.application.service.BacktestResultQueryService;
import com.ssafy.thorp.backtest.application.service.BacktestService;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.presentation.support.CurrentRequester;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 경계만 본다 — 본문 검증, 명령 변환, 상태 코드. 도메인 판단은 서비스 테스트가 맡는다.
 *
 * <p>요청 본문의 기준은 계약 예시 파일이다. 예시가 바뀌면 이 테스트가 먼저 깨져서 문서와 코드가 갈라지지 않는다.
 */
@WebMvcTest(BacktestController.class)
@Import(CurrentRequester.class)
@TestPropertySource(properties = "thorp.auth.trust-user-id-header=true")
class BacktestControllerTest {

    private static final String BACKTEST_ID = "BT-20260911-000001";
    private static final String USER = "7";

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

    private static BacktestJobResult accepted() {
        return new BacktestJobResult(
                BACKTEST_ID,
                RunStatus.QUEUED,
                ResultSource.NEW,
                LocalDate.of(2023, 7, 3),
                LocalDate.of(2024, 12, 31),
                Map.of("rules", "1"));
    }

    private org.springframework.test.web.servlet.ResultActions postBody(String body)
            throws Exception {
        return mockMvc.perform(
                post("/backtests")
                        .header(CurrentRequester.DEV_HEADER, USER)
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
                .andExpect(jsonPath("$.data.versions.rules").value("1"));
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
    @DisplayName("요청자 헤더가 없으면 400이고 접수하지 않는다")
    void missingRequesterHeaderIsRejected() throws Exception {
        mockMvc.perform(
                        post("/backtests")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(VALID_BODY))
                .andExpect(status().isBadRequest());

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

        mockMvc.perform(
                        get("/backtests/{id}", BACKTEST_ID)
                                .header(CurrentRequester.DEV_HEADER, USER))
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

        mockMvc.perform(
                        get("/backtests/{id}", BACKTEST_ID)
                                .header(CurrentRequester.DEV_HEADER, USER))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("결과가 준비되지 않은 하위 리소스는 409다")
    void subResourceNotReady() throws Exception {
        when(queryService.getTimeseries(BACKTEST_ID))
                .thenThrow(new BacktestResultNotReadyException(BACKTEST_ID, RunStatus.RUNNING));

        mockMvc.perform(get("/backtests/{id}/timeseries", BACKTEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("RESULT_NOT_READY"));
    }

    @Test
    @DisplayName("실패한 백테스트의 하위 리소스는 409 BACKTEST_FAILED다")
    void subResourceFailed() throws Exception {
        when(queryService.getTrades(eq(BACKTEST_ID), any()))
                .thenThrow(new BacktestFailedException(BACKTEST_ID));

        mockMvc.perform(get("/backtests/{id}/trades", BACKTEST_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("BACKTEST_FAILED"));
    }
}
