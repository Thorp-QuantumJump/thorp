package com.ssafy.thorp.backtest.application.result;

import com.ssafy.thorp.backtest.application.port.message.EngineOutput;
import com.ssafy.thorp.backtest.domain.model.BacktestRun;
import com.ssafy.thorp.backtest.domain.model.RunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /backtests/{backtestId}}의 유스케이스 출력. 상태 + 설정 + 요약 + 오류 + 실행 시각.
 *
 * <p>{@code config}는 Job에서 오므로 {@code QUEUED}부터 값이 있다. {@code summary}·{@code error}·시각은 결과 행이 있을
 * 때만이고, 상태에 따라 어느 쪽이 채워지는지가 갈린다.
 *
 * @param summary {@code COMPLETED}가 아니면 null
 * @param error {@code FAILED}일 때만
 */
public record BacktestDetailResult(
        String backtestId,
        RunStatus status,
        Config config,
        Summary summary,
        Error error,
        Instant startedAt,
        Instant finishedAt) {

    /**
     * 무엇을 어떻게 돌린 결과인지. 전부 Job에 저장된 값이거나 서버 고정 정책이다.
     *
     * <p>설계 ConfigResponse 표 그대로다. 재현·디버깅용 메타데이터({@code datasetId}·{@code commissionVersion}·{@code
     * randomSeed}·{@code engineVersion}·{@code inputHash})는 설계가 "결과 저장소에만 보관하고 응답으로 내려주지 않는다"고
     * 정했으므로 담지 않는다.
     *
     * @param initialCash 저장 컬럼이 {@code NUMERIC(19,2)}라 요청보다 자릿수가 늘 수 있다. 값은 같다
     * @param benchmarkId null = 벤치마크 미지정
     */
    public record Config(
            String strategyId,
            String strategyVersion,
            Map<String, Object> parameters,
            List<String> instrumentIds,
            LocalDate start,
            LocalDate end,
            BigDecimal initialCash,
            String currency,
            String benchmarkId) {}

    /**
     * 전체 기간 성과. {@code performance} + {@code execution}의 비용 2개 + 벤치마크 요약.
     *
     * <p>{@code execution}의 {@code orderCount}·{@code fillCount}·{@code rejectedOrderCount}는 설계가
     * "검증·디버깅용"이라 노출하지 않는다.
     *
     * @param benchmark null = 결과에 벤치마크가 없음
     */
    public record Summary(
            BigDecimal initialEquity,
            BigDecimal finalEquity,
            String currency,
            BigDecimal totalReturn,
            BigDecimal cagr,
            BigDecimal maxDrawdown,
            BigDecimal sharpeRatio,
            BigDecimal volatility,
            int tradeCount,
            BigDecimal winRate,
            BigDecimal totalCommission,
            BigDecimal totalSlippageCost,
            Benchmark benchmark) {}

    public record Benchmark(String instrumentId, BigDecimal totalReturn) {}

    /** 엔진이 준 실패 정보. 코드 집합은 엔진 소유이고 HTTP {@code ErrorCode}와 다르다. */
    public record Error(String code, String message) {}

    /** 결과 행이 아직 없는 Job({@code QUEUED}·{@code RUNNING}). */
    public static BacktestDetailResult withoutResult(BacktestRun job, String currency) {
        return new BacktestDetailResult(
                job.getBacktestId().value(),
                job.getStatus(),
                config(job, currency),
                null,
                null,
                null,
                null);
    }

    /** 결과 행이 있는 Job({@code COMPLETED}·{@code FAILED}). 상태에 따라 summary와 error가 갈린다. */
    public static BacktestDetailResult of(
            BacktestRun job, String currency, BacktestResultSummary result) {
        boolean completed = job.getStatus() == RunStatus.COMPLETED;
        return new BacktestDetailResult(
                job.getBacktestId().value(),
                job.getStatus(),
                config(job, currency),
                completed ? summary(result) : null,
                result.errorCode() == null
                        ? null
                        : new Error(result.errorCode(), result.errorMessage()),
                result.startedAt(),
                result.finishedAt());
    }

    private static Config config(BacktestRun job, String currency) {
        return new Config(
                job.getStrategyId(),
                job.getStrategyVersion(),
                job.getParameters(),
                job.getInstrumentIds().stream().map(id -> id.value()).toList(),
                job.getPeriod().startDate(),
                job.getPeriod().endDate(),
                job.getInitialCash(),
                currency,
                job.getBenchmarkInstrumentId() == null
                        ? null
                        : job.getBenchmarkInstrumentId().value());
    }

    private static Summary summary(BacktestResultSummary result) {
        EngineOutput.PerformanceResult p = result.performance();
        EngineOutput.ExecutionResult e = result.execution();
        return new Summary(
                p.initialEquity(),
                p.finalEquity(),
                p.currency(),
                p.totalReturn(),
                p.cagr(),
                p.maxDrawdown(),
                p.sharpeRatio(),
                p.volatility(),
                p.tradeCount(),
                p.winRate(),
                e.totalCommission(),
                e.totalSlippageCost(),
                result.hasBenchmark()
                        ? new Benchmark(
                                result.benchmarkInstrumentId(), result.benchmarkTotalReturn())
                        : null);
    }
}
