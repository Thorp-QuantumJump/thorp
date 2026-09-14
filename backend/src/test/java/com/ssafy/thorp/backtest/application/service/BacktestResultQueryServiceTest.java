package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.command.FindBacktestTradesQuery;
import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.port.message.WeightsSource;
import com.ssafy.thorp.backtest.application.repository.BacktestRequestRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestResultBlocks;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.application.result.BacktestWeightsResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestRequest;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.ExecutionType;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.ProgressStage;
import com.ssafy.thorp.backtest.domain.model.ResultSource;
import com.ssafy.thorp.backtest.domain.model.RunLifecycle;
import com.ssafy.thorp.backtest.domain.model.RunProgress;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 상태별 응답 규칙(HTTP 계약 §2.1·§2.5)을 고정한다. */
@ExtendWith(MockitoExtension.class)
class BacktestResultQueryServiceTest {

    private static final String BACKTEST_ID = "BT-20260907-000001";
    private static final Long JOB_ID = 1L;

    private static final long REQUESTER = 7L;

    @Mock private BacktestRunRepository jobRepository;
    @Mock private BacktestRequestRepository requestRepository;
    @Mock private BacktestResultRepository resultRepository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-07T01:00:20Z"), ZoneOffset.UTC);
    private BacktestResultQueryService service;

    @BeforeEach
    void createService() {
        service =
                new BacktestResultQueryService(
                        jobRepository, requestRepository, resultRepository, clock);
    }

    /** 사용자가 보는 식별자는 요청의 것이다. 실행은 그 뒤에 있다(R-02). */
    private static BacktestRequest requestFor(long requesterId) {
        return BacktestRequest.restore(
                21L,
                BacktestId.from(BACKTEST_ID),
                JOB_ID,
                requesterId,
                ExecutionType.SINGLE,
                ResultSource.NEW,
                null,
                null,
                null,
                false);
    }

