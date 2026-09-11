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
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestRunRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestPositionsResult;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.application.result.BacktestTradePageResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
import com.ssafy.thorp.common.exception.BusinessException;
import com.ssafy.thorp.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/** 상태별 응답 규칙(HTTP 계약 §2.1·§2.5)을 고정한다. */
@ExtendWith(MockitoExtension.class)
class BacktestResultQueryServiceTest {

    private static final String BACKTEST_ID = "BT-20260907-000001";
    private static final Long JOB_ID = 1L;

    @Mock private BacktestRunRepository jobRepository;
    @Mock private BacktestResultRepository resultRepository;
    @InjectMocks private BacktestResultQueryService service;

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
                status);
    }

    private static EngineOutput.PerformanceResult performance() {
        return new EngineOutput.PerformanceResult(
                new BigDecimal("10000000.00"),
                new BigDecimal("11666521.51"),
                "KRW",
                new BigDecimal("0.166652"),
                null,
                new BigDecimal("0.366917"),
                new BigDecimal("-0.041757"),
                new BigDecimal("2.342630"),
                new BigDecimal("0.132307"),
                2,
                new BigDecimal("0.500000"),
                null);
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
                benchmark ? new BigDecimal("0.053292") : null);
    }

    private void given(RunStatus status) {
        when(jobRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.of(jobIn(status)));
    }

    // ---------- getDetail ----------

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("결과 전에는 config만 채우고 결과 행을 읽지 않는다")
    void detailBeforeResult(RunStatus status) {
        given(status);

        BacktestDetailResult result = service.getDetail(BACKTEST_ID);

        assertThat(result.status()).isEqualTo(status);
        assertThat(result.config().strategyVersion()).isEqualTo("1.0.0");
        assertThat(result.config().currency()).isEqualTo(ExecutionPolicy.CURRENCY);
        assertThat(result.summary()).isNull();
        assertThat(result.error()).isNull();
        assertThat(result.startedAt()).isNull();
    }

    @Test
    @DisplayName("COMPLETED는 performance·execution·benchmark를 summary로 합친다")
    void detailCompleted() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.of(summary(null, true)));

        BacktestDetailResult.Summary summary = service.getDetail(BACKTEST_ID).summary();

        assertThat(summary.totalReturn()).isEqualByComparingTo("0.166652");
        assertThat(summary.tradeCount()).isEqualTo(2);
        // execution에서 비용 2개만 가져온다. orderCount 등은 노출하지 않는다
        assertThat(summary.totalCommission()).isEqualByComparingTo("3941.19");
        assertThat(summary.totalSlippageCost()).isEqualByComparingTo("13137.30");
        assertThat(summary.benchmark().instrumentId()).isEqualTo("069500.XKRX");
    }

    @Test
    @DisplayName("FAILED는 error만 채우고 summary는 null이다")
    void detailFailed() {
        given(RunStatus.FAILED);
        when(resultRepository.findSummary(JOB_ID))
                .thenReturn(Optional.of(summary("NO_MARKET_DATA", false)));

        BacktestDetailResult result = service.getDetail(BACKTEST_ID);

        assertThat(result.summary()).isNull();
        assertThat(result.error().code()).isEqualTo("NO_MARKET_DATA");
        assertThat(result.finishedAt()).isEqualTo(Instant.parse("2026-09-07T01:00:12Z"));
    }

    @Test
    @DisplayName("요청에 벤치마크가 있어도 결과에 없으면 summary.benchmark는 null이다")
    void detailWithoutBenchmarkInResult() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findSummary(JOB_ID)).thenReturn(Optional.of(summary(null, false)));

        BacktestDetailResult result = service.getDetail(BACKTEST_ID);

        // Job에는 벤치마크 종목이 있다 — config에는 보이고 summary에는 안 보인다
        assertThat(result.config().benchmarkId()).isEqualTo("069500.XKRX");
        assertThat(result.summary().benchmark()).isNull();
    }

    @Test
    @DisplayName("없는 backtestId는 404 예외")
    void detailNotFound() {
        when(jobRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(BACKTEST_ID))
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

        assertThatThrownBy(() -> service.getDetail(BACKTEST_ID))
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

        List<BacktestTimeseriesResult.Point> points = service.getTimeseries(BACKTEST_ID).points();

        assertThat(points).hasSize(2);
        assertThat(points.get(0).benchmarkReturn()).isEqualByComparingTo("0.000000");
        assertThat(points.get(1).benchmarkReturn()).isEqualByComparingTo("0.008000");
        assertThat(points.get(1).equity()).isEqualByComparingTo("10100000.00");
    }

    @Test
    @DisplayName("벤치마크에 없는 거래일은 benchmarkReturn이 null이다 — 축이 어긋나도 터지지 않는다")
    void timeseriesToleratesMissingBenchmarkDate() {
        givenSeries(benchmark("2025-01-02", "0.000000"));

        List<BacktestTimeseriesResult.Point> points = service.getTimeseries(BACKTEST_ID).points();

        assertThat(points.get(0).benchmarkReturn()).isNotNull();
        assertThat(points.get(1).benchmarkReturn()).isNull();
    }

    @Test
    @DisplayName("벤치마크가 없으면 모든 포인트의 benchmarkReturn이 null이다")
    void timeseriesWithoutBenchmark() {
        givenSeries(null);

        assertThat(service.getTimeseries(BACKTEST_ID).points())
                .allSatisfy(p -> assertThat(p.benchmarkReturn()).isNull());
    }

    @Test
    @DisplayName("시계열도 결과 행이 없으면 500이다")
    void timeseriesMissingResultRow() {
        given(RunStatus.COMPLETED);
        when(resultRepository.findTimeseries(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("시계열은 관문을 그대로 쓴다 — FAILED면 BACKTEST_FAILED")
    void timeseriesUsesGate() {
        given(RunStatus.FAILED);

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID))
                .isInstanceOf(BacktestFailedException.class);
    }

    // ---------- getTrades ----------

    @Test
    @DisplayName("거래 페이지는 Page의 메타데이터를 그대로 옮긴다")
    void tradesCopiesPageMetadata() {
        given(RunStatus.COMPLETED);
        when(resultRepository.exists(JOB_ID)).thenReturn(true);
        FindBacktestTradesQuery query = new FindBacktestTradesQuery(1, 2, null, null);
        when(resultRepository.findTrades(JOB_ID, query))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 2), 5));

        BacktestTradePageResult result = service.getTrades(BACKTEST_ID, query);

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
        when(resultRepository.findTrades(JOB_ID, query))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        assertThat(service.getTrades(BACKTEST_ID, query).content()).isEmpty();
    }

    @Test
    @DisplayName("거래도 관문을 그대로 쓴다 — RUNNING이면 RESULT_NOT_READY")
    void tradesUsesGate() {
        given(RunStatus.RUNNING);

        assertThatThrownBy(
                        () ->
                                service.getTrades(
                                        BACKTEST_ID,
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
                service.getPositions(BACKTEST_ID, LocalDate.of(2025, 1, 2));

        assertThat(result.requestedDate()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(result.snapshotDate()).isEqualTo(LocalDate.of(2025, 1, 2));
        assertThat(result.positions()).hasSize(1);
    }

    @Test
    @DisplayName("스냅샷이 없는 날은 직전 스냅샷을 돌려주고 snapshotDate가 앞선다")
    void positionsFallsBackToPreviousSnapshot() {
        givenPositionSeries();

        BacktestPositionsResult result =
                service.getPositions(BACKTEST_ID, LocalDate.of(2025, 1, 3));

        assertThat(result.requestedDate()).isEqualTo(LocalDate.of(2025, 1, 3));
        assertThat(result.snapshotDate()).isEqualTo(LocalDate.of(2025, 1, 2));
    }

    @Test
    @DisplayName("빈 positions는 '보유 없음 확정'이라 답이 된다 — 더 거슬러 올라가지 않는다")
    void positionsEmptyArrayIsAnAnswer() {
        givenPositionSeries();

        BacktestPositionsResult result =
                service.getPositions(BACKTEST_ID, LocalDate.of(2025, 1, 7));

        assertThat(result.snapshotDate()).isEqualTo(LocalDate.of(2025, 1, 6));
        assertThat(result.positions()).isEmpty();
    }

    @Test
    @DisplayName("마지막 스냅샷 이후를 요청하면 마지막 것을 돌려준다")
    void positionsAfterLastSnapshot() {
        givenPositionSeries();

        assertThat(service.getPositions(BACKTEST_ID, LocalDate.of(2025, 12, 31)).snapshotDate())
                .isEqualTo(LocalDate.of(2025, 1, 6));
    }

    @Test
    @DisplayName("첫 스냅샷보다 앞선 날짜는 400이다")
    void positionsBeforeFirstSnapshot() {
        givenPositionSeries();

        assertThatThrownBy(() -> service.getPositions(BACKTEST_ID, LocalDate.of(2024, 12, 31)))
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

        assertThatThrownBy(() -> service.getPositions(BACKTEST_ID, LocalDate.of(2025, 1, 3)))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("400 메시지는 언제부터 조회할 수 있는지 알려준다")
    void positionsBeforeFirstSnapshotSaysEarliest() {
        givenPositionSeries();

        assertThatThrownBy(() -> service.getPositions(BACKTEST_ID, LocalDate.of(2024, 12, 31)))
                .hasMessageContaining("2025-01-02");
    }

    @Test
    @DisplayName("보유도 관문을 그대로 쓴다 — FAILED면 BACKTEST_FAILED")
    void positionsUsesGate() {
        given(RunStatus.FAILED);

        assertThatThrownBy(() -> service.getPositions(BACKTEST_ID, LocalDate.of(2025, 1, 2)))
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
                                        new FindBacktestTradesQuery(0, 100, null, null)))
                .isInstanceOf(IllegalStateException.class);
        // 거래 조회까지 가지 않는다
        verify(resultRepository, never()).findTrades(any(), any());
    }

    // ---------- 벤치마크 불일치 감지 ----------

    @Test
    @DisplayName("결과에 벤치마크가 없으면 불일치가 아니다 — 계산기가 없는 현재 상태가 정상이다")
    void benchmarkAbsentIsNotMismatch() {
        assertThat(
                        BacktestResultQueryService.isBenchmarkMismatch(
                                jobIn(RunStatus.COMPLETED), summary(null, false)))
                .isFalse();
    }

    @Test
    @DisplayName("요청과 결과의 벤치마크가 같으면 불일치가 아니다")
    void benchmarkSameIsNotMismatch() {
        assertThat(
                        BacktestResultQueryService.isBenchmarkMismatch(
                                jobIn(RunStatus.COMPLETED), summary(null, true)))
                .isFalse();
    }

    @Test
    @DisplayName("결과에 값이 있는데 요청과 다르면 불일치다 — config와 summary가 모순된 응답이 된다")
    void benchmarkDifferentIsMismatch() {
        BacktestResultSummary other =
                new BacktestResultSummary(
                        null,
                        null,
                        Instant.parse("2026-09-07T01:00:00Z"),
                        Instant.parse("2026-09-07T01:00:12Z"),
                        performance(),
                        execution(),
                        "229200.XKRX",
                        new BigDecimal("0.011000"));

        assertThat(
                        BacktestResultQueryService.isBenchmarkMismatch(
                                jobIn(RunStatus.COMPLETED), other))
                .isTrue();
    }

    // ---------- 하위 리소스 관문 ----------

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = RunStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("하위 리소스: 결과 전이면 RESULT_NOT_READY (폴링 계속)")
    void gateNotReady(RunStatus status) {
        given(status);

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID))
                .isInstanceOf(BacktestResultNotReadyException.class);
    }

    @Test
    @DisplayName("하위 리소스: FAILED면 BACKTEST_FAILED (폴링 중단)")
    void gateFailed() {
        given(RunStatus.FAILED);

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID))
                .isInstanceOf(BacktestFailedException.class);
    }

    @Test
    @DisplayName("하위 리소스: COMPLETED만 통과한다")
    void gateCompleted() {
        given(RunStatus.COMPLETED);

        assertThat(service.requireResultReady(BACKTEST_ID).getId()).isEqualTo(JOB_ID);
    }

    @Test
    @DisplayName("하위 리소스: 없는 backtestId는 404")
    void gateNotFound() {
        when(jobRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID))
                .isInstanceOf(BacktestNotFoundException.class);
    }
}
