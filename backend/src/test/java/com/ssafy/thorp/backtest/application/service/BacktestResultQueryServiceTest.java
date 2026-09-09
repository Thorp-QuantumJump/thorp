package com.ssafy.thorp.backtest.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.ssafy.thorp.backtest.application.port.message.BenchmarkResult;
import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.application.repository.BacktestJobRepository;
import com.ssafy.thorp.backtest.application.repository.BacktestResultRepository;
import com.ssafy.thorp.backtest.application.result.BacktestDetailResult;
import com.ssafy.thorp.backtest.application.result.BacktestResultSummary;
import com.ssafy.thorp.backtest.application.result.BacktestSeries;
import com.ssafy.thorp.backtest.application.result.BacktestTimeseriesResult;
import com.ssafy.thorp.backtest.domain.exception.BacktestFailedException;
import com.ssafy.thorp.backtest.domain.exception.BacktestNotFoundException;
import com.ssafy.thorp.backtest.domain.exception.BacktestResultNotReadyException;
import com.ssafy.thorp.backtest.domain.model.BacktestJob;
import com.ssafy.thorp.backtest.domain.model.BacktestStatus;
import com.ssafy.thorp.backtest.domain.model.PositionSizingMode;
import com.ssafy.thorp.backtest.domain.vo.BacktestId;
import com.ssafy.thorp.backtest.domain.vo.BacktestPeriod;
import com.ssafy.thorp.backtest.domain.vo.InstrumentId;
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

/** 상태별 응답 규칙(HTTP 계약 §2.1·§2.5)을 고정한다. */
@ExtendWith(MockitoExtension.class)
class BacktestResultQueryServiceTest {

    private static final String BACKTEST_ID = "BT-20260907-000001";
    private static final Long JOB_ID = 1L;

    @Mock private BacktestJobRepository jobRepository;
    @Mock private BacktestResultRepository resultRepository;
    @InjectMocks private BacktestResultQueryService service;

    private static BacktestJob jobIn(BacktestStatus status) {
        return BacktestJob.restore(
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
                new BigDecimal("0.366917"),
                new BigDecimal("-0.041757"),
                new BigDecimal("2.342630"),
                new BigDecimal("0.132307"),
                2,
                new BigDecimal("0.500000"));
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

    private void given(BacktestStatus status) {
        when(jobRepository.findByBacktestId(BACKTEST_ID)).thenReturn(Optional.of(jobIn(status)));
    }

    // ---------- getDetail ----------

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = BacktestStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("결과 전에는 config만 채우고 결과 행을 읽지 않는다")
    void detailBeforeResult(BacktestStatus status) {
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
        given(BacktestStatus.COMPLETED);
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
        given(BacktestStatus.FAILED);
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
        given(BacktestStatus.COMPLETED);
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
            value = BacktestStatus.class,
            names = {"COMPLETED", "FAILED"})
    @DisplayName("최종 상태인데 결과 행이 없으면 500이다 — RESULT_NOT_READY로 감추지 않는다")
    void detailMissingResultRowIsServerError(BacktestStatus status) {
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
        given(BacktestStatus.COMPLETED);
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
        given(BacktestStatus.COMPLETED);
        when(resultRepository.findTimeseries(JOB_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("시계열은 관문을 그대로 쓴다 — FAILED면 BACKTEST_FAILED")
    void timeseriesUsesGate() {
        given(BacktestStatus.FAILED);

        assertThatThrownBy(() -> service.getTimeseries(BACKTEST_ID))
                .isInstanceOf(BacktestFailedException.class);
    }

    // ---------- 하위 리소스 관문 ----------

    @ParameterizedTest(name = "{0}")
    @EnumSource(
            value = BacktestStatus.class,
            names = {"QUEUED", "RUNNING"})
    @DisplayName("하위 리소스: 결과 전이면 RESULT_NOT_READY (폴링 계속)")
    void gateNotReady(BacktestStatus status) {
        given(status);

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID))
                .isInstanceOf(BacktestResultNotReadyException.class);
    }

    @Test
    @DisplayName("하위 리소스: FAILED면 BACKTEST_FAILED (폴링 중단)")
    void gateFailed() {
        given(BacktestStatus.FAILED);

        assertThatThrownBy(() -> service.requireResultReady(BACKTEST_ID))
                .isInstanceOf(BacktestFailedException.class);
    }

    @Test
    @DisplayName("하위 리소스: COMPLETED만 통과한다")
    void gateCompleted() {
        given(BacktestStatus.COMPLETED);

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