    private static BacktestRun jobIn(RunStatus status) {
        return BacktestRun.restore(
                        JOB_ID,
                        BacktestId.from(BACKTEST_ID),
                        "MA_CROSSOVER",
                        "1.0.0",
                        Map.of("shortWindow", 20),
                        List.of(new InstrumentId("005930.XKRX")),
                        new BacktestPeriod(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 6, 30)),
                        new BigDecimal("10000000.00"),
                        PositionSizingMode.STRATEGY_DEFINED,
                        null,
                        new InstrumentId("069500.XKRX"),
                        42,
                        status)
                .withLifecycle(RunLifecycle.queuedAt(Instant.parse("2026-09-07T01:00:00Z")));
    }

    private static EngineOutput.PerformanceResult performance() {
        return new EngineOutput.PerformanceResult(
                new BigDecimal("10000000.00"),
                new BigDecimal("11666521.51"),
                "KRW",
                new BigDecimal("0.166652"),
                new BigDecimal("0.166652"),
                new BigDecimal("0.366917"),
                new BigDecimal("-0.041757"),
                new BigDecimal("2.342630"),
                new BigDecimal("0.132307"),
                2,
                new BigDecimal("0.500000"),
                new BigDecimal("17078.49"));
    }

    private static EngineOutput.ExecutionResult execution() {
        return new EngineOutput.ExecutionResult(
                6, 6, 0, new BigDecimal("3941.19"), new BigDecimal("13137.30"));
    }

    private static BacktestResultSummary summary(String errorCode, boolean benchmark) {
        return new BacktestResultSummary(
                errorCode,
                errorCode == null ? null : "no data",
                Instant.parse("2026-09-07T01:00:00Z"),
                Instant.parse("2026-09-07T01:00:12Z"),
                errorCode == null ? performance() : null,
                errorCode == null ? execution() : null,
                benchmark ? "069500.XKRX" : null,
                benchmark ? new BigDecimal("0.053292") : null,
                errorCode == null ? costs() : null,
                errorCode == null ? liquidation() : null,
                errorCode == null ? analytics() : null,
                errorCode == null ? 1 : 0);
    }

    private static EngineOutput.Costs costs() {
        return new EngineOutput.Costs(
                new BigDecimal("3941.19"),
                new BigDecimal("0.00"),
                new BigDecimal("13137.30"),
                new BigDecimal("0.00"),
                new BigDecimal("17078.49"));
    }

    private static EngineOutput.Liquidation liquidation() {
        return new EngineOutput.Liquidation(
                new EngineOutput.MarkToMarket(
                        new BigDecimal("11666521.51"), new BigDecimal("0.166652")),
                new EngineOutput.Liquidated(
                        new BigDecimal("11662525.96"),
                        new BigDecimal("0.166253"),
                        new BigDecimal("3995.55")));
    }

    private static EngineOutput.Analytics analytics() {
        return new EngineOutput.Analytics(
                9, new BigDecimal("1.219991"), null, null, null, null, "sample-covariance-daily");
    }

    /** 먼저 취소해 실행은 계속 돈 요청(R-04). 스냅샷 날짜까지만 볼 수 있다. */
    private static BacktestRequest cancelledJoinedRequest(LocalDate snapshot) {
        return BacktestRequest.restore(
                21L,
                BacktestId.from(BACKTEST_ID),
                JOB_ID,
                REQUESTER,
                ExecutionType.SINGLE,
                ResultSource.JOINED,
                null,
                Instant.parse("2026-09-07T01:00:10Z"),
                snapshot,
                false);
    }

    private void givenCancelledJoined(LocalDate snapshot) {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(cancelledJoinedRequest(snapshot)));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(jobIn(RunStatus.COMPLETED)));
    }

    private void given(RunStatus status) {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(requestFor(REQUESTER)));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(jobIn(status)));
    }

    // ---------- getDetail ----------

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("결과 전에는 config만 채우고 결과 행을 읽지 않는다")
    void detailBeforeResult(RunStatus status) {
        given(status);

        BacktestDetailResult result = service.getDetail(BACKTEST_ID, REQUESTER);

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.resultSource()).isEqualTo(ResultSource.NEW);
        assertThat(result.config()).containsEntry("currency", ExecutionPolicy.CURRENCY);
        assertThat(result.summary()).isNull();
        assertThat(result.failure()).isNull();
        assertThat(result.startedAt()).isNull();
    }

    @Test
    @DisplayName("대기 시간은 아직 시작하지 않았어도 지금까지 기다린 시간으로 채운다")
    void queueWaitCountsWhileWaiting() {
        given(RunStatus.QUEUED);

        assertThat(service.getDetail(BACKTEST_ID, REQUESTER).queueWaitSeconds()).isNotNull();
    }

    @Test
    @DisplayName("COMPLETED는 performance·execution을 summary로 합친다")
    void detailCompleted() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.of(summary(null, true)));

        BacktestDetailResult.Summary summary = service.getDetail(BACKTEST_ID, REQUESTER).summary();

        assertThat(summary.totalReturn()).isEqualByComparingTo("0.166652");
        assertThat(summary.tradeCount()).isEqualTo(2);
        // execution에서 비용 2개만 가져온다. orderCount 등은 노출하지 않는다
        assertThat(summary.totalCommission()).isEqualByComparingTo("3941.19");
        assertThat(summary.totalSlippageCost()).isEqualByComparingTo("13137.30");
        // v2 항목(rest-api.md summary): 핵심 숫자 3개 + 청산 + 비용 + analytics 2개 + 예외 건수
        assertThat(summary.netTotalReturn()).isEqualByComparingTo("0.166652");
        assertThat(summary.totalTradingCost()).isEqualByComparingTo("17078.49");
        assertThat(summary.costs()).isEqualTo(costs());
        assertThat(summary.liquidation()).isEqualTo(liquidation());
        assertThat(summary.analytics().recoveryDays()).isEqualTo(9);
        assertThat(summary.analytics().turnover()).isEqualByComparingTo("1.219991");
        assertThat(summary.exceptionCount()).isEqualTo(1);
    }

    // ---------- 취소 실행의 상세(T011) ----------

    private static BacktestRequest stoppedRequest() {
        return BacktestRequest.restore(
                21L,
                BacktestId.from(BACKTEST_ID),
                JOB_ID,
                REQUESTER,
                ExecutionType.SINGLE,
                ResultSource.NEW,
                null,
                Instant.parse("2026-09-07T01:00:10Z"),
                null,
                true);
    }

    private static BacktestRun cancelledRun(LocalDate lastTradingDate) {
        return jobIn(RunStatus.RUNNING).markCancelled(lastTradingDate);
    }

    @Test
    @DisplayName("실행을 멈춘 취소 요청은 CANCELLED·partial 이고 부분 결과의 요약을 본다")
    void stoppedCancelledRunHasPartialSummary() {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(stoppedRequest()));
        when(jobRepository.findById(JOB_ID))
                .thenReturn(Optional.of(cancelledRun(LocalDate.parse("2025-03-25"))));
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.of(summary(null, false)));

        BacktestDetailResult result = service.getDetail(BACKTEST_ID, REQUESTER);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.partial()).isTrue();
        assertThat(result.cancelRequested()).isFalse();
        assertThat(result.summary()).isNotNull();
        assertThat(result.summaryUnavailable()).isNull();
        assertThat(result.progress().lastTradingDate()).isEqualTo(LocalDate.parse("2025-03-25"));
    }

    @Test
    @DisplayName(
            "대기 중 취소된 실행은 결과 행이 없어도 200 이고 summaryUnavailable=CANCELLED_BEFORE_START, 하위는 NO_PARTIAL_RESULT")
    void queuedCancelHasNoResultRow() {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(stoppedRequest()));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(cancelledRun(null)));
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.empty());

        BacktestDetailResult result = service.getDetail(BACKTEST_ID, REQUESTER);
        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.summary()).isNull();
        assertThat(result.summaryUnavailable()).isEqualTo("CANCELLED_BEFORE_START");

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NO_PARTIAL_RESULT);
    }

    @Test
    @DisplayName("먼저 취소한 합류 요청의 완료 일수는 비운다 — 날짜는 취소 시점인데 일수만 실행의 최신 값이면 어긋난다")
    void pinnedRequestHasNoCompletedTradingDays() {
        RunProgress ahead =
                new RunProgress(ProgressStage.COMPUTING, LocalDate.parse("2025-06-30"), 120, 120);
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(cancelledJoinedRequest(LocalDate.parse("2025-01-28"))));
        when(jobRepository.findById(JOB_ID))
                .thenReturn(Optional.of(jobIn(RunStatus.RUNNING).recordProgress(ahead)));

        BacktestDetailResult result = service.getDetail(BACKTEST_ID, REQUESTER);

        assertThat(result.progress().lastTradingDate()).isEqualTo(LocalDate.parse("2025-01-28"));
        assertThat(result.progress().completedTradingDays()).isNull();
        assertThat(result.progress().totalTradingDays()).isEqualTo(120);
    }

    @Test
    @DisplayName("워커 산출물 없이 끊긴 FAILED(DLQ 종결)는 500 이 아니라 실행 행의 실패 사유를 보여 준다")
    void failedWithoutResultRowShowsTheRunFailure() {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(requestFor(REQUESTER)));
        when(jobRepository.findById(JOB_ID))
                .thenReturn(
                        Optional.of(
                                jobIn(RunStatus.RUNNING)
                                        .markFailed(
                                                "SYSTEM_FAILURE", "작업 메시지가 모두 처리되지 못했습니다(DLQ).")));
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.empty());

        BacktestDetailResult result = service.getDetail(BACKTEST_ID, REQUESTER);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.failure().code()).isEqualTo("SYSTEM_FAILURE");
        assertThat(result.summary()).isNull();
        assertThat(result.partial()).isFalse();
    }

    @Test
    @DisplayName("진행 거래일이 있는 CANCELLED 인데 결과 행이 없으면 결과 유실(500)이다 — 실행 전 취소로 숨기지 않는다")
    void cancelledWithProgressButNoResultRowIsCorruption() {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(stoppedRequest()));
        when(jobRepository.findById(JOB_ID))
                .thenReturn(Optional.of(cancelledRun(LocalDate.parse("2025-03-25"))));
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(BACKTEST_ID, REQUESTER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("CANCEL 을 보냈지만 아직 FINISHED 가 오지 않은 요청은 cancelRequested=true 다")
    void cancelRequestedWhileRunStillRunning() {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(stoppedRequest()));
        when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(jobIn(RunStatus.RUNNING)));

        BacktestDetailResult result = service.getDetail(BACKTEST_ID, REQUESTER);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.cancelRequested()).isTrue();
        assertThat(result.summary()).isNull();
    }

    // ---------- getWeights · getExceptions ----------

    private static EngineOutput.WeightsPoint weightsPoint(String date) {
        return new EngineOutput.WeightsPoint(
                LocalDate.parse(date),
                Map.of("005930.XKRX", new BigDecimal("0.5")),
                Map.of("005930.XKRX", new BigDecimal("0.49")),
                new BigDecimal("0.01"),
                null,
                null);
    }

    private static EngineOutput.ExceptionRecord exception(String date) {
        return new EngineOutput.ExceptionRecord(
                LocalDate.parse(date), "005930.XKRX", "HALT_DEFERRED", "DEFER", "deferred");
    }

    private void givenBlocks() {
        when(resultRepository.findBlocks(JOB_ID))
                .thenReturn(
                        Optional.of(
                                new BacktestResultBlocks(
                                        List.of(
                                                weightsPoint("2025-01-02"),
                                                weightsPoint("2025-02-03")),
                                        WeightsSource.COMPUTED,
                                        List.of(
                                                exception("2025-01-10"),
                                                exception("2025-02-10")))));
    }

    @Test
    @DisplayName("비중 기록은 리밸런싱 포인트와 비중 출처를 그대로 돌려준다")
    void weightsReturnPointsAndSource() {
        given(RunStatus.COMPLETED);
        givenBlocks();

        BacktestWeightsResult result = service.getWeights(BACKTEST_ID, REQUESTER);

        assertThat(result.backtestId()).isEqualTo(BACKTEST_ID);
        assertThat(result.partial()).isFalse();
        assertThat(result.weightsSource()).isEqualTo(WeightsSource.COMPUTED);
        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(0).weightError()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("예외 기록은 그대로 돌려주고 관문을 쓴다 — RUNNING 이면 RESULT_NOT_READY")
    void exceptionsReturnRecordsAndUseGate() {
        given(RunStatus.COMPLETED);
        givenBlocks();
        assertThat(service.getExceptions(BACKTEST_ID, REQUESTER).items()).hasSize(2);

        given(RunStatus.RUNNING);
        assertThatThrownBy(() -> service.getExceptions(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestResultNotReadyException.class);
    }

    @Test
    @DisplayName("비중도 결과 행이 없으면 500이다")
    void weightsMissingResultRow() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findBlocks(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWeights(BACKTEST_ID, REQUESTER))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 먼저 취소한 요청의 읽기 필터(R-04, T005 결정 ⑥) ----------

    @Test
    @DisplayName("먼저 취소해 실행이 계속 돈 요청은 스냅샷 날짜까지만 보고 partial=true 다")
    void cancelledJoinedRequestIsCutAtItsSnapshot() {
        givenCancelledJoined(LocalDate.parse("2025-01-10"));
        when(resultRepository.findTimeseries(JOB_ID))
                .thenReturn(
                        Optional.of(
                                new BacktestSeries(
                                        List.of(
                                                snapshot("2025-01-02", "10000000.00"),
                                                snapshot("2025-01-10", "10100000.00"),
                                                snapshot("2025-01-13", "10200000.00")),
                                        null)));
        givenBlocks();

        BacktestTimeseriesResult series = service.getTimeseries(BACKTEST_ID, REQUESTER);
        assertThat(series.partial()).isTrue();
        assertThat(series.points())
                .extracting(BacktestTimeseriesResult.Point::date)
                .containsExactly(LocalDate.parse("2025-01-02"), LocalDate.parse("2025-01-10"));

        assertThat(service.getWeights(BACKTEST_ID, REQUESTER).points())
                .extracting(EngineOutput.WeightsPoint::date)
                .containsExactly(LocalDate.parse("2025-01-02"));
        assertThat(service.getExceptions(BACKTEST_ID, REQUESTER).items())
                .extracting(EngineOutput.ExceptionRecord::date)
                .containsExactly(LocalDate.parse("2025-01-10"));

        // 보유도 스냅샷 뒤 날짜를 요청하면 스냅샷 이하의 마지막 보유로 답하고 partial 을 싣는다
        when(resultRepository.findPortfolioSeries(JOB_ID))
                .thenReturn(
                        Optional.of(
                                List.of(
                                        snapshotWith(
                                                "2025-01-02", List.of(position("005930.XKRX"))),
                                        snapshotWith("2025-01-13", List.of()))));
        BacktestPositionsResult positions =
                service.getPositions(BACKTEST_ID, REQUESTER, LocalDate.parse("2025-01-13"));
        assertThat(positions.partial()).isTrue();
        assertThat(positions.snapshotDate()).isEqualTo(LocalDate.parse("2025-01-02"));
        assertThat(positions.positions()).hasSize(1);
    }

    @Test
    @DisplayName("완료한 거래일이 없는 취소 요청은 409 NO_PARTIAL_RESULT 다 — 실행 결과로 대신하지 않는다")
    void cancelledBeforeAnyProgressHasNoPartialResult() {
        givenCancelledJoined(null);

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NO_PARTIAL_RESULT);
    }

    @Test
    @DisplayName("취소 요청의 거래는 스냅샷 다음 날 0시(거래소 현지) 를 경계로 저장소에 요청한다")
    void cancelledJoinedRequestBoundsTradesByEntryTime() {
        givenCancelledJoined(LocalDate.parse("2025-01-10"));
        when(resultRepository.exists(JOB_ID)).thenReturn(true);
        FindBacktestTradesQuery query = new FindBacktestTradesQuery(0, 100, null, null);
        Instant bound =
                LocalDate.parse("2025-01-11").atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        when(resultRepository.findTrades(JOB_ID, query, bound))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        BacktestTradePageResult result = service.getTrades(BACKTEST_ID, REQUESTER, query);

        assertThat(result.partial()).isTrue();
        assertThat(result.content()).isEmpty();
    }

    @Test
    @DisplayName("취소하지 않은 요청의 보유 조회는 partial=false 다")
    void positionsOfOrdinaryRequestAreNotPartial() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findPortfolioSeries(JOB_ID))
                .thenReturn(
                        Optional.of(
                                List.of(
                                        snapshotWith(
                                                "2025-01-02", List.of(position("005930.XKRX"))))));

        assertThat(
                        service.getPositions(BACKTEST_ID, REQUESTER, LocalDate.parse("2025-01-02"))
                                .partial())
                .isFalse();
    }

    @Test
    @DisplayName("FAILED는 failure만 채우고 summary는 null이다")
    void detailFailed() {
        given(RunStatus.FAILED);
        when(resultRepository.findSummary(JOB_ID))
                .thenReturn(Optional.of(summary("DATA_INSUFFICIENT", false)));

        BacktestDetailResult result = service.getDetail(BACKTEST_ID, REQUESTER);

        assertThat(result.summary()).isNull();
        assertThat(result.failure().code()).isEqualTo("DATA_INSUFFICIENT");
    }

    @Test
    @DisplayName("없는 backtestId는 404 예외")
    void detailNotFound() {
        when(requestRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestNotFoundException.class);
    }

    @Test
    @DisplayName("남의 요청은 403이 아니라 404다 — 존재 여부를 알려 주지 않는다")
    void othersRequestIsNotFound() {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(requestFor(REQUESTER + 1)));

        assertThatThrownBy(() -> service.getDetail(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestNotFoundException.class);
    }

    @Test
    @DisplayName("남의 요청은 하위 리소스도 404다 — 상세만 막고 하위를 열어 두면 식별자만 알면 읽힌다")
    void subResourcesRejectOtherRequesters() {
        when(requestRepository.findByBacktestId(BACKTEST_ID))
                .thenReturn(Optional.of(requestFor(REQUESTER + 1)));

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                service.getTrades(
                                        BACKTEST_ID,
                                        REQUESTER,
                                        new FindBacktestTradesQuery(0, 100, null, null)))
                .isInstanceOf(BacktestNotFoundException.class);
        assertThatThrownBy(
                        () ->
                                service.getPositions(
                                        BACKTEST_ID, REQUESTER, LocalDate.of(2025, 1, 2)))
                .isInstanceOf(BacktestNotFoundException.class);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = RunStatus.class,
            names = {"COMPLETED", "FAILED"})
    @DisplayName("최종 상태인데 결과 행이 없으면 500이다 — RESULT_NOT_READY로 감추지 않는다")
    void detailMissingResultRowIsServerError(RunStatus status) {
        given(status);
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(BACKTEST_ID, REQUESTER))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BacktestResultNotReadyException.class);
    }

    // ---------- getTimeseries ----------

    private static EngineOutput.PortfolioSnapshot snapshot(String date, String equity) {
        return new EngineOutput.PortfolioSnapshot(
                LocalDate.parse(date),
                new BigDecimal(equity),
                new BigDecimal("1000000.00"),
                new BigDecimal("9000000.00"),
                null,
                new BigDecimal("0.010000"),
                new BigDecimal("-0.005000"),
                null,
                null);
    }

    private static BenchmarkResult benchmark(String... datesAndReturns) {
        List<BenchmarkResult.BenchmarkPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < datesAndReturns.length; i += 2) {
            points.add(
                    new BenchmarkResult.BenchmarkPoint(
                            LocalDate.parse(datesAndReturns[i]),
                            new BigDecimal("10000000.00"),
                            new BigDecimal(datesAndReturns[i + 1])));
        }
        return new BenchmarkResult("069500.XKRX", new BigDecimal("0.053292"), points);
    }

    private void givenSeries(BenchmarkResult benchmark) {
        given(RunStatus.COMPLETED);
        when(resultRepository.findTimeseries(JOB_ID))
                .thenReturn(
                        Optional.of(
                                new BacktestSeries(
                                        List.of(
                                                snapshot("2025-01-02", "10000000.00"),
                                                snapshot("2025-01-03", "10100000.00")),
                                        benchmark)));
    }

    @Test
    @DisplayName("시계열은 벤치마크 누적 수익률을 날짜로 조인한다")
    void timeseriesJoinsBenchmarkByDate() {
        givenSeries(benchmark("2025-01-02", "0.000000", "2025-01-03", "0.008000"));

        List<BacktestTimeseriesResult.Point> points =
                service.getTimeseries(BACKTEST_ID, REQUESTER).points();

        assertThat(points).hasSize(2);
        assertThat(points.get(0).benchmarkReturn()).isEqualByComparingTo("0.000000");
        assertThat(points.get(1).benchmarkReturn()).isEqualByComparingTo("0.008000");
        assertThat(points.get(1).equity()).isEqualByComparingTo("10100000.00");
    }

    @Test
    @DisplayName("벤치마크에 없는 거래일은 benchmarkReturn이 null이다 — 축이 어긋나도 터지지 않는다")
    void timeseriesToleratesMissingBenchmarkDate() {
        givenSeries(benchmark("2025-01-02", "0.000000"));

        List<BacktestTimeseriesResult.Point> points =
                service.getTimeseries(BACKTEST_ID, REQUESTER).points();

        assertThat(points.get(0).benchmarkReturn()).isNotNull();
        assertThat(points.get(1).benchmarkReturn()).isNull();
    }

    @Test
    @DisplayName("벤치마크가 없으면 모든 포인트의 benchmarkReturn이 null이다")
    void timeseriesWithoutBenchmark() {
        givenSeries(null);

        assertThat(service.getTimeseries(BACKTEST_ID, REQUESTER).points())
                .allSatisfy(p -> assertThat(p.benchmarkReturn()).isNull());
    }

    @Test
    @DisplayName("시계열도 결과 행이 없으면 500이다")
    void timeseriesMissingResultRow() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findTimeseries(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID, REQUESTER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("시계열은 관문을 그대로 쓴다 — FAILED면 BACKTEST_FAILED")
    void timeseriesUsesGate() {
        given(RunStatus.FAILED);

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestFailedException.class);
    }

    // ---------- getTrades ----------

    @Test
    @DisplayName("거래 페이지는 Page의 메타데이터를 그대로 옮긴다")
    void tradesCopiesPageMetadata() {
        given(RunStatus.COMPLETED);
        when(resultRepository.exists(JOB_ID)).thenReturn(true);
        FindBacktestTradesQuery query = new FindBacktestTradesQuery(1, 2, null, null);
        when(resultRepository.findTrades(JOB_ID, query, null))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 2), 5));

        BacktestTradePageResult result = service.getTrades(BACKTEST_ID, REQUESTER, query);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.totalElements()).isEqualTo(5);
        assertThat(result.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("거래 0건도 정상 응답이다 — 결과 자체가 없는 것과 다르다")
    void tradesEmptyIsOk() {
        given(RunStatus.COMPLETED);
        when(resultRepository.exists(JOB_ID)).thenReturn(true);
        FindBacktestTradesQuery query = new FindBacktestTradesQuery(0, 100, null, null);
        when(resultRepository.findTrades(JOB_ID, query, null))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        assertThat(service.getTrades(BACKTEST_ID, REQUESTER, query).content()).isEmpty();
    }

    @Test
    @DisplayName("거래도 관문을 그대로 쓴다 — RUNNING이면 RESULT_NOT_READY")
    void tradesUsesGate() {
        given(RunStatus.RUNNING);

        assertThatThrownBy(
                        () ->
                                service.getTrades(
                                        BACKTEST_ID,
                                        REQUESTER,
                                        new FindBacktestTradesQuery(0, 100, null, null)))
                .isInstanceOf(BacktestResultNotReadyException.class);
    }

    // ---------- getPositions ----------

    private static EngineOutput.PositionSnapshot position(String instrumentId) {
        return new EngineOutput.PositionSnapshot(
                instrumentId,
                new BigDecimal("90"),
                new BigDecimal("4743000.00"),
                new BigDecimal("0.406000"),
                new BigDecimal("-87300.00"));
    }

    private static EngineOutput.PortfolioSnapshot snapshotWith(
            String date, List<EngineOutput.PositionSnapshot> positions) {
        EngineOutput.PortfolioSnapshot base = snapshot(date, "10000000.00");
        return new EngineOutput.PortfolioSnapshot(
                base.date(),
                base.equity(),
                base.cash(),
                base.positionValue(),
                base.dailyReturn(),
                base.cumulativeReturn(),
                base.drawdown(),
                positions,
                null);
    }

    /** 01-02(보유 1) → 01-03(변경 없음) → 01-06(보유 없음 확정) → 01-07(변경 없음) */
    private void givenPositionSeries() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findPortfolioSeries(JOB_ID))
                .thenReturn(
                        Optional.of(
                                List.of(
                                        snapshotWith(
                                                "2025-01-02", List.of(position("005930.XKRX"))),
                                        snapshotWith("2025-01-03", null),
                                        snapshotWith("2025-01-06", List.of()),
                                        snapshotWith("2025-01-07", null))));
    }

    @Test
    @DisplayName("요청일에 스냅샷이 있으면 그대로 돌려준다")
    void positionsOnSnapshotDate() {
        givenPositionSeries();

        BacktestPositionsResult result =
                service.getPositions(BACKTEST_ID, REQUESTER, LocalDate.of(2025, 1, 2));

        assertThat(result.requestedDate()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(result.snapshotDate()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(result.positions()).hasSize(1);
    }

    @Test
    @DisplayName("스냅샷이 없는 날은 직전 스냅샷을 돌려주고 snapshotDate가 앞선다")
    void positionsFallsBackToPreviousSnapshot() {
        givenPositionSeries();

        BacktestPositionsResult result =
                service.getPositions(BACKTEST_ID, REQUESTER, LocalDate.of(2025, 1, 3));

        assertThat(result.requestedDate()).isEqualTo(LocalDate.of(2025, 1, 3));
        assertThat(result.snapshotDate()).isEqualTo(LocalDate.of(2025, 1, 2));
    }

    @Test
    @DisplayName("빈 positions는 '보유 없음 확정'이라 답이 된다 — 더 거슬러 올라가지 않는다")
    void positionsEmptyArrayIsAnAnswer() {
        givenPositionSeries();

        BacktestPositionsResult result =
                service.getPositions(BACKTEST_ID, REQUESTER, LocalDate.of(2025, 1, 7));

        assertThat(result.snapshotDate()).isEqualTo(LocalDate.of(2025, 1, 6));
        assertThat(result.positions()).isEmpty();
    }

    @Test
    @DisplayName("마지막 스냅샷 이후를 요청하면 마지막 것을 돌려준다")
    void positionsAfterLastSnapshot() {
        givenPositionSeries();

        assertThat(
                        service.getPositions(BACKTEST_ID, REQUESTER, LocalDate.of(2025, 12, 31))
                                .snapshotDate())
                .isEqualTo(LocalDate.of(2025, 1, 6));
    }

    @Test
    @DisplayName("첫 스냅샷보다 앞선 날짜는 400이다")
    void positionsBeforeFirstSnapshot() {
        givenPositionSeries();

        assertThatThrownBy(
                        () ->
                                service.getPositions(
                                        BACKTEST_ID, REQUESTER, LocalDate.of(2024, 12, 31)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("보유 스냅샷이 시계열 전체에 하나도 없으면 500이다 — 요청 잘못이 아니라 데이터 손상")
    void positionsWithoutAnySnapshotIsServerError() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findPortfolioSeries(JOB_ID))
                .thenReturn(
                        Optional.of(
                                List.of(
                                        snapshotWith("2025-01-02", null),
                                        snapshotWith("2025-01-03", null))));

        assertThatThrownBy(
                        () ->
                                service.getPositions(
                                        BACKTEST_ID, REQUESTER, LocalDate.of(2025, 1, 3)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("400 메시지는 언제부터 조회할 수 있는지 알려준다")
    void positionsBeforeFirstSnapshotSaysEarliest() {
        givenPositionSeries();

        assertThatThrownBy(
                        () ->
                                service.getPositions(
                                        BACKTEST_ID, REQUESTER, LocalDate.of(2024, 12, 31)))
                .hasMessageContaining("2025-01-02");
    }

    @Test
    @DisplayName("보유도 관문을 그대로 쓴다 — FAILED면 BACKTEST_FAILED")
    void positionsUsesGate() {
        given(RunStatus.FAILED);

        assertThatThrownBy(
                        () ->
                                service.getPositions(
                                        BACKTEST_ID, REQUESTER, LocalDate.of(2025, 1, 2)))
                .isInstanceOf(BacktestFailedException.class);
    }

    @Test
    @DisplayName("COMPLETED인데 결과 행이 없으면 거래도 500이다 — 빈 페이지를 주지 않는다")
    void tradesMissingResultRowIsServerError() {
        given(RunStatus.COMPLETED);
        when(resultRepository.exists(JOB_ID)).thenReturn(false);

        assertThatThrownBy(
                        () ->
                                service.getTrades(
                                        BACKTEST_ID,
                                        REQUESTER,
                                        new FindBacktestTradesQuery(0, 100, null, null)))
                .isInstanceOf(IllegalStateException.class);
        // 거래 조회까지 가지 않는다
        verify(resultRepository, never()).findTrades(any(), any(), any());
    }

    // ---------- 하위 리소스 관문 ----------

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("하위 리소스: 결과 전이면 RESULT_NOT_READY (폴링 계속)")
    void gateNotReady(RunStatus status) {
        given(status);

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestResultNotReadyException.class);
    }

    @Test
    @DisplayName("하위 리소스: FAILED면 BACKTEST_FAILED (폴링 중단)")
    void gateFailed() {
        given(RunStatus.FAILED);

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestFailedException.class);
    }

    @Test
    @DisplayName("하위 리소스: COMPLETED만 통과한다")
    void gateCompleted() {
        given(RunStatus.COMPLETED);

        assertThat(service.requireResultReady(BACKTEST_ID, REQUESTER).run().getId())
                .isEqualTo(JOB_ID);
    }

    @Test
    @DisplayName("하위 리소스: 없는 backtestId는 404")
    void gateNotFound() {
        when(requestRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID, REQUESTER))
                .isInstanceOf(BacktestNotFoundException.class);
    }
}
